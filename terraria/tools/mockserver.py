"""
A stand-in for the mod, so the second-screen UI can be designed without launching Terraria.

Serves the real web/ folder against a fake world: a walking player, an advancing clock, an explored
minimap, a full inventory and a few buffs. It actually applies swap/drop/trash/sort, so the touch
interactions can be tested end to end.

    py tools/mockserver.py

Then open http://localhost:27302/. The JSON shapes here must be kept in step with Dtos.cs.
"""

import base64
import json
import math
import os
import random
import struct
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 27302
WEB_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web")

WORLD_W, WORLD_H = 4200, 1200
SURFACE = 330.0
ROCK = 430.0

MAP_W, MAP_H = 220, 150

START = time.time()

# ---------------------------------------------------------------- fake world

ITEM_NAMES = [
    "Copper Shortsword", "Copper Pickaxe", "Copper Axe", "Gel", "Torch",
    "Lesser Healing Potion", "Dirt Block", "Stone Block", "Wood", "Iron Ore",
    "Silver Bar", "Grappling Hook", "Shuriken", "Mushroom", "Rope",
    "Glowstick", "Bomb", "Gold Coin", "Recall Potion", "Cobweb",
]

ITEM_COLORS = [
    (198, 118, 60), (198, 118, 60), (198, 118, 60), (110, 190, 130), (240, 200, 90),
    (230, 110, 130), (120, 90, 60), (130, 130, 140), (150, 110, 70), (170, 130, 90),
    (200, 200, 210), (150, 150, 160), (190, 190, 200), (220, 180, 160), (200, 170, 120),
    (140, 230, 160), (60, 60, 70), (240, 200, 90), (200, 130, 220), (220, 220, 230),
]


def make_item(index):
    slot = {"index": index}
    if index in (14, 21, 33, 44, 47):
        return slot  # a few holes so empty slots get exercised

    which = index % len(ITEM_NAMES)
    slot.update({
        "name": ITEM_NAMES[which],
        "stack": random.choice([1, 1, 1, 12, 99, 250]),
        "rare": random.choice([0, 0, 0, 1, 2, 3, 4, 8]),
        "iconKey": "i%d" % which,
        "meta": "12 damage · placeable",
        "healing": which == 5,
        "buffing": False,
    })
    return slot


def make_inventory():
    items = [make_item(i) for i in range(50)]
    for i in range(50, 54):
        items.append({"index": i, "name": "Gold Coin", "stack": 40, "rare": 0, "iconKey": "i17"})
    for i in range(54, 58):
        items.append({"index": i, "name": "Shuriken", "stack": 999, "rare": 0, "iconKey": "i12"})
    return items


INVENTORY = make_inventory()

EQUIPMENT = [
    {"index": 0, "slot": "Helmet", "name": "Copper Helmet", "rare": 0, "defense": 2, "iconKey": "i0"},
    {"index": 1, "slot": "Chest", "name": "Copper Chainmail", "rare": 0, "defense": 3, "iconKey": "i1"},
    {"index": 2, "slot": "Legs", "name": "Copper Greaves", "rare": 0, "defense": 2, "iconKey": "i2"},
    {"index": 3, "slot": "Accessory", "name": "Hermes Boots", "rare": 3, "defense": 0, "iconKey": "i11"},
    {"index": 4, "slot": "Accessory", "name": "Cloud in a Bottle", "rare": 1, "defense": 0, "iconKey": "i14"},
    {"index": 5, "slot": "Accessory"},
    {"index": 6, "slot": "Accessory"},
    {"index": 7, "slot": "Accessory"},
    {"index": 8, "slot": "Accessory"},
    {"index": 9, "slot": "Accessory"},
]

BUFFS = [
    {"type": 1, "name": "Obsidian Skin", "seconds": 412, "iconKey": "b1"},
    {"type": 2, "name": "Regeneration", "seconds": 88, "iconKey": "b2"},
    {"type": 3, "name": "Swiftness", "seconds": 19, "iconKey": "b3"},
    {"type": 4, "name": "Shine", "seconds": -1, "iconKey": "b4"},
]

