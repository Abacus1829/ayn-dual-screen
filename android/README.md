# Abacus's Dual Screen Interface

An Android app for the AYN Thor's second screen. Pick a game, connect, and the second screen opens
fullscreen on the display you choose.

**By Abacus.**

Version history is in [CHANGELOG.md](CHANGELOG.md).

> **This app contains AI-assisted code.** It was written with the help of an AI coding assistant and
> reviewed by a human before release.

---

## What it's for

Both dual-screen mods serve their bottom-screen UI as a web page, so any browser can show it. This
app exists to do the three things a browser can't:

1. **Launch onto the second display.** A browser opens wherever it already is. Android will move an
   activity to a chosen panel, but only if something asks — that's `ActivityOptions.setLaunchDisplayId`,
   and it's the main reason this app exists.
2. **Stay awake and stay out of the way.** No address bar, no scrollbars, no system bars, and the
   screen never sleeps. The page needs every pixel for the inventory.
3. **Tell you what's wrong when nothing appears.** A browser gives you a blank page. This tells you
   whether the PC answered, whether the mod is running, and which game it found.

It talks to the mods over plain HTTP and holds no game logic of its own — if a mod changes, the app
doesn't need to.

## Leaving a session

The second screen is tied to one running game, so when that game goes away the app hands control back
to the picker rather than sitting on a dead page.

- **The session ending returns you to the menu.** The WebView cannot detect this on its own — once the
  page has loaded, the mod's server disappearing produces no navigation error at all; the page's own
  polling just starts failing quietly. So the app checks `/state` every 4 seconds out of band, and two
  consecutive misses mean the game has gone.
- **The network is what decides the connection state, not the WebView.** Some WebView builds report a
  page as finished before anything has arrived, which is enough to show a confident "Connected" over a
  blank screen. `/state` answering means a companion server; the root answering means an ordinary web
  page; neither answering means nothing is there.
- **An address with nothing at it says so in about twelve seconds**, rather than sitting blank for the
  length of the WebView's own connect timeout, which is closer to two minutes.
- **With *Reconnect automatically* on**, it retries first (backing off to 5s) and only drops to the menu
  after 8 failed attempts — a game being restarted comes back well inside that. A successful connection
  resets the count.
- **With it off**, it returns to the menu as soon as the session ends.
- **An address that is not a companion server stays open.** The health check asks for `/state`, which an
  ordinary web page will never have; those are identified once and the check is switched off for them
  rather than closing a working page after eight seconds.

## Simple and Advanced

The mode switch at the top is remembered between launches.

**Simple** is the game picker and one button. It sweeps the network, works out which game is running,
takes that address and opens the second screen — no typing at all.

**Advanced** adds the address fields, the display picker, and three toggles. Both modes are the older,
single-address path and still work exactly as they did; **Connect** is the newer one and is where
saved connections, discovery and import/export live.


| Toggle | Default | What it does |
| --- | --- | --- |
| Reconnect automatically | on | Keeps retrying when the game restarts, instead of parking on an error. Backs off to 5s after a few tries. |
| Never sleep while open | on | Holds the panel awake. Turn it off to let it dim normally. |
| Remember this display | on | Reopens on the same panel next time without asking. Stored per game. |

## Saved connections

The **Connect** tile is the fast path: a list of everything you have saved, one tap to open. It is
also the first tile on the home grid, because it is what the app is for.

Each saved connection keeps:

| Field | What it does |
| --- | --- |
| Name | What it is called in the list, and in the session's status line |
| Host / IP | The PC. Never a name the app invents — `localhost` is rejected, because it means the PC itself |
| Port | Whatever the mod is serving on |
| Opens on | This screen, second screen, external display, automatic, or ask every time |
| Orientation | Automatic, landscape or portrait |
| Keep screen awake | Always, while connected, or never |
| Connect on launch | Opens this one as soon as the app starts. Only one connection can hold it |

Long-press a row (or tap **⋯**) for edit, duplicate, delete, and **Make default** — the default sorts
to the top and is marked with a star.

Connections are stored in the app's own preferences and survive reinstall-free updates. They are not
tied to any particular game: a connection pointing at something nobody has written a mod for yet
works exactly as well as one pointing at a known mod.

### Recent connections

Anything you actually opened appears under **RECENT**, newest first, deduplicated by host and port —
opening the same PC twenty times leaves one entry, not twenty. Tap to reconnect, long-press to keep
it as a saved connection, **Clear** to empty the list.

