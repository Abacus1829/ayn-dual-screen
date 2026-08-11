#include "Bsa.h"

#include <algorithm>
#include <cstring>
#include <fstream>

// ── inflate ─────────────────────────────────────────────────────────────────
//
// RFC 1951, written out because this project carries no compression library and BSA entries are
// genuinely zlib-compressed. Encoding side is easier -- Png.cpp gets away with stored blocks.

namespace
{
	class BitReader
	{
	public:
		BitReader(const uint8_t* data, size_t length) : data(data), length(length) {}

		/// Read n bits, least-significant first. Sets `bad` and returns 0 past the end.
		uint32_t Bits(int n)
		{
			uint32_t value = 0;
			for (int i = 0; i < n; ++i)
			{
				if (bitPos == 0)
				{
					if (bytePos >= length) { bad = true; return 0; }
					current = data[bytePos++];
				}
				value |= static_cast<uint32_t>((current >> bitPos) & 1) << i;
				bitPos = (bitPos + 1) & 7;
			}
			return value;
		}

		void AlignToByte() { bitPos = 0; }

		bool Bad() const { return bad; }
		size_t BytePos() const { return bytePos; }
		const uint8_t* Data() const { return data; }
		size_t Length() const { return length; }
		void Skip(size_t n) { bytePos += n; }

	private:
		const uint8_t* data;
		size_t length;
		size_t bytePos = 0;
		int bitPos = 0;
		uint8_t current = 0;
		bool bad = false;
	};

	/// A canonical Huffman decoder built from code lengths, per RFC 1951 section 3.2.2.
	struct Huffman
	{
		// counts[n] = how many codes have length n; symbols are ordered by (length, symbol).
		int counts[16]{};
		std::vector<int> symbols;

		void Build(const uint8_t* lengths, int count)
		{
			std::memset(counts, 0, sizeof counts);
			for (int i = 0; i < count; ++i)
				counts[lengths[i]]++;
			counts[0] = 0;

			int offsets[16]{};
			for (int i = 1; i < 16; ++i)
				offsets[i] = offsets[i - 1] + counts[i - 1];

			symbols.assign(count, 0);
			for (int i = 0; i < count; ++i)
				if (lengths[i])
					symbols[offsets[lengths[i]]++] = i;
		}

		int Decode(BitReader& in) const
		{
			int code = 0, first = 0, index = 0;
			for (int len = 1; len < 16; ++len)
			{
				code |= static_cast<int>(in.Bits(1));
				int count = counts[len];
				if (code - first < count)
					return symbols[index + (code - first)];
				index += count;
				first = (first + count) << 1;
				code <<= 1;
			}
			return -1;
		}
	};

	const int kLengthBase[29] = {
		3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
		35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258 };
	const int kLengthExtra[29] = {
		0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
		3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0 };
	const int kDistBase[30] = {
		1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
		257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577 };
	const int kDistExtra[30] = {
		0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
		7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13 };

