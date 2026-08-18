#include "PCH.h"

#include "Snapshot.h"

#include "Actions.h"
#include "Config.h"
#include "Describe.h"
#include "Json.h"
#include "MapData.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <unordered_map>

// Everything in this file that touches RE:: runs on the game thread. See Snapshot.h for why that
// matters more than anything else here.

namespace
{
	std::mutex g_publishLock;
	std::string g_current = R"({"ready":false,"tick":0,"game":"SkyrimSE"})";
	std::uint64_t g_tick = 0;
	std::chrono::steady_clock::time_point g_lastBuild{};

	std::mutex g_queueLock;
	std::deque<Command> g_queue;

	/// A ceiling on the queue. A screen that has lost its mind -- or a page left open on a phone in
	/// a pocket -- must not be able to make the game thread work through thousands of taps.
	constexpr size_t kMaxQueued = 64;

	/// Names the section, or the individual call, that is about to run. Defined further down;
	/// declared here because the very first reader in the file uses it. See its definition for why
	/// this exists at all.
	void Stage(const char* what);

	/// Septims carried, counted during the inventory walk rather than asked for. See WritePlayer.
	/// Game thread only, like everything else in this file.
	int g_gold = 0;

	/// Gold is a single well-known form, and has been since Morrowind.
	constexpr std::uint32_t kGoldFormId = 0x0000000F;

	std::string FormIdText(std::uint32_t id)
	{
		char tmp[16];
		std::snprintf(tmp, sizeof tmp, "%08x", id);
		return tmp;
	}

	/// The game's own display name for a form, or an empty string. Never a placeholder: the screen
	/// drops nameless entries rather than showing a row of "[NO NAME]".
	std::string NameOf(RE::TESForm* form)
	{
		if (!form)
			return {};
		const char* name = form->GetName();
		return name ? name : "";
	}

	// ── player ──────────────────────────────────────────────────────────────

	float AV(RE::PlayerCharacter* player, RE::ActorValue av)
	{
		return player->AsActorValueOwner()->GetActorValue(av);
	}

	/// The value the bar is full at. This is the permanent value plus temporary modifiers, which is
	/// what the game's own HUD draws -- a fortify-health potion moves the top of the bar, and a
	/// screen that showed the base value instead would report you as damaged while buffed.
	float AVMax(RE::PlayerCharacter* player, RE::ActorValue av)
	{
		auto* owner = player->AsActorValueOwner();
		return owner->GetPermanentActorValue(av) +
			player->GetActorValueModifier(RE::ACTOR_VALUE_MODIFIER::kTemporary, av);
	}

