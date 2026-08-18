#pragma once

#include <cstdint>

class Json;
struct Config;

namespace RE
{
	class PlayerCharacter;
	class TESObjectREFR;
}

namespace MapData
{
	/// Game thread only. Writes the "map" object: where you are, which worldspace you are in, and
	/// every map marker that worldspace holds.
	void Write(Json& j, RE::PlayerCharacter* player, const Config& config);

	/// Game thread only. Look a marker back up by form ID, and answer whether travelling to it is
	/// something the game itself would allow right now.
	///
	/// The screen sends an ID; it does not send permission. The decision about whether that marker
	/// has been discovered, and whether it can be travelled to, is made here every time -- because
	/// moving a character to somewhere they have never found is a save state the game does not
	/// produce on its own, and it is not this mod's place to invent one.
	RE::TESObjectREFR* FindTravellableMarker(std::uint32_t formId);
}
