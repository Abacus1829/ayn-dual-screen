#include "Config.h"

#include "Json.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>

// A hand-rolled ini reader rather than a library. The file has fourteen keys in one section and is
// written by this same file, so the only shapes it ever has to survive are the ones a person types
// into it by hand: blank lines, comments, and stray whitespace.

namespace
{
	std::string Trim(const std::string& s)
	{
		size_t start = s.find_first_not_of(" \t\r\n");
		if (start == std::string::npos)
			return {};
		size_t end = s.find_last_not_of(" \t\r\n");
		return s.substr(start, end - start + 1);
	}

	/// Anything a person might reasonably type. An unrecognised value keeps the old setting rather
	/// than defaulting to off, because silently disabling something someone tried to enable is the
	/// worse of the two failures.
	bool TruthyOf(const std::string& v, bool fallback)
	{
		std::string lower;
		for (unsigned char c : v)
			lower += static_cast<char>(std::tolower(c));

		if (lower == "1" || lower == "true" || lower == "yes" || lower == "on")
			return true;
		if (lower == "0" || lower == "false" || lower == "no" || lower == "off")
			return false;
		return fallback;
	}

	int ClampedInt(const std::string& v, int low, int high, int fallback)
	{
		if (v.empty())
			return fallback;
		int parsed = std::atoi(v.c_str());
		if (parsed < low || parsed > high)
			return fallback;
		return parsed;
	}
}

Config Config::Load(const std::string& path)
{
	Config config;

	std::ifstream file(path);
	if (!file)
	{
		// First run. Lay the file down with the defaults and the comments, so the settings are
		// discoverable without reading the README.
		config.WriteDefaults(path);
		return config;
	}

	std::string line;
	while (std::getline(file, line))
	{
		std::string trimmed = Trim(line);
		if (trimmed.empty() || trimmed[0] == ';' || trimmed[0] == '#' || trimmed[0] == '[')
			continue;

		size_t equals = trimmed.find('=');
		if (equals == std::string::npos)
			continue;

		config.Set(Trim(trimmed.substr(0, equals)), Trim(trimmed.substr(equals + 1)));
	}

	return config;
}

bool Config::Set(const std::string& key, const std::string& value)
{
	auto is = [&](const char* name) { return _stricmp(key.c_str(), name) == 0; };

	if (is("Port"))
	{
		// Below 1024 needs privileges we do not have and should not want; above 65535 is not a
		// port. Either one leaves the previous value in place rather than failing to listen.
		int parsed = ClampedInt(value, 1024, 65535, -1);
		if (parsed < 0)
			return false;
		port = static_cast<unsigned short>(parsed);
		return true;
	}

	if (is("AllowLanAccess"))     { allowLan = TruthyOf(value, allowLan); return true; }
	if (is("UpdatesPerSecond"))
	{
		int parsed = ClampedInt(value, 1, 30, -1);
		if (parsed < 0)
			return false;
		updatesPerSecond = parsed;
		return true;
	}

	if (is("AllowEquip"))         { allowEquip = TruthyOf(value, allowEquip); return true; }
	if (is("AllowUse"))           { allowUse = TruthyOf(value, allowUse); return true; }
	if (is("AllowFavorite"))      { allowFavorite = TruthyOf(value, allowFavorite); return true; }
	if (is("AllowSetQuest"))      { allowSetQuest = TruthyOf(value, allowSetQuest); return true; }
	if (is("AllowDrop"))          { allowDrop = TruthyOf(value, allowDrop); return true; }
	if (is("AllowFastTravel"))    { allowFastTravel = TruthyOf(value, allowFastTravel); return true; }
	if (is("AllowWait"))          { allowWait = TruthyOf(value, allowWait); return true; }
	if (is("EnableDescriptions")) { enableDescriptions = TruthyOf(value, enableDescriptions); return true; }
	if (is("DebugAutoLoad"))      { debugAutoLoad = TruthyOf(value, debugAutoLoad); return true; }

	if (is("MaxMapMarkers"))
	{
		int parsed = ClampedInt(value, 0, 5000, -1);
		if (parsed < 0)
			return false;
		maxMapMarkers = parsed;
		return true;
	}

	if (is("MaxInventoryItems"))
	{
		int parsed = ClampedInt(value, 0, 5000, -1);
		if (parsed < 0)
			return false;
		maxInventoryItems = parsed;
		return true;
	}

	if (is("AccessToken"))     { accessToken = value; return true; }
	if (is("WebRootOverride")) { webRootOverride = value; return true; }

	return false;
}

