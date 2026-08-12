"""
Development stand-in for the mod's built-in server.

Serves the real web/ folder plus fake /state, /map, /icon and /action endpoints, so the second-screen
UI can be designed and tested in a browser without launching Stardew Valley. The JSON shapes here must
stay in step with Dtos.cs.

    py tools/mockserver.py [port]

Then open http://localhost:27302/
"""

import json
import math
import random
import struct
import sys
import time
import zlib
from base64 import urlsafe_b64encode
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WEB_ROOT = Path(__file__).resolve().parent.parent / "web"
START = time.time()

CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".png": "image/png",
}


# --------------------------------------------------------------------------- fake map

MAP_WIDTH, MAP_HEIGHT = 48, 34


def build_map():
    rows = []
    for y in range(MAP_HEIGHT):
        row = []
        for x in range(MAP_WIDTH):
            if x == 0 or y == 0 or x == MAP_WIDTH - 1 or y == MAP_HEIGHT - 1:
                row.append("b")                                  # boundary wall
            elif (x - 38) ** 2 + (y - 8) ** 2 < 28:
                row.append("w")                                  # pond
            elif 6 <= x <= 17 and 18 <= y <= 27:
                row.append("c" if (x + y) % 3 else "d")          # crop field
            elif 4 <= x <= 11 and 4 <= y <= 9:
                row.append("B")                                  # barn footprint
            elif random.random() < 0.05:
                row.append("t")                                  # scattered trees
            elif random.random() < 0.03:
                row.append("o")                                  # stones / machines
            elif random.random() < 0.25:
                row.append("r")                                  # grass
            else:
                row.append("g")
            row[-1] = row[-1]
        rows.append("".join(row))

    return {
        "rev": 1,
        "locationId": "Farm",
        "locationName": "Mock Farm",
        "width": MAP_WIDTH,
        "height": MAP_HEIGHT,
        "rows": rows,
        "warps": [
            {"x": MAP_WIDTH - 2, "y": 20, "target": "BusStop"},
            {"x": 24, "y": MAP_HEIGHT - 2, "target": "Forest"},
        ],
    }


random.seed(7)
MAP = build_map()


# --------------------------------------------------------------------------- fake inventory

def key_for(item_id):
    return urlsafe_b64encode(item_id.encode()).decode().rstrip("=")


def item(name, item_id, stack=1, quality=0, category="Item", edible=False):
    return {
        "name": name,
        "stack": stack,
        "quality": quality,
        "category": category,
        "iconKey": key_for(item_id),
        "edible": edible,
    }


INVENTORY = [None] * 36
for slot, entry in {
    0: item("Watering Can", "(T)WateringCan", category="Tool"),
    1: item("Hoe", "(T)Hoe", category="Tool"),
    2: item("Pickaxe", "(T)Pickaxe", category="Tool"),
    3: item("Parsnip Seeds", "(O)472", 42, category="Seed"),
    4: item("Parsnip", "(O)24", 18, 2, "Vegetable", True),
    5: item("Cauliflower", "(O)190", 7, 1, "Vegetable", True),
    6: item("Salmonberry", "(O)296", 63, 0, "Forage", True),
    8: item("Copper Ore", "(O)378", 120, 0, "Resource"),
    9: item("Wood", "(O)388", 411, 0, "Resource"),
    11: item("Stone", "(O)390", 288, 0, "Resource"),
    12: item("Gold Bar", "(O)336", 4, 0, "Resource"),
    13: item("Sturgeon", "(O)698", 2, 4, "Fish", True),
    14: item("Ancient Fruit", "(O)454", 9, 2, "Fruit", True),
    19: item("Chest", "(BC)130", 3, 0, "Crafting"),
}.items():
    INVENTORY[slot] = entry

SELECTED = {"slot": 0}


# --------------------------------------------------------------------------- fake icons

def make_png(width, height, pixels):
    raw = b"".join(
        b"\x00" + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height)
    )

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


