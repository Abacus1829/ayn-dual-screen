#pragma once

// A reader for Bethesda's BSA archives, version 104 (Fallout 3 / New Vegas).
//
// Only what this project needs: open an archive, index its file names, pull one file out by path.
// Nothing writes, and nothing is cached here -- Assets.cpp owns caching.
//
// NOTHING EXTRACTED HERE IS EVER WRITTEN INTO THIS REPOSITORY. The archives belong to whoever
// installed the game; this reads them on that machine, at runtime, and serves the result to that
// same person's own second screen. That is the same arrangement the Terraria mod in this
// repository uses, and the line that keeps the mod distributable.

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

class Bsa
{
public:
	/// Open and index an archive. Returns false if it is missing, unreadable, or not a v104 BSA.
	bool Open(const std::string& path);

	/// Read one file out, by its archive-relative path ("textures\\interface\\icons\\x.dds").
	/// Case-insensitive; slashes either way round. Returns false if absent or corrupt.
	bool Read(const std::string& path, std::vector<uint8_t>& out) const;

	bool Has(const std::string& path) const;

	size_t FileCount() const { return entries.size(); }
	const std::string& Path() const { return archivePath; }

	/// Every indexed path, for diagnostics. Large -- 10,000+ strings on a texture archive.
	std::vector<std::string> AllPaths() const;

private:
	struct Entry
	{
		uint32_t offset = 0;
		uint32_t packedSize = 0;   // as stored, including the compression bit
	};

	std::string archivePath;
	std::unordered_map<std::string, Entry> entries;

	uint32_t archiveFlags = 0;

	static std::string Normalise(const std::string& path);
};

namespace Deflate
{
	/// Inflate a raw zlib stream (RFC 1950 header + RFC 1951 data).
	/// `expected` is the size the container claims; used to size the output up front.
	bool Zlib(const uint8_t* data, size_t length, size_t expected, std::vector<uint8_t>& out);
}
