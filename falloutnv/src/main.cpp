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

#include "Assets.h"
#include "Config.h"
#include "Snapshot.h"
#include "WebServer.h"

#include <windows.h>
#include <memory>
#include <mutex>
#include <string>

IDebugLog gLog("AynDualScreen.log");

static PluginHandle g_pluginHandle = kPluginHandle_Invalid;
static NVSEMessagingInterface* g_messaging = nullptr;

static Config g_config;
static std::unique_ptr<WebServer> g_server;
static std::string g_webRoot;
static std::string g_configPath;

/// Guards g_config. The game thread reads it every frame; the settings panel writes it from a
/// worker thread, so both take a copy under this rather than sharing the live object.
static std::mutex g_configLock;

static Config ConfigSnapshot()
{
	std::lock_guard<std::mutex> guard(g_configLock);
	return g_config;
}

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

/// Pull one string field out of a flat JSON object. The settings panel posts
/// {"key":"...","value":"..."} and nothing more nested than that, so this is enough and saves
/// taking a JSON parser as a dependency for two fields.
static std::string FieldOf(const std::string& body, const char* name)
{
	std::string needle = "\"";
	needle += name;
	needle += "\"";

	size_t at = body.find(needle);
	if (at == std::string::npos)
		return {};

	size_t colon = body.find(':', at + needle.size());
	if (colon == std::string::npos)
		return {};

	size_t open = body.find('"', colon);
	if (open == std::string::npos)
		return {};

	std::string out;
	for (size_t i = open + 1; i < body.size(); ++i)
	{
		if (body[i] == '\\' && i + 1 < body.size()) { out += body[++i]; continue; }
		if (body[i] == '"') break;
		out += body[i];
	}
	return out;
}

static HttpResponse Route(const HttpRequest& req)
{
	if (req.path == "/state")
		return HttpResponse::Json(Snapshot::Current());

	// /asset/<archive path>.png -- an icon out of the game's own texture archives.
	// Safe on a worker thread: it reads files and decodes pixels, and never touches game state.
	if (req.path.compare(0, 7, "/asset/") == 0)
	{
		if (req.method != "GET")
			return HttpResponse::NotFound();

		std::string png;
		if (!Assets::Png(req.path.substr(7), png))
			return HttpResponse::NotFound();

		HttpResponse res = HttpResponse::Text(std::move(png), "image/png");
		res.cacheControl = "max-age=86400";   // a texture never changes under us
		return res;
	}

	// The mod's own settings, so the ini can be edited from the screen rather than by alt-tabbing
	// out and restarting. Reads are free; writes save the file straight away so a change survives
	// a crash as well as a clean exit.
	if (req.path == "/config")
	{
		if (req.method == "GET")
			return HttpResponse::Json(ConfigSnapshot().ToJson());

		if (req.method != "POST")
			return HttpResponse::NotFound();

		// Body is {"key":"AllowDrop","value":"1"} -- one setting at a time, so a rejected value
		// never takes a good one down with it.
		std::string key = FieldOf(req.body, "key");
		std::string value = FieldOf(req.body, "value");
		if (key.empty())
			return HttpResponse::Json(R"({"ok":false,"error":"no key"})");

		bool ok = false;
		{
			std::lock_guard<std::mutex> guard(g_configLock);
			ok = g_config.Set(key, value);
			if (ok)
				g_config.WriteDefaults(g_configPath);
		}

		if (ok)
			_MESSAGE("Setting changed from the second screen: %s = %s", key.c_str(), value.c_str());

		return HttpResponse::Json(ok ? R"({"ok":true})" : R"({"ok":false,"error":"bad key or value"})");
	}

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
	_MESSAGE("Icons: %s", Assets::Describe().c_str());
}

static void MessageHandler(NVSEMessagingInterface::Message* msg)
{
	switch (msg->type)
	{
	case NVSEMessagingInterface::kMessage_DeferredInit:
		StartServer();
		break;

	case NVSEMessagingInterface::kMessage_MainGameLoop:
		// The only place game state is ever touched. Takes a copy of the config rather than the
		// live object, since the settings panel can write it from a worker thread.
		Snapshot::Tick(ConfigSnapshot());
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
	g_configPath = directory + "\\AynDualScreen.ini";
	g_config = Config::Load(g_configPath);

	g_webRoot = g_config.webRootOverride.empty()
		? directory + "\\AynDualScreen\\web"
		: g_config.webRootOverride;

	// The DLL sits in Data\NVSE\Plugins, so the Data folder is two levels up. That is where the
	// texture archives live.
	std::string data = directory;
	for (int i = 0; i < 2; ++i)
	{
		size_t slash = data.find_last_of("\\/");
		if (slash != std::string::npos)
			data.resize(slash);
	}
	Assets::Init(data, g_config.enableIcons);

	g_messaging = static_cast<NVSEMessagingInterface*>(nvse->QueryInterface(kInterface_Messaging));
	if (!g_messaging)
	{
		_ERROR("No messaging interface; the second screen cannot start.");
		return false;
	}

	// The console interface is how the write operations reach the game. It is optional: without
	// it those commands refuse rather than falling back to poking memory directly, which is the
	// trade this plugin makes everywhere -- a wrong read is a wrong number, a wrong write is a
	// corrupted save.
	auto* console = static_cast<NVSEConsoleInterface*>(nvse->QueryInterface(kInterface_Console));
	if (console && console->version >= 2 && console->RunScriptLine)
	{
		Snapshot::SetConsole(reinterpret_cast<bool(*)(const char*, void*)>(console->RunScriptLine));
		_MESSAGE("Console interface available; world-changing commands are enabled.");
	}
	else
	{
		_MESSAGE("No console interface. The screen stays read-only.");
	}

	g_messaging->RegisterListener(g_pluginHandle, "NVSE", MessageHandler);
	return true;
}

};