void Config::WriteDefaults(const std::string& path) const
{
	std::ofstream file(path, std::ios::trunc);
	if (!file)
		return;

	auto flag = [](bool v) { return v ? "1" : "0"; };

	file <<
		"; Ayn Dual Screen - Skyrim Special Edition\n"
		"; Delete this file to get the defaults back. It is rewritten whenever a setting is\n"
		"; changed from the second screen's own settings panel.\n"
		"\n"
		"[AynDualScreen]\n"
		"\n"
		"; The port the second screen is served on. Stardew and Terraria use 27301, Minecraft\n"
		"; 27302, Fallout: New Vegas 27303. They are likely to be on the same PC, and two mods\n"
		"; fighting over one port fails in a way that looks like the app's fault.\n"
		"Port=" << port << "\n"
		"\n"
		"; Let another device on your network open the screen. ON BY DEFAULT - anyone on your\n"
		"; network can open the page. Set 0 on a network you do not trust.\n"
		"AllowLanAccess=" << flag(allowLan) << "\n"
		"\n"
		"; How often the snapshot is rebuilt, on the game thread. Lower it if the feed costs you\n"
		"; frames on a big load order.\n"
		"UpdatesPerSecond=" << updatesPerSecond << "\n"
		"\n"
		"; What the screen is allowed to do. Every one of these is checked again on the game\n"
		"; thread before anything happens, so switching one off here is a real lock and not just\n"
		"; a greyed-out button.\n"
		"AllowEquip=" << flag(allowEquip) << "\n"
		"AllowUse=" << flag(allowUse) << "\n"
		"AllowFavorite=" << flag(allowFavorite) << "\n"
		"AllowSetQuest=" << flag(allowSetQuest) << "\n"
		"\n"
		"; The three that are off by default are the three you cannot undo by tapping again.\n"
		"; Dropping loses an item; fast travel and waiting move you through the world and burn\n"
		"; hours, which under a survival mod costs more than this plugin can see.\n"
		"AllowDrop=" << flag(allowDrop) << "\n"
		"AllowFastTravel=" << flag(allowFastTravel) << "\n"
		"AllowWait=" << flag(allowWait) << "\n"
		"\n"
		"; Effect and enchantment text. The most expensive part of the snapshot to build; turn it\n"
		"; off if a hoarder's inventory makes the feed stutter.\n"
		"EnableDescriptions=" << flag(enableDescriptions) << "\n"
		"\n"
		"; Ceilings, so one absurd save cannot produce a snapshot the screen chokes on.\n"
		"MaxMapMarkers=" << maxMapMarkers << "\n"
		"MaxInventoryItems=" << maxInventoryItems << "\n"
		"\n"
		"; A test harness, not a feature: loads your most recent save by itself a few seconds after\n"
		"; the main menu appears, so the mod can be driven through a full load without someone\n"
		"; sitting at the menu. It loads; it never saves. Leave this off.\n"
		"DebugAutoLoad=" << flag(debugAutoLoad) << "\n"
		"\n"
		"; A shared secret every request must carry, as an x-ayn-token header or a ?t= parameter.\n"
		"; Empty means no check. Worth setting if LAN access is on and you do not trust the\n"
		"; network - it stops the neighbour's laptop, not someone reading the wire.\n"
		"AccessToken=" << accessToken << "\n"
		"\n"
		"; Serve web/ from somewhere else - point it at this project's web folder and a CSS change\n"
		"; is live on the next refresh, with no rebuild.\n"
		"WebRootOverride=" << webRootOverride << "\n";
}

std::string Config::ToJson() const
{
	// "restart" marks the two that rebind the socket. The panel says so next to them rather than
	// leaving someone wondering why the change did nothing.
	Json j;
	j.BeginObject();
	j.Int("Port", port);
	j.Bool("AllowLanAccess", allowLan);
	j.Int("UpdatesPerSecond", updatesPerSecond);
	j.Bool("AllowEquip", allowEquip);
	j.Bool("AllowUse", allowUse);
	j.Bool("AllowFavorite", allowFavorite);
	j.Bool("AllowSetQuest", allowSetQuest);
	j.Bool("AllowDrop", allowDrop);
	j.Bool("AllowFastTravel", allowFastTravel);
	j.Bool("AllowWait", allowWait);
	j.Bool("EnableDescriptions", enableDescriptions);
	j.Int("MaxMapMarkers", maxMapMarkers);
	j.Int("MaxInventoryItems", maxInventoryItems);
	j.Bool("HasAccessToken", !accessToken.empty());   // never the token itself
	j.BeginArray("restart");
	j.Str(nullptr, "Port");
	j.Str(nullptr, "AllowLanAccess");
	j.EndArray();
	j.EndObject();
	return j.Take();
}
