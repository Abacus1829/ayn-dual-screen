#pragma once

#include <cstdint>
#include <string>

struct Config;

/// One command from the second screen, already parsed but not yet checked or applied.
///
/// Everything is addressed by form ID rather than by position in a list: a tap that lands one frame
/// after the inventory shifted must not be able to act on the wrong object, and the screen's idea
/// of "row 4" is always at least one frame stale.
struct Command
{
	enum class Kind
	{
		None,
		Equip,
		Unequip,
		Use,
		Drop,
		Favorite,
		EquipSpell,
		EquipShout,
		SetQuest,
		FastTravel,
		Wait,
	};

	Kind kind = Kind::None;
	std::uint32_t id = 0;        // form ID of the item, spell, quest or map marker
	int count = 1;               // drop
	int hours = 1;               // wait
	bool leftHand = false;       // equip, equipSpell
	bool handGiven = false;      // false means "wherever it goes" -- armor, ammo, one-hand default
	bool on = true;              // favorite
};

namespace Actions
{
	/// Parse one flat JSON command object. Returns Kind::None for anything unrecognised.
	Command Parse(const std::string& body);

	/// Game thread only. Re-checks the config permission and applies the command, or refuses it.
	///
	/// The permission is checked HERE and not only when the snapshot was built. The snapshot tells
	/// the screen what it may do so the right buttons grey out, but nothing the screen sends can
	/// talk its way past the config -- the decision is made again, on this side, every time.
	void Apply(const Command& command, const Config& config);
}
