#pragma once

// A write-only JSON builder. Small on purpose: the plugin only ever produces JSON, never parses
// anything more complicated than the flat command objects the screen posts back, so pulling in a
// real JSON library would be more dependency than this needs.

#include <string>
#include <cstdio>

class Json
{
public:
	Json() { buf.reserve(64 * 1024); }

	// ── containers ───────────────────────────────────────────────────────────

	Json& BeginObject()             { Sep(); buf += '{'; first = true; return *this; }
	Json& BeginObject(const char* k){ Key(k); buf += '{'; first = true; return *this; }
	Json& EndObject()               { buf += '}'; first = false; return *this; }

	Json& BeginArray(const char* k) { Key(k); buf += '['; first = true; return *this; }
	Json& BeginArray()              { Sep(); buf += '['; first = true; return *this; }
	Json& EndArray()                { buf += ']'; first = false; return *this; }

	// ── values ───────────────────────────────────────────────────────────────

	Json& Str(const char* k, const char* v)   { Key(k); Quote(v ? v : ""); return *this; }
	Json& Str(const char* k, const std::string& v) { Key(k); Quote(v.c_str()); return *this; }
	Json& Bool(const char* k, bool v)         { Key(k); buf += v ? "true" : "false"; return *this; }
	Json& Null(const char* k)                 { Key(k); buf += "null"; return *this; }

	Json& Int(const char* k, long long v)
	{
		Key(k);
		char tmp[24];
		std::snprintf(tmp, sizeof tmp, "%lld", v);
		buf += tmp;
		return *this;
	}

	/// Floats are emitted with one decimal by default. JSON has no NaN or Infinity, so anything
	/// the game hands us that isn't finite becomes null rather than invalid JSON.
	Json& Num(const char* k, double v, int decimals = 1)
	{
		if (!(v == v) || v > 1e300 || v < -1e300)
			return Null(k);

		Key(k);
		char fmt[8], tmp[40];
		std::snprintf(fmt, sizeof fmt, "%%.%df", decimals);
		std::snprintf(tmp, sizeof tmp, fmt, v);
		buf += tmp;
		return *this;
	}

	const std::string& Take() const { return buf; }

private:
	void Sep()
	{
		if (!first) buf += ',';
		first = false;
	}

	void Key(const char* k)
	{
		Sep();
		if (k) { Quote(k); buf += ':'; }
	}

	/// Escapes to strict JSON. Game strings are byte strings in the game's codepage rather than
	/// UTF-8, so anything above 0x7F is escaped as a Latin-1 code point -- which is what New Vegas
	/// text actually is, and keeps the payload valid UTF-8 either way.
	void Quote(const char* s)
	{
		buf += '"';
		for (const unsigned char* p = reinterpret_cast<const unsigned char*>(s); *p; ++p)
		{
			switch (*p)
			{
			case '"':  buf += "\\\""; break;
			case '\\': buf += "\\\\"; break;
			case '\n': buf += "\\n";  break;
			case '\r': buf += "\\r";  break;
			case '\t': buf += "\\t";  break;
			default:
				if (*p < 0x20 || *p > 0x7E)
				{
					char tmp[8];
					std::snprintf(tmp, sizeof tmp, "\\u%04X", static_cast<unsigned>(*p));
					buf += tmp;
				}
				else
					buf += static_cast<char>(*p);
			}
		}
		buf += '"';
	}

	std::string buf;
	bool first = true;
};