	void WritePlayer(Json& j, RE::PlayerCharacter* player, const Config&)
	{
		// Traced call by call, deliberately.
		//
		// This reader entered and never returned, twice, and section-level tracing could not say
		// which line did it. Every call below is resolved through Address Library at runtime, so
		// any one of them can be the wrong address on a runtime CommonLibSSE 3.7.0 (May 2023)
		// never saw -- and yours is 1.6.1170. A wrong address is a jump into arbitrary code, which
		// is exactly the sort of thing that hangs rather than crashes cleanly.
		//
		// These crumbs cost nothing after the third snapshot and they are the only way to find it.
		j.BeginObject("player");

		Stage("player: name");   j.Str("name", NameOf(player->GetActorBase()));
		Stage("player: race");   j.Str("race", player->GetRace() ? NameOf(player->GetRace()) : "");
		Stage("player: level");  j.Int("level", player->GetLevel());

		// Level progress is NOT sent. It lives on PlayerSkills, which sits at a different offset
		// in every runtime -- so in a build that covers SE, AE and VR at once, CommonLibSSE makes
		// the whole runtime-data block unavailable rather than let you read the wrong bytes. The
		// screen leaves the row out. A wrong XP bar is worse than no XP bar, and reading it would
		// mean giving up one of the three runtimes.

		Stage("player: hp");     j.Num("hp", AV(player, RE::ActorValue::kHealth));
		Stage("player: hpMax");  j.Num("hpMax", AVMax(player, RE::ActorValue::kHealth));
		Stage("player: mp");     j.Num("mp", AV(player, RE::ActorValue::kMagicka));
		Stage("player: mpMax");  j.Num("mpMax", AVMax(player, RE::ActorValue::kMagicka));
		Stage("player: sp");     j.Num("sp", AV(player, RE::ActorValue::kStamina));
		Stage("player: spMax");  j.Num("spMax", AVMax(player, RE::ActorValue::kStamina));

		Stage("player: weight in container");
		const float carried = player->GetWeightInContainer();

		Stage("player: carry weight");
		const float capacity = AV(player, RE::ActorValue::kCarryWeight);
		j.Num("weight", carried);
		j.Num("weightMax", capacity);
		j.Bool("overEncumbered", carried > capacity);

		// NOT GetGoldAmount().
		//
		// That call is what hung the game. Traced line by line, the player reader got as far as
		// `player: gold` and never came back -- while every read around it, including the walk of
		// the whole inventory, completed fine. Whatever GetGoldAmount() resolves to on 1.6.1170
		// under the library this was first built against, it does not return.
		//
		// The number is free anyway: the inventory scan already walks every stack the player
		// carries, and gold is just the stack whose form is 0x0000000F. So it is counted there,
		// out of data we already have, and this reads the answer. One fewer call into the engine
		// for a number we were being handed regardless.
		Stage("player: gold");   j.Int("gold", g_gold);
		Stage("player: armor");  j.Num("armorRating", AV(player, RE::ActorValue::kDamageResist));

		// The damage number the stats menu shows is the equipped right-hand weapon's, not an actor
		// value. Bare-handed there is no weapon and no number, so the field is left out rather than
		// sent as zero -- zero damage and no weapon are different things to draw.
		Stage("player: equipped weapon");
		if (auto* held = player->GetEquippedObject(false))
		{
			if (auto* weapon = held->As<RE::TESObjectWEAP>())
				j.Num("damage", static_cast<double>(weapon->GetAttackDamage()));
		}

		// Beast form is NOT read any more.
		//
		// It was two `HasKeywordString` calls, and that is a string comparison against every
		// keyword on the actor, resolved through Address Library, run every snapshot -- the single
		// most suspicious thing in this function and the least valuable. The screen loses a label
		// that says "werewolf"; it is not worth being the thing that hangs somebody's game. If it
		// comes back it will be as a form lookup done once, not a string search done constantly.
		j.Str("beast", "none");

		Stage("player: combat/sneak");
		j.Bool("inCombat", player->IsInCombat());
		j.Bool("sneaking", player->IsSneaking());

		Stage("player: done");
		j.EndObject();
	}

	// ── time ────────────────────────────────────────────────────────────────

	void WriteTime(Json& j)
	{
		auto* calendar = RE::Calendar::GetSingleton();
		if (!calendar)
			return;

		const float hour = calendar->GetHour();
		const int wholeHour = static_cast<int>(hour);
		const int minute = static_cast<int>((hour - wholeHour) * 60.0f);

		char text[8];
		std::snprintf(text, sizeof text, "%02d:%02d", wholeHour, minute);

		j.BeginObject("time");
		j.Str("text", text);
		j.Num("hour", hour, 3);
		j.Int("day", static_cast<int>(calendar->GetDay()));
		j.Int("month", static_cast<int>(calendar->GetMonth()));
		j.Str("monthName", calendar->GetMonthName());
		j.Int("year", static_cast<int>(calendar->GetYear()));
		j.Str("dayName", calendar->GetDayName());
		j.Num("daysPassed", calendar->GetDaysPassed(), 2);

		// Skyrim's own map dims after dark. 20:00 to 06:00 is close enough to the game's lighting
		// for a UI cue, and the screen only uses it to pick a palette.
		j.Bool("night", hour >= 20.0f || hour < 6.0f);
		j.EndObject();
	}

	// ── skills ──────────────────────────────────────────────────────────────

	struct SkillRow
	{
		RE::ActorValue av;
		const char* name;
		const char* school;
	};

	/// The eighteen, in the order the game's own skill menu groups them. The names are written down
	/// rather than read from the game because ActorValue has no display-name lookup that survives a
	/// translated install cleanly, and these are the English names the UI is laid out around.
	constexpr SkillRow kSkills[] = {
		{ RE::ActorValue::kOneHanded,    "One-Handed",   "combat"  },
		{ RE::ActorValue::kTwoHanded,    "Two-Handed",   "combat"  },
		{ RE::ActorValue::kArchery,      "Archery",      "combat"  },
		{ RE::ActorValue::kBlock,        "Block",        "combat"  },
		{ RE::ActorValue::kHeavyArmor,   "Heavy Armor",  "combat"  },
		{ RE::ActorValue::kSmithing,     "Smithing",     "combat"  },
		{ RE::ActorValue::kAlteration,   "Alteration",   "magic"   },
		{ RE::ActorValue::kConjuration,  "Conjuration",  "magic"   },
		{ RE::ActorValue::kDestruction,  "Destruction",  "magic"   },
		{ RE::ActorValue::kIllusion,     "Illusion",     "magic"   },
		{ RE::ActorValue::kRestoration,  "Restoration",  "magic"   },
		{ RE::ActorValue::kEnchanting,   "Enchanting",   "magic"   },
		{ RE::ActorValue::kLightArmor,   "Light Armor",  "stealth" },
		{ RE::ActorValue::kSneak,        "Sneak",        "stealth" },
		{ RE::ActorValue::kLockpicking,  "Lockpicking",  "stealth" },
		{ RE::ActorValue::kPickpocket,   "Pickpocket",   "stealth" },
		{ RE::ActorValue::kSpeech,       "Speech",       "stealth" },
		{ RE::ActorValue::kAlchemy,      "Alchemy",      "stealth" },
	};

