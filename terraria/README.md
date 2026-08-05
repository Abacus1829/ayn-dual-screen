# Ayn Dual Screen — Terraria

A tModLoader mod that turns any second display into a 3DS-style bottom screen for Terraria:
live map, live inventory you can touch, clock, depth, biome, buffs, boss health, life and mana.

Built for the AYN Thor's second screen, but the second screen can be any device with a browser.

**By Abacus.**

> **This mod contains AI-assisted code.** It was written with the help of an AI coding assistant and
> reviewed by a human before release. Flagged here so anyone reading, forking or reporting bugs
> against it knows what they're looking at.

This is a port of the Stardew Valley mod of the same name and keeps the same architecture — see
*How it works* below for the parts that had to change.

**It is a separate, self-contained project.** The two mods share no files, no build, and no output:
this one lives in `Desktop\terraria mod` and packages to tModLoader's `Mods` folder, the Stardew one
lives in `Desktop\stardew mod` and packages to Stardew's. Nothing here needs the other to be present.
Ideas were carried across by hand; if you change one, the other doesn't follow.

---

## How it works

A mod can't draw to a second physical display — the game owns exactly one window. So this is split in two:

| Part | Where it lives | What it does |
| --- | --- | --- |
| The mod | `DualScreenSystem.cs`, `WebServer.cs` | Runs a tiny HTTP server inside the game. Every few ticks it snapshots the player into JSON and applies commands sent back from the screen. |
| The screen | `web/` | A web page that polls that JSON 10x/second and draws the UI. Taps and drags are posted back to the mod. |

The upshot: **the second screen is just a browser pointed at the game.** It can be the Thor's second
display, a phone, a tablet, or a second monitor — anything that can open a URL.

### Endpoints

| Route | Purpose |
| --- | --- |
| `GET /` | The second-screen page. |
| `GET /state` | The live snapshot: clock, depth, biome, life, mana, inventory, buffs, nearby NPCs, boss. |
| `GET /minimap` | The rendered minimap, as a PNG data URI plus the world coordinates it covers. |
| `GET /icon/<key>.png` | An item or buff sprite, cropped out of the game's own textures at runtime. |
| `GET /asset/<name>.png` | An inventory slot background from the game's textures: `slot`, `slot-selected`, `slot-coin`, `slot-ammo`, `slot-cursor`. |
| `GET /asset/back<N>.png` | Every numbered `InventoryBack` variant (1–19), for checking the picks above by eye. |
| `POST /action` | A command from the touch screen: `select`, `swap`, `drop`, `trash`, `sort`, `heal`, `mana`, `buff`, `mount`, `mapmode`. |

### The map is an image, not a grid

This is the one real difference from the Stardew version. There, the whole location is shipped as a
tile grid — a few thousand characters — and the browser colours it in. A Terraria world is millions
of tiles, so that isn't an option.

Instead the mod reads `Main.Map`, the game's own record of what you've explored and what colour each
tile is on the minimap, renders a window of it to a PNG on the game thread, and sends the image along
with the world coordinates of its top-left corner. The browser scales the image to the panel and
plots you and every NPC against that origin.

There are two modes, toggled from the screen:

- **Follow** — a window of `MinimapTilesWide` × `MinimapTilesHigh` tiles centred on you, redrawn when
  you've moved a few tiles.
- **World** — the whole world, downsampled so its widest edge is about 900px, redrawn every few seconds.

The PNG is encoded by `Png.cs` rather than by `Texture2D.SaveAsPng`, because `Main.Map` lives in
ordinary memory and there's no reason to send it on a round trip through the GPU every few frames.

### Everything you see is a real game asset

Nothing is redrawn by hand. At runtime the mod reads the game's own textures, crops the regions it
needs, and serves them as PNGs:

| What | Where it comes from |
| --- | --- |
| Item icons | `TextureAssets.Item[type]`, using `Main.itemAnimations` to pick the right frame for animated items |
| Buff icons | `TextureAssets.Buff[type]` |
| Inventory slots | `TextureAssets.InventoryBack*` — the same textures `ItemSlot.Draw` picks between |
| Map colours | `MapHelper.GetMapTileXnaColor`, the game's own minimap palette |

The one thing *not* from the game is the panel frame. Terraria's UI is a flat translucent navy panel
rather than a nine-sliced frame, so that's drawn in CSS to match rather than extracted.

If a slot background ever looks wrong, open `http://localhost:27301/asset/back1.png` … `back19.png`
to see them all and correct the `UiAssets` table at the top of `DualScreenSystem.cs`.

### The one rule worth remembering

Game state may **only** be touched on the game thread. The web server answers requests on background
threads, so it never reads the world directly — the game thread publishes finished JSON strings, and
incoming commands go into a queue that's drained during `PostUpdateEverything`. Breaking this rule is
the classic way to corrupt a world, so keep new features on the same pattern.