	bool InflateBlocks(BitReader& in, std::vector<uint8_t>& out)
	{
		Huffman fixedLit, fixedDist;
		bool fixedReady = false;

		for (;;)
		{
			uint32_t last = in.Bits(1);
			uint32_t type = in.Bits(2);
			if (in.Bad())
				return false;

			if (type == 0)
			{
				// Stored: byte-aligned LEN / ~LEN then the bytes verbatim.
				in.AlignToByte();
				size_t at = in.BytePos();
				if (at + 4 > in.Length())
					return false;

				uint16_t len = static_cast<uint16_t>(in.Data()[at] | (in.Data()[at + 1] << 8));
				at += 4;
				if (at + len > in.Length())
					return false;

				out.insert(out.end(), in.Data() + at, in.Data() + at + len);
				in.Skip((at + len) - in.BytePos());
			}
			else if (type == 1 || type == 2)
			{
				Huffman literal, distance;

				if (type == 1)
				{
					if (!fixedReady)
					{
						uint8_t lengths[288];
						for (int i = 0; i < 144; ++i) lengths[i] = 8;
						for (int i = 144; i < 256; ++i) lengths[i] = 9;
						for (int i = 256; i < 280; ++i) lengths[i] = 7;
						for (int i = 280; i < 288; ++i) lengths[i] = 8;
						fixedLit.Build(lengths, 288);

						uint8_t distLengths[30];
						for (int i = 0; i < 30; ++i) distLengths[i] = 5;
						fixedDist.Build(distLengths, 30);
						fixedReady = true;
					}
					literal = fixedLit;
					distance = fixedDist;
				}
				else
				{
					int hlit = static_cast<int>(in.Bits(5)) + 257;
					int hdist = static_cast<int>(in.Bits(5)) + 1;
					int hclen = static_cast<int>(in.Bits(4)) + 4;
					if (in.Bad() || hlit > 288 || hdist > 30)
						return false;

					static const int order[19] = {
						16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15 };

					uint8_t codeLengths[19]{};
					for (int i = 0; i < hclen; ++i)
						codeLengths[order[i]] = static_cast<uint8_t>(in.Bits(3));

					Huffman codeTable;
					codeTable.Build(codeLengths, 19);

					// The literal and distance lengths share one run-length-coded sequence.
					uint8_t lengths[288 + 30]{};
					int have = 0;
					while (have < hlit + hdist)
					{
						int symbol = codeTable.Decode(in);
						if (symbol < 0 || in.Bad())
							return false;

						if (symbol < 16)
						{
							lengths[have++] = static_cast<uint8_t>(symbol);
						}
						else if (symbol == 16)
						{
							if (!have) return false;
							uint8_t previous = lengths[have - 1];
							int repeat = 3 + static_cast<int>(in.Bits(2));
							while (repeat-- && have < hlit + hdist) lengths[have++] = previous;
						}
						else if (symbol == 17)
						{
							int repeat = 3 + static_cast<int>(in.Bits(3));
							while (repeat-- && have < hlit + hdist) lengths[have++] = 0;
						}
						else
						{
							int repeat = 11 + static_cast<int>(in.Bits(7));
							while (repeat-- && have < hlit + hdist) lengths[have++] = 0;
						}
					}

					literal.Build(lengths, hlit);
					distance.Build(lengths + hlit, hdist);
				}

				for (;;)
				{
					int symbol = literal.Decode(in);
					if (symbol < 0 || in.Bad())
						return false;

					if (symbol < 256)
					{
						out.push_back(static_cast<uint8_t>(symbol));
					}
					else if (symbol == 256)
					{
						break;                      // end of block
					}
					else
					{
						symbol -= 257;
						if (symbol >= 29)
							return false;

						int length = kLengthBase[symbol] + static_cast<int>(in.Bits(kLengthExtra[symbol]));

						int distSymbol = distance.Decode(in);
						if (distSymbol < 0 || distSymbol >= 30)
							return false;

						int dist = kDistBase[distSymbol] + static_cast<int>(in.Bits(kDistExtra[distSymbol]));
						if (dist <= 0 || static_cast<size_t>(dist) > out.size())
							return false;

						// Copies may overlap, so this must be byte at a time.
						size_t from = out.size() - dist;
						for (int i = 0; i < length; ++i)
							out.push_back(out[from + i]);
					}
				}
			}
			else
			{
				return false;                       // type 3 is reserved
			}

			if (last)
				break;
		}

		return true;
	}
}

bool Deflate::Zlib(const uint8_t* data, size_t length, size_t expected, std::vector<uint8_t>& out)
{
	if (length < 2)
		return false;

	// RFC 1950 header: low nibble of byte 0 must be 8 (deflate), and the two bytes together
	// must be a multiple of 31.
	if ((data[0] & 0x0F) != 8 || ((data[0] << 8) | data[1]) % 31 != 0)
		return false;

	size_t start = 2;
	if (data[1] & 0x20)
		start += 4;                                 // preset dictionary, which we do not support
	if (start >= length)
		return false;

	out.clear();
	if (expected && expected < (64u << 20))
		out.reserve(expected);

	BitReader in(data + start, length - start);
	return InflateBlocks(in, out);
}

// ── the archive ─────────────────────────────────────────────────────────────

std::string Bsa::Normalise(const std::string& path)
{
	std::string out;
	out.reserve(path.size());
	for (char c : path)
	{
		if (c == '/') c = '\\';
		out += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
	}
	return out;
}

