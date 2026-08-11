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
//    "game":   string        "FalloutNV"
//
//    "player": {
//      "name": string, "level": int, "xp": int,
//      "hp": num, "hpMax": num,            current and permanent Health
//      "ap": num, "apMax": num,            Action Points
//      "dt": num,                          Damage Threshold (New Vegas; Fallout 3 had none)
//      "dr": num,                          Damage Resistance
//      "weight": num, "weightMax": num,    carried vs. carry weight
//      "rads": num, "radsMax": 1000, "radsText": string,
//      "karma": num, "karmaText": string,
//      "hardcore": bool,                   the three counters below only matter when true
//      "h2o": num, "h2oMax": 1000,         dehydration, counting UP toward death
//      "fod": num, "fodMax": 1000,         starvation
//      "slp": num, "slpMax": 1000,         sleep deprivation
//      "condition": { "head": 0..1, "torso": 0..1, "leftArm": 0..1,
//                     "rightArm": 0..1, "leftLeg": 0..1, "rightLeg": 0..1 }
//    }
//
//    "special": [ { "name": string, "value": int, "base": int } ]       7 entries
//    "skills":  [ { "name": string, "value": int, "base": int,
//                   "tag": bool } ]                                     13 entries
//    "perks":   [ { "name": string, "rank": int, "desc": string } ]
//    "effects": [ { "name": string, "duration": string } ]
//
//    "inventory": {
//      "weapons": [ Item ], "apparel": [ Item ], "aid": [ Item ],
//      "misc":    [ Item ], "ammo":    [ Item ]
//    }
//
//    "quests": [ {
//      "id": string,          form ID, 8 hex digits -- the handle actions use
//      "name": string, "active": bool, "completed": bool,
//      "objectives": [ { "text": string, "done": bool } ]
//    } ]
//
//    "notes": [ { "id": string, "name": string,
//                 "type": "note"|"holotape", "text": string } ]
//    "stats": [ { "group": string, "name": string, "value": string } ]
//    "radio": [ { "id": string, "name": string,
//                 "active": bool, "inRange": bool } ]
//
//    "map": {
//      "world": string,       worldspace name, empty in an interior
//      "cell":  string,       cell name
//      "x": num, "y": num,    world units
//      "angle": num,          degrees, clockwise from north
//      "localBounds": Bounds, a window around the player
//      "worldBounds": Bounds, the whole worldspace
//      "markers": [ { "name": string, "type": string, "x": num, "y": num,
//                     "visited": bool, "canFastTravel": bool } ]
//    }
//
//    "perms": {               what the config allows; the screen greys out the rest
//      "equip": bool, "use": bool, "drop": bool,
//      "fastTravel": bool, "radio": bool, "setQuest": bool
//    }
//  }
//
//  Item = {
//    "id":       string   form ID, 8 hex digits
//    "name":     string
//    "count":    int
//    "weight":   num      absent for classes the SDK has not mapped -- see ReadEntry()
//    "value":    int      in caps
//    "equipped": bool
//    "health":   0..1     present only for items that carry condition
//  }
//
//  Bounds = { "minX": num, "minY": num, "maxX": num, "maxY": num }
//
// ─────────────────────────────────────────────────────────────────────────────
//
// POST /action        { "action": string, ... }  ->  { "ok": bool }
//
//   { "action": "equip",      "id": <form id> }     weapons and apparel; toggles
//   { "action": "use",        "id": <form id> }     aid items
//   { "action": "drop",       "id": <form id>, "count": int }
//   { "action": "setQuest",   "id": <quest form id> }
//   { "action": "radio",      "id": <station id> }  empty id switches the radio off
//   { "action": "fastTravel", "marker": <name> }
//
// "ok" means the command was queued, not that it succeeded: it is applied on the next game frame,
// and the game thread re-checks the config permission before doing anything. The screen finds out
// what actually happened by watching the next snapshot, which is the only honest answer available
// without blocking the HTTP thread on the game loop.
//
// Items are addressed by form ID rather than by position in the list, so a tap that lands one
// frame after the inventory shifted cannot act on the wrong object.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// GET /            the second-screen page, and everything else under web/