# Interior of the stand-in menu box. Vanilla's is light parchment and recolour mods vary wildly, so
# --light reproduces a light-boxed setup (which is what broke the text) and the default a dark one.
MENU_FILL = (45, 36, 29)


def build_menu_box():
    """Stand-in for Maps/MenuTiles (0,256,60,60): a 60x60 nine-slice of 20px thirds."""
    pixels = bytearray()
    for y in range(60):
        for x in range(60):
            edge = min(x, y, 59 - x, 59 - y)
            if edge < 2:
                pixels += bytes((58, 42, 28, 255))
            elif edge < 6:
                pixels += bytes((138, 90, 50, 255))
            elif edge < 8:
                pixels += bytes((200, 155, 98, 255))
            else:
                pixels += bytes((*MENU_FILL, 255))
    return make_png(60, 60, bytes(pixels))


def menu_luma():
    """What the mod reports for the box interior: Rec. 601 luma, same formula as MeasureBrightness."""
    r, g, b = MENU_FILL
    return round(0.299 * r + 0.587 * g + 0.114 * b)


def build_face(seed):
    """Stand-in for an NPC head cropped from their sprite sheet."""
    value = (seed * 2654435761) % 0xFFFFFF
    r, g, b = (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF
    pixels = bytearray()
    for y in range(16):
        for x in range(16):
            if abs(x - 7.5) <= 5 and abs(y - 7.5) <= 6:
                pixels += bytes((r, g, b, 255))
            else:
                pixels += bytes((0, 0, 0, 0))
    return make_png(16, 16, bytes(pixels))


def build_slot():
    """Stand-in for the inventory slot background (menu tile 10)."""
    pixels = bytearray()
    for y in range(16):
        for x in range(16):
            edge = min(x, y, 15 - x, 15 - y)
            if edge == 0:
                pixels += bytes((42, 31, 22, 255))
            elif edge == 1:
                pixels += bytes((138, 106, 72, 255))
            else:
                pixels += bytes((58, 44, 32, 255))
    return make_png(16, 16, bytes(pixels))


def build_star(rgb):
    """Stand-in for the quality stars in LooseSprites/Cursors."""
    pixels = bytearray()
    for y in range(8):
        for x in range(8):
            if abs(x - 3.5) + abs(y - 3.5) <= 3.5:
                pixels += bytes((*rgb, 255))
            else:
                pixels += bytes((0, 0, 0, 0))
    return make_png(8, 8, bytes(pixels))


# the mod serves the real thing from the game's tilesheets; these only exist so the UI can be
# designed without launching Stardew
UI_ASSETS = {
    "menubox": build_menu_box(),
    "slot": build_slot(),
    "quality1": build_star((192, 192, 192)),
    "quality2": build_star((255, 209, 102)),
    "quality4": build_star((185, 140, 255)),
}


ICON_CACHE = {}


def icon_for(key):
    """A deterministic coloured blob so slots are visually distinguishable in the mock."""
    if key in ICON_CACHE:
        return ICON_CACHE[key]

    seed = sum(key.encode()) * 2654435761 % 0xFFFFFF
    r, g, b = (seed >> 16) & 0xFF, (seed >> 8) & 0xFF, seed & 0xFF
    size = 16
    pixels = bytearray()
    for y in range(size):
        for x in range(size):
            inside = 2 <= x < size - 2 and 2 <= y < size - 2
            edge = inside and (x in (2, size - 3) or y in (2, size - 3))
            if not inside:
                pixels += bytes((0, 0, 0, 0))
            elif edge:
                pixels += bytes((r // 3, g // 3, b // 3, 255))
            else:
                pixels += bytes((r, g, b, 255))

    ICON_CACHE[key] = make_png(size, size, bytes(pixels))
    return ICON_CACHE[key]


# --------------------------------------------------------------------------- fake state

QUESTS = [
    {"name": "Robin's Lost Axe", "objective": "Find Robin's axe", "daysLeft": 1, "complete": False},
    {"name": "Feeding Frenzy", "objective": "Feed every animal", "daysLeft": 2, "complete": False},
    {"name": "Advancement", "objective": "Craft a scarecrow", "daysLeft": -1, "complete": False},
    {"name": "Getting Started", "objective": "Plant 15 parsnip seeds", "daysLeft": -1, "complete": True},
]


VILLAGERS = [
    {"name": "Abigail", "location": "Pierre's General Store", "x": 5, "y": 9, "hearts": 8, "maxHearts": 10, "birthday": False, "talked": True, "here": True},
    {"name": "Sebastian", "location": "Mountain", "x": 12, "y": 3, "hearts": 6, "maxHearts": 10, "birthday": True, "talked": False, "here": False},
    {"name": "Leah", "location": "Cindersap Forest", "x": 40, "y": 60, "hearts": 10, "maxHearts": 14, "birthday": False, "talked": True, "here": False},
    {"name": "Linus", "location": "Mountain", "x": 30, "y": 8, "hearts": 4, "maxHearts": 10, "birthday": False, "talked": False, "here": False},
    {"name": "Robin", "location": "Carpenter's Shop", "x": 8, "y": 18, "hearts": 5, "maxHearts": 10, "birthday": False, "talked": False, "here": False},
]

COMMUNITY = {
    "available": True,
    "complete": False,
    "bundlesDone": 11,
    "bundlesTotal": 30,
    "rooms": [
        {"name": "Pantry", "complete": True, "done": 6, "total": 6, "remaining": []},
        {"name": "Crafts Room", "complete": False, "done": 3, "total": 6, "remaining": ["Construction", "Exotic Foraging", "Winter Foraging"]},
        {"name": "Fish Tank", "complete": False, "done": 1, "total": 6, "remaining": ["Ocean Fish", "Lake Fish", "Night Fishing", "Specialty Fish", "Crab Pot"]},
        {"name": "Boiler Room", "complete": False, "done": 1, "total": 3, "remaining": ["Adventurer's", "Geologist's"]},
        {"name": "Vault", "complete": False, "done": 0, "total": 4, "remaining": ["2,500g", "5,000g", "10,000g", "25,000g"]},
        {"name": "Bulletin Board", "complete": False, "done": 0, "total": 5, "remaining": ["Chef's", "Dye", "Field Research", "Fodder", "Enchanter's"]},
    ],
}


def build_state():
    elapsed = time.time() - START
    angle = elapsed * 0.35

    x = MAP_WIDTH / 2 + math.cos(angle) * 12
    y = MAP_HEIGHT / 2 + math.sin(angle * 1.3) * 9
    facing = [1, 2, 3, 0][int((angle / (math.pi / 2)) % 4)]

    minutes = int(elapsed * 20) % (19 * 60)
    time_of_day = 600 + (minutes // 10 * 10)
    time_of_day = (time_of_day // 100) * 100 + (time_of_day % 100) % 60

    entities = []
    for i, kind in enumerate(["npc", "npc", "monster", "animal", "farmer"]):
        entities.append({
            "kind": kind,
            "name": f"{kind}-{i}",
            "iconKey": f"face{i}" if kind == "npc" else None,
            "x": MAP_WIDTH / 2 + math.cos(angle * (0.6 + i * 0.2) + i) * (8 + i * 2),
            "y": MAP_HEIGHT / 2 + math.sin(angle * (0.4 + i * 0.3) + i) * (6 + i),
        })

    inventory = []
    for index, entry in enumerate(INVENTORY):
        slot = {"index": index}
        if entry:
            slot.update(entry)
        inventory.append(slot)

    return {
        "ready": True,
        "tick": int(elapsed * 60),
        "locationId": MAP["locationId"],
        "locationName": MAP["locationName"],
        "mapRev": MAP["rev"],
        "menuLuma": menu_luma(),
        "timeOfDay": time_of_day,
        "dayOfMonth": 14,
        "dayOfWeek": "Wed",
        "season": "summer",
        "year": 2,
        "weather": "rain",
        "money": 128450,
        "stamina": 168 + 40 * math.sin(elapsed / 6),
        "maxStamina": 270,
        "health": 92,
        "maxHealth": 100,
        "x": x,
        "y": y,
        "facing": facing,
        "selectedSlot": SELECTED["slot"],
        "hotbarSize": 12,
        "weatherTomorrow": ["sun", "rain", "storm", "snow", "wind"][int(elapsed / 11) % 5],
        "dailyLuck": [-0.09, -0.02, 0.0, 0.04, 0.09][int(elapsed / 7) % 5],
        "can": {"trash": True, "drop": True, "edit": True, "eat": True},
        "inventory": inventory,
        "entities": entities,
        "birthdays": ["Sebastian"],
        "festival": None,
        "cartToday": True,
        "quests": QUESTS,
        "skills": {"farming": 8, "mining": 5, "foraging": 6, "fishing": 3, "combat": 4},
    }


def apply_action(action):
    kind = action.get("type")
    index = action.get("index", -1)
    target = action.get("to", -1)

    def valid(i):
        return 0 <= i < len(INVENTORY)

    if kind == "select" and 0 <= index < 12:
        SELECTED["slot"] = index
    elif kind == "swap" and valid(index) and valid(target):
        INVENTORY[index], INVENTORY[target] = INVENTORY[target], INVENTORY[index]
    elif kind in ("drop", "trash") and valid(index):
        INVENTORY[index] = None
    elif kind == "eat" and valid(index) and INVENTORY[index]:
        entry = INVENTORY[index]
        entry["stack"] -= 1
        if entry["stack"] <= 0:
            INVENTORY[index] = None
    elif kind == "sort":
        filled = [entry for entry in INVENTORY if entry]
        filled.sort(key=lambda entry: entry["name"])
        for i in range(len(INVENTORY)):
            INVENTORY[i] = filled[i] if i < len(filled) else None

    print(f"  action: {kind} index={index} to={target}")


# --------------------------------------------------------------------------- server

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # the action log above is the only output worth seeing

    def reply(self, body, content_type, status=200):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def reply_json(self, payload):
        self.reply(json.dumps(payload).encode(), "application/json; charset=utf-8")

    def do_GET(self):
        path = self.path.split("?")[0]

        if path == "/state":
            return self.reply_json(build_state())
        if path == "/villagers":
            return self.reply_json(VILLAGERS)

        if path == "/community":
            return self.reply_json(COMMUNITY)

        if path == "/map":
            return self.reply_json(MAP)
        if path.startswith("/asset/"):
            name = path[len("/asset/"):].removesuffix(".png")
            if name not in UI_ASSETS:
                return self.reply(b"not found", "text/plain", 404)
            return self.reply(UI_ASSETS[name], "image/png")
        if path.startswith("/npc/"):
            key = path[len("/npc/"):].removesuffix(".png")
            return self.reply(build_face(sum(key.encode())), "image/png")
        if path.startswith("/icon/"):
            key = path[len("/icon/"):].removesuffix(".png")
            return self.reply(icon_for(key), "image/png")

        if path == "/":
            path = "/index.html"
        target = (WEB_ROOT / path.lstrip("/")).resolve()
        if not str(target).startswith(str(WEB_ROOT)) or not target.is_file():
            return self.reply(b"not found", "text/plain", 404)

        return self.reply(target.read_bytes(), CONTENT_TYPES.get(target.suffix, "application/octet-stream"))

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            apply_action(json.loads(raw))
        except json.JSONDecodeError:
            return self.reply_json({"ok": False})
        return self.reply_json({"ok": True})


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--light" in sys.argv:
        # reproduces a light menu box, vanilla or recoloured -- the case that made the text vanish
        MENU_FILL = (222, 199, 158)
        UI_ASSETS["menubox"] = build_menu_box()

    port = int(args[0]) if args else 27302
    print(f"Mock second screen on http://localhost:{port}/  (serving {WEB_ROOT})")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