### Import and export

**More → Export** writes `AynDualScreen/profiles.json` to shared storage, which this app's own FTP
server already serves — so an export is on your PC without a cable or a dialog. **Share** hands the
same JSON to another app instead.

**More → Import** reads that file back, or takes pasted JSON. Malformed entries are skipped rather
than fatal, and a connection whose host and port already exist updates that one instead of appearing
twice, so importing your own export back is a no-op. Only what the app actually stores is exported.

## Finding servers on the network

**Connect → Find** sweeps your /24 across several ports, 48 sockets at a time with a 350 ms timeout,
and asks whatever answered what it is. Each result shows the host, the port, and one of three states:
a companion server with a world loaded, one sitting at its menu, or a port that is open but is
something else. Tap to connect, **Save** to keep it.

**The limitation, stated plainly:** the companion mods are ordinary HTTP servers that bind a port and
announce nothing — no mDNS, no broadcast. There is no discovery protocol to listen for, so the only
honest way to find one is to ask every address on the subnet. That means discovery **cannot see past
a router**, and **cannot find a server on a port nobody thought to try**. The ports offered by default
are the mod defaults plus every port your saved connections use, which is why an unusual port is found
on the second attempt without typing anything.

## Which screen it opens on

A saved connection stores an *intent* — "the second screen" — and never a display id. Display ids are
handed out by the system and change when a dock is unplugged or the device reboots, so a connection
that remembered "display 2" would be pointing at nothing, or at something else, by tomorrow. The
intent is resolved against the displays that exist at the moment you tap connect.

- **Automatic** — the second screen when there is one, this one when there is not. The default.
- **Second screen** — the first display that is not the main one. On the Thor, the lower panel.
- **External display** — a display the system marks as presentable, which is what an HDMI dock gives.
- **This screen** — the main display.
- **Ask every time** — a chooser built from the displays present right now.

If the chosen screen is not there, the session opens on the main display rather than failing. If the
screen disappears *during* a session — the panel switched off, a dock pulled — the page reappears on
the main display instead of the app silently vanishing with it.

**More → Defaults for new connections** sets what a newly made connection starts out as. It is a
default, not an override: a connection that says landscape means landscape.

## In a session

The small **⋯** button, top-left, opens the session menu. It carries the page controls — Back,
Forward, Reload, Reconnect, Reset zoom, Fullscreen — and can be emptied entirely from
**Defaults for new connections**. They live in the menu rather than on the screen because the second
display is the whole point, and a permanent toolbar across it takes space from the thing you are
looking at.

The connection state appears as a small badge opposite it: **Connecting**, **Connected**,
**Reconnecting**, **Disconnected** or **Connection failed**. It fades out a second after a
connection goes green, so a healthy session carries no badge at all.

## Remote control profiles

The macro pad's layouts are remote control profiles. Each is a named set of buttons with positions,
sizes and actions, and each can be:

