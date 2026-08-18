# Ayn Dual Screen — Skyrim Special Edition

An SKSE64 plugin that turns any second display into the menu Skyrim never had room for: the map,
your inventory laid out the way SkyUI lays it out, your spells and shouts, your skills and perks,
your journal, and the clock — live, touchable, and off to one side while the game keeps the whole
screen to itself.

Built for the AYN Thor's second display, but the second screen can be any device with a browser.

**By Abacus.**

> **This mod contains AI-assisted code.** It was written with the help of an AI coding assistant and
> reviewed by a human before release. Flagged here so anyone reading, forking or reporting bugs
> against it knows what they're looking at.

This is a port of the Fallout: New Vegas mod in this repository and keeps the same architecture,
which is in turn the Stardew mod's. One DLL covers **SE, AE and VR**.

---

## Status

**It compiles. It has not yet been loaded by the game.** Read this table before anything else.

| Part | State |
| --- | --- |
| The second-screen UI (`web/`) | Written, and drivable end to end against `tools/mockserver.py`. |
| The wire format (`src/Dtos.h`) | Settled. The page and the mock both match it. |
| The plugin (`src/`) | **Builds clean** — x64 Release, no warnings, ~514 KB. Not yet run in Skyrim. |
| Player, vitals, carry weight, gold | Written against CommonLibSSE's actor-value API. |
| Time and the calendar | Written against `RE::Calendar`. |
| Skills | Values and base values only. **No XP and no progress bars** — see below. |
| Perks | Written, with the real skill tree found by walking the game's own perk trees. |
| Inventory, ten SkyUI categories | Written. |
| Spells, powers, shouts | Written, by asking the game rather than reading the player's lists — see below. |
| Quests and objectives | Written. |
| Map: position, bearing, markers | Written. The map is **drawn from marker data, not a picture of Skyrim** — see below. |
| Equip, unequip, use, drop | Written, applied on the game thread through `ActorEquipManager`. |
| Equip spell / shout | Written. |
| Fast travel | Written, off by default, and **it does not charge you the hours** — see below. |
| Set active quest | **Not implemented.** The button is greyed out and says so. |
| Favourite from the screen | **Not implemented.** Same. |
| Wait | **Not implemented.** Same. |

### What "builds but hasn't run" means

It compiles against CommonLibSSE-NG and produces a DLL, but it has never been loaded by SKSE and
has never read a real save. Everything above is written against a published API rather than
verified against the game. The numbers on the screen have not been checked against the game's own
menus even once. Treat it as early.

### The price of one DLL for three runtimes

CommonLibSSE-NG covers SE, AE and VR from a single build — but the player's *runtime-data block*
(`skills`, `addedPerks`, `addedSpells`, `selectedPower`) sits at a different offset in each, so in
a build that covers all three it makes those members unreachable rather than let you read the wrong
bytes. That is the correct call on its part, and it costs three things here:

- **No XP figure and no per-skill progress.** They exist only on `PlayerSkills`. The alternative
  was dropping a runtime to read one progress bar, which is not a good trade. The screen leaves the
  row out rather than inventing a fraction.
- **Spells, perks and shouts are found by asking, not by reading.** The plugin walks the load
  order's forms and calls `HasSpell` / `HasPerk` / `HasShout`, which are functions and therefore
  identical across runtimes. That is a few-thousand-form scan, far too much to do ten times a
  second, so it is cached and rebuilt every ~3 seconds. Spells do not change between frames.
- **Shout words report only whether the soul has been spent.** Whether a word has merely been found
  on a wall is tracked in the same unreachable place, and `TESWordOfPower` carries no flag for it,
  so the screen shows unlocked words and hides the rest rather than guessing at a middle state.

Nothing in the snapshot is invented: a reader that finds nothing sends an empty list, and the screen
renders an empty tab rather than showing a plausible lie about your character.

---

## The three things worth knowing before you install it

**The map is a chart, not the game's map.** Skyrim's world map is a rendered 3D scene. There is no
image file to extract and serve — that was the first thing tried. So the screen lays out the game's
own map-marker data in the worldspace's coordinates and draws you on top of it, with a bearing.
That is genuinely useful for "where am I and what's near me", and it is not a picture of Skyrim.
Drawing the terrain from the LOD textures in the archives is possible and is the next real piece of
work on that tab.

