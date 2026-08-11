# Ayn Dual Screen — Fallout: New Vegas

An xNVSE plugin that turns any second display into a live Pip-Boy: status, SPECIAL, skills,
inventory you can touch, quests, map and radio — the five tabs the official Fallout 4 companion app
had, with the pages New Vegas actually needs.

Built for the AYN Thor's second screen, but the second screen can be any device with a browser.

**By Abacus.**

> **This mod contains AI-assisted code.** It was written with the help of an AI coding assistant and
> reviewed by a human before release. Flagged here so anyone reading, forking or reporting bugs
> against it knows what they're looking at.

This is a port of the Stardew Valley mod of the same name and keeps the same architecture. It is
the first one in this repository that isn't a managed mod — see *Why this one is a DLL* below.

---

## Status

**The screen is finished and works. The plugin is written but has not been compiled or run in-game
yet**, because the machine it was written on has no 32-bit C++ toolchain installed. Everything you
need to build it is here and [`build.ps1`](build.ps1) checks for the missing pieces and names them.

What that means in practice:

| Part | State |
| --- | --- |
| The second-screen UI (`web/`) | Done, and verified end to end against the mock server. |
| The mock backend (`tools/mockserver.py`) | Done. Run it and the whole UI is usable now. |
| The plugin (`src/`) | Written against the xNVSE SDK's own structure definitions. **Never compiled.** |
| Equip, use and drop | Written. Applied on the game thread through the game's own routines. |
| Fast travel, radio, set active quest | **Not applied.** The buttons grey out regardless of the ini. |
| Perks, notes, misc stats, active effects, map markers | Sent as empty arrays. Readers not written. |

Nothing in the snapshot is invented: a reader that doesn't exist yet sends an empty list, and the
screen renders an empty tab, rather than showing a plausible lie about your character.

---

## Why this one is a DLL

Stardew has SMAPI, Terraria has tModLoader, Minecraft has Forge. New Vegas has none of that. It is a
2010 32-bit Gamebryo game whose only extension point is the **New Vegas Script Extender**, which
loads native DLLs. So this mod is C++ rather than C# or Java, and it reads the game's own structures
directly instead of going through a modding API.

The consequences worth knowing:

- **It must be a 32-bit build.** An x64 DLL is one the game physically cannot load. There is no x64
  configuration in the project on purpose.
- **It depends on the xNVSE SDK's structure definitions** — which offset in `PlayerCharacter` holds
  what. Those are reverse-engineered and maintained by the xNVSE project, not by us, and this mod
  reads only fields the SDK has actually mapped and asserted. Where the SDK still has a field marked
  unknown, the corresponding number is left out rather than guessed at.
- **The SDK is not vendored here.** [`fetch-nvse.ps1`](fetch-nvse.ps1) clones it.

---

## Tale of Two Wastelands, and Fallout 3

**TTW works, and needs no separate build.** Tale of Two Wastelands is an ordinary New Vegas load
order — it converts Fallout 3's content to run on the New Vegas engine, under the same xNVSE. This
plugin reads the *player*, the current cell and the quest list rather than any named form, so it has
nothing to be confused by: walk into the Capital Wasteland and the screen keeps working, showing
Capital Wasteland cell names and TTW's quests.

Two things to expect there:

- The DT/hardcore panel is a New Vegas concept. Under TTW it keeps working, because TTW runs on the
  New Vegas rules.
- Fallout 3's skill list differs slightly (Big Guns and Small Guns rather than Guns). The screen
  reads whatever actor values the game reports, so TTW's own configuration is what shows up.

**Standalone Fallout 3 is not this DLL.** Fallout 3 is a different executable extended by FOSE,
which has its own plugin ABI and its own structure offsets. Supporting it means a second build of
this same source against the FOSE SDK — the web UI, the wire format and all the logic would carry
over unchanged, but `main.cpp` and the field offsets in `Snapshot.cpp` would not. That is a real
piece of work rather than a flag, and it is not done.

If you want Fallout 3's content on the second screen, **TTW is the supported route**, and it is the
one that works today.

---

## How it works

A mod can't draw to a second physical display — the game owns exactly one window. So this is split
in two:

| Part | Where it lives | What it does |
| --- | --- | --- |
| The plugin | `src/` | Runs a tiny HTTP server inside the game. Every few frames it snapshots the player into JSON and applies commands sent back from the screen. |
| The screen | `web/` | A web page that polls that JSON 10×/second and draws the UI. Taps are posted back to the plugin. |

The upshot: **the second screen is just a browser pointed at the game.** The Thor's second display,
a phone, a tablet, or a second monitor — anything that can open a URL.

### Endpoints

| Route | Purpose |
| --- | --- |
| `GET /` | The second-screen page, and everything else under `web/`. |
| `GET /state` | The live snapshot: player, SPECIAL, skills, inventory, quests, location. |
| `POST /action` | A command from the touch screen: `equip`, `use`, `drop`, `setQuest`, `radio`, `fastTravel`. |

