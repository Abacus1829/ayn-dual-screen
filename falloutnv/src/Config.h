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

	/// Read icons out of the game's own texture archives and serve them to your screen. Nothing is
	/// written anywhere; set false to skip opening the archives at all.
	bool enableIcons = true;

	/// A folder to serve web/ from instead of the one beside the DLL. Set it to this project's
	/// web/ folder and a CSS change is live on the next refresh, with no rebuild.
	std::string webRootOverride;

	static Config Load(const std::string& path);
	void WriteDefaults(const std::string& path) const;
};
