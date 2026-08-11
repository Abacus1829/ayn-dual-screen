#include "Snapshot.h"
#include "Json.h"

#include "nvse/PluginAPI.h"
#include "nvse/GameAPI.h"
#include "nvse/GameForms.h"
#include "nvse/GameObjects.h"
#include "nvse/GameExtraData.h"
#include "nvse/GameData.h"
#include "nvse/GameRTTI.h"

#include <windows.h>
#include <algorithm>
#include <deque>
#include <map>
#include <cstdio>

// ── published state ─────────────────────────────────────────────────────────

namespace
{
	std::mutex g_lock;
	std::string g_json = R"({"ready":false})";
	long long g_tick = 0;
	DWORD g_lastBuild = 0;

	/// One command from the screen. Deliberately flat: the screen only ever names a thing and an
	/// action, never a memory address, so nothing it sends can be a pointer we then trust.
	struct Command
	{
		std::string action;
		std::string id;        // a form ID as 8 hex digits, for items and quests
		std::string marker;    // a map marker name, for fast travel
		int count = 1;
	};

	std::deque<Command> g_queue;

	/// Items are addressed by form ID rather than by list position, so a command that arrives one
	/// frame after the inventory shifted can't act on the wrong thing.
	std::string FormIdText(UInt32 refID)
	{
		char tmp[16];
		std::snprintf(tmp, sizeof tmp, "%08X", refID);
		return tmp;
	}

	UInt32 ParseFormId(const std::string& text)
	{
		return static_cast<UInt32>(strtoul(text.c_str(), nullptr, 16));
	}
}

// ── a very small JSON reader, for the command bodies only ───────────────────

namespace
{
	/// The screen posts flat objects of string and number fields. Rather than take a JSON parser
	/// as a dependency for that, pull the fields out by name and ignore everything else.
	std::string FieldString(const std::string& body, const char* name)
	{
		std::string needle = "\"";
		needle += name;
		needle += "\"";

		size_t at = body.find(needle);
		if (at == std::string::npos)
			return {};

		size_t colon = body.find(':', at + needle.size());
		if (colon == std::string::npos)
			return {};

		size_t open = body.find('"', colon);
		if (open == std::string::npos)
			return {};

		std::string out;
		for (size_t i = open + 1; i < body.size(); ++i)
		{
			if (body[i] == '\\' && i + 1 < body.size()) { out += body[++i]; continue; }
			if (body[i] == '"') break;
			out += body[i];
		}
		return out;
	}

	int FieldInt(const std::string& body, const char* name, int fallback)
	{
		std::string needle = "\"";
		needle += name;
		needle += "\"";

		size_t at = body.find(needle);
		if (at == std::string::npos)
			return fallback;

		size_t colon = body.find(':', at + needle.size());
		if (colon == std::string::npos)
			return fallback;

		return atoi(body.c_str() + colon + 1);
	}
}

// ── reading the player ──────────────────────────────────────────────────────

namespace
{
	float AV(PlayerCharacter* player, UInt32 code)
	{
		return player->avOwner.Fn_03(code);        // current value, mods included
	}

	float BaseAV(PlayerCharacter* player, UInt32 code)
	{
		return player->avOwner.Fn_01(code);        // base value
	}

	struct SkillRow { UInt32 code; const char* name; };

	const SkillRow kSkills[] = {
		{ eActorVal_Barter,        "Barter" },
		{ eActorVal_EnergyWeapons, "Energy Weapons" },
		{ eActorVal_Explosives,    "Explosives" },
		{ eActorVal_Guns,          "Guns" },
		{ eActorVal_Lockpick,      "Lockpick" },
		{ eActorVal_Medicine,      "Medicine" },
		{ eActorVal_MeleeWeapons,  "Melee Weapons" },
		{ eActorVal_Repair,        "Repair" },
		{ eActorVal_Science,       "Science" },
		{ eActorVal_Sneak,         "Sneak" },
		{ eActorVal_Speech,        "Speech" },
		{ eActorVal_Survival,      "Survival" },
		{ eActorVal_Unarmed,       "Unarmed" },
	};

	const SkillRow kSpecial[] = {
		{ eActorVal_Strength,     "Strength" },
		{ eActorVal_Perception,   "Perception" },
		{ eActorVal_Endurance,    "Endurance" },
		{ eActorVal_Charisma,     "Charisma" },
		{ eActorVal_Intelligence, "Intelligence" },
		{ eActorVal_Agility,      "Agility" },
		{ eActorVal_Luck,         "Luck" },
	};

	struct LimbRow { UInt32 code; const char* key; };