PROGRESS = {
    "bosses": [
        {"name": "King Slime", "done": True, "hardmode": False},
        {"name": "Eye of Cthulhu", "done": True, "hardmode": False},
        {"name": "Eater of Worlds / Brain of Cthulhu", "done": True, "hardmode": False},
        {"name": "Queen Bee", "done": False, "hardmode": False},
        {"name": "Skeletron", "done": False, "hardmode": False},
        {"name": "Deerclops", "done": False, "hardmode": False},
        {"name": "Wall of Flesh", "done": False, "hardmode": False},
        {"name": "Queen Slime", "done": False, "hardmode": True},
        {"name": "The Destroyer", "done": False, "hardmode": True},
        {"name": "Plantera", "done": False, "hardmode": True},
        {"name": "Moon Lord", "done": False, "hardmode": True},
    ],
    "events": [
        {"name": "Goblin Army", "done": True, "hardmode": False},
        {"name": "Old One's Army", "done": False, "hardmode": False},
        {"name": "Pirate Invasion", "done": False, "hardmode": True},
        {"name": "Martian Madness", "done": False, "hardmode": True},
    ],
    "bossesDone": 3,
    "bossesTotal": 11,
    "hardMode": False,
}

CRAFTABLE = {
    "count": 43,
    "recipes": [
        {"name": "Wooden Sword", "stack": 1, "rare": 0, "iconKey": "i0", "ingredients": ["7x Wood"]},
        {"name": "Torch", "stack": 33, "rare": 0, "iconKey": "i4", "ingredients": ["33x Wood", "33x Gel"]},
        {"name": "Wooden Platform", "stack": 2, "rare": 0, "iconKey": "i6", "ingredients": ["Wood"]},
        {"name": "Copper Broadsword", "stack": 1, "rare": 0, "iconKey": "i1", "ingredients": ["8x Copper Bar"]},
        {"name": "Lesser Healing Potion", "stack": 1, "rare": 1, "iconKey": "i5", "ingredients": ["Bottled Water", "Mushroom", "Gel"]},
        {"name": "Chest", "stack": 1, "rare": 0, "iconKey": "i9", "ingredients": ["8x Wood", "2x Iron Bar"]},
        {"name": "Furnace", "stack": 1, "rare": 0, "iconKey": "i7", "ingredients": ["20x Stone Block", "4x Wood", "3x Torch"]},
        {"name": "Silver Bar", "stack": 1, "rare": 0, "iconKey": "i10", "ingredients": ["4x Silver Ore"]},
    ],
}

MAP_MODE = ["local"]

# ---------------------------------------------------------------- png