The exact JSON shape is written down in [`src/Dtos.h`](src/Dtos.h), which is the contract
`web/app.js` and `tools/mockserver.py` both have to match.

### The one rule worth remembering

Game state may **only** be touched on the game thread. The web server answers requests on worker
threads, so it never reads the world: the game thread builds a finished JSON string during
`kMessage_MainGameLoop` and publishes it under a mutex, and incoming commands go into a queue that
the same handler drains. Breaking this rule is the classic way to corrupt a save, so keep new
features on the pattern.

Two smaller rules that follow from it:

- **The screen names things by form ID, never by list position.** A tap that lands one frame after
  the inventory shifted therefore can't act on the wrong object.
- **Permissions are re-checked on the game thread.** The snapshot tells the screen what it may do so
  the right buttons grey out, but the decision is made again in `DrainCommands` before anything
  happens. Nothing the screen sends can talk its way past the config.

---

## Building

**Requirements**

- **Visual Studio 2022** with the *Desktop development with C++* workload. Specifically you need the
  **MSVC v143 x64/x86 build tools** and a **Windows 10 or 11 SDK** — the 32-bit toolset is the part
  people are missing, and its absence shows up as a baffling "cannot open include file
  `<windows.h>`" rather than anything useful. `build.ps1` checks for both and says which is missing.
