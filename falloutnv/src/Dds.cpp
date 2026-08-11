#include "Dds.h"

#include <cstring>

namespace
{
	// DDS_PIXELFORMAT flags
	const uint32_t kFourCC = 0x4;
	const uint32_t kRGB = 0x40;
	const uint32_t kAlphaPixels = 0x1;

	uint32_t Read32(const uint8_t* p)
	{
		uint32_t v;
		std::memcpy(&v, p, 4);
		return v;
	}

	/// Expand a 5- or 6-bit channel to a full 8 bits by replicating the high bits, rather than
	/// shifting and leaving the low end dark.
	inline uint8_t Expand5(uint32_t v) { return static_cast<uint8_t>((v << 3) | (v >> 2)); }
	inline uint8_t Expand6(uint32_t v) { return static_cast<uint8_t>((v << 2) | (v >> 4)); }

	struct Colour { uint8_t r, g, b, a; };

	/// The colour half of a BC1/2/3 block: two endpoints and 16 two-bit indices.
	/// `opaque` is false only for BC1, where endpoint order signals one-bit alpha.
	void DecodeColourBlock(const uint8_t* block, Colour out[16], bool opaque)
	{
		uint16_t c0 = static_cast<uint16_t>(block[0] | (block[1] << 8));
		uint16_t c1 = static_cast<uint16_t>(block[2] | (block[3] << 8));

		Colour palette[4]{};
		palette[0] = { Expand5((c0 >> 11) & 0x1F), Expand6((c0 >> 5) & 0x3F), Expand5(c0 & 0x1F), 255 };
		palette[1] = { Expand5((c1 >> 11) & 0x1F), Expand6((c1 >> 5) & 0x3F), Expand5(c1 & 0x1F), 255 };

		if (opaque || c0 > c1)
		{
			// Four-colour block: two interpolated thirds.
			palette[2] = { static_cast<uint8_t>((2 * palette[0].r + palette[1].r) / 3),
			               static_cast<uint8_t>((2 * palette[0].g + palette[1].g) / 3),
			               static_cast<uint8_t>((2 * palette[0].b + palette[1].b) / 3), 255 };
			palette[3] = { static_cast<uint8_t>((palette[0].r + 2 * palette[1].r) / 3),
			               static_cast<uint8_t>((palette[0].g + 2 * palette[1].g) / 3),
			               static_cast<uint8_t>((palette[0].b + 2 * palette[1].b) / 3), 255 };
		}
		else
		{
			// Three-colour block: one midpoint, and index 3 is transparent black.
			palette[2] = { static_cast<uint8_t>((palette[0].r + palette[1].r) / 2),
			               static_cast<uint8_t>((palette[0].g + palette[1].g) / 2),
			               static_cast<uint8_t>((palette[0].b + palette[1].b) / 2), 255 };
			palette[3] = { 0, 0, 0, 0 };
		}

		uint32_t indices = Read32(block + 4);
		for (int i = 0; i < 16; ++i)
			out[i] = palette[(indices >> (i * 2)) & 3];
	}

	/// BC3's alpha half: two endpoints and 16 three-bit indices.
	void DecodeAlphaBlock(const uint8_t* block, uint8_t out[16])
	{
		uint8_t a0 = block[0], a1 = block[1];

		uint8_t alpha[8];
		alpha[0] = a0;
		alpha[1] = a1;
		if (a0 > a1)
			for (int i = 1; i < 7; ++i)
				alpha[i + 1] = static_cast<uint8_t>(((7 - i) * a0 + i * a1) / 7);
		else
		{
			for (int i = 1; i < 5; ++i)
				alpha[i + 1] = static_cast<uint8_t>(((5 - i) * a0 + i * a1) / 5);
			alpha[6] = 0;
			alpha[7] = 255;
		}

		uint64_t bits = 0;
		for (int i = 0; i < 6; ++i)
			bits |= static_cast<uint64_t>(block[2 + i]) << (i * 8);

		for (int i = 0; i < 16; ++i)
			out[i] = alpha[(bits >> (i * 3)) & 7];
	}
}