	const LimbRow kLimbs[] = {
		{ eActorVal_Head,     "head" },
		{ eActorVal_Torso,    "torso" },
		{ eActorVal_LeftArm,  "leftArm" },
		{ eActorVal_RightArm, "rightArm" },
		{ eActorVal_LeftLeg,  "leftLeg" },
		{ eActorVal_RightLeg, "rightLeg" },
	};

	const char* RadText(float rads)
	{
		// The thresholds the game's own rad stages use.
		if (rads < 200)  return "Minor Radiation";
		if (rads < 400)  return "Advanced Radiation";
		if (rads < 600)  return "Critical Radiation";
		if (rads < 800)  return "Deadly Radiation";
		if (rads < 1000) return "Fatal Radiation";
		return "Fatal Radiation";
	}

	/// New Vegas tracks karma as a signed number; the Pip-Boy shows the title rather than the
	/// figure, so both go out and the screen picks.
	const char* KarmaText(float karma)
	{
		if (karma >= 750)  return "Messiah";
		if (karma >= 250)  return "Wanderer";
		if (karma > -250)  return "Neutral";
		if (karma > -750)  return "Ruffian";
		return "Villain";
	}
}

// ── inventory ───────────────────────────────────────────────────────────────

namespace
{
	/// Which of the screen's five buckets an item belongs in. This follows the Pip-Boy's own
	/// grouping rather than the form type list, which is why ALCH splits by its food flag.
	const char* BucketFor(TESForm* form)
	{
		switch (form->typeID)
		{
		case kFormType_TESObjectWEAP: return "weapons";
		case kFormType_TESObjectARMO: return "apparel";
		case kFormType_TESAmmo:       return "ammo";
		case kFormType_AlchemyItem:   return "aid";
		case kFormType_BGSNote:       return "misc";
		default:                      return "misc";
		}
	}

	struct ItemRow
	{
		std::string id;
		std::string name;
		int count = 1;
		float weight = 0.f;
		int value = 0;
		bool equipped = false;
		bool hasHealth = false;
		float health = 1.f;
		const char* bucket = "misc";
	};

	/// Walk one EntryData's extend lists. An item with per-instance data -- a weapon at 44%
	/// condition, or the one that is actually worn -- appears once per distinct instance, which
	/// is exactly what the Pip-Boy shows.
	void ReadEntry(ExtraContainerChanges::EntryData* entry, std::vector<ItemRow>& out)
	{
		TESForm* form = entry ? entry->type : nullptr;
		if (!form)
			return;

		const char* name = form->GetTheName();
		if (!name || !*name)
			return;                                  // unnamed forms are scenery, not inventory

		ItemRow base;
		base.id = FormIdText(form->refID);
		base.name = name;
		base.bucket = BucketFor(form);

		// Weight and value live at a different offset in every item class, so each one has to be
		// cast before it can be read. Only the classes xNVSE has actually mapped are read here:
		// TESObjectMISC is still an opaque blob in the SDK, and AlchemyItem's weight sits inside
		// its unmapped region, so junk and chems report a value but no weight rather than a
		// number invented from the wrong four bytes.
		if (auto* weapon = DYNAMIC_CAST(form, TESForm, TESObjectWEAP))
		{
			base.weight = weapon->weight.weight;
			base.value = static_cast<int>(weapon->value.value);
		}
		else if (auto* armor = DYNAMIC_CAST(form, TESForm, TESObjectARMO))
		{
			base.weight = armor->weight.weight;
			base.value = static_cast<int>(armor->value.value);
		}
		else if (auto* aid = DYNAMIC_CAST(form, TESForm, AlchemyItem))
		{
			base.value = static_cast<int>(aid->value);
		}
		else if (auto* ammo = DYNAMIC_CAST(form, TESForm, TESAmmo))
		{
			base.value = static_cast<int>(ammo->value.value);
		}

		int stack = entry->countDelta;

		// Instances carrying their own extra data: condition, equipped flag, a partial stack.
		int accountedFor = 0;
		if (entry->extendData)
		{
			for (auto iter = entry->extendData->Begin(); !iter.End(); ++iter)
			{
				ExtraDataList* extra = iter.Get();
				if (!extra)
					continue;

				ItemRow row = base;
				row.count = 1;

				if (auto* count = static_cast<ExtraCount*>(extra->GetByType(kExtraData_Count)))
					row.count = count->count;

				if (auto* health = static_cast<ExtraHealth*>(extra->GetByType(kExtraData_Health)))
				{
					row.hasHealth = true;
					row.health = std::clamp(health->health, 0.f, 1.f);
				}

				row.equipped = extra->HasType(kExtraData_Worn) || extra->HasType(kExtraData_WornLeft);

				accountedFor += row.count;
				out.push_back(row);
			}
		}

		// Whatever is left is the plain, undamaged, unequipped remainder.
		int remainder = stack - accountedFor;
		if (remainder > 0)
		{
			ItemRow row = base;
			row.count = remainder;
			out.push_back(row);
		}
	}