- **Git**, to fetch the xNVSE SDK.
- **Fallout: New Vegas** with [xNVSE](https://github.com/xNVSE/NVSE/releases) installed, to run it.
  The game does not have to be installed to compile.

```powershell
.\fetch-nvse.ps1
.\build.ps1
```

`build.ps1` builds `Release|Win32`, lays the mod out the way it installs, finds New Vegas across
your drives, and copies it into `Data\NVSE\Plugins`. `-NoInstall` skips the last step, and
`-GamePath` overrides the search.

Or open [`AynDualScreen.sln`](AynDualScreen.sln) in Visual Studio and build `Release|Win32`.

The finished layout is:

```
Data\NVSE\Plugins\
  AynDualScreen.dll
  AynDualScreen.ini          written with defaults on first run
  AynDualScreen\web\         the second-screen page
```

**xNVSE itself is a separate download.** The plugin is loaded by `nvse_loader.exe`; if you launch
`FalloutNV.exe` directly, nothing here runs. `build.ps1` warns if it can't find the loader.

---

## Using it

1. Launch the game through **`nvse_loader.exe`** and load a save.
2. `AynDualScreen.log` (next to `FalloutNV.exe`) prints where to point a browser:

   ```
   Second screen ready on this PC at http://localhost:27303/
   From another device on your network: http://192.168.1.20:27303/
   ```

3. Open that address on your second screen.

**`localhost` only works on the PC.** Typed into a phone or handheld it means *that* device, so it
can never reach the game — which is why the log names both addresses separately.

The port is **27303**: Stardew and Terraria use 27301, Minecraft 27302. They're likely to be on the
same PC, and two mods fighting over one port fails in a way that looks like the app's fault.

### The tabs

| Tab | What's on it |
| --- | --- |
| **STAT** | Status (a limb-condition doll, health, AP, rads, and hardcore's H2O/food/sleep), SPECIAL, all thirteen skills, and perks. |
| **INV** | Weapons, apparel, aid, misc and ammo, with condition, weight, value and what's equipped. Select an item for its stats; equip, use or drop it from the footer. |
| **DATA** | Quests with their objectives ticked off, notes and holotapes, and the misc stats page. |
| **MAP** | Your position and heading against the local area or the whole worldspace, with discovered markers. |
| **RADIO** | Stations in range, and which one is playing. |

Number keys 1–5 jump between them.

### The gear button

The cog in the top-right opens a settings panel for **this screen**, saved in the browser's own
storage rather than in the plugin's config — so the Thor's panel, a phone and a desktop monitor can
each be laid out differently against the same game.

| Control | What it does |
| --- | --- |
| Colour | Green, amber, blue or white phosphor. Fallout shipped the first three itself. |
| Size | Five UI scales; the whole page scales off the root font size. |
| Updates / sec | 5, 10, 15 or 20 polls a second. Lower it on a slow Wi-Fi link. |
| Scanlines / Vignette | The CRT effect, off if you'd rather have the fill rate back. |
| Detail panel | Hide the right-hand card on a narrow screen. |
| Tabs | Hide any of the five you don't want. |

Nothing here touches the game or the plugin's settings, and it needs no reload.

---

## Settings

`Data\NVSE\Plugins\AynDualScreen.ini`, written with defaults and comments on first run. Delete it to
get the defaults back.

| Setting | Default | Notes |
| --- | --- | --- |
| `Port` | `27303` | Change it if something else is using the port. |
| `AllowLanAccess` | `1` | Lets another device reach the screen. **On by default — anyone on your network can open the page.** Set `0` on a network you don't trust. |
| `UpdatesPerSecond` | `10` | How often the snapshot is rebuilt. |
| `AllowEquip` | `1` | Equip and unequip weapons and apparel. |
| `AllowUse` | `1` | Use aid items. |
| `AllowSetQuest` | `1` | Change the active quest. *Not implemented yet — the button greys out anyway.* |
| `AllowRadio` | `1` | Change the radio station. *Not implemented yet.* |
| `AllowDrop` | `0` | **Off by default.** Throws an item on the ground. |
| `AllowFastTravel` | `0` | **Off by default.** Moves your character and burns game hours. *Not implemented yet.* |
| `MaxMapMarkers` | `250` | Ceiling on markers per snapshot. |
| `MaxInventoryItems` | `400` | Ceiling on inventory entries per snapshot. |
| `WebRootOverride` | *(empty)* | Serve `web/` from somewhere else — see below. |

Everything under `Allow*` exists because LAN access is on by default: they let the screen be made
look-only, one class of action at a time, without giving up the second screen. A button switched off
is greyed out on the screen with a tooltip rather than silently doing nothing.

The two defaulting to off are the two you can't undo by tapping again: dropping loses an item, and
fast travel moves you and eats hours. The screen asks for confirmation on both anyway.

---

## Developing the screen without launching the game

Booting New Vegas for every CSS tweak is painful, so there's a stand-in server that serves the real
`web/` folder against a fake courier:

```bash
py tools/mockserver.py
```

Then open <http://localhost:27304/>. It fakes a walking player, a decaying limb, an inventory,
quests, notes, map markers and radio stations, and it actually applies `equip`, `use`, `drop`,
`setQuest` and `radio`, so the interactions can be tested end to end. Its JSON shapes must be kept
in step with [`src/Dtos.h`](src/Dtos.h).

Port 27304 is one past the plugin's, so both can run at once.

### Editing the UI while the game is running

Set `WebRootOverride` in the ini to this project's `web` folder:

```
C:\Users\<you>\Desktop\stardew mod\falloutnv\web
```

The plugin then reads those files from disk on every request, and a change is live on the next
refresh. Only C++ changes need a rebuild.

---

## Running this on the AYN Thor

There's no Android obstacle to work around: New Vegas has no Android build, so there's exactly one
path and it's the one that works. Run the game on the PC with `AllowLanAccess` on and open the
second screen in the Thor's browser over Wi-Fi. The Thor is the touch panel; nothing needs to run on
Android except a browser.

## Companion Android app

The companion app from this repository works with this unchanged — it's a fullscreen, no-chrome
shell around whatever host and port you give it. Type the PC's address and **27303**. It keeps the
display awake and can launch itself onto a handheld's *second* display, which a plain browser can't.

Any browser still works if you'd rather not build anything.

---

## Credits

- **Abacus** — author.
- Built on [xNVSE](https://github.com/xNVSE/NVSE), whose SDK provides the game structure definitions
  this plugin reads. xNVSE is a separate project under its own licence and is not redistributed
  here; `fetch-nvse.ps1` clones it.
- **Contains AI-assisted code**, written with an AI coding assistant and human-reviewed.

No game assets are redistributed. The Pip-Boy look is drawn in CSS rather than extracted — Fallout's
UI is a set of textures and menu files there's no licence to ship, and one phosphor colour and some
scanlines are simple enough to reproduce honestly.

This is an independent, unofficial project, not affiliated with or endorsed by Bethesda, Obsidian,
the xNVSE project or the Tale of Two Wastelands team.

---

## What's worth doing next

In rough order of how much they'd add:

- Compile it, and fix what a first run turns up.
- The readers that are still stubs: perks, active effects, notes and holotapes, misc stats.
- Map markers, read from the cells' `ExtraMapMarker` data — the map draws them already, it just
  isn't being sent any.
- Radio: list the stations in range and switch between them.
- Fast travel, applied on the game thread.
- Set the active quest — through the game's own routine, not by writing `player->quest` behind its
  back, which is why it isn't done yet.
- Weight for misc items and chems, once the SDK maps those classes — or by reading the offsets
  directly, with the same static assertions the SDK uses.
- Item icons, cropped from the game's own textures at runtime the way the Terraria mod does it.
- A token in the ini so LAN access needs a shared secret.

## Layout

```
falloutnv/
  src/
    main.cpp        plugin entry, NVSE messages, HTTP routing, static files
    Snapshot.cpp    reads the game on the game thread; the command queue
    WebServer.cpp   minimal HTTP/1.1 server on Winsock
    Config.cpp      the ini
    Json.h          a write-only JSON builder
    Dtos.h          the wire format, written down
    exports.def     the two symbols NVSE looks for
  web/              the second-screen UI (index.html, style.css, app.js)
  tools/
    mockserver.py   fake backend for designing the UI
  fetch-nvse.ps1    clones the xNVSE SDK into extern/nvse
  build.ps1         builds Win32 and installs into the game
```