bool Dds::Decode(const std::vector<uint8_t>& data, Image& out)
{
	// 4-byte magic + 124-byte header.
	if (data.size() < 128 || std::memcmp(data.data(), "DDS ", 4) != 0)
		return false;

	const uint8_t* header = data.data() + 4;
	uint32_t height = Read32(header + 8);
	uint32_t width = Read32(header + 12);

	if (!width || !height || width > 8192 || height > 8192)
		return false;

	const uint8_t* pf = header + 72;             // DDS_PIXELFORMAT
	uint32_t pfFlags = Read32(pf + 4);
	uint32_t fourCC = Read32(pf + 8);
	uint32_t rgbBitCount = Read32(pf + 12);
	uint32_t rMask = Read32(pf + 16);
	uint32_t gMask = Read32(pf + 20);
	uint32_t bMask = Read32(pf + 24);
	uint32_t aMask = Read32(pf + 28);

	const uint8_t* pixels = data.data() + 128;
	size_t available = data.size() - 128;

	out.width = width;
	out.height = height;
	out.rgba.assign(static_cast<size_t>(width) * height * 4, 0);

	auto put = [&](uint32_t x, uint32_t y, Colour c) {
		if (x >= width || y >= height)
			return;
		size_t at = (static_cast<size_t>(y) * width + x) * 4;
		out.rgba[at + 0] = c.r;
		out.rgba[at + 1] = c.g;
		out.rgba[at + 2] = c.b;
		out.rgba[at + 3] = c.a;
	};

	if (pfFlags & kFourCC)
	{
		const uint32_t kDXT1 = 0x31545844;   // 'DXT1'
		const uint32_t kDXT3 = 0x33545844;
		const uint32_t kDXT5 = 0x35545844;

		if (fourCC != kDXT1 && fourCC != kDXT3 && fourCC != kDXT5)
			return false;

		size_t blockBytes = (fourCC == kDXT1) ? 8 : 16;
		uint32_t blocksWide = (width + 3) / 4;
		uint32_t blocksHigh = (height + 3) / 4;

		if (available < static_cast<size_t>(blocksWide) * blocksHigh * blockBytes)
			return false;

		for (uint32_t by = 0; by < blocksHigh; ++by)
		{
			for (uint32_t bx = 0; bx < blocksWide; ++bx)
			{
				const uint8_t* block = pixels + (static_cast<size_t>(by) * blocksWide + bx) * blockBytes;

				Colour colours[16];
				uint8_t alpha[16];
				for (int i = 0; i < 16; ++i)
					alpha[i] = 255;

				if (fourCC == kDXT1)
				{
					DecodeColourBlock(block, colours, false);
					for (int i = 0; i < 16; ++i)
						alpha[i] = colours[i].a;
				}
				else if (fourCC == kDXT3)
				{
					// Four bits of alpha per pixel, straight through.
					for (int i = 0; i < 16; ++i)
					{
						uint8_t nibble = (block[i / 2] >> ((i & 1) ? 4 : 0)) & 0x0F;
						alpha[i] = static_cast<uint8_t>(nibble * 17);
					}
					DecodeColourBlock(block + 8, colours, true);
				}
				else
				{
					DecodeAlphaBlock(block, alpha);
					DecodeColourBlock(block + 8, colours, true);
				}

				for (int i = 0; i < 16; ++i)
				{
					Colour c = colours[i];
					c.a = alpha[i];
					put(bx * 4 + (i & 3), by * 4 + (i >> 2), c);
				}
			}
		}

		return true;
	}

	if (pfFlags & kRGB)
	{
		if (rgbBitCount != 32 && rgbBitCount != 24)
			return false;

		size_t stride = rgbBitCount / 8;
		if (available < static_cast<size_t>(width) * height * stride)
			return false;

		// Masks vary (BGRA is the common one), so shift each channel down by its own mask.
		auto shiftOf = [](uint32_t mask) {
			if (!mask) return 0;
			int shift = 0;
			while (!((mask >> shift) & 1) && shift < 32) ++shift;
			return shift;
		};

		int rShift = shiftOf(rMask), gShift = shiftOf(gMask), bShift = shiftOf(bMask), aShift = shiftOf(aMask);
		bool hasAlpha = (pfFlags & kAlphaPixels) && aMask;

		for (uint32_t y = 0; y < height; ++y)
		{
			for (uint32_t x = 0; x < width; ++x)
			{
				const uint8_t* p = pixels + (static_cast<size_t>(y) * width + x) * stride;
				uint32_t value = (stride == 4) ? Read32(p) : (p[0] | (p[1] << 8) | (p[2] << 16));

				Colour c{};
				c.r = static_cast<uint8_t>((value & rMask) >> rShift);
				c.g = static_cast<uint8_t>((value & gMask) >> gShift);
				c.b = static_cast<uint8_t>((value & bMask) >> bShift);
				c.a = hasAlpha ? static_cast<uint8_t>((value & aMask) >> aShift) : 255;
				put(x, y, c);
			}
		}

		return true;
	}

	return false;
}