**Fast travel does not cost you time.** The plugin moves you with `MoveTo`, which the game does
cleanly, but it does not pass the hours that the game's own fast travel spends — and this mod will
not write the calendar behind the game's back to fake it. So it behaves more like a carriage that
forgot to charge you. It is **off by default** for exactly that reason. Turn it on knowing what it
is.

**LAN access is on by default.** Anyone on your network can open the page, and the page can equip,
use and drop. Set `AllowLanAccess=0` on a network you don't trust, or set an `AccessToken`.

---

## Why this one is a DLL

Stardew has SMAPI, Terraria has tModLoader, Minecraft has Forge. Skyrim has **Papyrus**, which
cannot open a socket, and the **Skyrim Script Extender**, which loads native DLLs. So this mod is
C++ rather than C# or Java.

Unlike the New Vegas plugin, it does *not* read raw structure offsets: CommonLibSSE-NG provides a
real, maintained API over the game's classes, so this reads `GetActorValue` and `GetInventory`
rather than a byte at `player + 0x94`. That is the meaningful difference between the two, and it is
why this one is far less likely to crash on a value it misreads.

**One DLL for SE, AE and VR.** CommonLibSSE-NG resolves the addresses per runtime at load. The
alternative is three builds to keep in step and three ways for a release to be half-broken.

---

## How it works

A mod can't draw to a second physical display — the game owns exactly one window. So this is split
in two:

| Part | Where it lives | What it does |
| --- | --- | --- |
| The plugin | `src/` | Runs a tiny HTTP server inside the game. Ten times a second it snapshots the player into JSON and applies commands sent back from the screen. |
| The screen | `web/` | A web page that polls that JSON and draws the UI. Taps are posted back to the plugin. |

The upshot: **the second screen is just a browser pointed at the game.**

### Endpoints

| Route | Purpose |
| --- | --- |
| `GET /` | The second-screen page, and everything else under `web/`. |
| `GET /state` | The live snapshot. |
| `POST /action` | A command from the touch screen. |
| `GET`/`POST /config` | The mod's own settings, for the screen's settings panel. |

The exact JSON shape is written down in [`src/Dtos.h`](src/Dtos.h), which is the contract
`web/app.js` and `tools/mockserver.py` both have to match.

### The one rule worth remembering

Game state may **only** be touched on the game thread. The web server answers requests on worker
threads, so it never reads the world.

SKSE has no per-frame message the way NVSE does, so the snapshot is paced by a thread that does
nothing but hand work to the game thread through `SKSE::GetTaskInterface()->AddTask` and go back to
sleep. That lambda runs on the main thread, between frames, where touching the game is legal. The
pacer thread itself never reads anything from the game — if it did, that would be exactly the bug
the rest of this project is written to avoid.

Two smaller rules follow from it:

- **The screen names things by form ID, never by list position.** A tap that lands one frame after
  the inventory shifted therefore can't act on the wrong object.
- **Permissions are re-checked on the game thread.** The snapshot tells the screen what it may do so
  the right buttons grey out, but the decision is made again in `Actions::Apply` before anything
  happens. Nothing the screen sends can talk its way past the config.

---

## Building

**Requirements**

- **Visual Studio 2022** with the *Desktop development with C++* workload — specifically the
  **MSVC v143 x64 build tools**, a **Windows 10/11 SDK**, and **C++ CMake tools for Windows**.
  `build.ps1` checks for these and says which is missing.
