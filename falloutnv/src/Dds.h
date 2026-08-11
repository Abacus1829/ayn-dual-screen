#pragma once

// Decodes a DDS texture to straight RGBA8, so it can be re-encoded as a PNG a browser will show.
//
// Only the formats the game's interface textures actually use: DXT1/3/5 (BC1/2/3) and the
// uncompressed 32- and 24-bit layouts. Anything else is refused rather than guessed at -- a wrong
// guess produces convincing garbage, which is worse than no icon.
//
// Only the top mip level is decoded; the rest of the file is ignored.

#include <cstdint>
#include <vector>

namespace Dds
{
	struct Image
	{
		uint32_t width = 0;
		uint32_t height = 0;
		std::vector<uint8_t> rgba;      // width * height * 4, top row first

		bool Valid() const { return width && height && rgba.size() == static_cast<size_t>(width) * height * 4; }
	};

	/// Returns false if the data isn't a DDS, or is in a format not handled here.
	bool Decode(const std::vector<uint8_t>& data, Image& out);
}
