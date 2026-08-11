#include "Png.h"

#include <cstring>

namespace
{
	void PutBE32(std::string& out, uint32_t value)
	{
		out += static_cast<char>((value >> 24) & 0xFF);
		out += static_cast<char>((value >> 16) & 0xFF);
		out += static_cast<char>((value >> 8) & 0xFF);
		out += static_cast<char>(value & 0xFF);
	}

	/// Adler-32, the checksum a zlib stream carries over its *uncompressed* data.
	uint32_t Adler32(const uint8_t* data, size_t length)
	{
		uint32_t a = 1, b = 0;
		for (size_t i = 0; i < length; ++i)
		{
			a = (a + data[i]) % 65521;
			b = (b + a) % 65521;
		}
		return (b << 16) | a;
	}

	void Chunk(std::string& out, const char type[4], const std::string& body)
	{
		PutBE32(out, static_cast<uint32_t>(body.size()));

		std::string payload(type, 4);
		payload += body;

		out += payload;
		PutBE32(out, Png::Crc32(reinterpret_cast<const uint8_t*>(payload.data()), payload.size()));
	}
}

uint32_t Png::Crc32(const uint8_t* data, size_t length, uint32_t seed)
{
	static uint32_t table[256];
	static bool ready = false;

	if (!ready)
	{
		for (uint32_t n = 0; n < 256; ++n)
		{
			uint32_t c = n;
			for (int k = 0; k < 8; ++k)
				c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
			table[n] = c;
		}
		ready = true;
	}

	uint32_t crc = seed ^ 0xFFFFFFFFu;
	for (size_t i = 0; i < length; ++i)
		crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
	return crc ^ 0xFFFFFFFFu;
}

std::string Png::Encode(const std::vector<uint8_t>& rgba, uint32_t width, uint32_t height)
{
	if (!width || !height || rgba.size() < static_cast<size_t>(width) * height * 4)
		return {};

	// ── raw scanlines, each prefixed with filter type 0 (none) ──────────────
	std::string raw;
	raw.reserve(static_cast<size_t>(height) * (width * 4 + 1));
	for (uint32_t y = 0; y < height; ++y)
	{
		raw += '\0';
		raw.append(reinterpret_cast<const char*>(rgba.data()) + static_cast<size_t>(y) * width * 4,
			static_cast<size_t>(width) * 4);
	}

	// ── wrap in a zlib stream made entirely of stored blocks ────────────────
	std::string z;
	z += static_cast<char>(0x78);   // CMF: deflate, 32K window
	z += static_cast<char>(0x01);   // FLG: no preset dictionary, check bits valid

	const size_t kMaxBlock = 65535;
	size_t offset = 0;
	while (offset < raw.size() || raw.empty())
	{
		size_t block = raw.size() - offset;
		if (block > kMaxBlock)
			block = kMaxBlock;

		bool last = (offset + block) >= raw.size();
		z += static_cast<char>(last ? 1 : 0);              // BFINAL, BTYPE=00 (stored)
		z += static_cast<char>(block & 0xFF);
		z += static_cast<char>((block >> 8) & 0xFF);
		z += static_cast<char>(~block & 0xFF);             // one's complement of LEN
		z += static_cast<char>((~block >> 8) & 0xFF);
		z.append(raw, offset, block);

		offset += block;
		if (last)
			break;
	}

	PutBE32(z, Adler32(reinterpret_cast<const uint8_t*>(raw.data()), raw.size()));

	// ── assemble ────────────────────────────────────────────────────────────
	std::string out;
	out.reserve(z.size() + 128);
	out += "\x89PNG\r\n\x1a\n";

	std::string ihdr;
	PutBE32(ihdr, width);
	PutBE32(ihdr, height);
	ihdr += static_cast<char>(8);    // bit depth
	ihdr += static_cast<char>(6);    // colour type 6 = RGBA
	ihdr += static_cast<char>(0);    // deflate
	ihdr += static_cast<char>(0);    // adaptive filtering
	ihdr += static_cast<char>(0);    // no interlace

	Chunk(out, "IHDR", ihdr);
	Chunk(out, "IDAT", z);
	Chunk(out, "IEND", std::string());

	return out;
}
