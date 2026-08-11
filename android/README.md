# Abacus's Dual Screen Interface

An Android app for the AYN Thor's second screen. Pick a game, connect, and the second screen opens
fullscreen on the display you choose.

**By Abacus.**

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

- **The session ending returns you to the menu.** The WebView can't detect this on its own — once the
  page has loaded, the mod's server disappearing produces no navigation error at all, the page's own
  polling just starts failing quietly. So the app checks `/state` every 4 seconds out of band, and two
  consecutive misses mean the game has gone.
- **With *Reconnect automatically* on**, it retries first (backing off to 5s) and only drops to the menu
  after 8 failed attempts — a game being restarted comes back well inside that.
- **With it off**, it returns to the menu as soon as the session ends.
- **The &#8942; button, top-left**, opens **Quit to menu** and **Reload page** at any time. It's on the
  left because the mod's own settings cog is top-right; they do different jobs and shouldn't overlap.
  The system back gesture does the same thing, but on a secondary display there may not be one.

## Simple and Advanced

The mode switch at the top is remembered between launches.

**Simple** is the game picker and one button. It sweeps the network, works out which game is running,
takes that address and opens the second screen — no typing at all.

**Advanced** adds the address fields, the display picker, and three toggles:

| Toggle | Default | What it does |
| --- | --- | --- |
| Reconnect automatically | on | Keeps retrying when the game restarts, instead of parking on an error. Backs off to 5s after a few tries. |
| Never sleep while open | on | Holds the panel awake. Turn it off to let it dim normally. |
| Remember this display | on | Reopens on the same panel next time without asking. Stored per game. |

## Finding the PC

**Find my PC** sweeps your /24 on the configured port, 48 sockets at a time with a 350 ms timeout, and
identifies each hit. Results are tappable — taking one also switches the game picker to whatever that
address is actually running, so the two can't disagree.

## Games

| Game | Mod required | Default port |
| --- | --- | --- |
| Stardew Valley | Ayn Dual Screen (SMAPI) — `Desktop\stardew mod` | 27301 |
| Terraria | Ayn Dual Screen (tModLoader) — `Desktop\terraria mod` | 27301 |

Each game remembers **its own address**, so switching between them doesn't mean retyping. They
default to the same port because they're never running at once, but they may well be on different
machines, so nothing is shared between them.

Adding a third game later means adding one entry to `Game.kt` and two strings. The picker, the
settings and the detection all read from that list — no layout or logic changes.

### It works out which game is running

Both mods default to port 27301, so pointing the app at the wrong one is an easy mistake that would
otherwise show up as a blank screen. **Test connection** identifies what's actually there:

- If a save is loaded, `/state` says which game it is and names the world or farm — Terraria's
  snapshot has a `worldName`, Stardew's has a `season` and a `locationName`.
- At the main menu `/state` says nothing useful, so it falls back to which map endpoint exists: the
  Terraria mod routes `/minimap` and 404s `/map`, and the Stardew mod does the reverse.

So the status line can distinguish all five cases: connected and in-game, connected but at the menu,
connected to *the other game*, something answering that isn't a mod at all, and nothing answering.

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

1. On the PC, start the game with the mod. **LAN access is on by default** in both mods, so there's
   nothing to switch on — but if it was ever turned off, the mod only listens on loopback and the Thor
   can't reach it.
2. Find the PC's address with `ipconfig`.
3. In the app: pick the game, enter the address, tap **Test connection**.
4. Tap **Open second screen**. If the Thor reports more than one display, a picker appears and
   defaults to the second one.

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
- Shows pages served by the Ayn Dual Screen mods for Stardew Valley and Terraria; it contains no
  game assets of its own.
- **Contains AI-assisted code**, written with an AI coding assistant and human-reviewed.

## Ideas worth adding next

- Scan the local subnet for a listening mod, so the address never has to be typed.
- A home-screen shortcut per game that skips the picker entirely.
- Remember the chosen display per game and reopen there automatically.
- Reconnect on its own when the game restarts, instead of showing the error panel.
