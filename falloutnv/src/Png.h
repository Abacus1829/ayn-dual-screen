#pragma once

// A minimal PNG encoder.
//
// Deliberately emits *stored* (uncompressed) deflate blocks rather than compressing. A real
// deflate would need a compression library this project does not otherwise carry, and the payload
// here is a 32x32 icon on a LAN -- a few hundred wasted bytes costs nothing next to the dependency.
// Decoding is a different matter: BSA entries really are compressed, so Bsa.cpp does carry an
// inflate.
//
// The output is a standard PNG that any browser accepts: 8-bit RGBA, no interlacing.

#include <cstdint>
#include <string>
#include <vector>

namespace Png
{
	/// Encode straight RGBA8 (row-major, top row first, 4 bytes per pixel) as a PNG.
	/// Returns an empty string if the dimensions and buffer size disagree.
	std::string Encode(const std::vector<uint8_t>& rgba, uint32_t width, uint32_t height);

	uint32_t Crc32(const uint8_t* data, size_t length, uint32_t seed = 0);
}
