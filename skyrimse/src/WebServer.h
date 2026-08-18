#pragma once

// A deliberately small HTTP/1.1 server on raw Winsock.
//
// The same server the Fallout: New Vegas plugin runs, and the same shape as the TcpListener the
// Stardew and Terraria mods use, for the same reason:
// the Windows HTTP stack (http.sys, via HttpApi) needs a URL ACL reservation and therefore admin
// rights to listen on anything but loopback, which is exactly the case we care about.
//
// Every request is answered on a worker thread and the socket is closed. The handler therefore
// must NOT touch game state -- see Snapshot.h for how the game thread publishes to it.

#include <functional>
#include <string>
#include <thread>
#include <vector>

struct HttpRequest
{
	std::string method;
	std::string path;
	std::string query;
	std::string body;

	/// The raw header block, request line included. Kept whole rather than parsed into a map,
	/// because exactly one thing looks at it -- the access-token check.
	std::string header;
};

struct HttpResponse
{
	int status = 200;
	std::string contentType = "text/plain; charset=utf-8";
	std::string cacheControl = "no-store";
	std::string body;

	static HttpResponse Text(std::string b, const char* type = "text/plain; charset=utf-8");
	static HttpResponse Json(std::string b);
	static HttpResponse NotFound();
};

class WebServer
{
public:
	using Handler = std::function<HttpResponse(const HttpRequest&)>;

	WebServer(unsigned short port, bool allowLan, Handler handler);
	~WebServer();

	/// Returns false if the port could not be bound; the caller should log and carry on without
	/// a second screen rather than take the game down.
	bool Start();
	void Stop();

	/// Every IPv4 address another device on the network could use to reach us, so the log can
	/// print something a phone can actually type.
	static std::vector<std::string> LocalAddresses();

private:
	void AcceptLoop();
	void Serve(unsigned long long client);

	unsigned short port;
	bool allowLan;
	Handler handler;

	unsigned long long listener = ~0ull;   // SOCKET, kept opaque so windows.h stays out of the header
	std::thread thread;
	volatile bool running = false;
};
