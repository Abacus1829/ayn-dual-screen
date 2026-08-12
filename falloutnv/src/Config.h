#pragma once

#include <string>

/// Read from Data\NVSE\Plugins\AynDualScreen.ini, written with defaults if it isn't there.
///
/// Defaults follow the same rule the Terraria and Minecraft mods settled on: anything that can
/// lose you an item, move your character or spend your time is off until you turn it on. LAN
/// access is the one exception, because a second screen you can't reach from the second device
/// isn't a second screen.
struct Config
{
	unsigned short port = 27303;
	bool allowLan = true;
	int updatesPerSecond = 10;

	bool allowEquip = true;
	bool allowUse = true;
	bool allowSetQuest = true;
	bool allowRadio = true;

	bool allowDrop = false;         // throws an item on the ground

	/// Moves the character and burns game hours. On by default because it is the whole point of a
	/// map you can touch, and unlike dropping an item it costs nothing you cannot walk back.
	bool allowFastTravel = true;

	int maxMapMarkers = 250;
	int maxInventoryItems = 400;

	/// Sketch a local map indoors from the doors, containers, actors and furniture in the cell.
	/// This is NOT the game's own local map -- that is rendered by the engine from cell geometry
	/// and cannot be read or extracted. Off by default because it is an approximation.
	bool enableLocalMap = false;
	int maxLocalRefs = 150;

	/// Read icons out of the game's own texture archives and serve them to your screen. Nothing is
	/// written anywhere; set false to skip opening the archives at all.
	bool enableIcons = true;

	/// Filter the FALLBACK station list to ones that look receivable.
	///
	/// Only applies before the Pip-Boy's own dial has been read. Once you have opened DATA -> Radio
	/// in game the list comes from the menu itself, which is the game's own answer and needs no
	/// filtering. Off by default because the fallback's idea of range is built on a structure the
	/// SDK does not map, and it was wrong in both directions -- an empty tab gives you no way to
	/// tell a quiet wasteland from a broken reader.
	bool radioInRangeOnly = false;

	/// Dump the Pip-Boy menu's tile tree into the snapshot while the Pip-Boy is open.
	///
	/// Purely a diagnostic. The menu's shape is not documented anywhere this project can consult,
	/// so learning it means looking at a real one; this is how. Off by default -- it walks a tree
	/// every snapshot and puts a few hundred lines into every response.
	bool dumpPipboyMenu = false;

	/// A shared secret every request must carry. Empty means no check, which is the default and
	/// matches how the other mods in this repository behave.
	///
	/// Worth setting on a network you do not fully trust: LAN access is on by default and the
	/// screen can equip, use, drop and fast travel. This is a doorlock, not encryption -- the
	/// traffic is still plain HTTP, so it stops the neighbour's laptop, not someone reading the
	/// wire.
	std::string accessToken;

	/// A folder to serve web/ from instead of the one beside the DLL. Set it to this project's
	/// web/ folder and a CSS change is live on the next refresh, with no rebuild.
	std::string webRootOverride;

	static Config Load(const std::string& path);

	/// Writes every current value, with the explanatory comments. Used both to lay the file down
	/// the first time and to save a change made from the second screen.
	void WriteDefaults(const std::string& path) const;

	/// The settings as JSON, for the screen's settings panel. Includes which of them need a
	/// restart, so the panel can say so rather than leaving someone wondering why nothing changed.
	std::string ToJson() const;

	/// Apply one setting by name. Returns false if the name isn't known or the value is out of
	/// range -- the caller keeps the old value rather than storing something nonsensical.
	bool Set(const std::string& key, const std::string& value);
};
