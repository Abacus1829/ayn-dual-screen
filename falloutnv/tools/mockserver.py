"""
A stand-in for the mod, so the second-screen UI can be designed without launching New Vegas.

Serves the real web/ folder against a fake courier: a walking player, a decaying limb, an
inventory you can equip and drop from, quests, notes, map markers and radio stations. Actions are
actually applied, so the touch interactions can be tested end to end.

    py tools/mockserver.py

Then open http://localhost:27304/. The JSON shapes here must be kept in step with src/Dtos.h.
"""

import json
import math
import os
import random
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 27304                    # one past the mod's 27303, so both can run at once
WEB_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web")

START = time.time()
random.seed(1979)

# ─────────────────────────────────────────────────────────── the fake courier

SPECIAL = [("Strength", 6), ("Perception", 5), ("Endurance", 7),
           ("Charisma", 4), ("Intelligence", 8), ("Agility", 6), ("Luck", 5)]

SKILLS = [("Barter", 32), ("Energy Weapons", 41), ("Explosives", 28), ("Guns", 68),
          ("Lockpick", 55), ("Medicine", 44), ("Melee Weapons", 36), ("Repair", 61),
          ("Science", 72), ("Sneak", 39), ("Speech", 50), ("Survival", 47), ("Unarmed", 30)]

TAGGED = {"Guns", "Repair", "Science"}

PERKS = [
    ("Educated", 1, "+2 skill points per level."),
    ("Comprehension", 1, "Gain one additional skill point from reading books."),
    ("Rapid Reload", 1, "25% faster reload with all weapons."),
    ("Travel Light", 1, "+10% movement speed in light or no armour."),
    ("Jury Rigging", 1, "Repair any item using a roughly similar item."),
    ("Better Criticals", 1, "+50% damage with critical hits."),
]

ITEMS = {
    "weapons": [
        dict(id="w1", name="This Machine", count=1, weight=10.0, value=4000, health=0.82,
             damage=45, dps=112, clip=8, ammoName=".308 Round", spread=0.4, equipped=True,
             desc="A pristine wartime rifle. Somebody kept it very well."),
        dict(id="w2", name="10mm Pistol", count=1, weight=3.0, value=250, health=0.44,
             damage=26, dps=68, clip=12, ammoName="10mm Round", spread=1.2),
        dict(id="w3", name="Varmint Rifle", count=1, weight=6.0, value=200, health=0.91,
             damage=13, dps=25, clip=5, ammoName="5.56mm Round", spread=0.5),
        dict(id="w4", name="Frag Grenade", count=6, weight=0.5, value=50, damage=100),
        dict(id="w5", name="Nine Iron", count=1, weight=4.0, value=125, health=0.66, damage=30),
    ],
    "apparel": [
        dict(id="a1", name="Combat Armor, Reinforced", count=1, weight=25.0, value=1200,
             health=0.73, dt=20, equipped=True, desc="Salvaged plates over a padded liner."),
        dict(id="a2", name="Courier Duster", count=1, weight=2.0, value=300, health=0.95, dt=2),
        dict(id="a3", name="Lucky Shades", count=1, weight=0.1, value=180, health=1.0,
             effect="+1 Luck", equipped=True),
        dict(id="a4", name="Pre-War Casualwear", count=1, weight=2.0, value=40, health=0.30, dt=0),
    ],
    "aid": [
        dict(id="d1", name="Stimpak", count=9, weight=0.0, value=75, effect="+30 Health over 3s"),
        dict(id="d2", name="Doctor's Bag", count=2, weight=2.0, value=175, effect="Heals crippled limbs"),
        dict(id="d3", name="Purified Water", count=14, weight=1.0, value=20, effect="+20 H2O"),
        dict(id="d4", name="Sunset Sarsaparilla", count=5, weight=1.0, value=15, effect="+15 H2O, +1 rad"),
        dict(id="d5", name="Med-X", count=3, weight=0.0, value=125, effect="+25 DR for 240s"),
        dict(id="d6", name="RadAway", count=4, weight=1.0, value=100, effect="-150 rads"),
        dict(id="d7", name="Bighorner Steak", count=2, weight=1.0, value=30, effect="+25 Food"),
    ],
    "misc": [
        dict(id="m1", name="Scrap Metal", count=23, weight=1.0, value=5),
        dict(id="m2", name="Sensor Module", count=4, weight=1.0, value=50),
        dict(id="m3", name="Wonderglue", count=7, weight=0.5, value=20),
        dict(id="m4", name="Bobby Pin", count=31, weight=0.0, value=1),
        dict(id="m5", name="Sunset Sarsaparilla Star Cap", count=18, weight=0.0, value=0),
    ],
    "ammo": [
        dict(id="n1", name=".308 Round", count=64, weight=0.0, value=3),
        dict(id="n2", name="10mm Round", count=212, weight=0.0, value=1),
        dict(id="n3", name="5.56mm Round", count=340, weight=0.0, value=1),
        dict(id="n4", name="Energy Cell", count=88, weight=0.0, value=2),
    ],
    # Weapon mods -- TESObjectIMOD in the game. The last two come from a fake third-party plugin,
    # so the "From" line on the item card gets exercised.
    "mods": [
        dict(id="k1", name="10mm Pistol Laser Sight", count=1, weight=1.0, value=250,
             effect="Improves accuracy", source="FalloutNV.esm"),
        dict(id="k2", name="Varmint Rifle Night Scope", count=1, weight=2.0, value=600,
             effect="Adds a night-vision scope", source="FalloutNV.esm"),
        dict(id="k3", name="This Machine Extended Mag", count=2, weight=1.0, value=400,
             effect="+4 magazine capacity", source="FalloutNV.esm"),
        dict(id="k4", name="Plasma Caster Focus Lens", count=1, weight=3.0, value=1100,
             effect="+15% damage", source="WastelandArsenal.esp"),
        dict(id="k5", name="Rebar Club Serrated Edge", count=1, weight=2.0, value=180,
             effect="Causes bleeding", source="WastelandArsenal.esp"),
    ],
}

