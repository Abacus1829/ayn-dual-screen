// Ayn Dual Screen — Skyrim Special Edition
//
// An SKSE64 plugin that serves a live character sheet, inventory, spellbook, journal and map to a
// second screen over HTTP.
//
// Skyrim has no managed modding framework -- no SMAPI, no tModLoader, no Forge -- so like the
// Fallout: New Vegas mod in this repository, and unlike the other three, this one is a native DLL
// loaded by the script extender. Papyrus cannot open a socket, so there is no version of this that
// isn't C++.
//
// What it does is the same as all four: snapshot the player to JSON on the game thread, serve it,
// and apply the commands the screen posts back on the game thread again.

#include "PCH.h"

#include "Actions.h"
#include "Config.h"
#include "Describe.h"
#include "Snapshot.h"
#include "WebServer.h"

#include <spdlog/sinks/basic_file_sink.h>

#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>

namespace
{
	void StartServer();   // the pacer restarts it on a port change, and is defined above it
	Config g_config;
	std::unique_ptr<WebServer> g_server;
	std::string g_webRoot;
	std::string g_configPath;
	std::string g_bootLogPath;

	/// Guards g_config. The game thread reads it every frame; the settings panel writes it from a
	/// worker thread, so both take a copy under this rather than sharing the live object.
	std::mutex g_configLock;

	/// Set when the port or LAN setting changes. The server cannot restart itself from inside one
	/// of its own request threads, so the game thread does it on the next tick.
	std::atomic<bool> g_restartServer{ false };

	std::atomic<bool> g_running{ false };

	/// True while a snapshot task is queued or running on the game thread. See PacerLoop.
	std::atomic<bool> g_taskPending{ false };
	std::thread g_pacer;

	Config ConfigSnapshot()
	{
		std::lock_guard<std::mutex> guard(g_configLock);
		return g_config;
	}

	// ── breadcrumbs ─────────────────────────────────────────────────────────

	/// A second log, written with nothing but an ofstream.
	///
	/// This exists because the spdlog one produced no file at all through two crashes -- and a
	/// logger that can fail silently is worse than no logger, because it costs you the crash you
	/// were trying to read. This one opens the file, writes the line, flushes and closes, every
	/// time. It is slow and it does not care: it runs a few dozen times at startup and never
	/// again, and its whole job is to still have the last line on disk when the process dies.
	void Crumb(const char* what)
	{
		static std::mutex lock;
		std::lock_guard<std::mutex> guard(lock);

		std::ofstream file(g_bootLogPath, std::ios::app);
		if (!file)
			return;

		file << what << '\n';
		file.flush();
	}

	// ── the tick ────────────────────────────────────────────────────────────

	/// SKSE has no "every frame" message the way NVSE does, so the snapshot is paced by a thread
	/// that does nothing but hand work to the game thread and go back to sleep.
	///
	/// AddTask is the whole point: the lambda runs on the main thread, between frames, where
	/// touching game state is legal. The pacer thread itself never reads anything from the game --
	/// if it did, this would be exactly the bug the rest of the project is written to avoid.
	///
	/// The alternative is hooking the main loop, which means writing to the game's code to read
	/// its data. This costs one sleeping thread instead.
	void PacerLoop()
	{
		while (g_running)
		{
			const Config config = ConfigSnapshot();

			if (g_restartServer.exchange(false))
			{
				// Rebinding the socket happens here rather than in the request that asked for it,
				// because that request is running on a thread of the very server being torn down.
				g_server.reset();
				StartServer();
			}

			// Never more than one snapshot in flight.
			//
			// Without this the pacer posts a task every 100ms whether or not the last one has run,
			// so if a snapshot ever takes longer than its interval the queue grows without bound
			// and the game thread does nothing but drain it -- which is precisely how this mod
			// hung Skyrim rather than crashing it. Skipping a beat is the correct response to a
			// slow frame; queueing another copy of the work is not.
			if (g_taskPending.exchange(true))
			{
				std::this_thread::sleep_for(std::chrono::milliseconds(
					1000 / std::max(1, config.updatesPerSecond)));
				continue;
			}

			if (auto* tasks = SKSE::GetTaskInterface())
			{
				// Nothing this does may take the game down. An exception escaping onto the game
				// thread terminates Skyrim, and a Skyrim crash gets blamed on the last mod
				// installed -- which would be this one. A snapshot that fails is a stale screen;
				// a snapshot that throws is somebody's session.
				tasks->AddTask([config]() {
					try
					{
						static bool first = true;
						if (first) { Crumb("first snapshot task on the game thread"); first = false; }

						Snapshot::Tick(config);

						static bool firstDone = true;
						if (firstDone) { Crumb("first snapshot task returned"); firstDone = false; }
					}
					catch (const std::exception& e)
					{
						SKSE::log::error("Snapshot failed: {}", e.what());
					}
					catch (...)
					{
						SKSE::log::error("Snapshot failed.");
					}

					g_taskPending = false;
				});
			}
			else
			{
				g_taskPending = false;      // no task interface yet; do not wedge the pacer
			}

			std::this_thread::sleep_for(
				std::chrono::milliseconds(1000 / std::max(1, config.updatesPerSecond)));
		}
	}

