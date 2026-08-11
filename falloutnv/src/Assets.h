#pragma once

// Serves textures out of the game's own archives, as PNGs the browser can display.
//
// The rule this whole feature lives under: the archives belong to whoever installed the game.
// This reads them on that machine, at runtime, and serves the result to that same person's second
// screen. Nothing extracted here is ever written into the repository, and the mod ships no game
// art of its own. That is the same arrangement the Terraria mod uses.
//
// Unlike almost everything else in this plugin, none of this touches game state -- it is file I/O
// and pixel maths -- so it runs on the HTTP worker threads, with a mutex around the cache only.

#include <cstdint>
#include <string>

namespace Assets
{
	/// Point at the game's Data folder and index the texture archives. Cheap: reads headers and
	/// name tables only, never pixel data. Safe to call more than once.
	void Init(const std::string& dataFolder, bool enabled);

	/// Fetch one texture as a PNG, by its archive-relative path
	/// ("textures\\interface\\icons\\pipboyimages\\weapons\\weapons_10mm_pistol.dds").
	/// The ".dds" may be given as ".png"; both resolve to the same entry.
	/// Returns false if disabled, not found, or not a format we decode.
	bool Png(const std::string& path, std::string& out);

	/// A one-line summary for the log: which archives opened and how many files each holds.
	std::string Describe();
}