	void WriteSkills(Json& j, RE::PlayerCharacter* player)
	{
		j.BeginArray("skills");
		for (const SkillRow& row : kSkills)
		{
			j.BeginObject();
			j.Str("name", row.name);
			j.Str("school", row.school);
			j.Int("value", static_cast<int>(AV(player, row.av)));
			j.Int("base", static_cast<int>(player->AsActorValueOwner()->GetBaseActorValue(row.av)));

			// No "progress" field, for the same reason there is no XP bar: per-skill progress is
			// on PlayerSkills, which a build covering all three runtimes cannot reach. The screen
			// draws the track empty rather than inventing a fraction.

			j.EndObject();
		}
		j.EndArray();
	}

	/// What the player has, found by asking the game about every candidate rather than by reading a
	/// list off the character.
	///
	/// The player's own lists -- addedPerks, addedSpells -- live in the runtime-data block, which a
	/// build covering SE, AE and VR at once cannot reach: the members sit at different offsets in
	/// each. So this walks the load order's forms and asks `HasPerk` / `HasSpell` / `HasShout`,
	/// which are functions and therefore the same everywhere.
	///
	/// That is a scan of a few thousand forms, which is far too much to do ten times a second --
	/// so it is cached, and rebuilt every few seconds. Perks and spells change when you level or
	/// buy something, not between frames, and a spell that takes three seconds to appear on the
	/// second screen is not a defect anybody can feel.
	struct Known
	{
		std::vector<RE::BGSPerk*>   perks;
		std::vector<RE::SpellItem*> spells;
		std::vector<RE::SpellItem*> powers;
		std::vector<RE::TESShout*>  shouts;
		std::uint64_t builtAtTick = 0;
		bool built = false;
	};

	Known g_known;
	constexpr std::uint64_t kRescanEvery = 30;   // snapshots; ~3 seconds at the default rate

	/// Which skill tree each perk belongs to.
	///
	/// The perk itself does not say -- `PerkData` carries the rank, the level and whether it is a
	/// trait, but nothing about where it sits. The relationship lives the other way round: each
	/// skill's ActorValueInfo owns a tree of nodes, and each node points at a perk. So this walks
	/// those eighteen trees once and inverts them.
	///
	/// Built once and never rebuilt: the trees are static form data. A mod that adds perks adds
	/// them to a tree at load time, well before any of this runs.
	std::unordered_map<RE::BGSPerk*, const char*> g_perkTree;

	void WalkPerkTree(RE::BGSSkillPerkTreeNode* node, const char* skillName, int depth = 0)
	{
		// The tree is a graph rather than a strict tree -- nodes can be reached more than once --
		// so the depth cap is what stops a cycle in somebody's mod from hanging the game thread.
		if (!node || depth > 32)
			return;

		if (node->perk && !g_perkTree.contains(node->perk))
			g_perkTree.emplace(node->perk, skillName);

		for (auto* child : node->children)
			WalkPerkTree(child, skillName, depth + 1);
	}