	// ── static files ────────────────────────────────────────────────────────

	const char* MimeFor(const std::string& path)
	{
		auto ends = [&](const char* suffix) {
			size_t n = std::strlen(suffix);
			return path.size() >= n && _stricmp(path.c_str() + path.size() - n, suffix) == 0;
		};

		if (ends(".html")) return "text/html; charset=utf-8";
		if (ends(".css"))  return "text/css; charset=utf-8";
		if (ends(".js"))   return "application/javascript; charset=utf-8";
		if (ends(".png"))  return "image/png";
		if (ends(".svg"))  return "image/svg+xml";
		if (ends(".woff2"))return "font/woff2";
		return "application/octet-stream";
	}

	bool ReadWholeFile(const std::string& path, std::string& out)
	{
		std::ifstream file(path, std::ios::binary | std::ios::ate);
		if (!file)
			return false;

		const std::streamoff size = file.tellg();
		if (size < 0 || size > 8 * 1024 * 1024)
			return false;

		out.resize(static_cast<size_t>(size));
		file.seekg(0);
		file.read(out.data(), size);
		return true;
	}

	/// Serve one file out of web/. The path is rejected rather than normalised if it contains any
	/// traversal at all -- this listens on the LAN by default, so "reject anything surprising"
	/// beats "canonicalise and hope".
	HttpResponse ServeStatic(const std::string& urlPath)
	{
		std::string rel = urlPath == "/" ? "index.html" : urlPath.substr(1);

		if (rel.empty() || rel.find("..") != std::string::npos ||
			rel.find(':') != std::string::npos || rel.find('\\') != std::string::npos)
			return HttpResponse::NotFound();

		for (unsigned char c : rel)
			if (c < 0x20 || c > 0x7E)
				return HttpResponse::NotFound();

		std::string body;
		if (!ReadWholeFile(g_webRoot + "\\" + rel, body))
			return HttpResponse::NotFound();

		HttpResponse res = HttpResponse::Text(std::move(body), MimeFor(rel));
		res.cacheControl = "no-store";   // an edit to app.js should show up on refresh
		return res;
	}

	// ── routing ─────────────────────────────────────────────────────────────

	std::string FieldOf(const std::string& body, const char* name)
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