def png(width, height, pixels):
    """Encode a list of (r, g, b, a) tuples, row-major, as a PNG."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter: none
        for x in range(width):
            raw.extend(pixels[y * width + x])

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
            + chunk(b"IEND", b""))


def solid_png(width, height, color):
    return png(width, height, [color] * (width * height))


def player_position():
    """A slow figure-of-eight through the caverns, so the map and depth readouts keep moving."""
    t = time.time() - START
    x = WORLD_W / 2 + math.sin(t / 9.0) * 260
    y = SURFACE + 90 + math.sin(t / 5.0) * 70
    return x, y


def minimap_png(mode, origin_x, origin_y, width, height, step):
    """A plausible cave-and-surface cross-section, so the map panel has something honest to scale."""
    px, py = player_position()
    pixels = []
    for y in range(height):
        world_y = origin_y + y * step
        for x in range(width):
            world_x = origin_x + x * step

            # unexplored beyond a radius of the player, like a real world's fog
            if math.hypot(world_x - px, world_y - py) > (900 if mode == "world" else 95):
                pixels.append((0, 0, 0, 0))
                continue

            if world_y < SURFACE - 6:
                pixels.append((92, 148, 214, 255))          # sky
            elif world_y < SURFACE:
                pixels.append((86, 140, 62, 255))           # grass
            elif world_y < ROCK:
                pixels.append((110, 78, 54, 255))           # dirt
            elif (world_x // 7 + world_y // 5) % 11 == 0:
                pixels.append((0, 0, 0, 255))               # cave
            else:
                pixels.append((100, 100, 110, 255))         # stone
    return png(width, height, pixels)


# the conversation flips on and off so the auto-switch behaviour can be exercised
def build_talk():
    t = time.time() - START
    if int(t / 20) % 2 == 0:
        return None
    return {
        "name": "Merchant",
        "artKey": "n17",
        "dialogue": "Care to see my wares? I have just the thing for a spelunker.",
        "shopOpen": True,
        "canBuy": True,
        "shop": [
            {"slot": 0, "name": "Mining Helmet", "stack": 1, "rare": 1, "iconKey": "i0", "price": 400000, "priceText": "40g", "affordable": True},
            {"slot": 1, "name": "Piggy Bank", "stack": 1, "rare": 0, "iconKey": "i9", "price": 1000000, "priceText": "1p", "affordable": True},
            {"slot": 2, "name": "Iron Anvil", "stack": 1, "rare": 0, "iconKey": "i7", "price": 500000, "priceText": "50g", "affordable": False},
            {"slot": 3, "name": "Copper Pickaxe", "stack": 1, "rare": 0, "iconKey": "i1", "price": 50000, "priceText": "5g", "affordable": True},
        ],
    }


def build_state():
    px, py = player_position()
    t = time.time() - START
    minutes = (4.5 * 60 + t * 4) % 1440

    entities = []
    for i in range(7):
        entities.append({
            "kind": ["enemy", "enemy", "town", "friendly", "boss", "enemy", "player"][i],
            "name": "Thing %d" % i,
            "x": px + math.sin(t / 3.0 + i) * (30 + i * 9),
            "y": py + math.cos(t / 4.0 + i) * (16 + i * 4),
        })

    return {
        "ready": True,
        "tick": int(t * 60),
        "worldName": "Mockingbird",
        "worldWidth": WORLD_W,
        "worldHeight": WORLD_H,
        "hardMode": True,
        "difficulty": "Expert",
        "timeMinutes": minutes,
        "dayTime": 300 < minutes < 1140,
        "moonPhase": int(t / 20) % 8,
        "events": ["Blood Moon", "Rain"] if int(t / 15) % 2 else ["Clear"],
        "life": 340 + int(math.sin(t / 2.0) * 60),
        "lifeMax": 400,
        "mana": 120 + int(math.cos(t / 3.0) * 40),
        "manaMax": 200,
        "defense": 42,
        "breath": 200 if int(t / 10) % 3 else 90,
        "breathMax": 200,
        "x": px,
        "y": py,
        "direction": 1 if math.cos(t / 9.0) >= 0 else -1,
        "depthFeet": int((py - SURFACE) * 2),
        "layer": "Underground",
        "biome": "Jungle",
        "coins": 1234567,
        "selectedSlot": int(t / 2) % 10,
        "hotbarSize": 10,
        "mapRev": int(t * 2),
        "healingItems": 37,
        "manaItems": 12,
        "potionCooldown": 24 if int(t / 12) % 3 == 0 else 0,
        "can": {"trash": True, "drop": True, "edit": True, "quickUse": True},
        "inventory": INVENTORY,
        "equipment": EQUIPMENT,
        "buffs": BUFFS,
        "entities": entities,
        "boss": {"name": "Eye of Cthulhu", "life": 2100 + int(math.sin(t) * 300), "lifeMax": 2800},
    }


def build_minimap():
    px, py = player_position()
    mode = MAP_MODE[0]

    if mode == "world":
        step = max(1, math.ceil(WORLD_W / 900.0))
        origin_x, origin_y = 0, 0
        width, height = WORLD_W // step, WORLD_H // step
    else:
        step = 1
        width, height = MAP_W, MAP_H
        origin_x = max(0, min(WORLD_W - width, int(px) - width // 2))
        origin_y = max(0, min(WORLD_H - height, int(py) - height // 2))

    image = minimap_png(mode, origin_x, origin_y, width, height, step)
    return {
        "rev": int((time.time() - START) * 2),
        "mode": mode,
        "originX": origin_x,
        "originY": origin_y,
        "width": width,
        "height": height,
        "step": step,
        "png": "data:image/png;base64," + base64.b64encode(image).decode("ascii"),
    }


# ---------------------------------------------------------------- server

CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".png": "image/png",
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # the console is for the UI work, not a request log

    def reply(self, body, content_type, status=200):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def json(self, payload):
        self.reply(json.dumps(payload).encode("utf-8"), "application/json; charset=utf-8")

    def do_GET(self):
        path = self.path.split("?")[0]

        if path == "/state":
            return self.json(build_state())

        if path == "/minimap":
            return self.json(build_minimap())

        if path == "/talk":
            return self.json(build_talk())

        if path == "/progress":
            return self.json(PROGRESS)

        if path == "/craftable":
            return self.json(CRAFTABLE)

        if path.startswith("/icon/"):
            key = path[len("/icon/"):].rsplit(".", 1)[0]
            if key.startswith("i"):
                color = ITEM_COLORS[int(key[1:]) % len(ITEM_COLORS)]
                return self.reply(solid_png(16, 16, color + (255,)), "image/png")
            return self.reply(solid_png(32, 32, (120, 160, 220, 255)), "image/png")

        if path.startswith("/asset/"):
            # the real slot art comes out of the game; a flat tile is enough to lay the grid out against
            name = path[len("/asset/"):].rsplit(".", 1)[0]
            tint = {
                "slot": (40, 46, 110, 220),
                "slot-selected": (110, 96, 40, 240),
                "slot-coin": (40, 96, 60, 220),
                "slot-ammo": (96, 64, 40, 220),
                "slot-cursor": (96, 40, 40, 220),
            }.get(name, (40, 46, 110, 220))
            return self.reply(solid_png(52, 52, tint), "image/png")

        if path == "/":
            path = "/index.html"

        full = os.path.normpath(os.path.join(WEB_ROOT, path.lstrip("/")))
        if not full.startswith(os.path.normpath(WEB_ROOT)) or not os.path.isfile(full):
            return self.reply(b"not found", "text/plain", 404)

        with open(full, "rb") as handle:
            body = handle.read()
        return self.reply(body, CONTENT_TYPES.get(os.path.splitext(full)[1], "application/octet-stream"))

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        action = json.loads(self.rfile.read(length) or b"{}")
        kind = action.get("type")

        if kind == "swap":
            a, b = action.get("index", -1), action.get("to", -1)
            if 0 <= a < len(INVENTORY) and 0 <= b < len(INVENTORY):
                INVENTORY[a], INVENTORY[b] = INVENTORY[b], INVENTORY[a]
                INVENTORY[a]["index"], INVENTORY[b]["index"] = a, b
        elif kind in ("drop", "trash"):
            i = action.get("index", -1)
            if 0 <= i < len(INVENTORY):
                INVENTORY[i] = {"index": i}
        elif kind == "sort":
            filled = [x for x in INVENTORY[:50] if x.get("name")]
            filled.sort(key=lambda x: x["name"])
            for i in range(50):
                INVENTORY[i] = filled[i] if i < len(filled) else {"index": i}
                INVENTORY[i]["index"] = i
        elif kind == "mapmode":
            MAP_MODE[0] = "world" if action.get("mode") == "world" else "local"

        self.json({"ok": True})


if __name__ == "__main__":
    print("Mock second screen on http://localhost:%d/" % PORT)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
