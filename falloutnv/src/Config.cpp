#include "Config.h"

#include <windows.h>
#include <cstdio>

static bool ReadBool(const char* section, const char* key, bool fallback, const char* path)
{
	return GetPrivateProfileIntA(section, key, fallback ? 1 : 0, path) != 0;
}

Config Config::Load(const std::string& path)
{
	Config c;

	if (GetFileAttributesA(path.c_str()) == INVALID_FILE_ATTRIBUTES)
	{
		c.WriteDefaults(path);
		return c;
	}

	const char* p = path.c_str();

	c.port = static_cast<unsigned short>(GetPrivateProfileIntA("Server", "Port", c.port, p));
	c.allowLan = ReadBool("Server", "AllowLanAccess", c.allowLan, p);
	c.updatesPerSecond = GetPrivateProfileIntA("Server", "UpdatesPerSecond", c.updatesPerSecond, p);

	c.allowEquip = ReadBool("Control", "AllowEquip", c.allowEquip, p);
	c.allowUse = ReadBool("Control", "AllowUse", c.allowUse, p);
	c.allowSetQuest = ReadBool("Control", "AllowSetQuest", c.allowSetQuest, p);
	c.allowRadio = ReadBool("Control", "AllowRadio", c.allowRadio, p);
	c.allowDrop = ReadBool("Control", "AllowDrop", c.allowDrop, p);
	c.allowFastTravel = ReadBool("Control", "AllowFastTravel", c.allowFastTravel, p);

	c.maxMapMarkers = GetPrivateProfileIntA("Limits", "MaxMapMarkers", c.maxMapMarkers, p);
	c.maxInventoryItems = GetPrivateProfileIntA("Limits", "MaxInventoryItems", c.maxInventoryItems, p);

	c.enableIcons = ReadBool("Assets", "EnableIcons", c.enableIcons, p);

	c.enableLocalMap = ReadBool("Map", "EnableLocalMap", c.enableLocalMap, p);
	c.maxLocalRefs = GetPrivateProfileIntA("Map", "MaxLocalRefs", c.maxLocalRefs, p);

	char buf[MAX_PATH]{};
	GetPrivateProfileStringA("Paths", "WebRootOverride", "", buf, sizeof buf, p);
	c.webRootOverride = buf;

	// A poll faster than the game's own frame rate just burns CPU for no extra information.
	if (c.updatesPerSecond < 1) c.updatesPerSecond = 1;
	if (c.updatesPerSecond > 30) c.updatesPerSecond = 30;

	return c;
}

std::string Config::ToJson() const
{
	// Hand-built, like the snapshot: four endpoints do not justify a JSON library. "restart" marks
	// the two that only take effect when the server is rebuilt, so the panel can say so instead of
	// leaving someone wondering why nothing happened.
	std::string out = "{\"settings\":[";

	auto boolRow = [&](const char* key, bool value, const char* label, const char* help, bool restart) {
		if (out.back() != '[') out += ',';
		out += "{\"key\":\""; out += key;
		out += "\",\"type\":\"bool\",\"value\":"; out += value ? "true" : "false";
		out += ",\"label\":\""; out += label;
		out += "\",\"help\":\""; out += help;
		out += "\",\"restart\":"; out += restart ? "true" : "false";
		out += '}';
	};

	auto intRow = [&](const char* key, int value, int lo, int hi, const char* label, const char* help, bool restart) {
		if (out.back() != '[') out += ',';
		out += "{\"key\":\""; out += key;
		out += "\",\"type\":\"int\",\"value\":" + std::to_string(value);
		out += ",\"min\":" + std::to_string(lo) + ",\"max\":" + std::to_string(hi);
		out += ",\"label\":\""; out += label;
		out += "\",\"help\":\""; out += help;
		out += "\",\"restart\":"; out += restart ? "true" : "false";
		out += '}';
	};

	intRow("Port", port, 1024, 65535, "Port",
		"Change it if something else is using it.", true);
	boolRow("AllowLanAccess", allowLan, "LAN access",
		"Lets another device on your network open this screen. Off restricts it to this PC.", true);
	intRow("UpdatesPerSecond", updatesPerSecond, 1, 30, "Updates / sec",
		"How often the snapshot is rebuilt. Lower it if you see a frame-rate cost.", false);

	boolRow("AllowEquip", allowEquip, "Allow equip", "Equip and unequip from the screen.", false);
	boolRow("AllowUse", allowUse, "Allow use", "Use aid items from the screen.", false);
	boolRow("AllowDrop", allowDrop, "Allow drop",
		"Lets the screen throw items on the ground. Off by default.", false);
	boolRow("AllowFastTravel", allowFastTravel, "Allow fast travel",
		"Moves your character and burns game hours.", false);
	boolRow("AllowSetQuest", allowSetQuest, "Allow set quest",
		"Make a quest the active one from the DATA tab.", false);
	boolRow("AllowRadio", allowRadio, "Allow radio",
		"Tune stations from the RADIO tab.", false);

	boolRow("EnableIcons", enableIcons, "Icons",
		"Read icons out of your own game archives. Off skips opening them entirely.", true);
	intRow("MaxMapMarkers", maxMapMarkers, 10, 1000, "Max map markers",
		"Ceiling on markers per snapshot.", false);
	intRow("MaxInventoryItems", maxInventoryItems, 20, 2000, "Max inventory items",
		"Ceiling on inventory entries per snapshot.", false);

	boolRow("EnableLocalMap", enableLocalMap, "Local map (approximate)",
		"Sketches a floor plan indoors from the doors, containers and people in the room. It is "
		"NOT the game's own local map, which the engine renders from cell geometry and cannot be "
		"read. Off by default.", false);
	intRow("MaxLocalRefs", maxLocalRefs, 20, 500, "Max local map points",
		"Ceiling on how many things the local map sketch plots.", false);

	out += "]}";
	return out;
}