	/// Does this request carry the shared secret, if one is set at all?
	///
	/// Accepted two ways because the page fetches JSON and is itself a page: fetch() can set a
	/// header, a bookmarked URL cannot. A constant-time comparison would be theatre -- this is
	/// plain HTTP on a LAN, and anyone positioned to time it can already read the token off the
	/// wire.
	bool Authorised(const HttpRequest& req, const std::string& token)
	{
		if (token.empty())
			return true;

		std::string lower;
		lower.reserve(req.header.size());
		for (char c : req.header)
			lower += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));

		const std::string needle = "x-ayn-token:";
		size_t at = lower.find(needle);
		if (at != std::string::npos)
		{
			size_t start = lower.find_first_not_of(" \t", at + needle.size());
			size_t end = lower.find("\r\n", start);
			if (start != std::string::npos)
			{
				std::string given = req.header.substr(start,
					(end == std::string::npos ? req.header.size() : end) - start);
				while (!given.empty() && (given.back() == '\r' || given.back() == ' '))
					given.pop_back();
				if (given == token)
					return true;
			}
		}

		const std::string wanted = "t=" + token;
		at = req.query.find(wanted);
		if (at != std::string::npos)
		{
			const bool startOk = (at == 0) || req.query[at - 1] == '&';
			const size_t after = at + wanted.size();
			const bool endOk = (after >= req.query.size()) || req.query[after] == '&';
			if (startOk && endOk)
				return true;
		}

		return false;
	}

	HttpResponse Route(const HttpRequest& req)
	{
		// Everything behind the token, including the page itself -- a screen that loads and then
		// fails every request is worse than one that says plainly it needs a key.
		if (!Authorised(req, ConfigSnapshot().accessToken))
		{
			HttpResponse denied;
			denied.status = 401;
			denied.contentType = "application/json; charset=utf-8";
			denied.body = R"({"ok":false,"error":"token required"})";
			return denied;
		}

		if (req.path == "/state")
			return HttpResponse::Json(Snapshot::Current());

		if (req.path == "/config")
		{
			if (req.method == "GET")
				return HttpResponse::Json(ConfigSnapshot().ToJson());

			if (req.method != "POST")
				return HttpResponse::NotFound();

			// One setting at a time, so a rejected value never takes a good one down with it.
			const std::string key = FieldOf(req.body, "key");
			const std::string value = FieldOf(req.body, "value");
			if (key.empty())
				return HttpResponse::Json(R"({"ok":false,"error":"no key"})");

			bool ok = false;
			{
				std::lock_guard<std::mutex> guard(g_configLock);
				ok = g_config.Set(key, value);
				if (ok)
					g_config.WriteDefaults(g_configPath);   // survives a crash as well as an exit
			}

			if (ok)
			{
				SKSE::log::info("Setting changed from the second screen: {} = {}", key, value);
				if (key == "Port" || key == "AllowLanAccess")
					g_restartServer = true;
			}

			return HttpResponse::Json(ok ? R"({"ok":true})"
				: R"({"ok":false,"error":"bad key or value"})");
		}

		if (req.path == "/action")
		{
			if (req.method != "POST")
				return HttpResponse::NotFound();

			const bool queued = Snapshot::QueueCommand(req.body);
			return HttpResponse::Json(queued ? R"({"ok":true})" : R"({"ok":false})");
		}

		if (req.method != "GET")
			return HttpResponse::NotFound();

		return ServeStatic(req.path);
	}

	// ── lifetime ────────────────────────────────────────────────────────────

	void StartServer()
	{
		if (g_server)
			return;

		const Config config = ConfigSnapshot();
		g_server = std::make_unique<WebServer>(config.port, config.allowLan, Route);

		if (!g_server->Start())
		{
			SKSE::log::error(
				"Could not listen on port {}. Another program is probably using it -- change Port "
				"in AynDualScreen.ini. The game is unaffected; there is just no second screen.",
				config.port);
			g_server.reset();
			return;
		}

		SKSE::log::info("Second screen ready on this PC at http://localhost:{}/", config.port);

		if (config.allowLan)
		{
			// localhost typed into a phone means *that* phone, so it can never reach the game.
			// Print the addresses another device should actually use.
			for (const std::string& address : WebServer::LocalAddresses())
				SKSE::log::info("From another device on your network: http://{}:{}/",
					address, config.port);
		}
		else
		{
			SKSE::log::info("AllowLanAccess is off, so only this PC can open the screen.");
		}

		SKSE::log::info("Serving the page from {}", g_webRoot);
	}

	void MessageHandler(SKSE::MessagingInterface::Message* message)
	{
		switch (message->type)
		{
		case SKSE::MessagingInterface::kDataLoaded:
			// Every plugin in the load order is loaded and the forms exist. The server can start
			// here -- it serves a "not ready" document until there is something to read.
			//
			// The snapshot pacer deliberately does NOT start here. kDataLoaded fires at the main
			// menu, long before there is a world, and a pacer running from that moment spends the
			// entire intro poking at a game that is still assembling itself. It waits for a save.
			Crumb("kDataLoaded: starting server");
			StartServer();
			Crumb("kDataLoaded: server started");

			// The test harness. See Config::debugAutoLoad -- off unless somebody turned it on.
			if (ConfigSnapshot().debugAutoLoad)
			{
				std::thread([]() {
					// The main menu needs a moment to exist. Loading into a menu that has not
					// finished building itself is the same class of mistake this mod already made
					// once with the cell.
					std::this_thread::sleep_for(std::chrono::seconds(12));

					if (auto* tasks = SKSE::GetTaskInterface())
					{
						tasks->AddTask([]() {
							Crumb("auto-load: asking for the most recent save");
							if (auto* saves = RE::BGSSaveLoadManager::GetSingleton())
								saves->LoadMostRecentSaveGame();
						});
					}
				}).detach();

				Crumb("auto-load armed");
			}
			break;

		case SKSE::MessagingInterface::kPostLoadGame:
		case SKSE::MessagingInterface::kNewGame:
			// A save has finished loading, or a new game has actually begun. THIS is the first
			// moment there is a player in a cell to read, and the first moment the pacer has any
			// business running.
			Crumb("save loaded: starting pacer");
			Snapshot::Reset();

			if (!g_running.exchange(true))
				g_pacer = std::thread(PacerLoop);

			Crumb("save loaded: pacer running");
			break;

		case SKSE::MessagingInterface::kPreLoadGame:
			// Going back through a load screen. Stop reading entirely until the load finishes:
			// the cell and its references are torn down and rebuilt across this, and walking them
			// mid-flight is the crash this mod already caused once.
			Crumb("kPreLoadGame: snapshot dropped");
			Snapshot::Reset();
			break;

		default:
			break;
		}
	}

	void SetUpLog()
	{
		// SKSE's own log folder, under My Games -- which is where anybody looking for a log looks
		// first, and where every other plugin puts one.
		//
		// The fallback matters more than it looks. If this returns nothing and we just give up,
		// the plugin runs with no log at all, and a crash then leaves nothing whatsoever to read.
		// That happened: the first crash here produced no log, so there was nothing to go on but
		// SKSE's own file. Beside the DLL is always writable enough to try.
		auto folder = SKSE::log::log_directory();
		if (!folder)
			folder = std::filesystem::current_path() / "Data" / "SKSE" / "Plugins";

		std::error_code ignored;
		std::filesystem::create_directories(*folder, ignored);

		*folder /= "AynDualScreen.log";

		try
		{
			auto sink = std::make_shared<spdlog::sinks::basic_file_sink_mt>(folder->string(), true);
			auto log = std::make_shared<spdlog::logger>("global", std::move(sink));
			log->set_level(spdlog::level::info);

			// Flushed on every line, not on a timer. The whole value of this file is what its LAST
			// line says after a crash, and a buffered logger loses exactly that line.
			log->flush_on(spdlog::level::info);

			spdlog::set_default_logger(std::move(log));
			spdlog::set_pattern("[%H:%M:%S] [%l] %v");
		}
		catch (...)
		{
			// Refusing to load over a log file would be absurd. Carry on without one.
		}
	}
}