	void RebuildKnown(RE::PlayerCharacter* player)
	{
		auto* handler = RE::TESDataHandler::GetSingleton();
		if (!handler)
			return;

		if (g_perkTree.empty())
		{
			if (auto* values = RE::ActorValueList::GetSingleton())
			{
				for (const SkillRow& row : kSkills)
				{
					auto* info = values->GetActorValue(row.av);
					if (info && info->perkTree)
						WalkPerkTree(info->perkTree, row.name);
				}
			}
		}

		g_known.perks.clear();
		g_known.spells.clear();
		g_known.powers.clear();
		g_known.shouts.clear();

		for (auto* perk : handler->GetFormArray<RE::BGSPerk>())
		{
			if (perk && perk->data.playable && player->HasPerk(perk))
				g_known.perks.push_back(perk);
		}

		for (auto* spell : handler->GetFormArray<RE::SpellItem>())
		{
			if (!spell || !player->HasSpell(spell))
				continue;

			switch (spell->GetSpellType())
			{
			case RE::MagicSystem::SpellType::kSpell:
				g_known.spells.push_back(spell);
				break;
			case RE::MagicSystem::SpellType::kPower:
			case RE::MagicSystem::SpellType::kLesserPower:
				g_known.powers.push_back(spell);
				break;
			default:
				// Abilities, diseases and voice effects are not things you cast, and listing them
				// would put "Rested" and "Ataxia" in among the fireballs.
				break;
			}
		}

		for (auto* shout : handler->GetFormArray<RE::TESShout>())
		{
			if (shout && player->HasShout(shout))
				g_known.shouts.push_back(shout);
		}

		g_known.built = true;
	}

	void WritePerks(Json& j, RE::PlayerCharacter*, const Config& config)
	{
		j.BeginArray("perks");
		for (auto* perk : g_known.perks)
		{
			j.BeginObject();
			j.Form("id", perk->GetFormID());
			j.Str("name", NameOf(perk));

			// Empty for a perk that belongs to no skill tree -- a quest reward, or one a mod hands
			// out directly. The screen groups those under nothing rather than under a guess.
			const auto tree = g_perkTree.find(perk);
			j.Str("tree", tree == g_perkTree.end() ? "" : tree->second);

			if (config.enableDescriptions)
			{
				RE::BSString desc;
				perk->GetDescription(desc, perk);
				j.Str("desc", desc.c_str());
			}
			j.EndObject();
		}
		j.EndArray();
	}

	// ── effects ─────────────────────────────────────────────────────────────

	void WriteEffects(Json& j, RE::PlayerCharacter* player)
	{
		j.BeginArray("effects");

		auto* target = player->AsMagicTarget();
		auto* list = target ? target->GetActiveEffectList() : nullptr;
		if (list)
		{
			for (auto* effect : *list)
			{
				// An actor with no active effects is the common case, and a null entry inside the
				// list is normal too -- the game leaves holes in it rather than compacting.
				if (!effect || !effect->effect || !effect->effect->baseEffect)
					continue;
				if (effect->flags.all(RE::ActiveEffect::Flag::kInactive))
					continue;

				auto* base = effect->effect->baseEffect;
				const std::string name = NameOf(base);
				if (name.empty())
					continue;                  // unnamed effects are engine bookkeeping

				const char* kind = "buff";
				if (base->data.flags.all(RE::EffectSetting::EffectSettingData::Flag::kDetrimental))
					kind = "debuff";
				if (base->data.flags.all(RE::EffectSetting::EffectSettingData::Flag::kNoDuration))
					kind = "blessing";

				j.BeginObject();
				j.Str("name", name);
				j.Str("kind", kind);
				j.Num("duration", effect->duration - effect->elapsedSeconds);
				j.EndObject();
			}
		}

		j.EndArray();
	}

	// ── inventory ───────────────────────────────────────────────────────────

	/// Which of the ten lists an object belongs on. These are SkyUI's categories rather than the
	/// vanilla menu's four, because that is the layout people know and it is the whole reason the
	/// inventory tab is usable at 400 items.
	const char* CategoryOf(RE::TESBoundObject* object)
	{
		switch (object->GetFormType())
		{
		case RE::FormType::Weapon:     return "weapons";
		case RE::FormType::Armor:      return "armor";
		case RE::FormType::Ammo:       return "ammo";
		case RE::FormType::Ingredient: return "ingredients";
		case RE::FormType::Scroll:     return "scrolls";
		case RE::FormType::Book:       return "books";
		case RE::FormType::KeyMaster:  return "keys";

		case RE::FormType::AlchemyItem:
		{
			// Food and drink are alchemy items with a flag set, and putting a cabbage in with the
			// healing potions is exactly the thing SkyUI existed to fix.
			auto* potion = object->As<RE::AlchemyItem>();
			if (potion && (potion->IsFood() || potion->data.flags.all(RE::AlchemyItem::AlchemyFlag::kFoodItem)))
				return "food";
			return "potions";
		}

		default:
			return "misc";
		}
	}