- **Git**, to fetch vcpkg and CommonLibSSE-NG.
- **Skyrim Special Edition** with [SKSE64](https://skse.silverlock.org/) installed, to run it. The
  game does not have to be installed to compile.

```powershell
.\fetch-deps.ps1
.\build.ps1
```

`fetch-deps.ps1` clones vcpkg into `extern/` and registers the CommonLibSSE-NG port. Nothing is
vendored here: CommonLibSSE-NG is a separate project under its own licence, the same arrangement as
the xNVSE SDK the New Vegas mod fetches rather than ships.

`build.ps1` builds x64 Release, lays the mod out the way it installs, finds Skyrim across your
drives, and copies it in. `-NoInstall` skips the last step, `-GamePath` overrides the search.

**The first build takes a long time** — it compiles CommonLibSSE from source.

The finished layout is:

```
Data\SKSE\Plugins\
  AynDualScreen.dll
  AynDualScreen.ini          written with defaults on first run
  AynDualScreen\web\         the second-screen page
```

**SKSE itself is a separate download.** The plugin is loaded by `skse64_loader.exe`; if you launch
`SkyrimSE.exe` directly, nothing here runs. `build.ps1` warns if it can't find the loader.

---

## Using it

1. Launch the game through **`skse64_loader.exe`** and load a save.
2. `AynDualScreen.log` (in `Documents\My Games\Skyrim Special Edition\SKSE\`) prints where to point
   a browser:

   ```
   Second screen ready on this PC at http://localhost:27305/
   From another device on your network: http://192.168.1.20:27305/
   ```

3. Open that address on your second screen.

**`localhost` only works on the PC.** Typed into a phone or handheld it means *that* device, so it
can never reach the game — which is why the log names both addresses separately.

The port is **27305**: Stardew and Terraria use 27301, Minecraft 27302, New Vegas 27303, and 27304
is the New Vegas mock server. They're likely to be on the same PC, and two mods fighting over one
port fails in a way that looks like the app's fault.

### The tabs

| Tab | What's on it |
| --- | --- |
| **Map** | Your position and bearing against the worldspace's markers, and a distance-sorted list of everywhere you know. Tap a place to travel there, if you've turned that on. |
| **Items** | Ten categories, search, and four sorts — including **value per weight**, which is the one that answers "what do I drop to get under the cap". Each list totals its own weight and worth. |
| **Magic** | Spells by school, powers, and shouts with their words: found on a wall, and unlocked with a soul, shown differently because they are different things. |
| **Skills** | The eighteen under the Warrior, Mage and Thief, with progress to the next point, and fortified or drained values coloured. Tap one for its perks. |
| **Journal** | Quests with their objectives ticked off. Make one active from here. |
| **Status** | Character, combat, carrying and the calendar, plus everything currently acting on you. |

Number keys 1–6 jump between them.

### The gear button

The cog in the top-right opens a settings panel for **this screen**, saved in the browser's own
storage rather than in the plugin's config — so the Thor's panel, a phone and a desktop monitor can
each be laid out differently against the same game. Update rate, UI scale, the detail panel, map
labels, and which tabs to show.

Underneath it, in its own section, are the **mod's** settings — the `Allow*` switches from the ini.
Those are shared by every screen and change what any device on your network may do to your save,
which is why they are kept visually apart from the ones that only move a layout.

---

## Settings

`Data\SKSE\Plugins\AynDualScreen.ini`, written with defaults and comments on first run. Delete it to
get the defaults back.

| Setting | Default | Notes |
| --- | --- | --- |
| `Port` | `27305` | Change it if something else is using the port. |
| `AllowLanAccess` | `1` | **On by default — anyone on your network can open the page.** |
| `UpdatesPerSecond` | `10` | How often the snapshot is rebuilt. |
| `AllowEquip` | `1` | Equip and unequip gear, and put spells in a hand. |
| `AllowUse` | `1` | Drink, eat, read. |
| `AllowFavorite` | `1` | *Not implemented yet — the button greys out anyway.* |
| `AllowSetQuest` | `1` | *Not implemented yet.* CommonLibSSE exposes `IsActive` but no setter. |
| `AllowDrop` | `0` | **Off by default.** Throws an item on the ground. |
| `AllowFastTravel` | `0` | **Off by default.** Moves you, and does not charge the hours. |
| `AllowWait` | `0` | *Not implemented yet.* |
| `EnableDescriptions` | `1` | Effect and enchantment text. The most expensive part of the snapshot. |
| `MaxMapMarkers` | `400` | Ceiling on markers per snapshot. |
| `MaxInventoryItems` | `600` | Ceiling on inventory entries per snapshot. |
| `AccessToken` | *(empty)* | A shared secret every request must carry. |
| `WebRootOverride` | *(empty)* | Serve `web/` from somewhere else — see below. |

Everything under `Allow*` exists because LAN access is on by default: they let the screen be made
look-only, one class of action at a time, without giving up the second screen. A button switched off
is greyed out on the screen with a tooltip rather than silently doing nothing.

---

## Developing the screen without launching the game

Booting Skyrim for every CSS tweak is painful, so there's a stand-in server that serves the real
`web/` folder against a fake Dragonborn:

```bash
py tools/mockserver.py
```

Then open <http://localhost:27306/>. It fakes a walking character, a regenerating pool, an
inventory, spells, shouts, quests, perks, effects and map markers, and it actually applies `equip`,
`use`, `drop`, `favorite`, `equipSpell`, `equipShout`, `setQuest` and `fastTravel`, so the
interactions can be tested end to end. Its JSON shapes must be kept in step with
[`src/Dtos.h`](src/Dtos.h).

Port 27306 is one past the New Vegas mock's, so everything can run at once.

### Editing the UI while the game is running

Set `WebRootOverride` in the ini to this project's `web` folder. The plugin then reads those files
from disk on every request, and a change is live on the next refresh. Only C++ changes need a
rebuild.

---

## Running this on the AYN Thor

Skyrim has no Android build, so there is exactly one path and it's the one that works: run the game
on the PC with `AllowLanAccess` on and open the second screen in the Thor's browser over Wi-Fi. The
Thor is the touch panel; nothing needs to run on Android except a browser.

## Companion Android app

The companion app from this repository works with this unchanged — it's a fullscreen, no-chrome
shell around whatever host and port you give it. Type the PC's address and **27305**. It keeps the
display awake and can launch itself onto a handheld's *second* display, which a plain browser can't.

Any browser still works if you'd rather not build anything.

---

## What's worth doing next

In rough order, because the first one blocks everything:

1. **Run it against a real save** and check the numbers against the game's own menus, value by
   value. That is the only verification that exists — there is no test suite, and nothing below
   matters until this is done.
2. **Favourites, set-active-quest and wait**, the three refused actions. Each needs a route through
   the game's own routine rather than a flag written by hand; each is a small piece of work and a
   real one.
3. **Measure the form scan.** Perks, spells and shouts are found by walking the load order every
   ~3 seconds. On a 300-mod load order that is a lot of forms, and nobody has timed it yet. If it
   costs frames, the fix is to rebuild only on the events that can change the answer.
4. **Terrain on the map**, from the LOD textures in the archives. The New Vegas mod's `Bsa.cpp`,
   `Dds.cpp` and `Png.cpp` do exactly this job for Gamebryo archives and are the obvious starting
   point — Skyrim's are BSA v105 and mostly the same shape.
6. **A plugintest harness** like the New Vegas one, which loads the real DLL and drives it without
   the game. That is what makes the HTTP layer testable at all.

---

## Layout

```
skyrimse/
  src/
    main.cpp        plugin entry, SKSE messages, HTTP routing, static files, the pacer
    Snapshot.cpp    reads the game on the game thread; the command queue
    Actions.cpp     parses and applies what the screen sends, permission-checked again
    MapData.cpp     worldspace bounds and the map-marker walk
    Describe.cpp    game data to the short strings the screen shows
    WebServer.cpp   minimal HTTP/1.1 server on Winsock
    Config.cpp      the ini
    Json.h          a write-only JSON builder
    Dtos.h          the wire format, written down
    PCH.h           CommonLibSSE, precompiled
  web/              the second-screen UI (index.html, style.css, app.js)
  tools/
    mockserver.py   fake backend for designing the UI, no game needed
  fetch-deps.ps1    clones vcpkg and registers CommonLibSSE-NG
  build.ps1         builds x64 and installs into the game
  deploy.ps1        pushes web/ (and optionally the DLL) into an existing install
  package-release.ps1  builds the release zip, always from source and a fresh build
```

---

## Credits

- **Abacus** — author.
- Built on [CommonLibSSE-NG](https://github.com/CharmedBaryon/CommonLibSSE-NG) and
  [SKSE64](https://skse.silverlock.org/). Both are separate projects under their own licences and
  neither is redistributed here; `fetch-deps.ps1` fetches them.
- **SkyUI** is not used, linked against or required — but the way this screen arranges an inventory
  is its idea, and saying otherwise would be silly.
- **Contains AI-assisted code**, written with an AI coding assistant and human-reviewed.

No game assets are redistributed. The look is drawn in CSS — Skyrim's interface is a set of Flash
movies and textures there's no licence to ship, and a dark panel with a gold rule is simple enough
to reproduce honestly.

This is an independent, unofficial project, not affiliated with or endorsed by Bethesda, the SKSE
team, the CommonLibSSE-NG project or the authors of SkyUI.