	void ReadInventory(PlayerCharacter* player, std::vector<ItemRow>& out, int cap)
	{
		ExtraContainerChanges* changes = ExtraContainerChanges::GetForRef(player);
		if (!changes || !changes->data || !changes->data->objList)
			return;

		for (auto iter = changes->data->objList->Begin(); !iter.End(); ++iter)
		{
			if (static_cast<int>(out.size()) >= cap)
				break;
			ReadEntry(iter.Get(), out);
		}

		// The Pip-Boy sorts alphabetically inside each tab, with worn items first.
		std::sort(out.begin(), out.end(), [](const ItemRow& a, const ItemRow& b) {
			if (a.equipped != b.equipped) return a.equipped;
			return _stricmp(a.name.c_str(), b.name.c_str()) < 0;
		});
	}
}

// ── quests ──────────────────────────────────────────────────────────────────

namespace
{
	void WriteQuests(Json& j, PlayerCharacter* player)
	{
		j.BeginArray("quests");

		DataHandler* data = DataHandler::Get();
		if (!data)
		{
			j.EndArray();
			return;
		}

		TESQuest* active = player->quest;

		for (auto iter = data->questList.Begin(); !iter.End(); ++iter)
		{
			TESQuest* quest = iter.Get();
			if (!quest)
				continue;

			const char* name = quest->GetTheName();
			if (!name || !*name)
				continue;                       // unnamed quests are the engine's own plumbing

			// Only quests the player has actually started have a stage set.
			if (quest->currentStage == 0)
				continue;

			bool running = (quest->flags & 1) != 0;
			bool completed = !running;

			// Objectives and local variables share one list; only the objectives have a quest
			// back-pointer to us, which is what tells the two apart.
			std::vector<BGSQuestObjective*> objectives;
			for (auto oiter = quest->lVarOrObjectives.Begin(); !oiter.End(); ++oiter)
			{
				BGSQuestObjective* objective = oiter.Get() ? oiter.Get()->objective : nullptr;
				if (objective && objective->quest == quest && (objective->status & BGSQuestObjective::eQObjStatus_displayed))
					objectives.push_back(objective);
			}

			j.BeginObject()
				.Str("id", FormIdText(quest->refID))
				.Str("name", name)
				.Bool("active", quest == active)
				.Bool("completed", completed)
				.BeginArray("objectives");

			for (BGSQuestObjective* objective : objectives)
			{
				const char* text = objective->displayText.CStr();
				j.BeginObject()
					.Str("text", text ? text : "")
					.Bool("done", (objective->status & BGSQuestObjective::eQObjStatus_completed) != 0)
					.EndObject();
			}

			j.EndArray().EndObject();
		}

		j.EndArray();
	}
}

// ── the snapshot ────────────────────────────────────────────────────────────

namespace
{
	void WritePlayer(Json& j, PlayerCharacter* player)
	{
		j.BeginObject("player");

		const char* name = player->GetTheName();
		j.Str("name", name ? name : "Courier");

		j.Int("level", static_cast<long long>(player->avOwner.Fn_0A()));
		j.Int("xp", static_cast<long long>(AV(player, eActorVal_XP)));

		j.Num("hp", AV(player, eActorVal_Health), 0);
		j.Num("hpMax", player->avOwner.Fn_08(eActorVal_Health), 0);
		j.Num("ap", AV(player, eActorVal_ActionPoints), 0);
		j.Num("apMax", player->avOwner.Fn_08(eActorVal_ActionPoints), 0);

		j.Num("dt", AV(player, eActorVal_Damagethreshold), 0);
		j.Num("dr", AV(player, eActorVal_DamageResistance), 0);

		j.Num("weight", AV(player, eActorVal_InventoryWeight), 1);
		j.Num("weightMax", AV(player, eActorVal_CarryWeight), 0);

		float rads = AV(player, eActorVal_RadLevel);
		j.Num("rads", rads, 0);
		j.Int("radsMax", 1000);
		j.Str("radsText", RadText(rads));

		float karma = AV(player, eActorVal_Karma);
		j.Num("karma", karma, 0);
		j.Str("karmaText", KarmaText(karma));

		// Hardcore's three counters exist whether or not hardcore is on; the screen only shows
		// them when it is.
		j.Bool("hardcore", player->isHardcore);
		j.Num("h2o", AV(player, eActorVal_Dehydration), 0);
		j.Int("h2oMax", 1000);
		j.Num("fod", AV(player, eActorVal_Hunger), 0);
		j.Int("fodMax", 1000);
		j.Num("slp", AV(player, eActorVal_Sleepdeprevation), 0);
		j.Int("slpMax", 1000);

		// Limb condition is an actor value out of the limb's own maximum, normalised to 0..1 so
		// the screen doesn't have to know the maximum.
		j.BeginObject("condition");
		for (const LimbRow& limb : kLimbs)
		{
			float max = player->avOwner.Fn_08(limb.code);
			float now = AV(player, limb.code);
			j.Num(limb.key, max > 0.f ? std::clamp(now / max, 0.f, 1.f) : 1.f, 3);
		}
		j.EndObject();

		j.EndObject();
	}

