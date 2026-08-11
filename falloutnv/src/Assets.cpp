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

	// Only the two stock texture archives. Sound and mesh archives hold nothing the screen wants,
	// and indexing a gigabyte of meshes to never read from it would be rude.
	const char* names[] = { "Fallout - Textures2.bsa", "Fallout - Textures.bsa" };

	std::string summary;
	for (const char* name : names)
	{
		Bsa archive;
		if (!archive.Open(dataFolder + "\\" + name))
		{
			summary += std::string(name) + ": not found; ";
			continue;
		}

		summary += std::string(name) + ": " + std::to_string(archive.FileCount()) + " files; ";
		g_archives.push_back(std::move(archive));
	}

	if (g_archives.empty())
		summary += "no texture archives opened, so icons are unavailable";

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

	std::string png = ::Png::Encode(image.rgba, image.width, image.height);
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
