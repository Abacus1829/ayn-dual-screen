#pragma once

// A write-only JSON builder. Small on purpose: the plugin only ever produces JSON, never parses
// anything more complicated than the flat command objects the screen posts back, so pulling in a
// real JSON library would be more dependency than this needs.

#include <cstdio>
#include <string>

class Json
{
public:
	Json() { buf.reserve(128 * 1024); }

	// ── containers ───────────────────────────────────────────────────────────

	Json& BeginObject()              { Sep(); buf += '{'; first = true; return *this; }
	Json& BeginObject(const char* k) { Key(k); buf += '{'; first = true; return *this; }
	Json& EndObject()                { buf += '}'; first = false; return *this; }

	Json& BeginArray(const char* k)  { Key(k); buf += '['; first = true; return *this; }
	Json& BeginArray()               { Sep(); buf += '['; first = true; return *this; }
	Json& EndArray()                 { buf += ']'; first = false; return *this; }

	// ── values ───────────────────────────────────────────────────────────────

	Json& Str(const char* k, const char* v)        { Key(k); Quote(v ? v : ""); return *this; }
	Json& Str(const char* k, const std::string& v) { Key(k); Quote(v.c_str()); return *this; }
	Json& Bool(const char* k, bool v)              { Key(k); buf += v ? "true" : "false"; return *this; }
	Json& Null(const char* k)                      { Key(k); buf += "null"; return *this; }

	Json& Int(const char* k, long long v)
	{
		Key(k);
		char tmp[24];
		std::snprintf(tmp, sizeof tmp, "%lld", v);
		buf += tmp;
		return *this;
	}

	/// A form ID as the screen addresses things: eight hex digits, lower case.
	Json& Form(const char* k, unsigned int id)
	{
		char tmp[16];
		std::snprintf(tmp, sizeof tmp, "%08x", id);
		return Str(k, tmp);
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

	/// Splice in a fragment of JSON that was built earlier.
	///
	/// This is what makes the expensive sections cacheable: the inventory, the spell list and the
	/// map markers are built as finished JSON on a slow cadence and pasted in here, rather than
	/// rebuilt on every snapshot. The caller owns the fragment's validity -- nothing checks it.
	Json& Raw(const char* k, const std::string& fragment)
	{
		Key(k);
		buf += fragment.empty() ? "null" : fragment;
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

	/// Escapes to strict JSON.
	///
	/// Skyrim SE's strings are already UTF-8 -- unlike New Vegas, whose text is codepage bytes and
	/// has to be escaped as Latin-1. So bytes above 0x7F are passed through untouched: escaping
	/// them per-byte would mangle every accented name in a localised install. Only the control
	/// range and the two structural characters need doing.
	///
	/// A malformed sequence coming out of the game would make invalid UTF-8 here, which the
	/// browser shows as a replacement character rather than failing the parse -- an acceptable
	/// failure for a name, and better than dropping the whole snapshot.
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
				if (*p < 0x20)
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