// ── plugin entry ────────────────────────────────────────────────────────────

SKSEPluginLoad(const SKSE::LoadInterface* skse)
{
	// The DLL sits in Data\SKSE\Plugins, and so does its ini, its web folder and its boot log.
	const auto plugin = std::filesystem::current_path() / "Data" / "SKSE" / "Plugins";
	g_bootLogPath = (plugin / "AynDualScreen.boot.log").string();

	// Truncate whatever the last run left, so what is in this file is always this run.
	{ std::ofstream fresh(g_bootLogPath, std::ios::trunc); }
	Crumb("plugin load entered");

	SKSE::Init(skse);
	Crumb("SKSE::Init done");

	SetUpLog();
	Crumb("log set up");

	SKSE::log::info("Ayn Dual Screen — Skyrim Special Edition, running on {}", RuntimeName());
	Crumb(RuntimeName());

	g_configPath = (plugin / "AynDualScreen.ini").string();
	g_config = Config::Load(g_configPath);
	Crumb("config loaded");

	g_webRoot = g_config.webRootOverride.empty()
		? (plugin / "AynDualScreen" / "web").string()
		: g_config.webRootOverride;

	auto* messaging = SKSE::GetMessagingInterface();
	if (!messaging || !messaging->RegisterListener(MessageHandler))
	{
		SKSE::log::error("No messaging interface; the second screen cannot start.");
		Crumb("NO MESSAGING INTERFACE - giving up");
		return false;
	}

	Crumb("listener registered; plugin load complete");
	return true;
}
