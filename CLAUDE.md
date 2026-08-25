# AYN Dual Screen — Project Instructions

Read this before doing anything. It applies to every session.

## What this project is

An Android companion app for the AYN Thor handheld, also known as
"AYN Thor Companion" / "Abacus's Dual Screen Interface". It is the second-screen
client for the AynDualScreen mod family (Stardew Valley via SMAPI, Terraria via
tModLoader; Minecraft is also supported). The app auto-finds the running game on
the local network and mirrors inventory, equipment, buffs, minimap, boss
checklist, crafting and NPC shops onto the handheld. No account, server, or
internet connection required.

Distributed as a sideloaded APK. Obfuscated with R8. All Rights Reserved,
personal use only.

## Physical device safety policy — non-negotiable

The AYN Thor is a **test target, not a modification target**.

Allowed without asking:
- Build, install, reinstall, and uninstall *this app*
- `adb logcat`, `adb shell dumpsys` and other read-only inspection
- Normal debugging and developer tooling
- Testing app functionality

**Stop and ask before** anything that touches the console at system level:
- Rooting, unlocking the bootloader, flashing firmware
- Modifying system partitions or replacing the OS
- Factory reset
- Changing device settings not required for app testing
- Deleting or modifying files unrelated to this app
- Installing any software other than this app and its explicit test dependencies

If an operation *could* modify the Thor outside this app, stop and explain what
you want to do and why before proceeding. When in doubt, ask.

## Core development principle

**Do not add complexity or new features until the existing ones are stable.**

This project has a long roadmap (see ROADMAP.md). It is deliberately ordered.
Resist the urge to jump ahead to the interesting architectural work — the plugin
system, the cheat engine — while Phase 1 bugs are still open. A polished plugin
API on top of a theme system that only recolors text is not progress.

## Working style

- Prefer fixing the root cause over patching the symptom. Three of the four
  known bugs are mapping/reference errors, and at least one (the theme system)
  is structural.
- Centralize rather than scatter. The theme bug exists because color values are
  hardcoded per-screen. Do not add a fifth place to hardcode a color.
- When a change touches device input or hardware behavior, say plainly in your
  summary that it needs verification on the physical Thor. Do not report
  hardware behavior as verified when it was only tested on desktop or emulator.
- Summarize modified files at the end of every substantial task.

## Repository layout

This repository is not only the app. It holds the app and the five game mods it
talks to, and a change to one of them is unrelated to the others:

```
android/     the companion app — everything below refers to this
stardew/     SMAPI mod (C#, .NET 6)
terraria/    tModLoader mod
minecraft/   Forge mod
falloutnv/   xNVSE plugin
skyrimse/    SKSE64 plugin (unreleased)
dist/latest/ the release assets
```

Release rules for the whole repository live in `CONTRIBUTING.md` and matter more
than they look: every GitHub release must carry **all** the mod assets under
their stable filenames, because the README links to
`releases/latest/download/<name>`. A release carrying only the thing you changed
breaks every download link at once.

## Build and test commands

Run from `android/`. **`ANDROID_HOME` must be set** — there is deliberately no
`local.properties` in the repository, because it would carry a real user path:

```bash
export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"   # adjust to your machine
```

| Task | Command | Verified |
|---|---|---|
| Build debug APK | `./gradlew assembleDebug` | yes — `app/build/outputs/apk/debug/app-debug.apk` |
| Install to attached device | `./gradlew installDebug` | not run (no device attached) |
| Unit tests | `./gradlew test` | yes — 8 test classes, all passing |
| Lint | `./gradlew lint` | yes — clean, report at `app/build/reports/lint-results-debug.html` |
| Confirm the Thor is attached | `adb devices` | yes — currently **nothing attached** |

Release builds are shipped with a version override rather than by editing the
build file:

```bash
./gradlew assembleRelease -PtestVersionName=0.21.0 -PtestVersionCode=22
```

### Logs

There is no single app-wide tag; each subsystem owns one. Watch the relevant
ones rather than the whole log:

```bash
adb logcat -s AynStream:V AynFtp:V AynTheme:V Update:V MacroRunner:V Scribble:V
```

