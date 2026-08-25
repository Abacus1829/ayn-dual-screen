# Ayn Dual Screen

A SMAPI mod that turns any second display into a 3DS-style bottom screen for Stardew Valley:
live map, live inventory you can touch, clock, date, season, weather, money, health and energy.

Built for the AYN Thor's second screen, but the second screen can be any device with a browser.

**By Abacus.**

> **This mod contains AI-assisted code.** It was written with the help of an AI coding assistant and
> reviewed by a human before release. Flagged here so anyone reading, forking or reporting bugs
> against it knows what they're looking at.

---

## How it works

A SMAPI mod can't draw to a second physical display — the game owns exactly one window. So this is split in two:

| Part | Where it lives | What it does |
| --- | --- | --- |
| The mod | `ModEntry.cs`, `WebServer.cs` | Runs a tiny HTTP server inside the game. Every tick it snapshots the save into JSON and applies commands sent back from the screen. |
| The screen | `web/` | A web page that polls that JSON 10x/second and draws the UI. Taps and drags are posted back to the mod. |

The upshot: **the second screen is just a browser pointed at the game.** It can be the Thor's second
display, a phone, a tablet, or a second monitor — anything that can open a URL.

### Endpoints

| Route | Purpose |
| --- | --- |
| `GET /` | The second-screen page. |
| `GET /state` | The live snapshot: time, weather, money, position, inventory, nearby characters. |
| `GET /map` | The current location's tile grid. Rebuilt on a location change or every ~5s, and cached by the client via a revision number. |
| `GET /icon/<key>.png` | An item sprite, cropped out of the game's own tilesheets at runtime. |
| `GET /asset/<name>.png` | A UI sprite from the game's tilesheets: `menubox`, `slot`, `quality1`, `quality2`, `quality4`. |
| `GET /sheet/<name>.png` | A whole tilesheet (`menu`, `cursors`) for checking crop coordinates. |
| `GET /farm` | Machines mid-cycle, animals and their produce, and fruit trees. Rebuilt on the slow timer, not per snapshot — it walks every location. |
| `GET /calendar` | The season as 28 days: birthdays, festivals and Travelling Cart days. |
| `POST /action` | A command from the touch screen: `select`, `swap`, `drop`, `trash`, `eat`, `use`, `sort`, `shiftRow`, `cancelQuest`, `chestTake`, `chestPut`. |

### Everything you see is a real game asset

Nothing is redrawn by hand. At runtime the mod reads the game's own tilesheets, crops the regions it
needs, and serves them as PNGs:

| What | Where it comes from |
| --- | --- |
| Item icons | `ItemRegistry.GetDataOrErrorItem(id)` → the item's real sprite and source rect |
| Panel frames | `Maps/MenuTiles` (0,256,60,60) — the same nine-slice `IClickableMenu.drawTextureBox` draws |
| Inventory slots | `Maps/MenuTiles` standard tile 10 |
| Quality stars | `LooseSprites/Cursors` — the rects `Object.drawInMenu` uses |

The crop rectangles live in one place, the `UiAssets` table at the top of `ModEntry.cs`. If a sprite
ever looks wrong, open `http://localhost:27301/sheet/menu.png` or `/sheet/cursors.png` to see the
whole sheet and correct the coordinates there.

The one thing still *not* from the game is the text: Stardew's font ships as a compiled XNB
`SpriteFont`, which a browser can't load, so the UI uses a system font.

### The one rule worth remembering

Game state may **only** be touched on the game thread. The web server answers requests on background
threads, so it never reads the save directly — the game thread publishes finished JSON strings, and
incoming commands go into a queue that's drained during `UpdateTicked`. Breaking this rule is the
classic way to corrupt a save, so keep new features on the same pattern.

---

## Using it

1. Build (this copies the mod straight into your `Mods` folder):

   ```bash
   dotnet build
   ```

2. Launch the game through SMAPI. The console will print:

   ```
   Second screen ready at http://localhost:27301/
   ```

3. Open that URL in a browser on your second display, and load a save.

### Controls

- **Tap a slot** — selects it. Slots 1–12 also become your held item.
- **Hold a slot** — uses what's in it. Food is eaten; everything else goes through the game's own
  use-button handler, so a can waters, an axe chops and a seed gets planted exactly as they would on
  the controller. Hotbar only, because the game acts on the *held* item.
