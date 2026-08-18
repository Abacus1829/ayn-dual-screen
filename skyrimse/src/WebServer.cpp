#include "WebServer.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "iphlpapi.lib")

// ── responses ───────────────────────────────────────────────────────────────

HttpResponse HttpResponse::Text(std::string b, const char* type)
{
	HttpResponse r;
	r.body = std::move(b);
	r.contentType = type;
	return r;
}

HttpResponse HttpResponse::Json(std::string b)
{
	return Text(std::move(b), "application/json; charset=utf-8");
}

HttpResponse HttpResponse::NotFound()
{
	HttpResponse r;
	r.status = 404;
	r.body = "not found";
	return r;
}

static const char* StatusText(int status)
{
	switch (status)
	{
	case 200: return "OK";
	case 400: return "Bad Request";
	case 403: return "Forbidden";
	case 404: return "Not Found";
	case 500: return "Internal Server Error";
	default:  return "OK";
	}
}

// ── lifetime ────────────────────────────────────────────────────────────────

WebServer::WebServer(unsigned short port, bool allowLan, Handler handler)
	: port(port), allowLan(allowLan), handler(std::move(handler))
{
}

WebServer::~WebServer()
{
	Stop();
}

bool WebServer::Start()
{
	WSADATA wsa;
	if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0)
		return false;

	SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
	if (s == INVALID_SOCKET)
		return false;

	// Without this a crash-and-relaunch inside the TIME_WAIT window can't rebind, which in
	// practice means "the second screen stopped working until I rebooted".
	BOOL reuse = TRUE;
	setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof reuse);

	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons(port);
	addr.sin_addr.s_addr = allowLan ? INADDR_ANY : htonl(INADDR_LOOPBACK);

	if (bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof addr) == SOCKET_ERROR ||
		listen(s, SOMAXCONN) == SOCKET_ERROR)
	{
		closesocket(s);
		return false;
	}

	listener = s;
	running = true;
	thread = std::thread(&WebServer::AcceptLoop, this);
	return true;
}

void WebServer::Stop()
{
	if (!running)
		return;

	running = false;

	if (listener != ~0ull)
	{
		closesocket(static_cast<SOCKET>(listener));   // unblocks accept()
		listener = ~0ull;
	}

	if (thread.joinable())
		thread.join();

	WSACleanup();
}

void WebServer::AcceptLoop()
{
	while (running)
	{
		SOCKET client = accept(static_cast<SOCKET>(listener), nullptr, nullptr);
		if (client == INVALID_SOCKET)
		{
			if (!running)
				return;      // Stop() closed the listener under us
			continue;
		}

		// One thread per request. At ~10 polls/second from one or two screens this costs less
		// than a pool would, and keeps a slow client from stalling anyone else.
		std::thread(&WebServer::Serve, this, static_cast<unsigned long long>(client)).detach();
	}
}

// ── one request ─────────────────────────────────────────────────────────────

/// Read until the blank line that ends the headers, or until the request is unreasonable.
static bool ReadHead(SOCKET s, std::string& head, std::string& leftover)
{
	char chunk[4096];
	while (head.size() < 16 * 1024)
	{
		size_t marker = head.find("\r\n\r\n");
		if (marker != std::string::npos)
		{
			leftover = head.substr(marker + 4);
			head.resize(marker);
			return true;
		}

		int got = recv(s, chunk, sizeof chunk, 0);
		if (got <= 0)
			return false;
		head.append(chunk, got);
	}
	return false;
}

static std::string HeaderValue(const std::string& head, const char* name)
{
	std::string needle = "\r\n";
	needle += name;
	needle += ':';

	// The request line has no leading CRLF, so search a copy that does.
	std::string padded = "\r\n" + head;
	size_t at = std::string::npos;

	for (size_t i = 0; i + needle.size() <= padded.size(); ++i)
	{
		if (_strnicmp(padded.c_str() + i, needle.c_str(), needle.size()) == 0)
		{
			at = i + needle.size();
			break;
		}
	}
	if (at == std::string::npos)
		return {};

	size_t end = padded.find("\r\n", at);
	std::string value = padded.substr(at, end == std::string::npos ? std::string::npos : end - at);

	size_t start = value.find_first_not_of(" \t");
	return start == std::string::npos ? std::string{} : value.substr(start);
}

