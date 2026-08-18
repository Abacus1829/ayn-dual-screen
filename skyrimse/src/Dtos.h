#pragma once

// The wire format, written down in one place.
//
// There is no code here. The JSON is built by hand in Snapshot.cpp because a struct-and-serialiser
// layer would be more machinery than four endpoints justify -- but the shape still needs an owner,
// and this is it. web/app.js and tools/mockserver.py both have to match what follows.
//
// Fields the mod cannot fill are omitted or sent empty rather than invented. The screen renders an
// empty tab correctly, so a missing reader shows up as "nothing here" rather than as a plausible
// lie about your character.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// GET /state
//
//  {
//    "ready":  bool          false until a save is loaded; the screen shows a waiting message
//    "tick":   int           increments per snapshot, for spotting a stalled feed
//    "game":   string        "SkyrimSE"
//    "runtime": string       "SE" | "AE" | "VR" -- which build of the game we loaded into
//
//    "player": {
//      "name": string, "race": string, "level": int,
//      "hp": num,  "hpMax": num,
//      "mp": num,  "mpMax": num,            magicka
//      "sp": num,  "spMax": num,            stamina
//      "weight": num, "weightMax": num,     carried vs. carry weight
//      "gold": int,
//      "armorRating": num, "damage": num,   the two numbers the stats menu shows
//      "beast": "none"|"werewolf"|"vampireLord",
//      "inCombat": bool, "sneaking": bool, "overEncumbered": bool
//    }
//
//    "time": {
//      "text":  string,       "07:42"
//      "hour":  num,          0..24, fractional -- the screen draws a dial from it
//      "day":   int, "month": int, "monthName": string, "year": int,
//      "dayName": string,     "Sundas"
//      "daysPassed": num,
//      "night": bool          the screen dims itself after dark, like the game's own map does
//    }
//
//    "skills": [ { "name": string, "value": int, "base": int,
//                  "school": "combat"|"magic"|"stealth" } ]    18 entries
//
//    "perks":  [ { "id": string, "name": string, "tree": string, "desc": string } ]
//
//  There is no XP figure and no per-skill "progress". Both live on PlayerSkills, which sits at a
//  different offset in every runtime -- so a build covering SE, AE and VR at once cannot reach it,
//  and CommonLibSSE refuses rather than letting you read the wrong bytes. The screen draws no bar
//  rather than a fabricated one. "tree" is the skill the perk actually hangs off, found by walking
//  the game's own perk trees, and is empty for a perk that belongs to no tree.
//    "stats":  [ { "group": string, "name": string, "value": string } ]
//
//    "effects": [ { "name": string, "desc": string, "duration": num,
//                   "kind": "buff"|"debuff"|"disease"|"blessing" } ]
//
//    "magic": {
//      "spells":  [ Spell ],   known spells, by school
//      "powers":  [ Spell ],   greater and lesser powers
//      "shouts":  [ Shout ],
//      "equippedLeft": string, "equippedRight": string    spell ids, empty if none
//      "equippedShout": string
//    }
//
//    "inventory": {
//      "weapons":     [ Item ], "armor":  [ Item ], "potions": [ Item ],
//      "ingredients": [ Item ], "scrolls":[ Item ], "books":   [ Item ],
//      "food":        [ Item ], "misc":   [ Item ], "ammo":    [ Item ],
//      "keys":        [ Item ]
//    }
//
//    "quests": [ {
//      "id": string,          form ID, 8 hex digits -- the handle actions use
//      "name": string, "type": string,       "Main"|"Side"|"Faction"|"Misc"...
//      "active": bool, "completed": bool,
//      "objectives": [ { "text": string, "done": bool } ]
//    } ]
//
//    "map": {
//      "world": string,       worldspace name, empty in an interior
//      "worldId": string,     form ID -- markers belong to a worldspace, not to the player
//      "cell":  string,       cell name; what the screen shows when you are indoors
//      "interior": bool,
//      "x": num, "y": num,    world units
//      "angle": num,          degrees, clockwise from north
//      "worldBounds": Bounds, the worldspace's own extents, from its map data
//      "markers": [ { "id": string, "name": string, "type": int,
//                     "x": num, "y": num,
//                     "visited": bool, "canFastTravel": bool } ]
//    }
//
//    "perms": {               what the config allows; the screen greys out the rest
//      "equip": bool, "use": bool, "drop": bool, "favorite": bool,
//      "equipSpell": bool, "setQuest": bool, "fastTravel": bool, "wait": bool
//    }
//  }
//
//  Item = {
//    "id":       string   form ID, 8 hex digits
//    "name":     string
//    "count":    int
//    "weight":   num
//    "value":    int      in septims
//    "equipped": bool
//    "favorite": bool
//    "stolen":   bool
//    "enchanted":bool
//    "desc":     string   effect text for potions, scrolls and enchanted gear; empty otherwise
//    "armorRating": num   armor only
//    "damage":   num      weapons only
//    "slot":     string   armor only: "head", "body", "hands", "feet", "shield"...
//    "type":     string   weapons only: "sword", "bow", "dagger"...
//    "charge":   0..1     enchanted gear with a charge left
//  }
//
//  Spell = {
//    "id": string, "name": string, "school": string, "level": string,
//    "cost": num, "desc": string, "equipped": bool, "favorite": bool
//  }
//
//  Shout = {
//    "id": string, "name": string, "desc": string, "equipped": bool,
//    "words": [ { "text": string, "unlocked": bool } ]                   1..3
//  }
//
//  "unlocked" is whether the dragon soul has been spent on that word. Whether a word has merely
//  been found on a wall is not sent: TESWordOfPower carries no flag for it and the player's own
//  record of it is in the runtime-data block this build cannot read. There is no "recharge"
//  either -- the shout cooldown lives in the same place.
//
//  Bounds = { "minX": num, "minY": num, "maxX": num, "maxY": num }
//
//  Marker "type" is the game's own MARKER_TYPE enum (0..40ish): 0 none, 1 city, 2 town,
//  3 settlement, 4 cave, 5 camp, 6 fort, 7 nordic ruin, 8 dwemer ruin, 9 shipwreck, 10 grove,
//  11 landmark, 12 dragon lair, 13 farm, 14 wood mill, 15 mine, 16 imperial camp,
//  17 stormcloak camp, 18 doomstone, 19 word wall, 20 giant camp, 21 shack, 22 lighthouse,
//  23 orc stronghold, 24 shrine, 25 stable, 26 clearing, 27 pass, 28 daedric shrine,
//  29 nordic dwelling, 30 hall of the dead, 31 smuggler's den, 32 spring, 33 unmarked,
//  34 player house, 35 dragon mound. The screen keeps its own glyph table keyed on this number
//  and falls back to a dot for anything it does not recognise, so a marker type added by a mod
//  shows up as a marker rather than as nothing.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// POST /action        { "action": string, ... }  ->  { "ok": bool }
//
//   { "action": "equip",      "id": <form id>, "hand": "left"|"right"|"" }
//   { "action": "unequip",    "id": <form id> }
//   { "action": "use",        "id": <form id> }     potions, food, scrolls, books
//   { "action": "drop",       "id": <form id>, "count": int }
//   { "action": "favorite",   "id": <form id>, "on": bool }
//   { "action": "equipSpell", "id": <spell id>, "hand": "left"|"right" }
//   { "action": "equipShout", "id": <shout id> }
//   { "action": "setQuest",   "id": <quest form id> }
//   { "action": "fastTravel", "id": <marker form id> }
//   { "action": "wait",       "hours": int }
//
// "ok" means the command was queued, not that it succeeded: it is applied on the next game frame,
// and the game thread re-checks the config permission before doing anything. The screen finds out
// what actually happened by watching the next snapshot, which is the only honest answer available
// without blocking the HTTP thread on the game loop.
//
// Items, spells, quests and markers are addressed by form ID rather than by position in a list, so
// a tap that lands one frame after the inventory shifted cannot act on the wrong object.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// GET  /config        the mod's own settings, for the screen's settings panel
// POST /config        { "key": string, "value": string }  -- one setting at a time
//
// GET  /              the second-screen page, and everything else under web/
