#!/usr/bin/env python3
"""
A stand-in for the plugin, so the second screen can be designed without launching Skyrim.

Serves the real web/ folder against a fake Dragonborn: a character who walks around Whiterun Hold,
whose magicka regenerates, whose potions can be drunk, whose spells can be equipped and whose
quests can be made active. The interactions work end to end, which is the point -- a mock that only
serves static JSON lets you build a screen whose buttons have never once been pressed.

    py tools/mockserver.py

Then open http://localhost:27306/. Port 27306 is one past the plugin's, so both can run at once.

The JSON here MUST match src/Dtos.h. When the wire format changes, this changes with it, or the
next person to design against it builds something the plugin cannot feed.
"""

import http.server
import json
import math
import os
import random
import socketserver
import threading
import time

PORT = 27306
WEB = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'web')

# ── the fake save ───────────────────────────────────────────────────────────

lock = threading.Lock()
started = time.time()

player = {
    'name': 'Ysolda the Unready',
    'race': 'Nord',
    'level': 24,
    'xp': 412.0, 'xpMax': 1180.0,
    'hp': 268.0, 'hpMax': 320.0,
    'mp': 90.0,  'mpMax': 190.0,
    'sp': 210.0, 'spMax': 240.0,
    'weight': 284.5, 'weightMax': 355.0,
    'gold': 4821,
    'armorRating': 187.0,
    'damage': 64.0,
    'beast': 'none',
    'inCombat': False,
    'sneaking': False,
    'overEncumbered': False,
}

# Position walks a slow circle around Whiterun, so the map arrow moves and the distances change.
position = {'x': 22000.0, 'y': -8000.0, 'angle': 0.0}

SKILLS = [
    ('One-Handed', 'combat', 62), ('Two-Handed', 'combat', 30), ('Archery', 'combat', 71),
    ('Block', 'combat', 44), ('Heavy Armor', 'combat', 58), ('Smithing', 'combat', 81),
    ('Alteration', 'magic', 25), ('Conjuration', 'magic', 40), ('Destruction', 'magic', 55),
    ('Illusion', 'magic', 18), ('Restoration', 'magic', 47), ('Enchanting', 'magic', 66),
    ('Light Armor', 'stealth', 33), ('Sneak', 'stealth', 74), ('Lockpicking', 'stealth', 52),
    ('Pickpocket', 'stealth', 21), ('Speech', 'stealth', 49), ('Alchemy', 'stealth', 63),
]