	/// Owned by somebody else, which the inventory menu shows as a stolen marker.
	///
	/// `IsOwnedBy` defaults to "assume it's yours" when the item carries no ownership at all, which
	/// is the right default: most of what you carry has no owner record and is not stolen.
	bool IsStolen(RE::InventoryEntryData* entry)
	{
		return entry && !entry->IsOwnedBy(RE::PlayerCharacter::GetSingleton(), true);
	}

	void WriteItem(Json& j, RE::TESBoundObject* object, RE::InventoryEntryData* entry,
		std::int32_t count, const Config& config)
	{
		j.BeginObject();
		j.Form("id", object->GetFormID());
		j.Str("name", entry && entry->GetDisplayName() ? entry->GetDisplayName() : NameOf(object));
		j.Int("count", count);
		j.Num("weight", object->GetWeight(), 2);
		j.Int("value", entry ? entry->GetValue() : object->GetGoldValue());
		j.Bool("equipped", entry && entry->IsWorn());
		j.Bool("favorite", entry && entry->IsFavorited());
		j.Bool("stolen", IsStolen(entry));

		bool enchanted = false;

		if (auto* weapon = object->As<RE::TESObjectWEAP>())
		{
			j.Num("damage", static_cast<double>(weapon->GetAttackDamage()));
			j.Str("type", WeaponTypeName(weapon));
			enchanted = weapon->formEnchanting != nullptr;
		}
		else if (auto* armor = object->As<RE::TESObjectARMO>())
		{
			j.Num("armorRating", armor->GetArmorRating() / 100.0);
			j.Str("slot", ArmorSlotName(armor));
			enchanted = armor->formEnchanting != nullptr;
		}

		// An enchantment applied by the player lives on the item's own extra data rather than on
		// the base form, so a self-enchanted sword needs both checks to show as enchanted.
		if (!enchanted && entry && entry->extraLists)
		{
			for (auto* list : *entry->extraLists)
				if (list && list->HasType(RE::ExtraDataType::kEnchantment))
					enchanted = true;
		}
		j.Bool("enchanted", enchanted);

		if (config.enableDescriptions)
		{
			if (auto* potion = object->As<RE::AlchemyItem>())
				j.Str("desc", EffectSummary(potion->effects));
			else if (auto* scroll = object->As<RE::ScrollItem>())
				j.Str("desc", EffectSummary(scroll->effects));
			else if (auto* ingredient = object->As<RE::IngredientItem>())
				j.Str("desc", KnownIngredientEffects(ingredient));
		}

		j.EndObject();
	}

	void WriteInventory(Json& j, RE::PlayerCharacter* player, const Config& config)
	{
		// GetInventory copies the counts and entry pointers out under the game's own locking, which
		// is why this is safe here and would not be from a request thread.
		auto inventory = player->GetInventory();

		struct Bucket { const char* name; std::vector<const decltype(inventory)::value_type*> rows; };
		Bucket buckets[] = {
			{ "weapons", {} }, { "armor", {} }, { "potions", {} }, { "ingredients", {} },
			{ "scrolls", {} }, { "books", {} }, { "food", {} }, { "misc", {} },
			{ "ammo", {} }, { "keys", {} },
		};

		// Recounted from scratch every time this runs, so spending gold lowers it.
		g_gold = 0;

		int taken = 0;
		for (const auto& pair : inventory)
		{
			if (taken >= config.maxInventoryItems)
				break;

			auto* object = pair.first;
			const auto& [count, entry] = pair.second;
			if (!object || count <= 0)
				continue;

			// Gold is caught before the item ceiling and before it can be dropped as "misc": it is
			// a number the screen shows on its own, not a row in a list.
			if (object->GetFormID() == kGoldFormId)
			{
				g_gold = count;
				continue;
			}

			const std::string name = entry && entry->GetDisplayName() ? entry->GetDisplayName() : NameOf(object);
			if (name.empty())
				continue;

			const char* category = CategoryOf(object);
			for (Bucket& bucket : buckets)
			{
				if (std::strcmp(bucket.name, category) == 0)
				{
					bucket.rows.push_back(&pair);
					++taken;
					break;
				}
			}
		}

		j.BeginObject("inventory");
		for (const Bucket& bucket : buckets)
		{
			j.BeginArray(bucket.name);
			for (const auto* pair : bucket.rows)
			{
				const auto& [count, entry] = pair->second;
				WriteItem(j, pair->first, entry.get(), count, config);
			}
			j.EndArray();
		}
		j.EndObject();
	}

	// ── magic ───────────────────────────────────────────────────────────────

