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

	/// Only list radio stations you can actually pick up, the way the Pip-Boy does.
	///
	/// Turning this off lists every station in the load order regardless. That is the escape hatch
	/// for when the range read is wrong: the range data lives in a structure the SDK does not map,
	/// so a filter built on it can hide stations that really are receivable, and an empty tab gives
	/// you no way to tell a quiet wasteland from a broken reader.
	bool radioInRangeOnly = true;

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