# The load order, as the DATA > PLUGINS page shows it.
PLUGINS = [
    ("00", "FalloutNV.esm", True), ("01", "DeadMoney.esm", True),
    ("02", "HonestHearts.esm", True), ("03", "OldWorldBlues.esm", True),
    ("04", "LonesomeRoad.esm", True), ("05", "GunRunnersArsenal.esm", True),
    ("06", "TaleOfTwoWastelands.esm", True), ("07", "YUP - Base Game + All DLC.esm", True),
    ("08", "WastelandArsenal.esp", False), ("09", "MojaveRaiders.esp", False),
    ("0A", "AynDualScreenTest.esp", False),
]

QUESTS = [
    dict(id="q1", name="Ain't That a Kick in the Head", active=False, completed=True, objectives=[
        dict(text="Get out of Doc Mitchell's house.", done=True)]),
    dict(id="q2", name="They Went That-a-Way", active=True, completed=False, objectives=[
        dict(text="Talk to Manny Vargas at the Dino Bite gift shop.", done=True),
        dict(text="Search the Nipton Town Hall for evidence.", done=True),
        dict(text="Follow the trail to Boulder City.", done=False),
        dict(text="Find Benny in New Vegas.", done=False)]),
    dict(id="q3", name="Ring-a-Ding-Ding!", active=False, completed=False, objectives=[
        dict(text="Enter The Tops casino.", done=False)]),
    dict(id="q4", name="Come Fly With Me", active=False, completed=False, objectives=[
        dict(text="Speak to Chris Haversam.", done=True),
        dict(text="Recover the rocket parts from the REPCONN basement.", done=False)]),
    dict(id="q5", name="Back in the Saddle", active=False, completed=True, objectives=[
        dict(text="Complete Sunny Smiles' training.", done=True)]),
]

NOTES = [
    dict(id="t1", name="Mysterious Broadcast", type="holotape",
         text="…repeating. This is an automated message.\nAll personnel report to the vault door."),
    dict(id="t2", name="Nipton Ledger", type="note",
         text="A record of bets taken on the lottery. Most of the names are crossed out."),
    dict(id="t3", name="Note from Benny", type="note",
         text="Truth is, the game was rigged from the start."),
    dict(id="t4", name="Repconn Test Site Terminal", type="note",
         text="Rocket test 14 — nominal until T+40 seconds."),
]

