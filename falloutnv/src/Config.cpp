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

	char buf[MAX_PATH]{};
	GetPrivateProfileStringA("Paths", "WebRootOverride", "", buf, sizeof buf, p);
	c.webRootOverride = buf;

	// A poll faster than the game's own frame rate just burns CPU for no extra information.
	if (c.updatesPerSecond < 1) c.updatesPerSecond = 1;
	if (c.updatesPerSecond > 30) c.updatesPerSecond = 30;

	return c;
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
		maxMapMarkers, maxInventoryItems, enableIcons ? 1 : 0);

	std::fclose(f);
}
