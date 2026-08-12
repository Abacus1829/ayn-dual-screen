#include "Snapshot.h"
#include "Json.h"

#include "nvse/PluginAPI.h"
#include "nvse/GameAPI.h"
#include "nvse/GameForms.h"
#include "nvse/GameObjects.h"
#include "nvse/GameEffects.h"       // ActiveEffect; GameObjects.h only forward-declares it
#include "nvse/GameExtraData.h"
#include "nvse/GameData.h"
#include "nvse/GameRTTI.h"

#include <windows.h>
#include <algorithm>
#include <cmath>
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

	/// The station this mod last tuned. See WriteRadio: the game's own tuned station cannot be
	/// read from here, so this is the best available answer and it is only right if the radio was
	/// last changed from the screen.
	std::string g_tunedStation;

	/// NVSE's console RunScriptLine, if the interface was available. Null means the write
	/// operations simply refuse rather than falling back to something less safe.
	bool (*g_runScriptLine)(const char*, void*) = nullptr;

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
		case kFormType_TESObjectIMOD: return "mods";   // weapon mods, as the app's MODS tab
		case kFormType_BGSNote:       return "misc";
		default:                      return "misc";
		}
	}

	struct ItemRow
	{
		std::string id;
		std::string name;
		std::string icon;      // the form's own Pip-Boy icon path, for GET /asset/
		int count = 1;
		float weight = 0.f;
		int value = 0;
		bool equipped = false;
		bool hasHealth = false;
		float health = 1.f;
		const char* bucket = "misc";

		bool isNote = false;    // also listed on DATA -> NOTES
		bool read = false;
	};

	/// The Pip-Boy icon the game itself associates with a form. Taken from the form rather than
	/// guessed from the item's name: a modded item has whatever icon its own plugin gave it, and
	/// no naming convention would find that.
	std::string IconPathFor(TESIcon& icon)
	{
		const char* path = icon.ddsPath.CStr();
		if (!path || !*path)
			return {};

		// The stored path is relative to textures\, which is where Assets looks anyway.
		return path;
	}

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
			base.icon = IconPathFor(weapon->icon);
		}
		else if (auto* armor = DYNAMIC_CAST(form, TESForm, TESObjectARMO))
		{
			base.weight = armor->weight.weight;
			base.value = static_cast<int>(armor->value.value);
			// Armour keeps its icon on the biped model, as a pair: [0] male, [1] female. The
			// Pip-Boy shows one icon per item regardless, and the male entry is the one always
			// filled in, so a female-only icon would be the odd case rather than the rule.
			base.icon = IconPathFor(armor->bipedModel.icon[0]);
		}
		else if (auto* aid = DYNAMIC_CAST(form, TESForm, AlchemyItem))
		{
			base.value = static_cast<int>(aid->value);
			base.icon = IconPathFor(aid->icon);
		}
		else if (auto* ammo = DYNAMIC_CAST(form, TESForm, TESAmmo))
		{
			base.value = static_cast<int>(ammo->value.value);
		}
		else if (auto* note = DYNAMIC_CAST(form, TESForm, BGSNote))
		{
			// Notes stay in MISC where the Pip-Boy puts them, and are listed again on DATA.
			base.isNote = true;
			base.read = note->read != 0;
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
	/// How far the nearest marker for this quest's outstanding objectives is, in world units.
	///
	/// Objectives carry target references, and a reference knows where it is -- so "which of these
	/// is nearest" is answerable without any guesswork. Returns -1 when nothing can be measured:
	/// an objective with no target, or one pointing somewhere in another worldspace, where a
	/// straight-line distance would be a meaningless number rather than a useful one.
	float NearestObjectiveDistance(TESQuest* quest, PlayerCharacter* player)
	{
		TESObjectCELL* playerCell = player->parentCell;
		TESWorldSpace* playerWorld = playerCell ? playerCell->worldSpace : nullptr;

		float best = -1.f;

		for (auto oiter = quest->lVarOrObjectives.Begin(); !oiter.End(); ++oiter)
		{
			BGSQuestObjective* objective = oiter.Get() ? oiter.Get()->objective : nullptr;
			if (!objective || objective->quest != quest)
				continue;

			// Only objectives you are actually being asked to do.
			if (!(objective->status & BGSQuestObjective::eQObjStatus_displayed))
				continue;
			if (objective->status & BGSQuestObjective::eQObjStatus_completed)
				continue;

			for (auto titer = objective->targets.Begin(); !titer.End(); ++titer)
			{
				// targets is tList<Target*>, so the iterator hands back a Target** -- a pointer to
				// the list's slot, not the target itself.
				BGSQuestObjective::Target** slot = titer.Get();
				BGSQuestObjective::Target* target = slot ? *slot : nullptr;
				TESObjectREFR* ref = target ? target->target : nullptr;
				if (!ref)
					continue;

				// Comparing across worldspaces is meaningless -- the Sierra Madre is not "nine
				// miles away" from the Mojave in any sense worth sorting on.
				TESObjectCELL* cell = ref->parentCell;
				TESWorldSpace* world = cell ? cell->worldSpace : nullptr;
				if (world != playerWorld)
					continue;

				float dx = ref->posX - player->posX;
				float dy = ref->posY - player->posY;
				float distance = std::sqrt(dx * dx + dy * dy);

				if (best < 0.f || distance < best)
					best = distance;
			}
		}

		return best;
	}

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

			float distance = completed ? -1.f : NearestObjectiveDistance(quest, player);

			j.BeginObject()
				.Str("id", FormIdText(quest->refID))
				.Str("name", name)
				.Bool("active", quest == active)
				.Bool("completed", completed);

			// Omitted rather than sent as -1 when there is nothing to measure, so the screen can
			// tell "far away" apart from "no idea".
			if (distance >= 0.f)
				j.Num("distance", distance, 0);

			j.BeginArray("objectives");

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

// ── active effects ──────────────────────────────────────────────────────────

namespace
{
	/// Seconds as m:ss, which is how the Pip-Boy shows an effect's clock.
	std::string FormatElapsed(float seconds)
	{
		if (!(seconds == seconds) || seconds < 0.f || seconds > 86400.f)
			return {};

		int total = static_cast<int>(seconds);
		char tmp[16];
		std::snprintf(tmp, sizeof tmp, "%d:%02d", total / 60, total % 60);
		return tmp;
	}

	/// Whatever is currently acting on the player: chems, food, addictions, crippled limbs.
	///
	/// MagicTarget::GetEffectList is a mapped virtual and EffectNode is just tList<ActiveEffect>,
	/// so this is an ordinary list walk rather than an offset guess. The list pointer is null when
	/// nothing is active, which is the common case and not an error.
	void WriteEffects(Json& j, PlayerCharacter* player)
	{
		j.BeginArray("effects");

		EffectNode* effects = player->magicTarget.GetEffectList();
		if (!effects)
		{
			j.EndArray();
			return;
		}

		int written = 0;
		for (auto iter = effects->Begin(); !iter.End(); ++iter)
		{
			if (written >= 40)
				break;                       // an addiction-heavy character can carry a lot

			ActiveEffect* effect = iter.Get();
			if (!effect || !effect->magicItem)
				continue;

			// MagicItem extends TESFullName rather than TESForm, so the name is right on it and
			// there is no GetTheName() to call.
			const char* name = effect->magicItem->name.CStr();
			if (!name || !*name)
				continue;

			j.BeginObject()
				.Str("name", name)
				// Elapsed rather than remaining: the effect's total duration lives on EffectItem,
				// whose layout is not mapped here, and a wrong subtraction reads worse than an
				// honest count-up.
				.Str("duration", FormatElapsed(effect->timeElapsed))
				.EndObject();

			++written;
		}

		j.EndArray();
	}
}

// ── map markers ─────────────────────────────────────────────────────────────

namespace
{
	/// The marker icons, mapped onto the glyph names web/app.js already draws.
	const char* MarkerTypeName(UInt16 type)
	{
		switch (type)
		{
		case 1:  return "City";
		case 2:  return "Settlement";
		case 3:  return "Camp";
		case 4:  return "Landmark";
		case 5:  return "Cave";
		case 6:  return "Factory";
		case 7:  return "Monument";
		case 8:  return "Military";
		case 9:  return "Office";
		case 10: return "Ruin";
		case 11: return "Ruin";
		case 12: return "Sewer";
		case 13: return "Metro";
		case 14: return "Vault";
		default: return "Unmarked";
		}
	}

	/// Every map marker in the current worldspace.
	///
	/// Markers are persistent references, and a worldspace keeps those in its own permanent cell
	/// (TESWorldSpace::cell) rather than in whichever cell you happen to be standing in. So this
	/// walks that cell's object list once and picks out the refs carrying ExtraMapMarker.
	///
	/// Interiors have no worldspace, so they get an empty list -- which is correct, since there is
	/// no world map to place anything on.
	void WriteMarkers(Json& j, PlayerCharacter* player, const Config& config)
	{
		j.BeginArray("markers");

		TESObjectCELL* cell = player->parentCell;
		TESWorldSpace* world = cell ? cell->worldSpace : nullptr;
		TESObjectCELL* persistent = world ? world->cell : nullptr;

		if (!persistent)
		{
			j.EndArray();
			return;
		}

		int written = 0;
		for (auto iter = persistent->objectList.Begin(); !iter.End(); ++iter)
		{
			if (written >= config.maxMapMarkers)
				break;

			TESObjectREFR* ref = iter.Get();
			if (!ref)
				continue;

			auto* marker = static_cast<ExtraMapMarker*>(ref->extraDataList.GetByType(kExtraData_MapMarker));
			if (!marker || !marker->data)
				continue;

			// Not every marker carries a name; the unnamed ones are the engine's own plumbing.
			const char* name = marker->data->fullName.name.CStr();
			if (!name || !*name)
				continue;

			UInt16 flags = marker->data->flags;
			bool visible = (flags & ExtraMapMarker::kFlag_Visible) != 0;
			bool canTravel = (flags & ExtraMapMarker::kFlag_CanTravel) != 0;

			// Only places you have actually found. Sending undiscovered markers dimmed would put
			// the whole Mojave on the screen from a level 1 save, which is a spoiler and not a
			// map -- the game does not show them either until you walk into them.
			if (!visible || (flags & ExtraMapMarker::kFlag_Hidden))
				continue;

			j.BeginObject()
				// The ref's own form ID, which is what fast travel moves you to. The screen only
				// ever echoes this back; the game thread looks it up again and checks it is
				// really a map marker before going anywhere.
				.Str("id", FormIdText(ref->refID))
				.Str("name", name)
				.Str("type", MarkerTypeName(marker->data->type))
				.Num("x", ref->posX, 0)
				.Num("y", ref->posY, 0)
				.Bool("visited", visible)
				.Bool("canFastTravel", canTravel)
				.EndObject();

			++written;
		}

		j.EndArray();
	}
}

// ── companions ──────────────────────────────────────────────────────────────

namespace
{
	/// Whoever is travelling with you, and how they are holding up.
	///
	/// PlayerCharacter keeps its teammates in a mapped list, so this needs no offset hunting. It
	/// is the one thing the Pip-Boy itself will not tell you -- companion health is only visible
	/// from the companion wheel, mid-fight, which is exactly when you cannot look at it.
	void WriteCompanions(Json& j, PlayerCharacter* player)
	{
		j.BeginArray("companions");

		int written = 0;
		for (auto iter = player->teammates.Begin(); !iter.End(); ++iter)
		{
			if (written >= 8)
				break;                       // more than this and something else is wrong

			Actor* mate = iter.Get();
			if (!mate)
				continue;

			const char* name = mate->GetTheName();
			if (!name || !*name)
				continue;

			float hp = mate->avOwner.Fn_03(eActorVal_Health);
			float hpMax = mate->avOwner.Fn_08(eActorVal_Health);

			// Distance, so you know whether they are still with you or stuck on a rock somewhere.
			float dx = mate->posX - player->posX;
			float dy = mate->posY - player->posY;

			j.BeginObject()
				.Str("name", name)
				.Num("hp", hp, 0)
				.Num("hpMax", hpMax, 0)
				.Num("distance", std::sqrt(dx * dx + dy * dy), 0)
				.EndObject();

			++written;
		}

		j.EndArray();
	}
}

// ── radio ───────────────────────────────────────────────────────────────────

namespace
{
	/// The radio stations in this worldspace.
	///
	/// Stations are references carrying ExtraRadioData. The SDK maps the type ID for that but not
	/// the structure behind it, so this detects which references ARE stations without reading any
	/// of their fields -- which is the difference between something reliable and a guess at an
	/// offset.
	///
	/// What that costs: there is no way here to tell which station is currently tuned, so `active`
	/// reflects what this mod last tuned rather than what the game thinks. Tune from the Pip-Boy
	/// instead and the screen will not notice.
	/// A reference's radio data, which the SDK header lists only as "ExtraRadioData ???????? 68 1C".
	///
	/// The size is the useful clue: 0x1C total, and BSExtraData's own header is 0x0C, which leaves
	/// exactly four fields. The GECK's Radio Data panel on a reference has exactly four controls --
	/// range radius, a broadcast-range mode, a static percentage, and an optional position
	/// reference -- so this is the obvious reading of those sixteen bytes.
	///
	/// It is still a reading, so nothing here trusts it blindly: RadioDataLooksSane below refuses
	/// values that could not have come from that panel, and a reference whose data fails that test
	/// is treated as having none rather than as having whatever the bytes happened to say.
	struct ExtraRadioDataGuess
	{
		void* vtbl;              // 00
		UInt8 type;              // 04
		UInt8 pad05[3];
		void* next;              // 08
		float rangeRadius;       // 0C
		UInt32 broadcastRange;   // 10  0 radius, 1 everywhere, 2 worldspace+linked interiors
		float staticPercentage;  // 14
		TESObjectREFR* posRef;   // 18
	};

	enum
	{
		kBroadcast_Radius = 0,
		kBroadcast_Everywhere = 1,
		kBroadcast_WorldAndLinked = 2,
	};

	bool RadioDataLooksSane(const ExtraRadioDataGuess* d)
	{
		if (!d)
			return false;
		if (d->broadcastRange > kBroadcast_WorldAndLinked)
			return false;
		if (!(d->staticPercentage >= 0.f && d->staticPercentage <= 100.f))
			return false;                      // NaN fails this too, which is the point
		if (!(d->rangeRadius >= 0.f && d->rangeRadius < 1.0e7f))
			return false;
		return true;
	}

	/// The station a transmitter broadcasts. Named off the talking activator rather than the
	/// transmitter, because a transmitter is usually a mast or a terminal and is named like one,
	/// which is not what belongs on a radio dial.
	///
	/// BGSTalkingActivator is only forward-declared in this SDK, so it cannot be dereferenced as
	/// itself. It can be read as a TESForm: every form class in this hierarchy is single
	/// inheritance rooted at TESForm, so the TESForm subobject sits at offset zero and the cast is
	/// a no-op rather than a reinterpretation of unrelated memory.
	const char* StationName(TESObjectACTI* activator)
	{
		const char* name = nullptr;
		if (auto* station = reinterpret_cast<TESForm*>(activator->radioStation))
			name = station->GetTheName();
		if (!name || !*name)
			name = activator->fullName.name.CStr();
		return name;
	}

	/// What one station looks like once every transmitter for it has been considered.
	struct StationEntry
	{
		TESObjectACTI* activator = nullptr;
		TESObjectREFR* best = nullptr;   // the transmitter that gives the strongest signal
		float bestDistance = -1.f;       // -1 means "no positioned transmitter found"
		bool inRange = false;
		bool everywhere = false;
		float radius = 0.f;
		bool sawRadioData = false;
		UInt32 mode = 0xFFFFFFFF;         // the broadcast mode read, or none seen
		float staticPct = -1.f;
	};

	/// Consider one placed transmitter against the player, folding it into its station's entry.
	void ConsiderTransmitter(StationEntry& entry, TESObjectREFR* ref, PlayerCharacter* player,
		TESWorldSpace* playerWorld)
	{
		// reinterpret, not static_cast: ExtraRadioDataGuess is this file's own reading of those
		// bytes rather than a declared subclass of BSExtraData, so the compiler has no relationship
		// between the two to convert along. The layout above starts with BSExtraData's own header
		// for exactly this reason.
		auto* radio = reinterpret_cast<ExtraRadioDataGuess*>(
			ref->extraDataList.GetByType(kExtraData_RadioData));
		if (!RadioDataLooksSane(radio))
			radio = nullptr;
		if (radio)
		{
			entry.sawRadioData = true;
			entry.mode = radio->broadcastRange;
			entry.staticPct = radio->staticPercentage;
		}

		float dx = ref->posX - player->posX;
		float dy = ref->posY - player->posY;
		float dz = ref->posZ - player->posZ;
		float distance = std::sqrt(dx * dx + dy * dy + dz * dz);

		if (entry.bestDistance < 0.f || distance < entry.bestDistance)
		{
			entry.bestDistance = distance;
			entry.best = ref;
		}

		if (!radio)
			return;

		// A transmitter set to broadcast everywhere reaches you wherever you are, which is how the
		// story stations work -- their masts are nowhere near where you can first hear them.
		if (radio->broadcastRange == kBroadcast_Everywhere)
		{
			entry.inRange = true;
			entry.everywhere = true;
			return;
		}

		// Worldspace mode reaches anywhere in the same worldspace. An interior counts as being in
		// the worldspace it is linked to, which is not something this can see from here, so the
		// player's own worldspace is the test and interiors fall through to the radius.
		TESObjectCELL* refCell = ref->parentCell;
		TESWorldSpace* refWorld = refCell ? refCell->worldSpace : nullptr;
		if (radio->broadcastRange == kBroadcast_WorldAndLinked)
		{
			if (playerWorld && refWorld == playerWorld)
			{
				entry.inRange = true;
				entry.everywhere = true;
			}
			return;
		}

		// Plain radius. A zero radius in the GECK means "no limit", not "reaches nothing".
		float radius = radio->rangeRadius;
		if (radius <= 0.f)
		{
			entry.inRange = true;
			entry.everywhere = true;
			return;
		}

		if (radius > entry.radius)
			entry.radius = radius;
		if (distance <= radius)
			entry.inRange = true;
	}

	/// Every radio station in the load order, with whether you can actually pick it up.
	///
	/// The earlier version walked only the worldspace's persistent cell looking for transmitters,
	/// which is why it found two stations out of a Mojave full of them and marked both unreachable:
	/// most transmitters are not persistent refs of the worldspace you happen to be standing in,
	/// and range is a property of the reference's radio data rather than of how far away it is.
	///
	/// So the station list now comes from the forms -- every activator in the load order that
	/// carries a station, which is the complete set and costs one walk -- and range comes from the
	/// transmitters' own data. Coming at it from the forms is the same approach the perk reader
	/// takes, and for the same reason: the list of things that exist is knowable, so it should not
	/// be inferred from whatever happens to be nearby.
	void WriteRadio(Json& j, PlayerCharacter* player, const std::string& tuned)
	{
		j.BeginArray("radio");

		DataHandler* data = DataHandler::Get();
		if (!data || !data->boundObjectList)
		{
			j.EndArray();
			return;
		}

		TESObjectCELL* playerCell = player->parentCell;
		TESWorldSpace* playerWorld = playerCell ? playerCell->worldSpace : nullptr;

		// Keyed by the station form, so a station with several transmitters appears once.
		std::map<TESForm*, StationEntry> stations;

		for (TESBoundObject* object = data->boundObjectList->first; object; object = object->next)
		{
			auto* activator = DYNAMIC_CAST(object, TESForm, TESObjectACTI);
			if (!activator || !activator->radioStation)
				continue;

			auto* key = reinterpret_cast<TESForm*>(activator->radioStation);
			StationEntry& entry = stations[key];
			if (!entry.activator)
				entry.activator = activator;
		}

		if (stations.empty())
		{
			j.EndArray();
			return;
		}

		// Now the placed transmitters, for range and for something to activate. Both the cell the
		// player is standing in and the worldspace's persistent cell are walked: persistent refs
		// are where the story transmitters live, and the local cell catches the props.
		auto sweep = [&](TESObjectCELL* cell) {
			if (!cell)
				return;
			for (auto iter = cell->objectList.Begin(); !iter.End(); ++iter)
			{
				TESObjectREFR* ref = iter.Get();
				if (!ref || ref->IsDeleted() || !ref->baseForm)
					continue;
				auto* activator = DYNAMIC_CAST(ref->baseForm, TESForm, TESObjectACTI);
				if (!activator || !activator->radioStation)
					continue;

				auto found = stations.find(reinterpret_cast<TESForm*>(activator->radioStation));
				if (found == stations.end())
					continue;
				ConsiderTransmitter(found->second, ref, player, playerWorld);
			}
		};

		sweep(playerCell);
		if (playerWorld && playerWorld->cell != playerCell)
			sweep(playerWorld->cell);

		int written = 0;
		for (auto& pair : stations)
		{
			if (written >= 32)
				break;

			StationEntry& entry = pair.second;
			const char* name = StationName(entry.activator);
			if (!name || !*name)
				continue;

			// Tuning activates a placed transmitter, so a station with none found cannot be tuned
			// and says so rather than offering a button that quietly does nothing.
			std::string id = entry.best ? FormIdText(entry.best->refID) : std::string();

			j.BeginObject()
				.Str("id", id)
				.Str("name", name)
				.Bool("active", !id.empty() && !tuned.empty() && tuned == id)
				.Bool("inRange", entry.inRange)
				.Bool("canTune", !id.empty());

			// What the range decision was actually made on. This is here to be checked against the
			// game rather than taken on trust -- the radio data layout above is read off a size and
			// a GECK panel, not off a mapped header, and these are the numbers that would look
			// wrong first if it were misread.
			j.Bool("hasData", entry.sawRadioData);
			if (entry.sawRadioData)
			{
				j.Int("mode", static_cast<long long>(entry.mode));
				j.Num("staticPct", entry.staticPct);
			}
			if (entry.radius > 0.f)
				j.Num("radius", entry.radius, 0);
			if (entry.bestDistance >= 0.f)
				j.Num("distance", entry.bestDistance, 0);

			j.EndObject();

			++written;
		}

		j.EndArray();
	}
}

// ── the local map ───────────────────────────────────────────────────────────

namespace
{
	/// What the current cell contains, as points the screen can sketch a floor plan from.
	///
	/// THIS IS NOT THE GAME'S LOCAL MAP. The real one is rendered by the engine from the cell's
	/// geometry into a render target -- there is no texture to extract and no structure to read.
	/// What this does instead is plot the things in the room that you actually navigate by: doors,
	/// containers, and whoever is standing around. The shape they make is a rough footprint.
	///
	/// Same walk as the marker reader, which is already proven, and capped for the same reason.
	void WriteLocalRefs(Json& j, PlayerCharacter* player, const Config& config)
	{
		j.BeginArray("localRefs");

		TESObjectCELL* cell = player->parentCell;
		if (!cell || !config.enableLocalMap)
		{
			j.EndArray();
			return;
		}

		int written = 0;
		for (auto iter = cell->objectList.Begin(); !iter.End(); ++iter)
		{
			if (written >= config.maxLocalRefs)
				break;

			TESObjectREFR* ref = iter.Get();
			if (!ref || !ref->baseForm)
				continue;

			// Skip deleted refs; they are still in the list but not in the room. There is no
			// IsDisabled() on TESForm in this SDK, so a disabled ref may still be plotted --
			// which is a stray dot, not a crash.
			if (ref->IsDeleted())
				continue;

			const char* kind = nullptr;
			switch (ref->baseForm->typeID)
			{
			case kFormType_TESObjectDOOR: kind = "door"; break;
			case kFormType_TESObjectCONT: kind = "container"; break;
			case kFormType_Character:     kind = "actor"; break;
			case kFormType_Creature:      kind = "actor"; break;
			case kFormType_TESFurniture:  kind = "furniture"; break;
			default: continue;
			}

			// The player is in this list too, and is already drawn as the arrow.
			if (ref == static_cast<TESObjectREFR*>(player))
				continue;

			const char* name = ref->baseForm->GetTheName();

			j.BeginObject()
				.Str("kind", kind)
				.Str("name", name ? name : "")
				.Num("x", ref->posX, 0)
				.Num("y", ref->posY, 0)
				.EndObject();

			++written;
		}

		j.EndArray();
	}
}

// ── perks ───────────────────────────────────────────────────────────────────

namespace
{
	/// The player's perks and traits.
	///
	/// Read by asking the game, not by walking the player's perk list. That list lives at an
	/// offset the SDK has not mapped -- it is inside an unk block, known only from a comment --
	/// and dereferencing a guess there is how you crash somebody's save. Actor::GetPerkRank is a
	/// mapped virtual, so this iterates every perk form and asks the game about each one instead.
	///
	/// That is a few hundred virtual calls per rebuild, which is nothing next to being wrong, and
	/// it is why the result is cached: perks change on level-up, not per frame.
	void WritePerks(Json& j, PlayerCharacter* player)
	{
		j.BeginArray("perks");

		DataHandler* data = DataHandler::Get();
		if (!data)
		{
			j.EndArray();
			return;
		}

		for (auto iter = data->perkList.Begin(); !iter.End(); ++iter)
		{
			BGSPerk* perk = iter.Get();
			if (!perk)
				continue;

			// Hidden perks are the engine's own bookkeeping -- quest rewards, difficulty
			// modifiers -- and the Pip-Boy does not show them either.
			if (perk->data.isHidden)
				continue;

			const char* name = perk->GetTheName();
			if (!name || !*name)
				continue;

			UInt8 rank = player->GetPerkRank(perk, false);
			bool alternate = false;

			if (!rank)
			{
				rank = player->GetPerkRank(perk, true);
				alternate = rank != 0;
			}

			if (!rank)
				continue;

			// No description. TESDescription holds a file offset rather than a string -- the text
			// is fetched by a virtual that reads out of the source plugin -- so pulling it would
			// mean a disk read per perk per rebuild. The screen only uses it for a tooltip, and
			// an empty one beats ten disk seeks a second.
			j.BeginObject()
				.Str("name", name)
				.Int("rank", rank)
				.Bool("trait", perk->data.isTrait != 0)
				.Bool("alt", alternate)
				.EndObject();
		}

		j.EndArray();
	}
}

// ── the load order ──────────────────────────────────────────────────────────

namespace
{
	/// The active plugins, in load order.
	///
	/// Worth having on its own -- but the real reason it is here is that when a modded item looks
	/// wrong on the screen, the first question is always "which plugin added it", and this is the
	/// page that answers it without leaving the game.
	void WritePlugins(Json& j)
	{
		j.BeginArray("plugins");

		DataHandler* data = DataHandler::Get();
		if (!data)
		{
			j.EndArray();
			return;
		}

		UInt32 count = data->modList.loadedModCount;
		if (count > 0xFF)
			count = 0xFF;                       // the array is fixed at 255; trust the bound, not the field

		for (UInt32 i = 0; i < count; ++i)
		{
			ModInfo* mod = data->modList.loadedMods[i];
			if (!mod || !mod->name[0])
				continue;

			// The load index is the position in this array, which is exactly what a load order is
			// and what every other tool shows in the left-hand column.
			char index[4];
			std::snprintf(index, sizeof index, "%02X", i);

			// Masters end in .esm. Reading the flag out of the header would be better, but the
			// extension is what the game itself sorts on and what the user sees everywhere else.
			size_t length = strnlen(mod->name, sizeof mod->name);
			bool master = length > 4 && _stricmp(mod->name + length - 4, ".esm") == 0;

			j.BeginObject()
				.Str("index", index)
				.Str("name", mod->name)
				.Bool("master", master)
				.EndObject();
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

	void WriteLocation(Json& j, PlayerCharacter* player, const Config& config)
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

		WriteMarkers(j, player, config);
		WriteLocalRefs(j, player, config);

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
		for (const char* bucket : { "weapons", "apparel", "aid", "mods", "misc", "ammo" })
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

				if (!item.icon.empty())
					j.Str("icon", item.icon);

				if (item.hasHealth)
					j.Num("health", item.health, 3);

				j.EndObject();
			}
			j.EndArray();
		}
		j.EndObject();

		WriteQuests(j, player);
		WriteLocation(j, player, config);
		WritePlugins(j);
		WritePerks(j, player);

		// Notes, stats, effects and radio are stubbed until their readers land; the screen already
		// renders an empty tab correctly, so shipping them empty beats shipping fiction.
		WriteEffects(j, player);

		WriteRadio(j, player, g_tunedStation);
		WriteCompanions(j, player);

		// Notes and holotapes, pulled back out of the inventory walk above.
		j.BeginArray("notes");
		for (const ItemRow& item : items)
		{
			if (!item.isNote)
				continue;

			j.BeginObject()
				.Str("id", item.id)
				.Str("name", item.name)
				.Str("type", "note")
				// No body text. BGSNote keeps it as a file offset resolved on demand, the same
				// arrangement as perk descriptions, so reading it means a disk seek per note per
				// rebuild. The name is what the list needs.
				.Bool("read", item.read)
				.EndObject();
		}
		j.EndArray();

		// Misc stats.
		//
		// The game's own stat tracker is not exposed by this SDK -- there is no GetGameStat to
		// call -- so rather than an empty tab these are counted from what the snapshot already
		// reads. Fewer entries than the Pip-Boy's page, but every one of them is real.
		{
			int discovered = 0;
			int questsDone = 0;
			int questsActive = 0;

			DataHandler* data = DataHandler::Get();
			if (data)
			{
				for (auto qi = data->questList.Begin(); !qi.End(); ++qi)
				{
					TESQuest* quest = qi.Get();
					if (!quest || quest->currentStage == 0)
						continue;
					const char* qn = quest->GetTheName();
					if (!qn || !*qn)
						continue;
					(quest->flags & 1) ? ++questsActive : ++questsDone;
				}
			}

			TESObjectCELL* cell = player->parentCell;
			TESWorldSpace* world = cell ? cell->worldSpace : nullptr;
			if (TESObjectCELL* persistent = world ? world->cell : nullptr)
			{
				for (auto ri = persistent->objectList.Begin(); !ri.End(); ++ri)
				{
					TESObjectREFR* ref = ri.Get();
					if (!ref)
						continue;
					auto* mk = static_cast<ExtraMapMarker*>(ref->extraDataList.GetByType(kExtraData_MapMarker));
					if (mk && mk->data && (mk->data->flags & ExtraMapMarker::kFlag_Visible))
						++discovered;
				}
			}

			int carried = static_cast<int>(items.size());

			j.BeginArray("stats");
			auto stat = [&](const char* group, const char* name, long long value) {
				j.BeginObject().Str("group", group).Str("name", name)
					.Str("value", std::to_string(value)).EndObject();
			};

			stat("General", "Level", static_cast<long long>(player->avOwner.Fn_0A()));
			stat("General", "Locations Discovered", discovered);
			stat("General", "Quests Active", questsActive);
			stat("General", "Quests Completed", questsDone);
			stat("Inventory", "Items Carried", carried);
			stat("Inventory", "Carry Weight Used", static_cast<long long>(AV(player, eActorVal_InventoryWeight)));
			stat("Inventory", "Carry Weight Max", static_cast<long long>(AV(player, eActorVal_CarryWeight)));
			j.EndArray();
		}

		j.BeginObject("perms")
			.Bool("equip", config.allowEquip)
			.Bool("use", config.allowUse)
			.Bool("drop", config.allowDrop)
			// Fast travel is real now, so it follows the config -- but it also needs the console
			// interface, since that is how it reaches the game. Without it the button greys out
			// rather than pretending.
			.Bool("fastTravel", config.allowFastTravel && g_runScriptLine != nullptr)
			.Bool("radio", config.allowRadio && g_runScriptLine != nullptr)
			.Bool("setQuest", config.allowSetQuest && g_runScriptLine != nullptr)
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

void Snapshot::SetConsole(bool (*runScriptLine)(const char*, void*))
{
	g_runScriptLine = runScriptLine;
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
				// Parenthesised because the SDK needs Windows' min/max macros left defined, and
				// a bare std::max( would be eaten by the macro.
				player->RemoveItem(form, nullptr, (std::max)(1, cmd.count), 0, 0, nullptr, 0, 0, 1, 0);
		}
		else if (cmd.action == "fastTravel" && config.allowFastTravel && g_runScriptLine)
		{
			// The screen sends a form ID, and it is not trusted. Look it up, confirm it is a real
			// reference that actually carries a map marker, and confirm the marker is one you have
			// discovered and may travel to. Only then move. Anything else is ignored -- a screen
			// on the LAN must not be able to teleport you into a wall by inventing an ID.
			TESForm* form = LookupFormByID(ParseFormId(cmd.id));
			TESObjectREFR* ref = form ? DYNAMIC_CAST(form, TESForm, TESObjectREFR) : nullptr;
			if (!ref)
				continue;

			auto* marker = static_cast<ExtraMapMarker*>(ref->extraDataList.GetByType(kExtraData_MapMarker));
			if (!marker || !marker->data)
				continue;

			UInt16 flags = marker->data->flags;
			if (!(flags & ExtraMapMarker::kFlag_Visible) || !(flags & ExtraMapMarker::kFlag_CanTravel))
				continue;

			// Through the game's own console rather than moving the player by hand: fast travel
			// has consequences -- time passes, companions follow, encounters roll -- and the
			// engine owns all of that.
			char line[64];
			std::snprintf(line, sizeof line, "player.MoveTo %08X", ref->refID);
			g_runScriptLine(line, nullptr);
		}
		else if (cmd.action == "radio" && config.allowRadio && g_runScriptLine)
		{
			// Empty id means "switch it off", which is the same activation on whatever is tuned.
			if (cmd.id.empty())
			{
				if (!g_tunedStation.empty())
				{
					char line[64];
					std::snprintf(line, sizeof line, "%s.Activate player 1", g_tunedStation.c_str());
					g_runScriptLine(line, nullptr);
					g_tunedStation.clear();
				}
				continue;
			}

			// Same shape as fast travel: the ID from the screen is looked up and checked to be a
			// real reference that actually carries radio data before anything is activated.
			TESForm* form = LookupFormByID(ParseFormId(cmd.id));
			TESObjectREFR* ref = form ? DYNAMIC_CAST(form, TESForm, TESObjectREFR) : nullptr;
			if (!ref || !ref->baseForm)
				continue;

			// Same test the listing uses, so the screen can only tune something it was actually
			// offered: a placed reference whose base activator carries a station.
			auto* activator = DYNAMIC_CAST(ref->baseForm, TESForm, TESObjectACTI);
			if (!activator || !activator->radioStation)
				continue;

			// Switching stations while one is playing has to turn the old one off first, or the
			// game is left with two active and the Pip-Boy shows whichever it feels like.
			if (!g_tunedStation.empty() && g_tunedStation != cmd.id)
			{
				char off[64];
				std::snprintf(off, sizeof off, "%s.Activate player 1", g_tunedStation.c_str());
				g_runScriptLine(off, nullptr);
			}

			// Activating the station reference is how the game itself tunes one -- it is an
			// ordinary activation, not a write into the radio's own data, which is the part this
			// SDK does not map.
			char line[64];
			std::snprintf(line, sizeof line, "%08X.Activate player 1", ref->refID);
			g_runScriptLine(line, nullptr);
			g_tunedStation = FormIdText(ref->refID);
		}
		else if (cmd.action == "setQuest" && config.allowSetQuest && g_runScriptLine)
		{
			// Through SetCurrentQuest, which is xNVSE's own command for this.
			//
			// The obvious shortcut -- assigning player->quest directly -- was refused earlier and
			// still is: the active quest has bookkeeping around it (the objective list, the map
			// target, the Pip-Boy's own state) and setting the pointer behind the game's back
			// leaves those stale. This lets the game do it.
			TESForm* form = LookupFormByID(ParseFormId(cmd.id));
			if (!form || !DYNAMIC_CAST(form, TESForm, TESQuest))
				continue;

			char line[64];
			std::snprintf(line, sizeof line, "SetCurrentQuest %08X", form->refID);
			g_runScriptLine(line, nullptr);
		}
		//
		// setQuest is deliberately NOT done by writing player->quest directly: the active quest
		// has bookkeeping around it -- the objective list, the map target, the Pip-Boy's own
		// state -- and assigning the pointer behind the game's back sets up exactly the kind of
		// desync that shows up later as a corrupt save. It needs the game's own routine, which
		// means finding and calling it properly. Until then the screen is told it may not, and
		// greys the button out. See the README rather than pretending.
	}
}