`AynTheme` is the one to watch for Phase 1 work.

## Project facts

| | |
|---|---|
| Package / namespace | `com.abacus.dualscreen` |
| Version | `0.20.0` (versionCode 21) |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| UI framework | **Views + viewBinding.** No Compose anywhere. |
| Language | Kotlin, no coroutines — background work is `Thread {}` plus `runOnUiThread` |
| Release build | R8 minify + resource shrinking, signed with the SDK debug key |

Signing is worth knowing about: shipped APKs use the **debug** key, so the
updater's signature check proves continuity between builds, not origin.

### Where things live

```
android/app/src/main/java/com/abacus/dualscreen/
  *.kt            one file per screen — HomeActivity, ControlsActivity, …
  Appearance.kt   accent, font, corners, icon set; the view-tree repainter
  Settings.kt     the persisted preference object
  theme/          console skins: ConsoleTheme (data), ConsoleSkin (drawables), ThemeStore
  ui/             the design system — Ui, Motion, Feedback, Focus, Nav, Sounds
  boot/           the abacus boot animation
  update/         the GitHub updater (Phase 3, already built)
  codes/ connect/ macro/ notes/ scribble/ setup/ stream/ widgets/
```

Layouts are in `app/src/main/res/layout/`, one `activity_*.xml` per screen, and
almost all of them are colour-free by design — see the theme notes below.

## How colour reaches a screen

Worth reading before touching anything in Phase 1, because there are **two**
systems and they overlap on exactly one screen.

**1. Appearance** (`Appearance.kt`) — accent, font family, font scale, corner
radius, icon set, background. Every activity calls the same line in `onResume`:

```kotlin
Appearance.apply(this, binding.root, settings, binding.backgroundImage)
```

`apply` walks the whole view tree and repaints each view according to its
`android:tag`: `accent`, `accentFill`, `accentEdge`, `card`, or `plain` for a
view that styles itself. That tag convention is why the layouts contain almost
no colours — a screen declares what a view *is*, and one function decides what
that looks like. **Do not add a colour to a layout; add a tag.**

**2. Console skins** (`theme/`) — a whole handheld look: background gradient,
tile faces, status bar, tray, glyph colour, tile scale. `ConsoleTheme` is a data
class, `ConsoleSkin` turns it into drawables, `ThemeStore` loads and persists it.
Skins are JSON, so a user file in `/sdcard/AynDualScreen/themes/` is as valid as
a built-in one.

**Where they meet:** `HomeActivity` paints its tiles one way or the other —

```kotlin
background = if (skinned) ConsoleSkin.tileFace(this, skin)
             else Appearance.tile(this, settings, accent, tool.available)
```

So with a console skin active, the skin's palette wins on the home screen and the
accent does not move the tiles. That is currently deliberate. It is also
indistinguishable, from the sofa, from "the theme only changes text" — which is
worth confirming before treating either as a bug.

## Current status

Phase 1 (stability). ROADMAP.md describes the state before the 0.19–0.20
releases; the code has moved since, so check before starting an item.

- **1.1 brightness panel opens audio settings** — believed fixed in 0.20.0
  (`ControlsActivity.showRequestedPanel`). **Not verified on the Thor.**
- **1.2 brightness icon renders yellow** — believed fixed in 0.20.0
  (`Appearance.EMOJI_RISK` + U+FE0E). **Not verified on the Thor.**
- **1.3 theme changes only affect text** — the structural fix the roadmap asks
  for is already in place; see the section above. What remains is deciding what a
  console skin should do with the accent. **Open.**

Phases 2 (boot animation, first-run, permissions) and 3 (GitHub updater) were
built ahead of the roadmap's order and shipped in 0.14.0–0.20.0.

The Konami code has been reclassified: it is a cosmetic easter egg, not a
critical unlock mechanism, and has moved to Phase 8. Do not spend hardware
debugging time on it during Phase 1.

## A note on verification

No session so far has reached the physical Thor. USB never enumerated
(`VID_0000&PID_0002`) and wireless debugging was never paired. Everything
described as fixed above was verified by reading, building, and unit tests only.
Say so plainly rather than reporting hardware behaviour as confirmed.
