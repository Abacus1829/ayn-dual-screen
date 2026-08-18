#include "PCH.h"

#include "Actions.h"

#include "Config.h"
#include "MapData.h"

#include <cstdlib>

// Everything in Apply() runs on the game thread. Parse() does not touch the game at all, which is
// what makes it safe to call from the socket thread that received the request.

namespace
{
	/// Pull one string field out of a flat JSON object. The screen posts nothing more nested than
	/// {"action":"equip","id":"0001397e","hand":"left"}, so this is enough and saves taking a JSON
	/// parser as a dependency for four fields.
	std::string FieldOf(const std::string& body, const char* name)
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

		// Numbers and booleans arrive unquoted; strings arrive quoted. Take whichever this is.
		size_t start = body.find_first_not_of(" \t", colon + 1);
		if (start == std::string::npos)
			return {};

		if (body[start] != '"')
		{
			size_t end = body.find_first_of(",}", start);
			std::string raw = body.substr(start, end == std::string::npos ? std::string::npos : end - start);
			while (!raw.empty() && (raw.back() == ' ' || raw.back() == '\t'))
				raw.pop_back();
			return raw;
		}

		std::string out;
		for (size_t i = start + 1; i < body.size(); ++i)
		{
			if (body[i] == '\\' && i + 1 < body.size()) { out += body[++i]; continue; }
			if (body[i] == '"') break;
			out += body[i];
		}
		return out;
	}

	std::uint32_t FormIdOf(const std::string& text)
	{
		if (text.empty())
			return 0;
		return static_cast<std::uint32_t>(std::strtoul(text.c_str(), nullptr, 16));
	}

	/// The form the screen named, if it is still there and is still the kind of thing it claimed.
	/// A form ID that has gone away between the snapshot and the tap is the normal case after a
	/// load, not an error.
	template <class T>
	T* Resolve(std::uint32_t formId)
	{
		auto* form = RE::TESForm::LookupByID(formId);
		return form ? form->As<T>() : nullptr;
	}

	RE::TESBoundObject* ResolveCarried(std::uint32_t formId, std::int32_t& countOut)
	{
		auto* player = RE::PlayerCharacter::GetSingleton();
		auto* object = Resolve<RE::TESBoundObject>(formId);
		if (!player || !object)
			return nullptr;

		// Acting on something the player does not have is how a screen one frame out of date turns
		// into an item duplicated out of thin air. The count comes from the game, never from the
		// request.
		countOut = player->GetItemCount(object);
		return countOut > 0 ? object : nullptr;
	}

	void Refuse(const char* what, const char* why)
	{
		// Logged rather than answered: the HTTP response went out the moment the command was
		// queued, and the screen learns what happened by watching the next snapshot. A line in the
		// log is what turns "the button did nothing" into something diagnosable.
		SKSE::log::info("Refused {}: {}", what, why);
	}
}

Command Actions::Parse(const std::string& body)
{
	Command command;

	const std::string action = FieldOf(body, "action");
	if (action.empty())
		return command;

	if (action == "equip")           command.kind = Command::Kind::Equip;
	else if (action == "unequip")    command.kind = Command::Kind::Unequip;
	else if (action == "use")        command.kind = Command::Kind::Use;
	else if (action == "drop")       command.kind = Command::Kind::Drop;
	else if (action == "favorite")   command.kind = Command::Kind::Favorite;
	else if (action == "equipSpell") command.kind = Command::Kind::EquipSpell;
	else if (action == "equipShout") command.kind = Command::Kind::EquipShout;
	else if (action == "setQuest")   command.kind = Command::Kind::SetQuest;
	else if (action == "fastTravel") command.kind = Command::Kind::FastTravel;
	else if (action == "wait")       command.kind = Command::Kind::Wait;
	else                             return command;

	command.id = FormIdOf(FieldOf(body, "id"));

	const std::string hand = FieldOf(body, "hand");
	command.handGiven = !hand.empty();
	command.leftHand = hand == "left";

	const std::string count = FieldOf(body, "count");
	if (!count.empty())
		command.count = std::max(1, std::atoi(count.c_str()));

	const std::string hours = FieldOf(body, "hours");
	if (!hours.empty())
		command.hours = std::clamp(std::atoi(hours.c_str()), 1, 24);

	const std::string on = FieldOf(body, "on");
	command.on = on.empty() || on == "true" || on == "1";

	// Every kind except wait names something. A command with no ID would act on form 0, which is
	// not nothing -- it is a real form.
	if (command.kind != Command::Kind::Wait && command.id == 0)
		command.kind = Command::Kind::None;

	return command;
}

