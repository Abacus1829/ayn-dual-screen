// A test harness for the BSA reader, the DDS decoder and the PNG encoder.
//
// These three carry no game dependency at all -- they are file-format code -- so they can be built
// and exercised outside New Vegas, against the real archives, without launching anything. That is
// the whole point of this file: the plugin itself can only be tested by playing the game, and this
// part does not have to be.
//
//     tools\build-assetdump.ps1
//     build\assetdump.exe "<game>\Data\Fallout - Textures2.bsa" list interface\icons
//     build\assetdump.exe "<game>\Data\Fallout - Textures2.bsa" png "textures\...\x.dds" out.png
//
// Anything it writes is scratch output on the machine that already owns the game. Nothing it
// produces belongs in this repository.

#include "../src/Bsa.h"
#include "../src/Dds.h"
#include "../src/Png.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

static int Usage()
{
	std::printf(
		"usage:\n"
		"  assetdump <archive.bsa> list [substring]      index the archive, optionally filtered\n"
		"  assetdump <archive.bsa> info <path-in-bsa>    decode and report size/format\n"
		"  assetdump <archive.bsa> png <path-in-bsa> <out.png>\n"
		"  assetdump <archive.bsa> sweep <substring>     decode everything matching, report failures\n");
	return 2;
}

int main(int argc, char** argv)
{
	if (argc < 3)
		return Usage();

	Bsa bsa;
	if (!bsa.Open(argv[1]))
	{
		std::printf("FAILED to open %s as a v104 BSA\n", argv[1]);
		return 1;
	}

	std::printf("opened %s: %zu files\n", argv[1], bsa.FileCount());
	std::string command = argv[2];

	if (command == "list")
	{
		std::string filter = argc > 3 ? argv[3] : "";
		for (char& c : filter)
			c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));

		// Prints every match. This used to stop at forty, which quietly hid whole folders and led
		// to at least two wrong "it isn't in the archives" conclusions. If the output is long,
		// redirect it -- a truncating search tool is worse than no search tool.
		size_t shown = 0;
		for (const std::string& path : bsa.AllPaths())
		{
			if (!filter.empty() && path.find(filter) == std::string::npos)
				continue;
			std::printf("  %s\n", path.c_str());
			++shown;
		}
		std::printf("%zu matching\n", shown);
		return 0;
	}

	if (command == "sweep")
	{
		std::string filter = argc > 3 ? argv[3] : "";
		for (char& c : filter)
			c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));

		size_t tried = 0, readFail = 0, decodeFail = 0, encodeFail = 0, ok = 0;
		size_t widest = 0;

		for (const std::string& path : bsa.AllPaths())
		{
			if (!filter.empty() && path.find(filter) == std::string::npos)
				continue;
			if (path.size() < 4 || path.compare(path.size() - 4, 4, ".dds") != 0)
				continue;

			++tried;

			std::vector<uint8_t> raw;
			if (!bsa.Read(path, raw)) { ++readFail; std::printf("  READ FAIL  %s\n", path.c_str()); continue; }

			Dds::Image image;
			if (!Dds::Decode(raw, image) || !image.Valid())
			{
				++decodeFail;
				if (decodeFail < 8) std::printf("  DECODE FAIL %s (%zu bytes)\n", path.c_str(), raw.size());
				continue;
			}

			std::string png = Png::Encode(image.rgba, image.width, image.height);
			if (png.empty()) { ++encodeFail; continue; }

			if (png.size() > widest) widest = png.size();
			++ok;
		}

		std::printf("\nsweep: %zu tried, %zu ok, %zu read-fail, %zu decode-fail, %zu encode-fail\n",
			tried, ok, readFail, decodeFail, encodeFail);
		std::printf("largest PNG produced: %zu bytes\n", widest);
		return (readFail || encodeFail) ? 1 : 0;
	}

	if (argc < 4)
		return Usage();

	std::vector<uint8_t> raw;
	if (!bsa.Read(argv[3], raw))
	{
		std::printf("FAILED to read %s out of the archive\n", argv[3]);
		return 1;
	}
	std::printf("read %s: %zu bytes, starts %.4s\n", argv[3], raw.size(), reinterpret_cast<const char*>(raw.data()));

	Dds::Image image;
	if (!Dds::Decode(raw, image) || !image.Valid())
	{
		std::printf("FAILED to decode as DDS\n");
		return 1;
	}
	std::printf("decoded %ux%u, %zu bytes RGBA\n", image.width, image.height, image.rgba.size());

	if (command == "info")
		return 0;

	if (command != "png" || argc < 5)
		return Usage();

	std::string png = Png::Encode(image.rgba, image.width, image.height);
	if (png.empty())
	{
		std::printf("FAILED to encode PNG\n");
		return 1;
	}

	std::ofstream out(argv[4], std::ios::binary);
	out.write(png.data(), static_cast<std::streamsize>(png.size()));
	std::printf("wrote %s, %zu bytes\n", argv[4], png.size());
	return 0;
}