bool Bsa::Open(const std::string& path)
{
	entries.clear();
	archivePath = path;

	std::ifstream file(path, std::ios::binary);
	if (!file)
		return false;

	struct Header
	{
		char magic[4];
		uint32_t version;
		uint32_t folderOffset;
		uint32_t flags;
		uint32_t folderCount;
		uint32_t fileCount;
		uint32_t totalFolderNameLength;
		uint32_t totalFileNameLength;
		uint32_t fileFlags;
	} header{};

	file.read(reinterpret_cast<char*>(&header), sizeof header);
	if (!file || std::memcmp(header.magic, "BSA\0", 4) != 0 || header.version != 104)
		return false;

	// Names are what we index by; without them there is nothing to look anything up with.
	if (!(header.flags & 0x1) || !(header.flags & 0x2))
		return false;

	archiveFlags = header.flags;

	struct FolderRecord { uint64_t hash; uint32_t count; uint32_t offset; };
	std::vector<FolderRecord> folders(header.folderCount);

	file.seekg(header.folderOffset);
	file.read(reinterpret_cast<char*>(folders.data()),
		static_cast<std::streamsize>(folders.size() * sizeof(FolderRecord)));
	if (!file)
		return false;

	// Pass one: folder names and the file records under each.
	std::vector<std::string> folderNames;
	std::vector<Entry> flat;
	folderNames.reserve(header.fileCount);
	flat.reserve(header.fileCount);

	for (const FolderRecord& folder : folders)
	{
		// The recorded offset counts the file-name block too, which sits after the folder blocks.
		file.seekg(static_cast<std::streamoff>(folder.offset) - header.totalFileNameLength);
		if (!file)
			return false;

		uint8_t nameLength = 0;
		file.read(reinterpret_cast<char*>(&nameLength), 1);

		std::string name(nameLength, '\0');
		file.read(name.data(), nameLength);
		while (!name.empty() && name.back() == '\0')
			name.pop_back();

		for (uint32_t i = 0; i < folder.count; ++i)
		{
			struct FileRecord { uint64_t hash; uint32_t size; uint32_t offset; } record{};
			file.read(reinterpret_cast<char*>(&record), sizeof record);
			if (!file)
				return false;

			folderNames.push_back(name);
			flat.push_back(Entry{ record.offset, record.size });
		}
	}

	// Pass two: the file-name block, one null-terminated name per record, in the same order.
	std::string block(header.totalFileNameLength, '\0');
	file.read(block.data(), header.totalFileNameLength);
	if (!file)
		return false;

	size_t at = 0;
	for (size_t i = 0; i < flat.size(); ++i)
	{
		if (at >= block.size())
			break;

		size_t end = block.find('\0', at);
		if (end == std::string::npos)
			end = block.size();

		std::string fileName = block.substr(at, end - at);
		at = end + 1;

		entries.emplace(Normalise(folderNames[i] + "\\" + fileName), flat[i]);
	}

	return !entries.empty();
}

bool Bsa::Has(const std::string& path) const
{
	return entries.find(Normalise(path)) != entries.end();
}

bool Bsa::Read(const std::string& path, std::vector<uint8_t>& out) const
{
	auto found = entries.find(Normalise(path));
	if (found == entries.end())
		return false;

	const Entry& entry = found->second;

	// Bit 30 flips the archive-wide compression default for this one file.
	bool compressed = ((entry.packedSize & 0x40000000u) != 0) != ((archiveFlags & 0x4) != 0);
	uint32_t stored = entry.packedSize & 0x3FFFFFFFu;
	if (!stored || stored > (256u << 20))
		return false;

	std::ifstream file(archivePath, std::ios::binary);
	if (!file)
		return false;

	file.seekg(entry.offset);
	std::vector<uint8_t> blob(stored);
	file.read(reinterpret_cast<char*>(blob.data()), stored);
	if (!file)
		return false;

	size_t cursor = 0;

	// Fallout 3 / New Vegas archives may prefix each entry with its own name.
	if (archiveFlags & 0x100)
	{
		if (blob.empty())
			return false;
		cursor = 1 + blob[0];
		if (cursor > blob.size())
			return false;
	}

	if (!compressed)
	{
		out.assign(blob.begin() + cursor, blob.end());
		return !out.empty();
	}

	if (cursor + 4 > blob.size())
		return false;

	uint32_t originalSize = 0;
	std::memcpy(&originalSize, blob.data() + cursor, 4);
	cursor += 4;

	return Deflate::Zlib(blob.data() + cursor, blob.size() - cursor, originalSize, out);
}

std::vector<std::string> Bsa::AllPaths() const
{
	std::vector<std::string> out;
	out.reserve(entries.size());
	for (const auto& pair : entries)
		out.push_back(pair.first);
	std::sort(out.begin(), out.end());
	return out;
}
