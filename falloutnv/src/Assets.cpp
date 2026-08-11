#include "Assets.h"

#include "Bsa.h"
#include "Dds.h"
#include "Png.h"

#include <algorithm>
#include <map>
#include <mutex>
#include <vector>

namespace
{
	std::mutex g_lock;
	bool g_enabled = false;
	bool g_ready = false;

	/// The texture archives, in the order they should be searched. Later archives in a real load
	/// order win, but for the stock interface art these two never collide, so first hit is fine.
	std::vector<Bsa> g_archives;
	std::string g_summary = "not initialised";

	/// Decoded PNGs, keyed by normalised path. Icons are a few hundred KB each once expanded to
	/// RGBA, so this is bounded: the screen only ever asks for what is on it.
	std::map<std::string, std::string> g_cache;
	size_t g_cacheBytes = 0;

	const size_t kCacheLimit = 48u << 20;   // 48 MB of decoded PNGs, then we stop adding

	std::string Lower(std::string s)
	{
		for (char& c : s)
			c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
		return s;
	}

	/// Accept a path with either slash, any case, and either extension. The screen asks for .png
	/// because that is what it gets back; the archive holds .dds.
	std::string ToArchivePath(const std::string& request)
	{
		std::string path = Lower(request);
		for (char& c : path)
			if (c == '/') c = '\\';

		while (!path.empty() && path.front() == '\\')
			path.erase(path.begin());

		if (path.size() > 4 && path.compare(path.size() - 4, 4, ".png") == 0)
			path.replace(path.size() - 4, 4, ".dds");
		else if (path.size() < 4 || path.compare(path.size() - 4, 4, ".dds") != 0)
			path += ".dds";

		// Everything we serve lives under textures\; allow the caller to omit it.
		if (path.compare(0, 9, "textures\\") != 0)
			path = "textures\\" + path;

		return path;
	}

	/// Reject anything that could walk out of the archive namespace. There is no filesystem access
	/// here at all -- lookups are against an in-memory index -- but the path still comes off the
	/// network, so it gets the same treatment as one that would touch disk.
	bool Suspicious(const std::string& path)
	{
		if (path.find("..") != std::string::npos)
			return true;
		if (path.find(':') != std::string::npos)
			return true;
		for (unsigned char c : path)
			if (c < 0x20 || c > 0x7E)
				return true;
		return false;
	}
}

void Assets::Init(const std::string& dataFolder, bool enabled)
{
	std::lock_guard<std::mutex> guard(g_lock);

	g_enabled = enabled;
	if (!enabled)
	{
		g_summary = "disabled in the ini";
		return;
	}
	if (g_ready)
		return;

	// Every .bsa in Data, not a fixed list.
	//
	// A hardcoded pair of base archives meant DLC items had no icon at all -- Dead Money, Honest
	// Hearts and the rest each ship their own -- and any texture a mod added was invisible too.
	// Scanning costs nothing: opening an archive reads its header and name table, never pixels.
	//
	// Sound and voice archives are skipped by name. They are hundreds of megabytes of audio with
	// no texture in them, and indexing one to never read from it would be waste.
	std::vector<std::string> names;
	{
		WIN32_FIND_DATAA found{};
		HANDLE search = FindFirstFileA((dataFolder + "\\*.bsa").c_str(), &found);
		if (search != INVALID_HANDLE_VALUE)
		{
			do
			{
				std::string name = found.cFileName;
				std::string lower = Lower(name);
				if (lower.find("sound") != std::string::npos || lower.find("voice") != std::string::npos)
					continue;
				names.push_back(name);
			} while (FindNextFileA(search, &found));
			FindClose(search);
		}
	}

	// Later archives win on a name collision, which is the same order the game resolves in, so
	// search back to front and let a DLC or mod texture take precedence over the base game's.
	std::sort(names.begin(), names.end());

	std::string summary;
	size_t total = 0;
	for (const std::string& name : names)
	{
		Bsa archive;
		if (!archive.Open(dataFolder + "\\" + name))
			continue;                        // not a v104 BSA, or unreadable; simply skip it

		total += archive.FileCount();
		g_archives.push_back(std::move(archive));
	}

	summary = std::to_string(g_archives.size()) + " archives, "
		+ std::to_string(total) + " files indexed";

	if (g_archives.empty())
		summary = "no archives opened, so icons are unavailable";

	g_summary = summary;
	g_ready = true;
}

bool Assets::Png(const std::string& request, std::string& out)
{
	if (request.empty() || Suspicious(request))
		return false;

	std::string path = ToArchivePath(request);

	{
		std::lock_guard<std::mutex> guard(g_lock);
		if (!g_enabled)
			return false;

		auto cached = g_cache.find(path);
		if (cached != g_cache.end())
		{
			out = cached->second;
			return true;
		}
	}

	// Decoding happens outside the lock: a 1024x1024 texture is milliseconds of work, and holding
	// the lock across it would serialise every icon the screen asks for on first paint.
	std::vector<uint8_t> raw;
	bool found = false;
	{
		std::lock_guard<std::mutex> guard(g_lock);
		for (const Bsa& archive : g_archives)
		{
			if (archive.Read(path, raw))
			{
				found = true;
				break;
			}
		}
	}

	if (!found)
		return false;

	Dds::Image image;
	if (!Dds::Decode(raw, image) || !image.Valid())
		return false;

	// Refuse anything too large to encode safely.
	//
	// This runs inside a 32-bit game with a fragmented address space. A 2048x2048 texture is 16 MB
	// decoded, and the encoder's stored-deflate output plus its intermediate buffer roughly triples
	// that -- enough to fail an allocation and, before the handler learned to catch, to take the
	// game down. UI art is never this big; the world map is, and it is better served scaled by the
	// browser from a smaller mip than by risking the process.
	const uint32_t kMaxPixels = 1024 * 1024;   // 1024x1024, or any shape up to the same area
	if (static_cast<uint32_t>(image.width) * image.height > kMaxPixels)
		return false;

	std::string png;
	try
	{
		png = ::Png::Encode(image.rgba, image.width, image.height);
	}
	catch (...)
	{
		return false;                        // out of memory encoding it; a missing icon is fine
	}

	if (png.empty())
		return false;

	{
		std::lock_guard<std::mutex> guard(g_lock);
		if (g_cacheBytes + png.size() <= kCacheLimit)
		{
			g_cacheBytes += png.size();
			g_cache.emplace(path, png);
		}
	}

	out = std::move(png);
	return true;
}

std::string Assets::Describe()
{
	std::lock_guard<std::mutex> guard(g_lock);
	return g_summary;
}