- **Drag a slot onto another** — swaps them, including into and out of the hotbar.
- **L / R** — rotates your backpack rows through the hotbar. This is the game's own Tab-key shift, so
  the screen and the game never disagree about which twelve slots are live.
- **Eat / Drop / Trash / Sort** — act on the selected slot. Trash needs two taps to confirm.
- **Fit / Follow** — toggles between the whole map and a zoomed view that tracks you.
- **&minus; / +** — zoom the map from 1x to 5x. Past 1x it centres on you.
- **Tap the map** — names whatever is there: the nearest villager, animal or monster, or the tile coordinates.
- **The dot on the hotbar row** — green while snapshots are arriving, amber if they stall, red if they stop.

Two readouts sit on the slots themselves, as bars rather than numbers, because at that size a number
is three unreadable pixels wide:

- a **blue bar** on the watering can, showing what's left in it;
- a **red bar** on a weapon, draining while it's on cooldown.


### The pages

One page fills the screen at a time and the bar along the top chooses which. There is no fixed side
panel: on a nearly-square handheld panel a two-column layout turns both columns into strips, and the
narrower one could not fit its own tab labels.

| Page | What's on it |
| --- | --- |
| **Today** | Weekday, date, time, weather with tomorrow's forecast, the day's luck, gold, what's already in the shipping bin, energy and health. Then the hotbar, every unlocked backpack row directly beneath it, the selected item, the action buttons and your skill levels. |
| **Map** | The minimap, or the game's own world map with everyone placed on it. |
| **Farm** | Three lists, each answering a question that otherwise costs a walk: machines mid-cycle and which are ready, animals and whether they've been petted, fed or still owe you produce, and fruit trees with what's hanging on them. Each list scrolls on its own, so ninety kegs can't bury four cows. |
| **Journal** | Every quest, with its objective, its deadline, what it pays, and a two-tap Drop for the ones the game itself lets you drop. |
| **Bundles** | The community centre board, down to the item: each unfinished bundle shows exactly which ingredients are still missing, at the quality it wants them. Says so plainly when there's no centre to track. |
| **Calendar** | The season as the game's own four-by-seven grid, with birthdays (portraits and names), festivals and Travelling Cart days. |
| **People** | Every villager: where they are right now, how many hearts, whose birthday it is, and a tick for the ones you've already spoken to today. Sorted by whoever is in the room with you, then birthdays, then closest friends. Filter by name or location. |
| **Settings** | Everything below. It's a page rather than a panel over the top, because a settings sheet that covers the screen it configures makes you dismiss it to see every change. |

Any page except Today and Settings can be turned off in **Settings → Pages** if you never use it.

### Settings

Settings are saved in the browser's own storage rather than in the mod's config — so the Thor's panel,
a phone and a desktop monitor can each be laid out differently against the same game.

| Control | What it does |
| --- | --- |
| Pages | Which tabs appear in the bar. |
| Show | Hide the bottom status bar, the map legend, skill levels, the selected item, or the action buttons. |
| Accent | Eight preset highlight colours, used for the selected slot and focus rings. |
| Size | Four UI scales, from small to huge — the whole page scales off the root font size. |
| Updates per second | 5, 10, 15 or 20 polls a second. Lower it on a slow Wi-Fi link. |
| Connection | Read-only: what this page is connected to, whether snapshots are arriving, and how fast it's asking. |
| What the mod allows | Read-only: which of the touch actions `config.json` has left switched on. A greyed-out Trash button is a setting, not a bug, and this is where you can see that. |
| Reset to defaults | Puts everything back. |

Nothing here touches the game or the mod's own settings, and it needs no reload.

#### Theme and behaviour

| Option | Default | What it does |
| --- | --- | --- |
| **Theme** | Stardew | `Stardew` uses the game's own menu art and follows any recolour mod you have. `Plain` drops the frame for flat dark panels. |
| **Follow the controller's selected item** | on | Moves the cursor and the description when you change the held item in-game, instead of only on tap. |
| **Villager faces on the map** | on | Draws each villager's face instead of a coloured dot. |

#### The world map

The **World** button above the map switches from the tile minimap to the map the game draws in your
journal, with you and every villager placed on it.

