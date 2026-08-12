// Loads the real AynDualScreen.dll and drives it, without New Vegas.
//
// Most of this plugin can only be tested by playing the game -- anything that reads the player is
// dereferencing a pointer that only exists inside a running FalloutNV.exe. But three quarters of
// what the DLL does is not that: loading cleanly, standing up an HTTP server, serving the page,
// and decoding icons out of the archives. None of that needs the game, so none of that should be
// left untested until someone launches it.
//
// So this stands in for xNVSE. It builds a plausible NVSEInterface, calls the two exported entry
// points exactly as the loader would, captures the message listener the plugin registers, and
// dispatches kMessage_DeferredInit -- which is the plugin's cue to start its server. Then it
// makes real HTTP requests against it.
//
// It deliberately never dispatches kMessage_MainGameLoop. That is the one message that would send
// the plugin into the game's memory, and there is no game here; /state answering "not ready" is
// the correct result under this harness, not a failure.
//
//     tools\build-plugintest.ps1
//     build\plugintest.exe build\Release\AynDualScreen.dll

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <cstdio>
#include <string>
#include <vector>

#pragma comment(lib, "ws2_32.lib")

// ── the shape xNVSE passes in ───────────────────────────────────────────────
// Mirrored by hand rather than by including the SDK: the harness only has to be layout-compatible
// with what the plugin reads, and including the SDK would drag the whole game header set in.

typedef unsigned int UInt32;
typedef UInt32 PluginHandle;

struct PluginInfo
{
	UInt32 infoVersion;
	const char* name;
	UInt32 version;
};

struct Message
{
	const char* sender;
	UInt32 type;
	UInt32 dataLen;
	void* data;
};

typedef void (*EventCallback)(Message* msg);

// Only the two members the plugin actually calls need to behave; the rest just has to occupy the
// right slots so the offsets line up.
struct MessagingInterface
{
	UInt32 version;
	bool (*RegisterListener)(PluginHandle listener, const char* sender, EventCallback handler);
	bool (*Dispatch)(PluginHandle sender, UInt32 type, void* data, UInt32 dataLen, const char* receiver);
};

struct NVSEInterface
{
	UInt32 nvseVersion;
	UInt32 runtimeVersion;
	UInt32 editorVersion;
	UInt32 isEditor;
	bool (*RegisterCommand)(void*);
	void (*SetOpcodeBase)(UInt32);
	void* (*QueryInterface)(UInt32 id);
	PluginHandle(*GetPluginHandle)(void);
	bool (*RegisterTypedCommand)(void*, int);
	const char* (*GetRuntimeDirectory)();
	UInt32 isNogore;
	void (*InitExpressionEvaluatorUtils)(void*);
	bool (*RegisterTypedCommandVersion)(void*, int, UInt32);
};

// Counted from PluginAPI.h's enum, where the comment lines make it easy to miscount: Serialization
// is 0, Console 1, Messaging 2.
const UInt32 kInterface_Messaging = 2;
// Counted from the real enum members only -- the surrounding comments mention other kMessage_
// names, and counting those puts this off by eight, which lands on ReloadConfig and silently
// does nothing.
const UInt32 kMessage_DeferredInit = 18;

static EventCallback g_listener = nullptr;
static std::string g_runtimeDirectory;

static bool RegisterListener(PluginHandle, const char*, EventCallback handler)
{
	g_listener = handler;
	return true;
}

static bool Dispatch(PluginHandle, UInt32, void*, UInt32, const char*) { return true; }

static MessagingInterface g_messaging{ 4, RegisterListener, Dispatch };

static void* QueryInterface(UInt32 id) { return id == kInterface_Messaging ? &g_messaging : nullptr; }
static PluginHandle GetPluginHandle() { return 1; }
static const char* GetRuntimeDirectory() { return g_runtimeDirectory.c_str(); }

// ── a tiny HTTP client, so the harness carries no dependencies either ───────

/// Sent as X-Ayn-Token on every request when set, so the harness can exercise a server that has
/// an access token configured. Without this, a token in the ini makes every check fail with 401
/// and the run tells you nothing about anything else.
static std::string g_token;

static bool Get(const char* host, int port, const std::string& path, std::string& out, std::string& status)
{
	out.clear();
	status.clear();

	SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
	if (s == INVALID_SOCKET)
		return false;

	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons(static_cast<u_short>(port));
	inet_pton(AF_INET, host, &addr.sin_addr);

	DWORD timeout = 8000;
	setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof timeout);

	if (connect(s, reinterpret_cast<sockaddr*>(&addr), sizeof addr) != 0)
	{
		closesocket(s);
		return false;
	}

	std::string request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n";
	if (!g_token.empty())
		request += "X-Ayn-Token: " + g_token + "\r\n";
	request += "Connection: close\r\n\r\n";
	send(s, request.data(), static_cast<int>(request.size()), 0);

	char buffer[8192];
	int got;
	std::string raw;
	while ((got = recv(s, buffer, sizeof buffer, 0)) > 0)
		raw.append(buffer, got);
	closesocket(s);

	size_t split = raw.find("\r\n\r\n");
	if (split == std::string::npos)
		return false;

	size_t eol = raw.find("\r\n");
	status = raw.substr(0, eol);
	out = raw.substr(split + 4);
	return true;
}

