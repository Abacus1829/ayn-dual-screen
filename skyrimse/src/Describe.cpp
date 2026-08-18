#include "PCH.h"

#include "Describe.h"

#include <cstdio>

const char* RuntimeName()
{
	if (REL::Module::IsVR())
		return "VR";
	if (REL::Module::IsAE())
		return "AE";
	return "SE";
}

const char* WeaponTypeName(RE::TESObjectWEAP* weapon)
{
	if (!weapon)
		return "";

	switch (weapon->GetWeaponType())
	{
	case RE::WEAPON_TYPE::kHandToHandMelee: return "unarmed";
	case RE::WEAPON_TYPE::kOneHandSword:    return "sword";
	case RE::WEAPON_TYPE::kOneHandDagger:   return "dagger";
	case RE::WEAPON_TYPE::kOneHandAxe:      return "war axe";
	case RE::WEAPON_TYPE::kOneHandMace:     return "mace";
	case RE::WEAPON_TYPE::kTwoHandSword:    return "greatsword";
	case RE::WEAPON_TYPE::kTwoHandAxe:      return "battleaxe";
	case RE::WEAPON_TYPE::kBow:             return "bow";
	case RE::WEAPON_TYPE::kStaff:           return "staff";
	case RE::WEAPON_TYPE::kCrossbow:        return "crossbow";
	default:                                return "weapon";
	}
}

const char* ArmorSlotName(RE::TESObjectARMO* armor)
{
	if (!armor)
		return "";

	// Skyrim describes armor by which biped slots it covers, and a piece can claim several. The
	// first match wins, in the order a player would name the thing: nobody calls a helmet with a
	// hair slot "hair".
	using Slot = RE::BIPED_MODEL::BipedObjectSlot;
	auto has = [&](Slot slot) { return armor->HasPartOf(slot); };

	if (has(Slot::kShield))  return "shield";
	if (has(Slot::kCirclet) && !has(Slot::kHead))
		return "circlet";
	if (has(Slot::kHead) || has(Slot::kHair) || has(Slot::kLongHair) || has(Slot::kCirclet))
		return "head";
	if (has(Slot::kBody))    return "body";
	if (has(Slot::kHands))   return "hands";
	if (has(Slot::kFeet))    return "feet";
	if (has(Slot::kAmulet))  return "amulet";
	if (has(Slot::kRing))    return "ring";

	return "armor";
}

const char* QuestTypeName(RE::QUEST_DATA::Type questType)
{
	switch (questType)
	{
	case RE::QUEST_DATA::Type::kMainQuest:        return "Main";
	case RE::QUEST_DATA::Type::kMagesGuild:       return "College of Winterhold";
	case RE::QUEST_DATA::Type::kThievesGuild:     return "Thieves Guild";
	case RE::QUEST_DATA::Type::kDarkBrotherhood:  return "Dark Brotherhood";
	case RE::QUEST_DATA::Type::kCompanionsQuest:  return "Companions";
	case RE::QUEST_DATA::Type::kMiscellaneous:    return "Misc";
	case RE::QUEST_DATA::Type::kDaedric:          return "Daedric";
	case RE::QUEST_DATA::Type::kSideQuest:        return "Side";
	case RE::QUEST_DATA::Type::kCivilWar:         return "Civil War";
	case RE::QUEST_DATA::Type::kDLC01_Vampire:    return "Dawnguard";
	case RE::QUEST_DATA::Type::kDLC02_Dragonborn: return "Dragonborn";
	default:                                      return "Other";
	}
}

const char* SpellLevelName(RE::SpellItem* spell)
{
	if (!spell || spell->GetSpellType() != RE::MagicSystem::SpellType::kSpell)
		return "";

	// The magic menu's Novice-to-Master label is not stored on the spell. It comes from the
	// hardest effect the spell carries, which is where the game gets it too.
	std::int32_t hardest = 0;
	for (auto* effect : spell->effects)
	{
		if (effect && effect->baseEffect)
			hardest = std::max(hardest, effect->baseEffect->GetMinimumSkillLevel());
	}

	if (hardest >= 100) return "Master";
	if (hardest >= 75)  return "Expert";
	if (hardest >= 50)  return "Adept";
	if (hardest >= 25)  return "Apprentice";
	return "Novice";
}

namespace
{
	/// One effect, the way the game's own tooltip words it: name, then whichever of magnitude and
	/// duration the effect actually uses. An effect with neither -- a great many scripted ones --
	/// is just its name, which is still worth showing.
	std::string DescribeEffect(RE::Effect* effect)
	{
		if (!effect || !effect->baseEffect)
			return {};

		auto* base = effect->baseEffect;
		const char* name = base->GetName();
		if (!name || !*name)
			return {};

		std::string out = name;
		char tail[64];

		if (effect->effectItem.magnitude > 0.0f)
		{
			std::snprintf(tail, sizeof tail, " %d", static_cast<int>(effect->effectItem.magnitude));
			out += tail;
		}

		if (effect->effectItem.duration > 0)
		{
			std::snprintf(tail, sizeof tail, " for %ds", effect->effectItem.duration);
			out += tail;
		}

		return out;
	}
}

std::string EffectSummary(const RE::BSTArray<RE::Effect*>& effects)
{
	std::string out;

	for (auto* effect : effects)
	{
		std::string one = DescribeEffect(effect);
		if (one.empty())
			continue;

		if (!out.empty())
			out += ", ";
		out += one;

		// Four is where a one-line summary stops being readable on a handheld panel. The rest are
		// not lost -- the detail card shows the item's own description too.
		if (out.size() > 160)
		{
			out += "...";
			break;
		}
	}

	return out;
}

std::string KnownIngredientEffects(RE::IngredientItem* ingredient)
{
	if (!ingredient)
		return {};

	std::string out;

	for (std::uint32_t i = 0; i < ingredient->effects.size() && i < 4; ++i)
	{
		// knownEffectFlags is the game's own record of what this character has tasted -- one bit
		// per effect slot, and an ingredient has four. Reading it is why the screen can show
		// alchemy without giving it away.
		if ((ingredient->gamedata.knownEffectFlags & (1 << i)) == 0)
			continue;

		std::string one = DescribeEffect(ingredient->effects[i]);
		if (one.empty())
			continue;

		if (!out.empty())
			out += ", ";
		out += one;
	}

	return out;
}