It isn't a picture of the map — it's built from the game's own world map data at runtime. The image
is composed the way the journal composes it, base artwork plus each area's overlays, so the state of
the Community Centre, your farm's layout and seasonal artwork all come through. Positions come from
the same lookup the NPC map mods use, and they're measured against the image that was actually
composed, so markers line up rather than being nudged into place.

That means **Stardew Valley Expanded's map works without the mod knowing SVE exists.** SVE replaces
the world map data with its own regions and artwork; the image and the coordinates both come back
changed together.

Plenty of places have no spot on the world map — mine levels, most interiors, anything a content pack
adds without world map data. There the button reads *Not mapped* and the tile map is drawn instead,
rather than leaving you with an empty panel.

The composed image is cached and rebuilt when it can have changed: on a new day, and whenever a
content pack invalidates the map assets. That's what keeps a Joja-route map, a new barn or a seasonal
repaint from being stuck on yesterday's picture.

#### Open chests

Open a chest in-game and its contents appear under your inventory. Tap a chest slot to take that
stack; **Store** puts the selected inventory stack in.

Both directions go through the game's own add methods, which stack into what's already there, respect
capacity, and hand back whatever didn't fit — and that remainder is written back to the slot it came
from. A move into a full chest leaves the stack exactly where it was.

Editing is only offered when the container is a real chest. Shipping bins, shop menus and containers
added by other mods are shown read-only, because moving items through something this mod doesn't
understand is how an item transfer destroys things.

#### Recolour and content mods

The theme reads `Maps/MenuTiles` through SMAPI's content pipeline, so whatever recolour you have
installed is what the screen draws — Cinderbox, SVE's interface, any Content Patcher retexture.

Because a recolour can make that box any shade, the mod measures the brightness of the box's own
interior and sends it as `menuLuma`, and the page picks dark or light ink from it. That's what stops
the clock and tab labels disappearing into a light frame. Nothing to configure; if you'd rather opt
out of the whole thing, switch the theme to Plain.

Stardew Valley Expanded needs nothing special. The map is built from whatever tile layers the current
location has and villagers come from the location's own character list, so SVE's added maps and NPCs
are drawn like any other — including their faces, which are cropped from each NPC's sprite sheet
rather than a fixed list of vanilla names.

#### config.json

`config.json` appears next to the mod after the first run, for the things that have to be decided
before the screen connects:

| Setting | Default | Notes |
| --- | --- | --- |
| `Port` | `27301` | Change it if something else is using the port. |
| `AllowLanAccess` | `true` | Lets another device reach the screen. **On by default — anyone on your network can open the page and destroy your items. Set `false` on a network you don't trust.** |
| `UpdatesPerSecond` | `10` | How often the snapshot refreshes. Lower it if you see a frame-rate cost. |
| `EnableItemIcons` | `true` | Set `false` to skip extracting item sprites. |
| `AllowTrash` | `true` | Set `false` to disable the trash button entirely. |
| `AllowDrop` | `true` | Set `false` to stop the screen throwing items on the ground. |
| `AllowInventoryEdit` | `true` | Set `false` to stop it rearranging or sorting the inventory. |
| `AllowEat` | `true` | Set `false` to disable the Eat button. |
| `AllowUse` | `true` | Set `false` to stop holding a slot from using what's in it. Separate from `AllowEat` because they are different risks: eating the wrong thing costs one item, swinging a pickaxe acts on the world. |
| `EnableWorldMap` | `true` | Offer the game's world map. `false` also skips the per-character map lookups. |
| `ShowChests` | `true` | Mirror an open chest onto the screen. |
| `ShowMonsters` | `true` | Whether monsters get a dot on the minimap. |
| `ShowNpcs` | `true` | Whether villagers get a dot. |
| `ShowAnimals` | `true` | Whether farm animals get a dot. |
| `MaxQuests` | `20` | How many journal entries to send. `0` hides the page. |
| `MaxFarmEntries` | `60` | The cap on each of the Farm page's three lists. |
| `EnableNpcIcons` | `true` | Extract villager portraits, for the map and the calendar's birthdays. |
| `MaxVillagers` | `40` | How many villagers the tracker sends. Nearest and birthdays come first. |