	const char* SchoolName(RE::ActorValue school)
	{
		switch (school)
		{
		case RE::ActorValue::kAlteration:  return "Alteration";
		case RE::ActorValue::kConjuration: return "Conjuration";
		case RE::ActorValue::kDestruction: return "Destruction";
		case RE::ActorValue::kIllusion:    return "Illusion";
		case RE::ActorValue::kRestoration: return "Restoration";
		default:                           return "";
		}
	}

	void WriteSpell(Json& j, RE::SpellItem* spell, RE::PlayerCharacter* player,
		RE::TESForm* leftHand, RE::TESForm* rightHand, const Config& config)
	{
		j.BeginObject();
		j.Form("id", spell->GetFormID());
		j.Str("name", NameOf(spell));
		j.Str("school", SchoolName(spell->GetAssociatedSkill()));
		j.Str("level", SpellLevelName(spell));
		j.Num("cost", spell->CalculateMagickaCost(player));
		j.Bool("equipped", spell == leftHand || spell == rightHand);
		if (config.enableDescriptions)
			j.Str("desc", EffectSummary(spell->effects));
		j.EndObject();
	}

	/// Shouts are not on the player the way spells are: the game keeps a form array of every shout
	/// in the load order and asks the player whether it has each one. So this walks that array and
	/// filters, rather than reading a list off the character.
	///
	/// Word knowledge is per word-of-power, and a word can be *known* (found on a wall) without
	/// being *unlocked* (paid for with a dragon soul). The screen draws those differently, because
	/// the difference is the whole progression.
	void WriteShouts(Json& j, RE::PlayerCharacter* player, const Config& config)
	{
		j.BeginArray("shouts");

		auto* equipped = player->GetCurrentShout();

		for (auto* shout : g_known.shouts)
		{
			const std::string name = NameOf(shout);
			if (name.empty())
				continue;

			j.BeginObject();
			j.Form("id", shout->GetFormID());
			j.Str("name", name);
			j.Bool("equipped", equipped == shout);

			if (config.enableDescriptions && shout->variations[0].spell)
				j.Str("desc", EffectSummary(shout->variations[0].spell->effects));

			j.BeginArray("words");
			for (const auto& variation : shout->variations)
			{
				auto* word = variation.word;
				if (!word)
					continue;

				j.BeginObject();
				j.Str("text", NameOf(word));

				// Only "unlocked" is sent -- whether the dragon soul has been spent, which is
				// exactly whether the player has that variation's spell.
				//
				// Whether a word has merely been *found on a wall* is tracked somewhere this build
				// cannot read, and TESWordOfPower carries no flag for it. So the screen shows
				// unlocked words and hides the rest, rather than guessing at a middle state.
				j.Bool("unlocked", variation.spell && player->HasSpell(variation.spell));
				j.EndObject();
			}
			j.EndArray();

			j.EndObject();
		}

		j.EndArray();
	}

	void WriteMagic(Json& j, RE::PlayerCharacter* player, const Config& config)
	{
		auto* leftHand = player->GetEquippedObject(true);
		auto* rightHand = player->GetEquippedObject(false);

		j.BeginObject("magic");

		j.BeginArray("spells");
		for (auto* spell : g_known.spells)
		{
			if (!NameOf(spell).empty())
				WriteSpell(j, spell, player, leftHand, rightHand, config);
		}
		j.EndArray();

		j.BeginArray("powers");
		for (auto* spell : g_known.powers)
		{
			if (!NameOf(spell).empty())
				WriteSpell(j, spell, player, leftHand, rightHand, config);
		}
		j.EndArray();

		WriteShouts(j, player, config);

		j.Str("equippedLeft", leftHand ? FormIdText(leftHand->GetFormID()) : "");
		j.Str("equippedRight", rightHand ? FormIdText(rightHand->GetFormID()) : "");
		j.EndObject();
	}

	// ── quests ──────────────────────────────────────────────────────────────

	void WriteQuests(Json& j, RE::PlayerCharacter*)
	{
		j.BeginArray("quests");

		auto* handler = RE::TESDataHandler::GetSingleton();
		if (handler)
		{
			for (auto* quest : handler->GetFormArray<RE::TESQuest>())
			{
				// The form array is every quest in the load order, most of which the player has
				// never touched. Only ones the game has actually started have anything to show.
				if (!quest || (!quest->IsRunning() && !quest->IsCompleted()))
					continue;

				const std::string name = NameOf(quest);
				if (name.empty())
					continue;                   // engine and mod bookkeeping quests are unnamed

				j.BeginObject();
				j.Form("id", quest->GetFormID());
				j.Str("name", name);
				j.Str("type", QuestTypeName(quest->GetType()));
				j.Bool("active", quest->IsActive());
				j.Bool("completed", quest->IsCompleted());

				j.BeginArray("objectives");
				for (auto* objective : quest->objectives)
				{
					if (!objective || objective->state == RE::QUEST_OBJECTIVE_STATE::kDormant)
						continue;

					j.BeginObject();
					j.Str("text", objective->displayText.c_str());
					j.Bool("done", objective->state == RE::QUEST_OBJECTIVE_STATE::kCompleted);
					j.EndObject();
				}
				j.EndArray();

				j.EndObject();
			}
		}

		j.EndArray();
	}