	void WriteStats(Json& j, PlayerCharacter* player)
	{
		j.BeginArray("special");
		for (const SkillRow& row : kSpecial)
		{
			j.BeginObject()
				.Str("name", row.name)
				.Num("value", AV(player, row.code), 0)
				.Num("base", BaseAV(player, row.code), 0)
				.EndObject();
		}
		j.EndArray();

		j.BeginArray("skills");
		for (const SkillRow& row : kSkills)
		{
			j.BeginObject()
				.Str("name", row.name)
				.Num("value", AV(player, row.code), 0)
				.Num("base", BaseAV(player, row.code), 0)
				.EndObject();
		}
		j.EndArray();
	}

	void WriteLocation(Json& j, PlayerCharacter* player)
	{
		j.BeginObject("map");

		TESObjectCELL* cell = player->parentCell;
		const char* cellName = cell ? cell->GetTheName() : nullptr;
		j.Str("cell", cellName ? cellName : "");

		TESWorldSpace* world = cell ? cell->worldSpace : nullptr;
		const char* worldName = world ? world->GetTheName() : nullptr;
		j.Str("world", worldName ? worldName : "");

		j.Num("x", player->posX, 0);
		j.Num("y", player->posY, 0);

		// The game's Z rotation is radians, clockwise from north. The screen wants degrees.
		j.Num("angle", player->rotZ * 57.2957795f, 1);

		// Bounds: a fixed window around the player for LOCAL, and the whole worldspace for
		// WORLD. A cell has no extent we can read cheaply, so LOCAL is a window rather than a
		// true room outline.
		const float kLocalHalfSpan = 9000.f;
		j.BeginObject("localBounds")
			.Num("minX", player->posX - kLocalHalfSpan, 0)
			.Num("minY", player->posY - kLocalHalfSpan, 0)
			.Num("maxX", player->posX + kLocalHalfSpan, 0)
			.Num("maxY", player->posY + kLocalHalfSpan, 0)
			.EndObject();

		// The Mojave in world units. Read from the worldspace when we can; the fallback covers
		// interiors, where there is no worldspace at all.
		j.BeginObject("worldBounds")
			.Num("minX", -140000.0, 0)
			.Num("minY", -140000.0, 0)
			.Num("maxX", 140000.0, 0)
			.Num("maxY", 140000.0, 0)
			.EndObject();

		j.EndObject();
	}
}

// ── the public surface ──────────────────────────────────────────────────────