bool Config::Set(const std::string& key, const std::string& value)
{
	auto asBool = [&]() { return value == "1" || value == "true" || value == "on"; };

	auto asInt = [&](int lo, int hi, int& target) {
		int parsed = atoi(value.c_str());
		if (parsed < lo || parsed > hi)
			return false;                    // out of range: keep what we had
		target = parsed;
		return true;
	};

	if (key == "AllowLanAccess")   { allowLan = asBool(); return true; }
	if (key == "AllowEquip")       { allowEquip = asBool(); return true; }
	if (key == "AllowUse")         { allowUse = asBool(); return true; }
	if (key == "AllowDrop")        { allowDrop = asBool(); return true; }
	if (key == "AllowFastTravel")  { allowFastTravel = asBool(); return true; }
	if (key == "AllowSetQuest")    { allowSetQuest = asBool(); return true; }
	if (key == "AllowRadio")       { allowRadio = asBool(); return true; }
	if (key == "EnableIcons")      { enableIcons = asBool(); return true; }
	if (key == "EnableLocalMap")   { enableLocalMap = asBool(); return true; }

	if (key == "UpdatesPerSecond")  return asInt(1, 30, updatesPerSecond);
	if (key == "MaxMapMarkers")     return asInt(10, 1000, maxMapMarkers);
	if (key == "MaxLocalRefs")      return asInt(20, 500, maxLocalRefs);
	if (key == "MaxInventoryItems") return asInt(20, 2000, maxInventoryItems);

	if (key == "Port")
	{
		int parsed = atoi(value.c_str());
		if (parsed < 1024 || parsed > 65535)
			return false;
		port = static_cast<unsigned short>(parsed);
		return true;
	}

	return false;                            // unknown key; ignore rather than guess
}

void Config::WriteDefaults(const std::string& path) const
{
	// Written by hand rather than through WritePrivateProfileString so the comments survive --
	// the point of the file is that someone can read it and understand what they are turning on.
	FILE* f = nullptr;
	if (fopen_s(&f, path.c_str(), "w") != 0 || !f)
		return;

	std::fprintf(f,
		"; Ayn Dual Screen - Fallout: New Vegas\n"
		"; Delete this file to get the defaults back.\n"
		"\n"
		"[Server]\n"
		"Port=%u\n"
		"\n"
		"; Lets another device on your network open the screen. ON by default -- with it on,\n"
		"; anyone on your network can open the page. Set to 0 on a network you do not trust.\n"
		"AllowLanAccess=%d\n"
		"\n"
		"; How often the snapshot is rebuilt. Lower it if you see a frame-rate cost.\n"
		"UpdatesPerSecond=%d\n"
		"\n"
		"[Control]\n"
		"; What the touch screen is allowed to do. Everything that can lose you an item, move\n"
		"; your character or burn game hours is off until you turn it on.\n"
		"AllowEquip=%d\n"
		"AllowUse=%d\n"
		"AllowSetQuest=%d\n"
		"AllowRadio=%d\n"
		"AllowDrop=%d\n"
		"AllowFastTravel=%d\n"
		"\n"
		"[Limits]\n"
		"; Ceilings on how much goes into one snapshot. The Mojave has a lot of map markers and\n"
		"; a hoarder has a lot of junk; these keep the payload from getting silly.\n"
		"MaxMapMarkers=%d\n"
		"MaxInventoryItems=%d\n"
		"\n"
		"[Map]\n"
		"; Sketch a local map indoors from the doors, containers, actors and furniture in the\n"
		"; cell. This is NOT the game's own local map -- that one is rendered by the engine from\n"
		"; the cell's geometry, and there is no texture to extract or structure to read. This is\n"
		"; an approximation built from the things you navigate by. Off by default.\n"
		"EnableLocalMap=%d\n"
		"MaxLocalRefs=%d\n"
		"\n"
		"[Assets]\n"
		"; Read item and perk icons out of the game's own texture archives and serve them to your\n"
		"; screen. Nothing is copied or written anywhere -- the images are decoded in memory from\n"
		"; your own installed copy of the game. Set 0 to skip opening the archives entirely.\n"
		"EnableIcons=%d\n"
		"\n"
		"[Paths]\n"
		"; Serve web/ from somewhere else, so the UI can be edited without rebuilding the DLL.\n"
		"WebRootOverride=\n",
		static_cast<unsigned>(port), allowLan ? 1 : 0, updatesPerSecond,
		allowEquip ? 1 : 0, allowUse ? 1 : 0, allowSetQuest ? 1 : 0, allowRadio ? 1 : 0,
		allowDrop ? 1 : 0, allowFastTravel ? 1 : 0,
		maxMapMarkers, maxInventoryItems,
		enableLocalMap ? 1 : 0, maxLocalRefs,
		enableIcons ? 1 : 0);

	std::fclose(f);
}