STATS = [
    ("General", "Locations Discovered", 34), ("General", "Days Survived", 21),
    ("General", "Quests Completed", 12), ("General", "Level", 14),
    ("Combat", "People Killed", 88), ("Combat", "Creatures Killed", 143),
    ("Combat", "Critical Strikes", 61), ("Combat", "Sneak Attacks", 19),
    ("Exploration", "Caps Found", 4820), ("Exploration", "Locks Picked", 27),
    ("Exploration", "Terminals Hacked", 15), ("Exploration", "Speech Successes", 22),
]

MARKERS = [
    ("Goodsprings", "Town", -13000, 8600, True), ("Primm", "Town", -6800, -2400, True),
    ("Nipton", "Town", 12000, -9800, True), ("Novac", "Town", 22000, 2200, True),
    ("Boulder City", "Town", 5200, 14000, True), ("New Vegas Strip", "City", -2400, 22000, True),
    ("Hidden Valley", "Military", -9200, 1200, True), ("REPCONN Test Site", "Factory", 24000, 7400, True),
    ("Helios One", "Factory", 9800, -3200, True), ("Vault 22", "Vault", -18000, 14500, True),
    ("Camp McCarran", "Military", -1200, 16800, True), ("Black Mountain", "Landmark", 14000, 4200, False),
    ("Bitter Springs", "Camp", 26000, 18000, False), ("Jacobstown", "Settlement", -21000, 24000, False),
    ("Cottonwood Cove", "Camp", 28000, -6000, False), ("The Fort", "Camp", 8000, 26000, False),
]

RADIO = [
    ("r1", "Radio New Vegas", True), ("r2", "Mojave Music Radio", True),
    ("r3", "Black Mountain Radio", True), ("r4", "Radio Vegas Emergency", False),
    ("r5", "Mysterious Broadcast", False),
]

# Mutable state the actions actually change.
world = dict(
    equipped={"w1", "a1", "a3"},
    activeQuest="q2",
    station="r1",
    caps=4820,
    condition={"head": 1.0, "torso": 0.88, "leftArm": 0.34, "rightArm": 1.0,
               "leftLeg": 0.0, "rightLeg": 0.72},
)


def snapshot():
    t = time.time() - START

    # Wander a loop around Goodsprings so the map arrow visibly moves.
    x = -13000 + math.cos(t / 26) * 5200
    y = 8600 + math.sin(t / 26) * 4200
    angle = (math.degrees(math.atan2(math.cos(t / 26), -math.sin(t / 26))) + 360) % 360

    hp_max = 285
    hp = 150 + 60 * math.sin(t / 9)

    inv = {}
    for bucket, items in ITEMS.items():
        out = []
        for it in items:
            copy = dict(it)
            copy["equipped"] = it["id"] in world["equipped"]
            out.append(copy)
        inv[bucket] = out

    total_weight = sum(i["weight"] * i["count"] for b in ITEMS.values() for i in b)

    # The Pip-Boy's own date line. The Mojave campaign opens in October 2281.
    day = 19 + int(t / 240) % 12
    hour = int((t / 8) % 24)
    minute = int((t / 8 * 60) % 60)
    suffix = "AM" if hour < 12 else "PM"
    clock = f"{(hour % 12) or 12}:{minute:02d} {suffix}"

    return {
        "ready": True,
        "tick": int(t * 10),
        "game": "FalloutNV",
        "gameTime": f"10.{day}.2281   {clock}",
        "plugins": [{"index": i, "name": n, "master": m, "items": (7 if not m else None)}
                    for i, n, m in PLUGINS],
        "player": {
            "name": "Courier Six",
            "level": 14,
            "xp": 24800, "xpBase": 21000, "xpNext": 28000,
            "caps": world["caps"],
            "karma": 340, "karmaText": "Wanderer",
            "hp": round(hp), "hpMax": hp_max,
            "ap": round(60 + 25 * math.sin(t / 3)), "apMax": 90,
            "dt": 22, "dr": 0,
            "weight": round(total_weight, 1), "weightMax": 235,
            "rads": round(120 + 40 * math.sin(t / 17)), "radsMax": 1000, "radsText": "Minor Radiation",
            "hardcore": True,
            "h2o": round(180 + 90 * math.sin(t / 21)), "h2oMax": 1000,
            "fod": round(240 + 60 * math.cos(t / 19)), "fodMax": 1000,
            "slp": round(410 + 80 * math.sin(t / 29)), "slpMax": 1000,
            "condition": world["condition"],
        },
        "special": [{"name": n, "value": v, "base": v} for n, v in SPECIAL],
        "skills": [{"name": n, "value": v, "base": v, "tag": n in TAGGED} for n, v in SKILLS],
        "perks": [{"name": n, "rank": r, "desc": d} for n, r, d in PERKS],
        "effects": [
            {"name": "Med-X", "duration": "3:12"},
            {"name": "Crippled Left Leg", "duration": ""},
            {"name": "Well Rested", "duration": "1:04"},
        ],
        "inventory": inv,
        "quests": [dict(q, active=(q["id"] == world["activeQuest"])) for q in QUESTS],
        "notes": NOTES,
        "stats": [{"group": g, "name": n, "value": v} for g, n, v in STATS],
        "radio": [{"id": i, "name": n, "inRange": r, "active": i == world["station"]}
                  for i, n, r in RADIO],
        "map": {
            "world": "Mojave Wasteland",
            "cell": "Goodsprings",
            "x": x, "y": y, "angle": angle,
            "worldBounds": {"minX": -32000, "minY": -16000, "maxX": 32000, "maxY": 32000},
            "localBounds": {"minX": x - 9000, "minY": y - 9000, "maxX": x + 9000, "maxY": y + 9000},
            "markers": [{"name": n, "type": ty, "x": mx, "y": my,
                         "visited": vis, "canFastTravel": vis}
                        for n, ty, mx, my, vis in MARKERS],
        },
        "perms": {
            "equip": True, "use": True, "drop": True,
            "fastTravel": True, "radio": True, "setQuest": True,
        },
    }