	// ── the whole document ──────────────────────────────────────────────────

	constexpr const char* kNotReady = R"({"ready":false,"tick":0,"game":"SkyrimSE"})";

	// ── the expensive half ──────────────────────────────────────────────────

	/// The sections that cost real time, kept as finished JSON and rebuilt rarely.
	///
	/// This is the fix for the hang this mod caused. The first version built the whole document
	/// ten times a second on the game thread -- and "the whole document" meant walking every form
	/// in the load order for spells and perks, every quest, the inventory twice, and every
	/// reference in the persistent cell for map markers. On an Anniversary Edition load order that
	/// is far more than 100ms of work, so the tasks piled up faster than the game could drain them
	/// and the main thread never got back to drawing a frame. Windows called it a hang, correctly.
	///
	/// So: the cheap things (health, magicka, stamina, time, position) are rebuilt every tick,
	/// because they are why you glance at a second screen. Everything else is rebuilt on a slow
	/// rotation -- **at most one section per tick** -- and spliced in from its last build. Your
	/// inventory is at worst two seconds stale, which nobody can feel, and the game keeps its
	/// frames.
	// Defined further down; declared here because the scheduler is the first thing that needs them.
	void Stage(const char* what);
	void RebuildKnown(RE::PlayerCharacter* player);
	void WriteInventory(Json& j, RE::PlayerCharacter* player, const Config& config);
	void WriteMagic(Json& j, RE::PlayerCharacter* player, const Config& config);
	void WritePerks(Json& j, RE::PlayerCharacter* player, const Config& config);
	void WriteQuests(Json& j, RE::PlayerCharacter* player);

	struct Slow
	{
		std::string inventory = "{}";
		std::string magic = "{}";
		std::string perks = "[]";
		std::string quests = "[]";
	};

	Slow g_slow;

	/// One section per tick, each refreshed every ~2 seconds at the default rate, and never two in
	/// the same frame -- the whole point is that no single tick is expensive.
	void RefreshSlowSections(RE::PlayerCharacter* player, const Config& config)
	{
		constexpr std::uint64_t kCycle = 20;

		auto capture = [&](auto&& writer) {
			Json j;
			j.BeginObject();
			writer(j);
			j.EndObject();

			// The writers emit `"key":<value>`, so the fragment is the object's body minus the
			// braces and the leading key.
			const std::string& whole = j.Take();
			const size_t colon = whole.find(':');
			return colon == std::string::npos ? std::string("null")
				: whole.substr(colon + 1, whole.size() - colon - 2);
		};

		switch (g_tick % kCycle)
		{
		case 0:
			Stage("slow: inventory");
			g_slow.inventory = capture([&](Json& j) { WriteInventory(j, player, config); });
			break;

		case 5:
			// The form scan feeds both magic and perks, so it runs immediately before the first of
			// them and its result is reused by the second five ticks later.
			Stage("slow: known forms scan");
			RebuildKnown(player);
			g_slow.magic = capture([&](Json& j) { WriteMagic(j, player, config); });
			break;

		case 10:
			Stage("slow: perks");
			g_slow.perks = capture([&](Json& j) { WritePerks(j, player, config); });
			break;

		case 15:
			Stage("slow: quests");
			g_slow.quests = capture([&](Json& j) { WriteQuests(j, player); });
			break;

		default:
			break;
		}
	}

	/// Is the game in a state where reading the world is safe?
	///
	/// This is the check that was missing, and its absence crashed the game on the first load. A
	/// non-null player is NOT enough: during a load screen the player object exists while its 3D,
	/// its parent cell and the cell's reference list are still being built, and walking those
	/// half-built structures is a crash rather than a wrong number.
	///
	/// So: the game must be running, no loading or main menu may be up, and the player must have
	/// its 3D. All four together, every snapshot, because a load screen can start between any two.
	bool SafeToRead(RE::PlayerCharacter* player)
	{
		auto* main = RE::Main::GetSingleton();
		if (!main || !main->gameActive)
			return false;

		auto* ui = RE::UI::GetSingleton();
		if (!ui || ui->IsMenuOpen(RE::LoadingMenu::MENU_NAME) || ui->IsMenuOpen(RE::MainMenu::MENU_NAME))
			return false;

		return player && player->Is3DLoaded() && player->GetParentCell();
	}