inventory = {
    'weapons': [
        {'id': '0001397e', 'name': 'Skyforge Steel Sword', 'count': 1, 'weight': 9.0,
         'value': 90, 'equipped': True, 'favorite': True, 'stolen': False, 'enchanted': False,
         'damage': 9.0, 'type': 'sword', 'desc': ''},
        {'id': '000139a1', 'name': 'Hunting Bow', 'count': 1, 'weight': 7.0, 'value': 50,
         'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False,
         'damage': 7.0, 'type': 'bow', 'desc': ''},
        {'id': '000f82fa', 'name': 'Dawnbreaker', 'count': 1, 'weight': 10.0, 'value': 1500,
         'equipped': False, 'favorite': True, 'stolen': False,
         'enchanted': True, 'damage': 12.0, 'type': 'sword',
         'desc': 'Fire Damage 10 for 1s, Turn Undead 20 for 30s'},
    ],
    'armor': [
        {'id': '00013948', 'name': 'Steel Plate Armor', 'count': 1, 'weight': 38.0, 'value': 725,
         'equipped': True, 'favorite': False, 'stolen': False, 'enchanted': False,
         'armorRating': 40.0, 'slot': 'body', 'desc': ''},
        {'id': '00013949', 'name': 'Amulet of Talos', 'count': 1, 'weight': 1.0, 'value': 155,
         'equipped': True, 'favorite': False, 'stolen': True, 'enchanted': True,
         'armorRating': 0.0, 'slot': 'amulet', 'desc': 'Time between shouts reduced 20%'},
    ],
    'potions': [
        {'id': '0003eadd', 'name': 'Potion of Healing', 'count': 7, 'weight': 0.5, 'value': 36,
         'equipped': False, 'favorite': True, 'stolen': False, 'enchanted': False,
         'desc': 'Restore Health 50'},
        {'id': '0003eade', 'name': 'Potion of Magicka', 'count': 3, 'weight': 0.5, 'value': 41,
         'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False,
         'desc': 'Restore Magicka 50'},
    ],
    'ingredients': [
        {'id': '00034d22', 'name': 'Blue Mountain Flower', 'count': 12, 'weight': 0.1, 'value': 2,
         'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False,
         'desc': 'Restore Health, Fortify Conjuration'},
        {'id': '00034d23', 'name': 'Nirnroot', 'count': 4, 'weight': 0.2, 'value': 10,
         'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False,
         'desc': ''},   # undiscovered: the screen shows nothing rather than spoiling it
    ],
    'scrolls': [
        {'id': '0004dee4', 'name': 'Scroll of Fireball', 'count': 2, 'weight': 0.5, 'value': 114,
         'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False,
         'desc': '40 points of fire damage in a 15 foot radius'},
    ],
    'books': [
        {'id': '0001acd0', 'name': 'The Book of the Dragonborn', 'count': 1, 'weight': 1.0,
         'value': 15, 'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False,
         'desc': ''},
    ],
    'food': [
        {'id': '00064b3f', 'name': 'Sweet Roll', 'count': 3, 'weight': 0.5, 'value': 5,
         'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False,
         'desc': 'Restore Health 5'},
    ],
    'misc': [
        {'id': '0000000a', 'name': "Dwemer Gyro", 'count': 5, 'weight': 5.0, 'value': 30,
         'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False, 'desc': ''},
    ],
    'ammo': [
        {'id': '0001397d', 'name': 'Steel Arrow', 'count': 143, 'weight': 0.0, 'value': 1,
         'equipped': True, 'favorite': False, 'stolen': False, 'enchanted': False, 'desc': ''},
    ],
    'keys': [
        {'id': '000ad5be', 'name': 'Breezehome Key', 'count': 1, 'weight': 0.0, 'value': 0,
         'equipped': False, 'favorite': False, 'stolen': False, 'enchanted': False, 'desc': ''},
    ],
}

spells = [
    {'id': '00012fcd', 'name': 'Flames', 'school': 'Destruction', 'level': 'Novice',
     'cost': 14.0, 'desc': 'A gout of fire that does 8 points per second', 'equipped': True},
    {'id': '00012fcc', 'name': 'Healing', 'school': 'Restoration', 'level': 'Novice',
     'cost': 12.0, 'desc': 'Heals 10 points per second', 'equipped': True},
    {'id': '0001c789', 'name': 'Conjure Familiar', 'school': 'Conjuration', 'level': 'Novice',
     'cost': 82.0, 'desc': 'Summons a familiar for 60 seconds', 'equipped': False},
    {'id': '0002dd2a', 'name': 'Candlelight', 'school': 'Alteration', 'level': 'Novice',
     'cost': 21.0, 'desc': 'Creates a hovering light for 60 seconds', 'equipped': False},
]

powers = [
    {'id': '000e40cd', 'name': 'Battle Cry', 'school': '', 'level': '', 'cost': 0.0,
     'desc': 'Targets flee for 30 seconds. Once a day.', 'equipped': False},
]

shouts = [
    {'id': '00013e07', 'name': 'Unrelenting Force', 'equipped': True, 'recharge': 0.0,
     'desc': 'Your voice is raw power, pushing aside anything in your path.',
     'words': [{'text': 'Fus', 'known': True, 'unlocked': True},
               {'text': 'Ro', 'known': True, 'unlocked': True},
               {'text': 'Dah', 'known': True, 'unlocked': False}]},
    {'id': '0002f7bb', 'name': 'Whirlwind Sprint', 'equipped': False, 'recharge': 12.0,
     'desc': 'The Thu\'um rushes forward, carrying you in its wake.',
     'words': [{'text': 'Wuld', 'known': True, 'unlocked': True},
               {'text': 'Nah', 'known': False, 'unlocked': False},
               {'text': 'Kest', 'known': False, 'unlocked': False}]},
]

quests = [
    {'id': '0003372b', 'name': 'Dragon Rising', 'type': 'Main', 'active': True, 'completed': False,
     'objectives': [{'text': 'Talk to the Jarl of Whiterun', 'done': True},
                    {'text': 'Investigate the Western Watchtower', 'done': True},
                    {'text': 'Kill the dragon', 'done': False}]},
    {'id': '0001f7a0', 'name': 'Proving Honor', 'type': 'Companions', 'active': False,
     'completed': False,
     'objectives': [{'text': 'Retrieve the fragment of Wuuthrad', 'done': False}]},
    {'id': '0001f7a1', 'name': 'Take Up Arms', 'type': 'Companions', 'active': False,
     'completed': True,
     'objectives': [{'text': 'Join the Companions', 'done': True}]},
]

perks = [
    {'id': '000babe1', 'name': 'Armsman', 'tree': 'OneHanded',
     'desc': 'One-handed weapons do 40% more damage.'},
    {'id': '000c44b8', 'name': 'Steel Smithing', 'tree': 'Smithing',
     'desc': 'You can create Steel armor and weapons at forges.'},
    {'id': '00058210', 'name': 'Stealth', 'tree': 'Sneak',
     'desc': 'You are 20% harder to detect when sneaking.'},
]

effects = [
    {'name': 'Blessing of Talos', 'kind': 'blessing', 'duration': 0.0, 'desc': ''},
    {'name': 'Well Rested', 'kind': 'buff', 'duration': 2400.0, 'desc': ''},
    {'name': 'Rockjoint', 'kind': 'disease', 'duration': 0.0, 'desc': ''},
]

# Whiterun Hold, roughly to scale against the worldspace bounds below.
markers = [
    {'id': '00018d3a', 'name': 'Whiterun',        'type': 1,  'x': 22000.0, 'y': -6000.0},
    {'id': '00018d3b', 'name': 'Riverwood',       'type': 2,  'x': 8000.0,  'y': -60000.0},
    {'id': '00018d3c', 'name': 'Bleak Falls Barrow', 'type': 7, 'x': -14000.0, 'y': -52000.0},
    {'id': '00018d3d', 'name': 'Dragonsreach',    'type': 34, 'x': 24000.0, 'y': -3000.0},
    {'id': '00018d3e', 'name': 'Western Watchtower', 'type': 11, 'x': 8000.0, 'y': -12000.0},
    {'id': '00018d3f', 'name': 'Honningbrew Meadery', 'type': 13, 'x': 40000.0, 'y': -22000.0},
    {'id': '00018d40', 'name': 'Rorikstead',      'type': 3,  'x': -60000.0, 'y': -20000.0},
    {'id': '00018d41', 'name': 'Windhelm',        'type': 1,  'x': 120000.0, 'y': 40000.0},
]
for index, marker in enumerate(markers):
    marker['visited'] = index < 6         # the last two are undiscovered
    marker['canFastTravel'] = marker['visited']

perms = {'equip': True, 'use': True, 'drop': False, 'favorite': True,
         'equipSpell': True, 'setQuest': True, 'fastTravel': True, 'wait': False}

MONTHS = ["Morning Star", "Sun's Dawn", "First Seed", "Rain's Hand", "Second Seed", "Mid Year",
          "Sun's Height", "Last Seed", "Hearthfire", "Frostfall", "Sun's Dusk", "Evening Star"]
DAYS = ["Sundas", "Morndas", "Tirdas", "Middas", "Turdas", "Fredas", "Loredas"]

tick = 0


def snapshot():
    """The whole document, rebuilt each poll -- exactly as the plugin does it."""
    global tick
    with lock:
        tick += 1
        elapsed = time.time() - started

        # An in-game day every four real minutes, so the clock, the dial and the night palette can
        # all be watched changing without waiting for one.
        hour = (elapsed / 240.0 * 24.0) % 24.0
        day = 17 + int(elapsed / 240.0)

        player['mp'] = min(player['mpMax'], player['mp'] + 0.6)
        player['sp'] = min(player['spMax'], player['sp'] + 1.1)
        player['overEncumbered'] = player['weight'] > player['weightMax']

        position['angle'] = (elapsed * 8.0) % 360.0
        position['x'] = 22000.0 + math.cos(elapsed / 12.0) * 9000.0
        position['y'] = -8000.0 + math.sin(elapsed / 12.0) * 9000.0

        skills = [{'name': name, 'school': school, 'value': value, 'base': value,
                   'progress': ((tick / 200.0) + index / 18.0) % 1.0}
                  for index, (name, school, value) in enumerate(SKILLS)]

        return {
            'ready': True,
            'tick': tick,
            'game': 'SkyrimSE',
            'runtime': 'SE',
            'player': dict(player),
            'time': {
                'text': '%02d:%02d' % (int(hour), int((hour % 1) * 60)),
                'hour': round(hour, 3),
                'day': day, 'month': 8, 'monthName': MONTHS[7],
                'year': 201, 'dayName': DAYS[day % 7],
                'daysPassed': round(elapsed / 240.0 + 41.0, 2),
                'night': hour >= 20.0 or hour < 6.0,
            },
            'skills': skills,
            'perks': perks,
            'effects': effects,
            'magic': {
                'spells': spells, 'powers': powers, 'shouts': shouts,
                'equippedLeft': '00012fcc', 'equippedRight': '00012fcd',
                'equippedShout': '00013e07',
            },
            'inventory': inventory,
            'quests': quests,
            'map': {
                'world': 'Skyrim', 'worldId': '0000003c',
                'cell': 'Whiterun Plains', 'interior': False,
                'x': round(position['x'], 1), 'y': round(position['y'], 1),
                'angle': round(position['angle'], 1),
                'worldBounds': {'minX': -150000.0, 'minY': -150000.0,
                                'maxX': 150000.0, 'maxY': 150000.0},
                'markers': markers,
            },
            'perms': perms,
        }


def apply_action(command):
    """Really applies it, so the buttons can be tested rather than only drawn."""
    action = command.get('action')
    form = command.get('id', '')

    with lock:
        if action in ('equip', 'unequip'):
            for rows in inventory.values():
                for item in rows:
                    if item['id'] == form:
                        item['equipped'] = (action == 'equip')
            return True

        if action == 'use':
            for rows in inventory.values():
                for item in rows:
                    if item['id'] == form and item['count'] > 0:
                        item['count'] -= 1
                        if 'Healing' in item['name']:
                            player['hp'] = min(player['hpMax'], player['hp'] + 50)
                        if 'Magicka' in item['name']:
                            player['mp'] = min(player['mpMax'], player['mp'] + 50)
                        return True
            return False

        if action == 'drop':
            if not perms['drop']:
                return False
            for rows in inventory.values():
                for item in rows:
                    if item['id'] == form:
                        item['count'] = max(0, item['count'] - int(command.get('count', 1)))
                        return True
            return False

        if action == 'favorite':
            for rows in inventory.values():
                for item in rows:
                    if item['id'] == form:
                        item['favorite'] = bool(command.get('on', True))
                        return True
            return False

        if action == 'equipSpell':
            for spell in spells + powers:
                spell['equipped'] = spell['id'] == form
            return True

        if action == 'equipShout':
            for shout in shouts:
                shout['equipped'] = shout['id'] == form
            return True

        if action == 'setQuest':
            for quest in quests:
                quest['active'] = quest['id'] == form
            return True

        if action == 'fastTravel':
            for marker in markers:
                if marker['id'] == form and marker['canFastTravel']:
                    position['x'], position['y'] = marker['x'], marker['y']
                    return True
            return False

    return False


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB, **kwargs)

    def log_message(self, *args):
        pass                      # ten polls a second would bury anything worth reading

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split('?')[0]
        if path == '/state':
            return self._json(snapshot())
        if path == '/config':
            return self._json({'Port': PORT, 'AllowLanAccess': True, 'UpdatesPerSecond': 10,
                               'AllowEquip': True, 'AllowUse': True, 'AllowFavorite': True,
                               'AllowSetQuest': True, 'AllowDrop': False,
                               'AllowFastTravel': True, 'AllowWait': False,
                               'EnableDescriptions': True, 'MaxMapMarkers': 400,
                               'MaxInventoryItems': 600, 'HasAccessToken': False,
                               'restart': ['Port', 'AllowLanAccess']})
        return super().do_GET()

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8') if length else '{}'

        try:
            command = json.loads(body)
        except json.JSONDecodeError:
            return self._json({'ok': False}, 400)

        path = self.path.split('?')[0]
        if path == '/action':
            return self._json({'ok': apply_action(command)})
        if path == '/config':
            with lock:
                key, value = command.get('key'), command.get('value')
                if key and key.startswith('Allow'):
                    perms_key = key[5].lower() + key[6:]
                    if perms_key in perms:
                        perms[perms_key] = value in ('1', 'true', True)
            return self._json({'ok': True})

        return self._json({'ok': False}, 404)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    print(f'Fake Skyrim on http://localhost:{PORT}/  (serving {os.path.abspath(WEB)})')
    print('Ctrl-C to stop.')
    with Server(('', PORT), Handler) as server:
        server.serve_forever()