void Actions::Apply(const Command& command, const Config& config)
{
	auto* player = RE::PlayerCharacter::GetSingleton();
	if (!player)
		return;

	auto* equipManager = RE::ActorEquipManager::GetSingleton();

	switch (command.kind)
	{
	case Command::Kind::Equip:
	{
		if (!config.allowEquip)
			return Refuse("equip", "AllowEquip is off");

		std::int32_t held = 0;
		auto* object = ResolveCarried(command.id, held);
		if (!object || !equipManager)
			return Refuse("equip", "not carried");

		// Which hand, when the screen said. Armor and ammo ignore the slot entirely -- the game
		// works out where a cuirass goes better than a web page can.
		const RE::BGSEquipSlot* slot = nullptr;
		if (command.handGiven)
		{
			auto* defaults = RE::BGSDefaultObjectManager::GetSingleton();
			if (defaults)
				slot = defaults->GetObject<RE::BGSEquipSlot>(command.leftHand
					? RE::DEFAULT_OBJECT::kLeftHandEquip
					: RE::DEFAULT_OBJECT::kRightHandEquip);
		}

		equipManager->EquipObject(player, object, nullptr, 1, slot);
		break;
	}

	case Command::Kind::Unequip:
	{
		if (!config.allowEquip)
			return Refuse("unequip", "AllowEquip is off");

		std::int32_t held = 0;
		auto* object = ResolveCarried(command.id, held);
		if (!object || !equipManager)
			return Refuse("unequip", "not carried");

		equipManager->UnequipObject(player, object);
		break;
	}

	case Command::Kind::Use:
	{
		if (!config.allowUse)
			return Refuse("use", "AllowUse is off");

		std::int32_t held = 0;
		auto* object = ResolveCarried(command.id, held);
		if (!object || !equipManager)
			return Refuse("use", "not carried");

		// Drinking a potion, eating and reading a scroll are all "equip it" as far as Skyrim is
		// concerned; the game consumes it and runs the effect. Doing it through the equip manager
		// rather than by applying the effects directly is what keeps the count, the sound and the
		// achievement bookkeeping right.
		if (object->Is(RE::FormType::AlchemyItem) || object->Is(RE::FormType::Scroll) ||
			object->Is(RE::FormType::Ingredient))
		{
			equipManager->EquipObject(player, object);
		}
		else
		{
			Refuse("use", "not something that can be consumed");
		}
		break;
	}

	case Command::Kind::Drop:
	{
		if (!config.allowDrop)
			return Refuse("drop", "AllowDrop is off");

		std::int32_t held = 0;
		auto* object = ResolveCarried(command.id, held);
		if (!object)
			return Refuse("drop", "not carried");

		// Never more than you have. The screen's count is at best one frame old, and a request for
		// more than the stack is a request to create items.
		const std::int32_t count = std::min<std::int32_t>(command.count, held);
		if (count <= 0)
			return Refuse("drop", "nothing to drop");

		player->DropObject(object, nullptr, count);
		break;
	}

	case Command::Kind::Favorite:
	{
		if (!config.allowFavorite)
			return Refuse("favorite", "AllowFavorite is off");

		// Not implemented. Item favourites live in the favourites menu's own bookkeeping rather
		// than on the item, and the routine that maintains it is not something this plugin can
		// call without reaching into the menu -- which is the class of thing that corrupts a save
		// when it goes wrong. The screen greys the star out; it does not silently do nothing.
		Refuse("favorite", "not implemented -- see the README");
		break;
	}

	case Command::Kind::EquipSpell:
	{
		if (!config.allowEquip)
			return Refuse("equipSpell", "AllowEquip is off");

		auto* spell = Resolve<RE::SpellItem>(command.id);
		if (!spell || !player->HasSpell(spell) || !equipManager)
			return Refuse("equipSpell", "spell not known");

		auto* defaults = RE::BGSDefaultObjectManager::GetSingleton();
		const RE::BGSEquipSlot* slot = defaults
			? defaults->GetObject<RE::BGSEquipSlot>(command.leftHand
				? RE::DEFAULT_OBJECT::kLeftHandEquip
				: RE::DEFAULT_OBJECT::kRightHandEquip)
			: nullptr;

		equipManager->EquipSpell(player, spell, slot);
		break;
	}

	case Command::Kind::EquipShout:
	{
		if (!config.allowEquip)
			return Refuse("equipShout", "AllowEquip is off");

		auto* shout = Resolve<RE::TESShout>(command.id);
		if (!shout || !player->HasShout(shout) || !equipManager)
			return Refuse("equipShout", "shout not known");

		equipManager->EquipShout(player, shout);
		break;
	}

	case Command::Kind::SetQuest:
	{
		if (!config.allowSetQuest)
			return Refuse("setQuest", "AllowSetQuest is off");

		auto* quest = Resolve<RE::TESQuest>(command.id);
		if (!quest || !quest->IsRunning())
			return Refuse("setQuest", "quest is not running");

		// Not implemented. CommonLibSSE exposes `IsActive` but no setter, and the tempting version
		// -- writing the active flag by hand -- skips the bookkeeping that moves the quest marker
		// with it, which is exactly how a journal ends up pointing at nothing. The screen greys
		// the button out; it does not silently do nothing.
		Refuse("setQuest", "not implemented -- see the README");
		break;
	}

	case Command::Kind::FastTravel:
	{
		if (!config.allowFastTravel)
			return Refuse("fastTravel", "AllowFastTravel is off");

		if (player->IsInCombat())
			return Refuse("fastTravel", "in combat");

		auto* marker = MapData::FindTravellableMarker(command.id);
		if (!marker)
			return Refuse("fastTravel", "marker is undiscovered or cannot be travelled to");

		// MoveTo is the move itself. It does NOT pass the time the game's own fast travel spends,
		// and this mod does not add hours to the clock behind the game's back -- writing the
		// calendar is precisely the kind of bookkeeping-skipping the rest of this file avoids.
		//
		// So this is closer to a carriage than to the map: it takes you there, and it does not
		// charge you for the trip. The README says so plainly, and the setting is off by default.
		player->MoveTo(marker);
		break;
	}

	case Command::Kind::Wait:
	{
		if (!config.allowWait)
			return Refuse("wait", "AllowWait is off");

		// Not implemented. Waiting is a menu the game drives, with its own rules about combat,
		// enemies nearby and being indoors; advancing the calendar directly instead would skip
		// every one of them, and the things that are meant to happen while you wait -- respawns,
		// restocks, healing -- would not. A wrong wait is a wrong world state, so this refuses
		// rather than approximating.
		Refuse("wait", "not implemented -- see the README");
		break;
	}

	case Command::Kind::None:
	default:
		break;
	}
}