	/// Which section of the snapshot is being built, for when one of them takes the game down.
	///
	/// A Skyrim crash leaves no stack this project can read, so the log has to be the stack: each
	/// section names itself before it runs, and the last line in the file is the one that died.
	/// Only traced for the first few snapshots -- after that the path is known good and the log
	/// would be nothing but this.
	std::uint64_t g_traced = 0;

	void Stage(const char* what)
	{
		if (g_traced < 3)
			SKSE::log::info("  snapshot stage: {}", what);
	}

	std::string Build(const Config& config)
	{
		auto* player = RE::PlayerCharacter::GetSingleton();
		if (!SafeToRead(player))
			return kNotReady;

		if (g_traced < 3)
			SKSE::log::info("Building snapshot {}", g_tick + 1);

		// Everything expensive happens here, and only one of them per tick. See Slow, above.
		RefreshSlowSections(player, config);

		Json j;
		j.BeginObject();
		j.Bool("ready", true);
		j.Int("tick", static_cast<long long>(++g_tick));
		j.Str("game", "SkyrimSE");
		j.Str("runtime", RuntimeName());

		// Cheap, every tick: the things that change while you are standing still, and that a second
		// screen is worth glancing at for.
		Stage("player");  WritePlayer(j, player, config);
		Stage("time");    WriteTime(j);
		Stage("skills");  WriteSkills(j, player);
		Stage("effects"); WriteEffects(j, player);

		// Expensive, spliced in from the last time each was built.
		Stage("splice");
		j.Raw("perks", g_slow.perks);
		j.Raw("magic", g_slow.magic);
		j.Raw("inventory", g_slow.inventory);
		j.Raw("quests", g_slow.quests);

		Stage("map"); MapData::Write(j, player, config);
		Stage("done");
		++g_traced;

		j.BeginObject("perms");
		j.Bool("equip", config.allowEquip);
		j.Bool("use", config.allowUse);
		j.Bool("drop", config.allowDrop);
		j.Bool("favorite", config.allowFavorite);
		j.Bool("equipSpell", config.allowEquip);
		j.Bool("setQuest", config.allowSetQuest);
		j.Bool("fastTravel", config.allowFastTravel);
		j.Bool("wait", config.allowWait);
		j.EndObject();

		j.EndObject();
		return j.Take();
	}
}

// ── the public surface ──────────────────────────────────────────────────────

void Snapshot::Tick(const Config& config)
{
	const auto now = std::chrono::steady_clock::now();
	const auto interval = std::chrono::milliseconds(1000 / std::max(1, config.updatesPerSecond));

	if (now - g_lastBuild >= interval)
	{
		g_lastBuild = now;
		std::string built = Build(config);
		std::lock_guard<std::mutex> guard(g_publishLock);
		g_current = std::move(built);
	}

	// Commands are drained every frame regardless of the snapshot rate: a tap should not wait on
	// the next rebuild to happen.
	for (;;)
	{
		Command command;
		{
			std::lock_guard<std::mutex> guard(g_queueLock);
			if (g_queue.empty())
				break;
			command = g_queue.front();
			g_queue.pop_front();
		}
		Actions::Apply(command, config);
	}
}

std::string Snapshot::Current()
{
	std::lock_guard<std::mutex> guard(g_publishLock);
	return g_current;
}

bool Snapshot::QueueCommand(const std::string& body)
{
	Command command = Actions::Parse(body);
	if (command.kind == Command::Kind::None)
		return false;

	std::lock_guard<std::mutex> guard(g_queueLock);
	if (g_queue.size() >= kMaxQueued)
		return false;

	g_queue.push_back(command);
	return true;
}

void Snapshot::Reset()
{
	{
		std::lock_guard<std::mutex> guard(g_publishLock);
		g_current = kNotReady;
		g_tick = 0;
		g_known.built = false;      // a different character has different spells and perks
	}

	// Anything queued was aimed at the character being replaced. Applying it after the load would
	// act on whichever form ID happens to match in the new save.
	std::lock_guard<std::mutex> guard(g_queueLock);
	g_queue.clear();
}