def find_item(item_id):
    for bucket in ITEMS.values():
        for it in bucket:
            if it["id"] == item_id:
                return bucket, it
    return None, None


def apply(cmd):
    action = cmd.get("action")

    if action == "equip":
        bucket, it = find_item(cmd.get("id"))
        if not it:
            return
        if it["id"] in world["equipped"]:
            world["equipped"].discard(it["id"])
        else:
            # Only one weapon and one suit of armour at a time, like the game.
            if bucket is ITEMS["weapons"]:
                world["equipped"] -= {i["id"] for i in ITEMS["weapons"]}
            world["equipped"].add(it["id"])

    elif action == "use":
        bucket, it = find_item(cmd.get("id"))
        if it and it["count"] > 0:
            it["count"] -= 1
            if it["count"] == 0:
                bucket.remove(it)

    elif action == "drop":
        bucket, it = find_item(cmd.get("id"))
        if it:
            it["count"] -= int(cmd.get("count", 1))
            world["equipped"].discard(it["id"])
            if it["count"] <= 0:
                bucket.remove(it)

    elif action == "setQuest":
        world["activeQuest"] = cmd.get("id")

    elif action == "radio":
        world["station"] = cmd.get("id") or None

    elif action == "fastTravel":
        for name, ty, mx, my, vis in MARKERS:
            if name == cmd.get("marker") and vis:
                print(f"  → fast travel to {name}")


# ─────────────────────────────────────────────────────────────── the server

MIME = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
        ".js": "application/javascript; charset=utf-8", ".png": "image/png",
        ".svg": "image/svg+xml"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass  # the access log drowns out anything useful at 10 polls a second

    def _send(self, body, content_type, status=200):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]

        if path == "/state":
            self._send(json.dumps(snapshot()), "application/json; charset=utf-8")
            return

        rel = "index.html" if path == "/" else path.lstrip("/")
        full = os.path.normpath(os.path.join(WEB_ROOT, rel))
        if not full.startswith(os.path.normpath(WEB_ROOT)) or not os.path.isfile(full):
            self._send("not found", "text/plain", 404)
            return

        with open(full, "rb") as fh:
            body = fh.read()
        ext = os.path.splitext(full)[1].lower()
        self._send(body, MIME.get(ext, "application/octet-stream"))

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            cmd = json.loads(raw)
        except ValueError:
            self._send('{"ok":false}', "application/json", 400)
            return

        print("action:", cmd)
        apply(cmd)
        self._send('{"ok":true}', "application/json; charset=utf-8")


if __name__ == "__main__":
    print(f"Mock Pip-Boy on http://localhost:{PORT}/  (serving {os.path.normpath(WEB_ROOT)})")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