static int g_failures = 0;

static void Check(bool ok, const char* what, const std::string& detail = {})
{
	std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", what,
		detail.empty() ? "" : " -- ", detail.c_str());
	if (!ok)
		++g_failures;
}

int main(int argc, char** argv)
{
	if (argc < 2)
	{
		std::printf("usage: plugintest <path to AynDualScreen.dll> [game folder]\n");
		return 2;
	}

	WSADATA wsa;
	WSAStartup(MAKEWORD(2, 2), &wsa);

	g_token = argc > 2 ? argv[2] : "";
	g_runtimeDirectory = argc > 3 ? argv[3] : "";
	if (!g_token.empty())
		std::printf("sending access token on every request\n");

	std::printf("loading %s\n", argv[1]);
	HMODULE dll = LoadLibraryA(argv[1]);
	if (!dll)
	{
		std::printf("  [FAIL] LoadLibrary failed, error %lu\n", GetLastError());
		return 1;
	}
	Check(true, "DLL loads (all imports resolved)");

	auto query = reinterpret_cast<bool(*)(const NVSEInterface*, PluginInfo*)>(GetProcAddress(dll, "NVSEPlugin_Query"));
	auto load = reinterpret_cast<bool(*)(const NVSEInterface*)>(GetProcAddress(dll, "NVSEPlugin_Load"));
	Check(query && load, "both NVSE entry points are exported");
	if (!query || !load)
		return 1;

	NVSEInterface nvse{};
	// xNVSE packs its version as (major << 24) | (minor << 16) | (build << 4). The plugin refuses
	// anything below the PACKED_NVSE_VERSION it was built against, so claim exactly that: 6.4.8.
	nvse.nvseVersion = (6u << 24) | (4u << 16) | (8u << 4);   // 0x06040080
	nvse.runtimeVersion = 0x040020D0;
	nvse.isEditor = 0;
	nvse.QueryInterface = QueryInterface;
	nvse.GetPluginHandle = GetPluginHandle;
	nvse.GetRuntimeDirectory = GetRuntimeDirectory;

	PluginInfo info{};
	bool queried = query(&nvse, &info);
	Check(queried, "NVSEPlugin_Query accepts a current xNVSE");
	Check(info.name && std::string(info.name) == "AynDualScreen", "reports its name",
		info.name ? info.name : "(null)");

	// The editor must be refused: nothing here is any use inside the GECK.
	NVSEInterface editor = nvse;
	editor.isEditor = 1;
	PluginInfo editorInfo{};
	Check(!query(&editor, &editorInfo), "refuses to load into the GECK");

	Check(load(&nvse), "NVSEPlugin_Load succeeds");
	Check(g_listener != nullptr, "registers a message listener");
	if (!g_listener)
		return 1;

	// DeferredInit is the plugin's cue to start serving.
	Message message{ "NVSE", kMessage_DeferredInit, 0, nullptr };
	g_listener(&message);
	Sleep(400);

	std::printf("\nHTTP:\n");
	std::string body, status;

	Check(Get("127.0.0.1", 27303, "/", body, status) && status.find("200") != std::string::npos,
		"GET / serves the page", status);
	Check(body.find("Pip-Boy") != std::string::npos, "the page is ours",
		"got " + std::to_string(body.size()) + " bytes");

	Check(Get("127.0.0.1", 27303, "/style.css", body, status) && status.find("200") != std::string::npos,
		"GET /style.css", status);
	Check(Get("127.0.0.1", 27303, "/app.js", body, status) && status.find("200") != std::string::npos,
		"GET /app.js", status);

	// Without the game there is no player, so "not ready" is the right answer here.
	Check(Get("127.0.0.1", 27303, "/state", body, status) && status.find("200") != std::string::npos,
		"GET /state answers", status);
	Check(body.find("\"ready\":false") != std::string::npos,
		"/state correctly reports not-ready with no game", body.substr(0, 60));

	// Path traversal must be refused even though lookups never touch the filesystem.
	Check(Get("127.0.0.1", 27303, "/../../Fallout_default.ini", body, status) &&
		status.find("404") != std::string::npos, "refuses traversal out of web/", status);

	std::printf("\nAssets:\n");
	const char* icon = "/asset/textures/interface/icons/pipboyimages/weapons/weapons_10mm_pistol.png";
	bool served = Get("127.0.0.1", 27303, icon, body, status) && status.find("200") != std::string::npos;
	Check(served, "GET an icon out of the game archives", status);
	if (served)
	{
		Check(body.size() > 1000 && body.compare(0, 8, "\x89PNG\r\n\x1a\n") == 0,
			"and it is a real PNG", std::to_string(body.size()) + " bytes");
	}

	Check(Get("127.0.0.1", 27303, "/asset/textures/nope/not_a_real_texture.png", body, status) &&
		status.find("404") != std::string::npos, "missing texture 404s rather than hanging", status);

	std::printf("\n%s (%d failure%s)\n", g_failures ? "FAILURES" : "all checks passed",
		g_failures, g_failures == 1 ? "" : "s");
	return g_failures ? 1 : 0;
}
