// Ayn Dual Screen — Fallout: New Vegas
//
// An xNVSE plugin that serves a Pip-Boy to a second screen over HTTP.
//
// New Vegas has no managed modding framework -- no SMAPI, no tModLoader, no Forge -- so unlike the
// other three mods in this repository this one is a native DLL loaded by the script extender. What
// it does is the same: snapshot the player to JSON on the game thread, serve it, and apply the
// commands the screen posts back on the game thread again.
//
// Tale of Two Wastelands needs nothing special from us: TTW is an ordinary New Vegas load order, so
// a plugin that reads the player rather than named forms works in the Capital Wasteland unchanged.
// Standalone Fallout 3 is a different engine build behind FOSE, and is not this DLL -- see the
// README.

#include "nvse/PluginAPI.h"
#include "nvse/CommandTable.h"
#include "nvse/GameAPI.h"

#include "Config.h"
#include "Snapshot.h"
#include "WebServer.h"

#include <windows.h>
#include <memory>
#include <string>

IDebugLog gLog("AynDualScreen.log");

static PluginHandle g_pluginHandle = kPluginHandle_Invalid;
static NVSEMessagingInterface* g_messaging = nullptr;

static Config g_config;
static std::unique_ptr<WebServer> g_server;
static std::string g_webRoot;

// ── paths ───────────────────────────────────────────────────────────────────

/// The folder this DLL was loaded from, which is Data\NVSE\Plugins.
static std::string PluginDirectory()
{
	HMODULE self = nullptr;
	GetModuleHandleExA(
		GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
		reinterpret_cast<LPCSTR>(&PluginDirectory), &self);

	char path[MAX_PATH]{};
	GetModuleFileNameA(self, path, sizeof path);

	std::string full = path;
	size_t slash = full.find_last_of("\\/");
	return slash == std::string::npos ? std::string(".") : full.substr(0, slash);
}

// ── static files ────────────────────────────────────────────────────────────

static const char* MimeFor(const std::string& path)
{
	auto ends = [&](const char* suffix) {
		size_t n = strlen(suffix);
		return path.size() >= n && _stricmp(path.c_str() + path.size() - n, suffix) == 0;
	};

	if (ends(".html")) return "text/html; charset=utf-8";
	if (ends(".css"))  return "text/css; charset=utf-8";
	if (ends(".js"))   return "application/javascript; charset=utf-8";
	if (ends(".png"))  return "image/png";
	if (ends(".svg"))  return "image/svg+xml";
	return "application/octet-stream";
}