- **created, renamed, duplicated, deleted, reset** — from **Macros → Layout editor → More**.
- **exported and imported** — versioned JSON carrying the macros its buttons need. See
  [Layout sharing](#layout-sharing).
- **assigned to a game**, from **More → Use for a game**. A profile assigned to a game is the one
  `MacroStore.profileFor(gameId)` returns while that game is what the app is connected to; the
  active profile is the general one and is what everything falls back to. Only one profile may hold
  a given game — assigning it moves it.

### Gestures

Every button has a tap action, and optionally:

| Gesture | What it does |
| --- | --- |
| Tap | The button's own action: type text, press a key, open an app or a tool, run a macro |
| Long press | Runs a saved macro |
| Double tap | Runs a saved macro |
| Toggle | Makes the tap hold its key down and release it on the next press |

Long press and double tap run **macros** rather than offering a second and third copy of the action
editor: a macro can already do anything a button can. Set them in the button dialog.

A toggling button dims while it is holding its key, because a toggle whose state you cannot see is
worse than no toggle. Anything held is released when the pad closes.

The double-tap delay only applies to buttons that actually have a double-tap binding — the rest fire
immediately, rather than every button on the pad waiting a quarter of a second for a gesture almost
none of them use.

**Extending this later.** Gestures are stored as a map of trigger id to macro id, not as a field per
gesture. A swipe or a gamepad button is one entry in `Macro.Trigger` and needs no change to the
stored format: an older build reading a layout that uses one finds a binding it does not recognise
and ignores it.

## Dashboard

**Dashboard** on the home grid is what the handheld knows about itself. Everything on it works with
no game, no server, no network and no permission the app did not already hold:

| Widget | Shows |
| --- | --- |
| Time | Clock and date |
| Battery | Percentage, whether it is charging and how, temperature |
| Network | Wi-Fi, Ethernet, mobile or offline, and this device's address |
| Storage | Free and used on shared storage |
| Memory | Free and total RAM, and whether the system considers it low |
| Device | Model and Android version |
| Stopwatch | Start/stop/reset, plus a countdown timer in minutes |

The stopwatch measures against `SystemClock.elapsedRealtime` and keeps its state in preferences, so
it survives the screen closing, the app being killed and the device sleeping — and it cannot be
thrown off by the wall clock changing.

Battery is read from the sticky `ACTION_BATTERY_CHANGED` broadcast; the network address comes from
the interface list rather than `WifiManager`, which keeps it clear of the location permission that
reading Wi-Fi details now requires and covers Ethernet docks as well.

Adding another widget is one object in `widgets/Widget.kt` and one entry in `Widget.ALL`. No layout
changes.

## Getting back

Every tool screen has a visible **Back** control that returns to whatever opened it — the home menu
for a tool opened from the grid, the list above for a nested editor. They all go through
`ui/Nav.kt`, so the behaviour is identical everywhere and there is one place to change it.

This is deliberate rather than relying on the system back gesture: on a handheld the gesture is easy
to miss, and on the second panel there may be no gesture area at all. The system back button still
does exactly what it did before.

## Touch feedback

Feedback goes through `ui/Feedback.kt` so it is the same everywhere. Haptics use
`View.performHapticFeedback`, which honours the system's touch-feedback setting, needs no VIBRATE
permission, and does nothing on a device with haptics turned off — the accessible answer rather than
the obvious one.

Status wording distinguishes **sent** from **succeeded**. A request that left the device is not a
request that worked, and only a protocol that answers earns a success state.

## Games

| Game | Mod required | Default port |
| --- | --- | --- |
| Stardew Valley | Ayn Dual Screen (SMAPI) | 27301 |
| Terraria | Ayn Dual Screen (tModLoader) | 27301 |
| Minecraft | Ayn Dual Screen (Forge 1.21.1) | 27302 |
| Fallout: New Vegas | Ayn Dual Screen (xNVSE) | 27303 |
| Other address | — | 80 |

Each game remembers **its own address**, so switching between them doesn't mean retyping. Stardew and
Terraria share a port because they're never running at once; the later two were given their own so
that four mods on one PC can't collide.

Fallout needs the game launched through `nvse_loader.exe`. Started as `FalloutNV.exe` the plugin
never loads, and the app will correctly report nothing listening. Tale of Two Wastelands works with
the same plugin and needs no separate entry.

Adding a game means one entry in `Game.kt` and two strings. The picker, the settings and the
detection all read from that list — no layout or logic changes.

### It works out which game is running

Pointing the app at the wrong mod would otherwise show up as a blank screen, so **Test connection**
identifies what's actually there rather than trusting the dropdown:

- If a save is loaded, `/state` identifies the game. The Minecraft and Fallout mods name themselves
  in a `game` field; the older two are recognised by fields they happen to have — Terraria's snapshot
  has a `worldName`, Stardew's a `season` and a `locationName`.
- At the main menu `/state` says nothing useful, so it falls back to which map endpoint exists: the
  Terraria mod routes `/minimap` and 404s `/map`, and the Stardew mod does the reverse.

**Fallout is the exception to the fallback.** The plugin serves only `/`, `/state` and `/action`, and
`/` answers for every mod — so there is no endpoint that would identify it rather than misidentify
something else. It is recognised from the snapshot once a save is loaded, and simply not guessed at
before then. That is why `Game.probePath` is allowed to be null while `isMod` stays true.

So the status line can distinguish all five cases: connected and in-game, connected but at the menu,
connected to *a different game*, something answering that isn't a mod at all, and nothing answering.

## Building

Needs the Android SDK. Gradle finds it via `local.properties` (`sdk.dir=...`) or the `ANDROID_HOME`
environment variable. `local.properties` is machine-specific and is not distributed.

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. A copy of the last build is checked in at the
project root as `AbacusDualScreenInterface-0.1.0-debug.apk` so it can be sideloaded without a build.

Install it on the Thor by copying the APK across and opening it, or over USB:

```bash
adb install -r AbacusDualScreenInterface-0.1.0-debug.apk
```

`minSdk` is 26 — `setLaunchDisplayId` arrived in Oreo, and without it the app has no reason to exist.

## Using it

1. On the PC, start the game with the mod. **LAN access is on by default** in the mods, so there is
   nothing to switch on — but if it was ever turned off, the mod listens on loopback only and the Thor
   cannot reach it.
2. In the app, tap **Connect → Find**. It sweeps the network and lists what answered.
3. Tap a result to open it, or **Save** to keep it as a named connection first.
4. From then on it is one tap: open the app, tap the connection. Set **Connect on launch** on the one
   you use most and it is no taps at all.

If you would rather type the address: **Connect → New**, enter host and port, tap **Test** to check
it before saving. `ipconfig` on the PC gives you the address.

> **LAN access means the second screen can rearrange and destroy your items.** Only turn it on for a
> network you trust.

## When it won't connect

Three things account for almost every failure, and the status line names which one you've hit.

### "localhost" is not your PC

The mods print `http://localhost:27301/` because that's the address to use **on the PC itself**. Typed
into this app it means *this Android device*, so it can never reach the game. The app now catches it
and says so rather than just failing.

Use the PC's network address instead. Both mods now print it at startup:

```
Second screen ready on this PC at http://localhost:27301/
From another device on your network: http://192.168.1.20:27301/
```

That second line is the one to type here. Otherwise `ipconfig` on the PC gives you the same thing.

### The mod is listening on loopback only

If LAN access is off, the mod binds `127.0.0.1` and nothing outside the PC can reach it — the game is
running, the port is simply closed to everyone else. That shows up as **"nothing is listening on that
port"**, and the fix is to enable LAN access in the mod and restart the game.

### A VPN is in the way

A VPN adapter routinely blocks or reroutes local traffic, so the handheld and the PC stop being able
to see each other even on the same Wi-Fi. The mods now detect an active VPN and warn about it at
startup. If the address looks right and still times out, disconnect the VPN and try again.

Windows Firewall is the other common cause of a timeout — the port needs to be allowed for **Private**
networks.

## Layout

```
Abacus Dual Screen Interface/
  app/src/main/java/com/abacus/dualscreen/
    HomeActivity.kt    the picker: game, address, display, test, open
    ScreenActivity.kt  the second screen itself — a full-bleed WebView, no chrome
    Game.kt            the game list; everything else is driven from it
    Probe.kt           works out what's listening at an address
    Settings.kt        per-game saved addresses
  app/src/main/res/    layouts, theme, launcher icon
```

## Credits

- **Abacus** — author.
- Shows pages served by the Ayn Dual Screen mods for Stardew Valley, Terraria, Minecraft and
  Fallout: New Vegas; it contains no
  game assets of its own.
- **Contains AI-assisted code**, written with an AI coding assistant and human-reviewed.

## Ideas worth adding next

Everything that used to be listed here — subnet scanning, remembering the display, reconnecting on its
own — is built. What is left:

- **A home-screen shortcut per saved connection**, so the second screen is one tap from the launcher
  with the app never appearing at all.
- **Discovery that does not have to sweep.** If the mods ever advertise themselves over mDNS, the scan
  becomes a listen, works across subnets, and stops looking like a port scanner to an access point.
- **Remembering where a session was scrolled** when it comes back from a reconnect.

## Known limitations

- **Discovery cannot see past a router**, and cannot find a server on a port it was not told to try.
  There is nothing to listen for; see [Finding servers on the network](#finding-servers-on-the-network).
- **The second display needs Android 8.0.** Launching onto a chosen display is
  `ActivityOptions.setLaunchDisplayId`, which arrived in Oreo. The `-PtestMinSdk` build flag lowers the
  minimum for testing the parts that have nothing to do with displays; a build made that way is not
  shippable.
- **HTTP only.** The mods serve plain HTTP on a LAN. Nothing here does TLS, and nothing should be
  pointed across the internet.
- **Import merges on host and port.** Two saved connections to the same address are treated as the same
  connection by an import, which is deliberate and means you cannot keep two profiles for one address
  that differ only in their display or orientation and import them separately.
