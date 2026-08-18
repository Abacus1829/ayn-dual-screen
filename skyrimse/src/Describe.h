#pragma once

#include <RE/Skyrim.h>

#include <string>

// Turning the game's own data into the short strings the second screen shows.
//
// None of this touches mutable game state -- it reads static form data and formats it -- but it is
// called from the snapshot builder and therefore still runs on the game thread. There is no reason
// to call it from anywhere else.

/// "SE", "AE" or "VR". CommonLibSSE-NG produces one DLL for all three, so the screen can say which
/// one it is actually attached to rather than making the reader guess from a version number.
const char* RuntimeName();

/// "sword", "greatsword", "bow"... The screen groups and sorts on this the way SkyUI does.
const char* WeaponTypeName(RE::TESObjectWEAP* weapon);

/// "head", "body", "hands", "feet", "shield", "amulet"... Derived from the biped slots the armor
/// occupies, because Skyrim has no single "this is a helmet" field -- a circlet, a helmet and a
/// mod's hood all just claim slot 30.
const char* ArmorSlotName(RE::TESObjectARMO* armor);

/// "Main", "Side", "Companions", "Misc"... the journal's own tabs.
const char* QuestTypeName(RE::QUEST_DATA::Type questType);

/// "Novice" through "Master", from the hardest effect in the spell. Skyrim has no spell-level
/// field: the level shown in the magic menu is derived from the minimum skill its effects ask for,
/// and this derives it the same way.
const char* SpellLevelName(RE::SpellItem* spell);

/// One line summarising what a potion, scroll or spell does: the magnitudes and durations of its
/// effects, joined. Built from the effect list rather than from a description string, because most
/// items in Skyrim have no description text at all -- the menus compose it exactly like this.
std::string EffectSummary(const RE::BSTArray<RE::Effect*>& effects);

/// The effects of an ingredient that this character has actually discovered.
///
/// Undiscovered effects are omitted, not blanked out with question marks: the point of alchemy is
/// finding out, and a second screen that quietly spoiled every ingredient would be a cheat rather
/// than a companion. What you have eaten, you can see.
std::string KnownIngredientEffects(RE::IngredientItem* ingredient);