Everything under `Allow*` exists because LAN access is on by default: they let the screen be made
look-only, one class of action at a time, without giving up the second screen. A button switched off
here is greyed out on the screen with a tooltip rather than silently doing nothing.


With `AllowLanAccess` on, the SMAPI console prints the address another device should use — you don't
have to go looking for it:

```
Second screen ready on this PC at http://localhost:27301/
From another device on your network: http://192.168.1.20:27301/
```

**`localhost` only works on the PC.** Typed into a phone or handheld it means *that* device, so it can
never reach the game — which is why the console names both addresses separately. If a VPN adapter is
active it warns about that too, because VPNs routinely stop two devices on the same Wi-Fi from seeing
each other.

---

## Developing the screen without launching the game

Booting Stardew for every CSS tweak is painful, so there's a stand-in server that serves the real
`web/` folder against fake data:

```bash
py tools/mockserver.py
```

Then open <http://localhost:27302/>. It fakes a farm, a walking player, an advancing clock and a
full inventory, and it actually applies `swap`/`drop`/`trash`/`sort` so the interactions can be
tested end to end. Its JSON shapes must be kept in step with `Dtos.cs`.

### Editing the UI while the game is running

`dotnet build` will fail with *"the process cannot access the file … AynDualScreen.dll"* while
Stardew is open, because the game holds the DLL. You don't need a rebuild for UI work: the mod reads
`web/` from disk on every request, so copying the files over is enough and the change is live on the
next refresh.

```bash
cp -r web/* "/c/Program Files (x86)/Steam/steamapps/common/Stardew Valley/Mods/AynDualScreen/web/"
```

Only C# changes need a rebuild, and that needs the game closed.

---

## Running this on the AYN Thor — read this before you buy time on it

The desktop half of this works today. The Android half has a real obstacle you should know about up front:

- **SMAPI is officially Windows, macOS and Linux only.** The Android version of Stardew Valley is a
  separate build, and SMAPI on Android is an unofficial community port that has historically lagged
  well behind the current game version. Whether it supports the Stardew version on your Thor is
  something to confirm *before* planning around it.
- **This mod is compiled against the desktop game's assemblies** (`net6.0`). If an Android SMAPI is
  available for your version, the mod will very likely need recompiling against that build's
  assemblies. The code was written with this in mind — the HTTP server is a plain `TcpListener`
  rather than `HttpListener` specifically because it has to survive a different runtime — but
  "needs a recompile" is not the same as "will just work".

**The fallback that works regardless:** run the game on the PC with `AllowLanAccess: true`, and open
the second screen in the Thor's browser over Wi-Fi. You get the same dual-screen experience with the
Thor acting as the touch panel. That path needs nothing from Android SMAPI at all.

---

## Companion Android app

There's a companion app in [`../android`](../android) — a fullscreen, no-chrome shell around this
page that keeps the display awake and can launch itself onto a handheld's *second* display, which a
plain browser can't do.

It's source only; building an APK needs the Android SDK. See [android/README.md](../android/README.md).

Any browser still works if you'd rather not build anything.

---

## Credits

- **Abacus** — author.
- Built on [SMAPI](https://smapi.io/) by Pathoschild, and the
  [Mod Build Config](https://github.com/Pathoschild/SMAPI/tree/develop/src/SMAPI.ModBuildConfig) package.
- Sprites are the property of ConcernedApe and are read from your own installed copy of the game at
  runtime — none are redistributed with this mod.
- **Contains AI-assisted code**, written with an AI coding assistant and human-reviewed.

---

## Ideas worth adding next

- Swap polling for a WebSocket so the map updates at full frame rate instead of 10Hz.
- A quests / journal tab, and a calendar and bundle tracker.
- Tap the map to set a walking destination.
- Show crop growth stages and machine-ready state as distinct map colours.
- A token in `config.json` so LAN access requires a shared secret.

## Layout

```
AynDualScreen/
  ModEntry.cs        snapshots, command handling, HTTP routing
  WebServer.cs       minimal HTTP/1.1 server on TcpListener
  Dtos.cs            the wire format shared with web/app.js
  ModConfig.cs       config.json
  web/               the second-screen UI (index.html, style.css, app.js)
  tools/mockserver.py  fake backend for designing the UI
```