namespace
{
	std::string BuildSnapshot(const Config& config)
	{
		PlayerCharacter* player = PlayerCharacter::GetSingleton();
		if (!player || !player->parentCell)
			return R"({"ready":false})";

		Json j;
		j.BeginObject();
		j.Bool("ready", true);
		j.Int("tick", ++g_tick);

		// Tale of Two Wastelands is an ordinary FNV load order, so it needs no special build --
		// but the screen likes to know, and so does a bug report.
		j.Str("game", "FalloutNV");

		WritePlayer(j, player);
		WriteStats(j, player);

		// Inventory, bucketed the way the Pip-Boy buckets it.
		std::vector<ItemRow> items;
		ReadInventory(player, items, config.maxInventoryItems);

		j.BeginObject("inventory");
		for (const char* bucket : { "weapons", "apparel", "aid", "misc", "ammo" })
		{
			j.BeginArray(bucket);
			for (const ItemRow& item : items)
			{
				if (strcmp(item.bucket, bucket) != 0)
					continue;

				j.BeginObject()
					.Str("id", item.id)
					.Str("name", item.name)
					.Int("count", item.count)
					.Num("weight", item.weight, 1)
					.Int("value", item.value)
					.Bool("equipped", item.equipped);

				if (item.hasHealth)
					j.Num("health", item.health, 3);

				j.EndObject();
			}
			j.EndArray();
		}
		j.EndObject();

		WriteQuests(j, player);
		WriteLocation(j, player);

		// Notes, stats, perks, effects and radio are stubbed until their readers land; the screen
		// already renders an empty tab correctly, so shipping them empty beats shipping fiction.
		j.BeginArray("notes").EndArray();
		j.BeginArray("stats").EndArray();
		j.BeginArray("perks").EndArray();
		j.BeginArray("effects").EndArray();
		j.BeginArray("radio").EndArray();

		j.BeginObject("perms")
			.Bool("equip", config.allowEquip)
			.Bool("use", config.allowUse)
			.Bool("drop", config.allowDrop)
			// These three are gated by the config AND by whether the game-thread side exists
			// yet. Both have to be true, so a config that permits something unimplemented still
			// greys the button out instead of offering a button that does nothing.
			.Bool("fastTravel", config.allowFastTravel && false)
			.Bool("radio", config.allowRadio && false)
			.Bool("setQuest", config.allowSetQuest && false)
			.EndObject();

		j.EndObject();
		return j.Take();
	}
}

void Snapshot::Tick(const Config& config)
{
	DrainCommands(config);

	DWORD now = GetTickCount();
	DWORD interval = 1000u / static_cast<DWORD>(config.updatesPerSecond);
	if (g_lastBuild != 0 && now - g_lastBuild < interval)
		return;
	g_lastBuild = now;

	std::string built = BuildSnapshot(config);

	std::lock_guard<std::mutex> guard(g_lock);
	g_json.swap(built);
}

std::string Snapshot::Current()
{
	std::lock_guard<std::mutex> guard(g_lock);
	return g_json;
}

void Snapshot::Reset()
{
	std::lock_guard<std::mutex> guard(g_lock);
	g_json = R"({"ready":false})";
	g_queue.clear();
}

bool Snapshot::QueueCommand(const std::string& body)
{
	Command cmd;
	cmd.action = FieldString(body, "action");
	if (cmd.action.empty())
		return false;

	cmd.id = FieldString(body, "id");
	cmd.marker = FieldString(body, "marker");
	cmd.count = FieldInt(body, "count", 1);

	std::lock_guard<std::mutex> guard(g_lock);

	// A screen that reconnects mid-stall shouldn't be able to replay a hundred queued drops.
	if (g_queue.size() > 32)
		return false;

	g_queue.push_back(std::move(cmd));
	return true;
}

// ── applying commands, on the game thread ───────────────────────────────────

void Snapshot::DrainCommands(const Config& config)
{
	std::deque<Command> batch;
	{
		std::lock_guard<std::mutex> guard(g_lock);
		batch.swap(g_queue);
	}

	if (batch.empty())
		return;

	PlayerCharacter* player = PlayerCharacter::GetSingleton();
	if (!player)
		return;

	for (const Command& cmd : batch)
	{
		// Every permission is re-checked here rather than trusted from the snapshot: the screen
		// is told what it may do, but the game thread decides.
		if (cmd.action == "equip" && config.allowEquip)
		{
			if (TESForm* form = LookupFormByID(ParseFormId(cmd.id)))
				player->EquipItem(form, 1, nullptr, 1, false, true);
		}
		else if (cmd.action == "use" && config.allowUse)
		{
			if (TESForm* form = LookupFormByID(ParseFormId(cmd.id)))
				player->EquipItem(form, 1, nullptr, 1, false, true);   // aid items "equip" to be used
		}
		else if (cmd.action == "drop" && config.allowDrop)
		{
			if (TESForm* form = LookupFormByID(ParseFormId(cmd.id)))
				player->RemoveItem(form, nullptr, std::max(1, cmd.count), 0, 0, nullptr, 0, 0, 1, 0);
		}
		// setQuest, fastTravel and radio are accepted and queued, but not applied yet.
		//
		// setQuest is deliberately NOT done by writing player->quest directly: the active quest
		// has bookkeeping around it -- the objective list, the map target, the Pip-Boy's own
		// state -- and assigning the pointer behind the game's back sets up exactly the kind of
		// desync that shows up later as a corrupt save. It needs the game's own routine, which
		// means finding and calling it properly. Until then the screen is told it may not, and
		// greys the button out. See the README rather than pretending.
	}
}