---

## Using it

1. Build (this packages the mod straight into your `Mods` folder):

   ```bash
   dotnet build
   ```

   This project deliberately sits outside tModLoader's `ModSources` folder so it can live next to
   the Stardew mod rather than inside the game's save directory. `AynDualScreen.csproj` therefore
   imports `tMLMod.targets` from the game install directly instead of via `..\..\`; set the
   `tMLSteamPath` property or environment variable if tModLoader isn't in the default Steam location.

   The trade-off is that tModLoader's in-game **Workshop → Develop Mods** screen won't list this mod,
   since it only scans `ModSources`. Build from the command line instead — the packaged `.tmod` still
   lands in the `Mods` folder either way, so the game picks it up normally.

2. Enable **Ayn Dual Screen** in the mod list and launch. The log will print:

   ```
   Second screen ready at http://localhost:27301/
   ```

3. Open that URL in a browser on your second display, and enter a world.

### Controls

- **Tap a slot** — selects it. Slots 1–10 also become your held item.
- **Tap the map** — names whatever is there: the nearest NPC or player, or the tile coordinates.
- **&minus; / +** — zoom the map from 1x to 5x. Past 1x it follows you and stops at the edges.
- **The dot by the map title** — green while snapshots are arriving, amber if they stall, red if they stop.
- **Drag a slot onto another** — swaps them, anywhere across the hotbar, main grid, coins and ammo.
- **Heal / Mana / Buff / Mount** — the vanilla quick-use keys. They pick the right item themselves.
- **Drop / Trash / Sort** — act on the selected slot. Trash needs two taps to confirm.
- **Follow / World** — toggles between the window around you and the whole world.

The right-hand panel also shows your **equipped armour and accessories** with their combined defence,
and the stat strip tracks how many **healing potions** you have left — replaced by a countdown while
Potion Sickness is up, since the Heal button can't do anything until it clears.


### The gear button

The cog in the top-right corner opens a settings panel for **this screen**, saved in the browser's own
storage rather than in the mod's config — so the Thor's panel, a phone and a desktop monitor can each
be laid out differently against the same game.

| Control | What it does |
| --- | --- |
| Show | Hide any section you don't want: top bar, map, legend, stat strip, buffs, boss bar, inventory, coin & ammo slots, equipment, selected item, action buttons. Hiding one of the two main panels gives the other the full width. |
| Accent | Eight preset highlight colours, used for the selected slot and focus rings. |
| Slot outline | Subtle, normal or bold. The game's own slot texture is translucent, so this is what makes an empty slot readable on your particular panel. |
| Size | Four UI scales, from small to huge — the whole page scales off the root font size. |
| Updates per second | 5, 10, 15 or 20 polls a second. Lower it on a slow Wi-Fi link. |
| Reset to defaults | Puts everything back. |

Nothing here touches the game or the mod's own settings, and it needs no reload.

### The left panel's tabs

| Tab | What's on it |
| --- | --- |
| Map | The minimap, as before. |
| Bosses | Every boss and event in progression order, ticked off from the world's own `downed` flags. The next one you can actually attempt is outlined; hardmode entries are dimmed until the Wall of Flesh is down. |
| Craft | What you can make **where you're standing**, taken from `Main.availableRecipe` — so it already accounts for nearby stations, water, honey and other mods' recipes. Filter matches names *and* ingredients. |
| NPC | Appears only while you're talking to someone. |

### Talking to an NPC

While a conversation is open the panel shows the villager's own sprite, their current line, and their
shop if one is open on the PC. The tab opens itself and puts you back where you were when the
conversation ends — turn that off with *Switch to the NPC tab* in the gear panel if you'd rather it
didn't move.

**Buying is off by default.** `AllowShopping` gates only the Buy button; the stock list is always
visible. It's separate from the other permissions because LAN access is on by default and buying
spends real coins. The screen only ever sends a *slot number* — the mod re-reads the price and counts
your purse at the moment of purchase, so nothing the screen says can change what something costs.

### Settings

In-game under **Settings → Mod Configuration → Ayn Dual Screen**:

| Setting | Default | Notes |
| --- | --- | --- |
| `Port` | `27301` | Change it if something else is using the port. Needs a reload. |
| `AllowLanAccess` | `true` | Lets another device reach the screen. **On by default — anyone on your network can open the page and destroy your items. Set `false` on a network you don't trust.** Needs a reload. |
| `UpdatesPerSecond` | `10` | How often the snapshot refreshes. Lower it if you see a frame-rate cost. |
| `EnableItemIcons` | `true` | Set `false` to skip extracting item and buff sprites. |
| `AllowTrash` | `true` | Set `false` to disable the trash button entirely. |
| `AllowDrop` | `true` | Set `false` to stop the screen throwing items on the ground. |
| `AllowInventoryEdit` | `true` | Set `false` to stop it rearranging or sorting the inventory. |
| `AllowQuickUse` | `true` | Set `false` to disable the Heal / Mana / Buff / Mount buttons. |
| `AllowShopping` | `false` | Lets the second screen buy from an open shop. Off by default — see above. |
| `ShowEnemies` | `true` | Whether hostile NPCs get a dot on the minimap. Bosses always do. |
| `ShowTownNpcs` | `true` | Whether town NPCs get a dot. |
| `MaxMapEntities` | `80` | Ceiling on map dots per snapshot; the nearest survive the cut. An invasion can otherwise put hundreds in the payload. |
| `MinimapTilesWide` / `High` | `220` / `150` | The size of the follow-mode window, in world tiles. |
| `WebRootOverride` | *(empty)* | A folder to serve `web/` from instead of the packed copy. See below. |

Everything under `Allow*` exists because LAN access is on by default: they let the screen be made
look-only, one class of action at a time, without giving up the second screen. A button switched off
here is greyed out on the screen with a tooltip rather than silently doing nothing.


With `AllowLanAccess` on, the log prints the address another device should use — you don't have to go
looking for it:

```
Second screen ready on this PC at http://localhost:27301/
From another device on your network: http://192.168.1.20:27301/
```

**`localhost` only works on the PC.** Typed into a phone or handheld it means *that* device, so it can
never reach the game — which is why the log names both addresses separately. If a VPN adapter is
active the log warns about it too, because VPNs routinely stop two devices on the same Wi-Fi from
seeing each other.

---

## Developing the screen without launching the game

Booting Terraria for every CSS tweak is painful, so there's a stand-in server that serves the real
`web/` folder against fake data:

```bash
py tools/mockserver.py
```

Then open <http://localhost:27302/>. It fakes a world, a walking player, an advancing clock, an
explored minimap, a full inventory and a few buffs, and it actually applies `swap`/`drop`/`trash`/
`sort`/`mapmode` so the interactions can be tested end to end. Its JSON shapes must be kept in step
with `Dtos.cs`.

### Editing the UI while the game is running

Unlike the Stardew version, the page is packed *inside* the `.tmod` file, so there's no folder to
copy files into. Instead, set `WebRootOverride` in the mod config to this project's `web` folder:

```
C:\Users\<you>\Desktop\terraria mod\AynDualScreen\web
```

The mod then reads those files from disk on every request, and a change is live on the next refresh.
Only C# changes need a rebuild.

---

## Running this on the AYN Thor

Unlike the Stardew version, there's no Android obstacle to work around here — **tModLoader has no
Android build at all**, official or community. The mobile version of Terraria is a separate product
that doesn't load mods.

So there's exactly one path, and it's the one that works: run the game on the PC with
`AllowLanAccess: true`, and open the second screen in the Thor's browser over Wi-Fi. The Thor acts as
the touch panel. Nothing needs to run on Android except a browser.

## Companion Android app

The companion app from the Stardew project works with this unchanged — it's a fullscreen, no-chrome
shell around whatever host and port you give it, and it defaults to the same port `27301`. It keeps
the display awake and can launch itself onto a handheld's *second* display, which a plain browser
can't do.

Its on-screen text says "Stardew" in a few places; that's cosmetic, in
`android/app/src/main/res/values/strings.xml`.

Any browser still works if you'd rather not build anything.

---

## Credits

- **Abacus** — author.
- Built on [tModLoader](https://github.com/tModLoader/tModLoader).
- Sprites are the property of Re-Logic and are read from your own installed copy of the game at
  runtime — none are redistributed with this mod.
- **Contains AI-assisted code**, written with an AI coding assistant and human-reviewed.

---

## Ideas worth adding next

- Swap polling for a WebSocket so the map updates at full frame rate instead of 10Hz.
- Tap the map to place a ping, or to show what's at that tile.
- A crafting tab: what you can make from what's in range.
- An equipment and accessories panel, and the piggy bank / safe when they're open.
- Show pinned NPC homes and important tiles (chests, hearts, orbs) as map markers.
- A token in the config so LAN access requires a shared secret.

## Layout

```
AynDualScreen/
  DualScreenSystem.cs  snapshots, minimap rendering, command handling, HTTP routing
  WebServer.cs         minimal HTTP/1.1 server on TcpListener
  Png.cs               8-bit RGBA PNG encoder, used for the map and every sprite
  Dtos.cs              the wire format shared with web/app.js
  DualScreenConfig.cs  the in-game config screen
  AynDualScreen.cs     the Mod class tModLoader requires
  web/                 the second-screen UI (index.html, style.css, app.js)
  tools/mockserver.py  fake backend for designing the UI
```
