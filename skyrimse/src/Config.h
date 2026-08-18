#pragma once

#include <string>

/// Read from Data\SKSE\Plugins\AynDualScreen.ini, written with defaults if it isn't there.
///
/// Defaults follow the rule the other mods in this repository settled on: anything that can lose
/// you an item, move your character or spend your time is off until you turn it on. LAN access is
/// the one exception, because a second screen you can't reach from the second device isn't a
/// second screen.
struct Config
{
	unsigned short port = 27305;
	bool allowLan = true;
	int updatesPerSecond = 10;

	bool allowEquip = true;         // weapons, armor, spells in a hand
	bool allowUse = true;           // drink a potion, eat, read a scroll
	bool allowFavorite = true;      // toggling a favourite changes nothing you can lose
	bool allowSetQuest = true;

	bool allowDrop = false;         // throws an item on the ground

	/// Moves the character and burns game hours. Off by default, unlike the New Vegas mod's, and
	/// the difference is deliberate: Skyrim's travel eats a real amount of in-game time, wandering
	/// monsters resolve during it, and survival mods make it costly in ways this plugin has no way
	/// to see. Turn it on if you want the map to be more than a map.
	bool allowFastTravel = false;

	/// Passing time from the panel. Off by default for the same reason as fast travel, and refused
	/// outright in combat and indoors-when-the-game-would-refuse, on the game thread.
	bool allowWait = false;

	int maxMapMarkers = 400;
	int maxInventoryItems = 600;

	/// Send effect descriptions and enchantment text. They are looked up per item and cost more to
	/// build than any other part of the snapshot; turn it off if a huge inventory makes the feed
	/// stutter on a weak machine.
	bool enableDescriptions = true;

	/// Load the most recent save by itself, a few seconds after the main menu appears.
	///
	/// A TEST HARNESS, not a feature. It exists so this mod can be driven through a full
	/// load-and-snapshot cycle without a person sitting at the menu clicking Load -- which is how
	/// the hang in the player reader was finally cornered. It loads; it never saves.
	///
	/// Off by default and it should stay off in anything shipped. If you find this on in a release,
	/// that is a mistake worth reporting.
	bool debugAutoLoad = false;

	/// A shared secret every request must carry. Empty means no check, which is the default and
	/// matches how the other mods in this repository behave.
	///
	/// Worth setting on a network you do not fully trust: LAN access is on by default and the
	/// screen can equip, use and drop. This is a doorlock, not encryption -- the traffic is still
	/// plain HTTP, so it stops the neighbour's laptop, not someone reading the wire.
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