void WebServer::Serve(unsigned long long clientHandle)
{
	SOCKET client = static_cast<SOCKET>(clientHandle);

	DWORD timeout = 5000;
	setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof timeout);
	setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&timeout), sizeof timeout);

	std::string head, body;
	if (!ReadHead(client, head, body))
	{
		closesocket(client);
		return;
	}

	// Request line: METHOD SP TARGET SP VERSION
	size_t lineEnd = head.find("\r\n");
	std::string requestLine = head.substr(0, lineEnd);
	size_t sp1 = requestLine.find(' ');
	size_t sp2 = sp1 == std::string::npos ? std::string::npos : requestLine.find(' ', sp1 + 1);
	if (sp1 == std::string::npos)
	{
		closesocket(client);
		return;
	}

	HttpRequest req;
	req.method = requestLine.substr(0, sp1);
	std::string target = requestLine.substr(sp1 + 1, (sp2 == std::string::npos ? requestLine.size() : sp2) - sp1 - 1);

	size_t q = target.find('?');
	req.path = q == std::string::npos ? target : target.substr(0, q);
	req.query = q == std::string::npos ? "" : target.substr(q + 1);

	long declared = atol(HeaderValue(head, "Content-Length").c_str());
	if (declared > 0 && declared <= 256 * 1024)
	{
		char chunk[4096];
		while (body.size() < static_cast<size_t>(declared))
		{
			int got = recv(client, chunk, sizeof chunk, 0);
			if (got <= 0)
				break;
			body.append(chunk, got);
		}
		body.resize(std::min<size_t>(body.size(), declared));
	}
	req.body = std::move(body);
	req.header = head;

	HttpResponse res;
	if (req.method == "OPTIONS")
	{
		res.status = 200;                 // the browser's CORS preflight for POST /action
	}
	else
	{
		// NOTHING the handler does may take the game down.
		//
		// This is a worker thread inside SkyrimSE.exe, and an exception that escapes a thread
		// terminates the process -- so a single bad request would crash somebody's game rather
		// than return an error. A Skyrim crash gets blamed on the last mod installed, which would
		// be this one, so the catch-all is worth having even where nothing ought to throw.
		try
		{
			res = handler(req);
		}
		catch (const std::exception& e)
		{
			res.status = 500;
			res.body = std::string("handler failed: ") + e.what();
		}
		catch (...)
		{
			res.status = 500;
			res.body = "handler failed";
		}
	}

	char header[512];
	int headerLen = std::snprintf(header, sizeof header,
		"HTTP/1.1 %d %s\r\n"
		"Content-Type: %s\r\n"
		"Content-Length: %zu\r\n"
		"Cache-Control: %s\r\n"
		"Access-Control-Allow-Origin: *\r\n"
		"Access-Control-Allow-Headers: Content-Type\r\n"
		"Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
		"Connection: close\r\n\r\n",
		res.status, StatusText(res.status), res.contentType.c_str(),
		res.body.size(), res.cacheControl.c_str());

	send(client, header, headerLen, 0);

	// send() is free to take less than we gave it on a slow link.
	size_t sent = 0;
	while (sent < res.body.size())
	{
		int got = send(client, res.body.data() + sent, static_cast<int>(res.body.size() - sent), 0);
		if (got <= 0)
			break;
		sent += got;
	}

	shutdown(client, SD_SEND);
	closesocket(client);
}

// ── addresses ───────────────────────────────────────────────────────────────

std::vector<std::string> WebServer::LocalAddresses()
{
	std::vector<std::string> out;

	ULONG size = 16 * 1024;
	std::vector<char> buffer(size);
	auto* table = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());

	ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
	if (GetAdaptersAddresses(AF_INET, flags, nullptr, table, &size) == ERROR_BUFFER_OVERFLOW)
	{
		buffer.resize(size);
		table = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
		if (GetAdaptersAddresses(AF_INET, flags, nullptr, table, &size) != NO_ERROR)
			return out;
	}

	for (auto* a = table; a; a = a->Next)
	{
		if (a->OperStatus != IfOperStatusUp || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK)
			continue;

		for (auto* u = a->FirstUnicastAddress; u; u = u->Next)
		{
			auto* sin = reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr);
			char text[INET_ADDRSTRLEN]{};
			inet_ntop(AF_INET, &sin->sin_addr, text, sizeof text);

			// 169.254.x.x means DHCP never answered; printing it would send someone chasing a
			// dead address.
			if (strncmp(text, "169.254.", 8) == 0)
				continue;

			out.emplace_back(text);
		}
	}

	return out;
}