static bool ReadWholeFile(const std::string& path, std::string& out)
{
	HANDLE file = CreateFileA(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
		OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
	if (file == INVALID_HANDLE_VALUE)
		return false;

	LARGE_INTEGER size{};
	if (!GetFileSizeEx(file, &size) || size.QuadPart > 8 * 1024 * 1024)
	{
		CloseHandle(file);
		return false;
	}

	out.resize(static_cast<size_t>(size.QuadPart));
	DWORD got = 0;
	bool ok = out.empty() || ReadFile(file, out.data(), static_cast<DWORD>(out.size()), &got, nullptr);
	CloseHandle(file);

	if (!ok)
		return false;

	out.resize(got);
	return true;
}

/// Serve one file out of web/. The path is rejected rather than normalised if it contains any
/// traversal at all -- this listens on the LAN by default, so "reject anything surprising" beats
/// "canonicalise and hope".
static HttpResponse ServeStatic(const std::string& urlPath)
{
	std::string rel = urlPath == "/" ? "index.html" : urlPath.substr(1);

	if (rel.find("..") != std::string::npos || rel.find(':') != std::string::npos ||
		rel.find('\\') != std::string::npos || rel.empty())
		return HttpResponse::NotFound();

	for (unsigned char c : rel)
		if (c < 0x20 || c > 0x7E)
			return HttpResponse::NotFound();

	std::string body;
	if (!ReadWholeFile(g_webRoot + "\\" + rel, body))
		return HttpResponse::NotFound();

	HttpResponse res = HttpResponse::Text(std::move(body), MimeFor(rel));

	// The page itself must not be cached -- an edit to app.js should show up on refresh -- but
	// nothing here is big enough for caching to matter either way.
	res.cacheControl = "no-store";
	return res;
}

// ── routing ─────────────────────────────────────────────────────────────────

static HttpResponse Route(const HttpRequest& req)
{
	if (req.path == "/state")
		return HttpResponse::Json(Snapshot::Current());

	if (req.path == "/action")
	{
		if (req.method != "POST")
			return HttpResponse::NotFound();

		bool queued = Snapshot::QueueCommand(req.body);
		return HttpResponse::Json(queued ? R"({"ok":true})" : R"({"ok":false})");
	}

	if (req.method != "GET")
		return HttpResponse::NotFound();

	return ServeStatic(req.path);
}

// ── NVSE messages ───────────────────────────────────────────────────────────

static void StartServer()
{
	if (g_server)
		return;

	g_server = std::make_unique<WebServer>(g_config.port, g_config.allowLan, Route);

	if (!g_server->Start())
	{
		_MESSAGE("Could not listen on port %u. Another program is probably using it -- change "
			"Port in AynDualScreen.ini. The game is unaffected; there is just no second screen.",
			static_cast<unsigned>(g_config.port));
		g_server.reset();
		return;
	}

	_MESSAGE("Second screen ready on this PC at http://localhost:%u/", static_cast<unsigned>(g_config.port));

	if (g_config.allowLan)
	{
		// localhost typed into a phone means *that* phone, so it can never reach the game. Print
		// the addresses another device should actually use.
		for (const std::string& address : WebServer::LocalAddresses())
			_MESSAGE("From another device on your network: http://%s:%u/", address.c_str(),
				static_cast<unsigned>(g_config.port));
	}
	else
	{
		_MESSAGE("AllowLanAccess is off, so only this PC can open the screen.");
	}

	_MESSAGE("Serving the page from %s", g_webRoot.c_str());
}

static void MessageHandler(NVSEMessagingInterface::Message* msg)
{
	switch (msg->type)
	{
	case NVSEMessagingInterface::kMessage_DeferredInit:
		StartServer();
		break;

	case NVSEMessagingInterface::kMessage_MainGameLoop:
		// The only place game state is ever touched.
		Snapshot::Tick(g_config);
		break;

	case NVSEMessagingInterface::kMessage_PreLoadGame:
	case NVSEMessagingInterface::kMessage_NewGame:
		// Drop the old save's snapshot so a screen polling across the load never shows the
		// previous character's inventory against the new one's name.
		Snapshot::Reset();
		break;

	case NVSEMessagingInterface::kMessage_ExitGame:
	case NVSEMessagingInterface::kMessage_ExitGame_Console:
		if (g_server)
		{
			g_server->Stop();
			g_server.reset();
		}
		break;

	default:
		break;
	}
}

// ── plugin entry points ─────────────────────────────────────────────────────

extern "C" {

__declspec(dllexport) bool NVSEPlugin_Query(const NVSEInterface* nvse, PluginInfo* info)
{
	info->infoVersion = PluginInfo::kInfoVersion;
	info->name = "AynDualScreen";
	info->version = 1;

	if (nvse->isEditor)
		return false;              // nothing here is any use inside the GECK

	if (nvse->nvseVersion < PACKED_NVSE_VERSION)
	{
		_ERROR("This build needs a newer xNVSE than the one loaded.");
		return false;
	}

	return true;
}

__declspec(dllexport) bool NVSEPlugin_Load(const NVSEInterface* nvse)
{
	g_pluginHandle = nvse->GetPluginHandle();

	std::string directory = PluginDirectory();
	g_config = Config::Load(directory + "\\AynDualScreen.ini");

	g_webRoot = g_config.webRootOverride.empty()
		? directory + "\\AynDualScreen\\web"
		: g_config.webRootOverride;

	g_messaging = static_cast<NVSEMessagingInterface*>(nvse->QueryInterface(kInterface_Messaging));
	if (!g_messaging)
	{
		_ERROR("No messaging interface; the second screen cannot start.");
		return false;
	}

	g_messaging->RegisterListener(g_pluginHandle, "NVSE", MessageHandler);
	return true;
}

};
