# Ayn Dual Screen

A second-screen companion for handhelds with two panels. Built for the **AYN Thor**, but the second
screen can be any device with a browser — a phone, a tablet, or a second monitor.

The idea is simple: a small mod runs inside a game and serves a live, touchable UI over your local
network — map, inventory, clock, status. Anything with a browser can display it. The Android app
displays it better: fullscreen with no browser chrome, the panel kept awake, and — the part a web
page cannot do for itself — it can launch onto the device's *second* display.

**By Abacus.** Contains AI-assisted code, human-reviewed before release.

---

## Download

Grab the file for your game from the
**[latest release](https://github.com/Abacus1829/ayn-dual-screen/releases/latest)**, or straight
from here:

| Game | Download | Also on | Source | Port |
| --- | --- | --- | --- | --- |
| **Stardew Valley** (SMAPI) | [`.zip`](https://github.com/Abacus1829/ayn-dual-screen/releases/latest/download/AynDualScreen-Stardew.zip) | [Nexus Mods](https://www.nexusmods.com/stardewvalley/mods/49903) | [`stardew/`](stardew) | 27301 |
| **Terraria** (tModLoader) | [`.tmod`](https://github.com/Abacus1829/ayn-dual-screen/releases/latest/download/AynDualScreen-Terraria.tmod) | [Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3778092427) | [`terraria/`](terraria) | 27301 |
| **Minecraft** (Forge 1.21.1) | [`.jar`](https://github.com/Abacus1829/ayn-dual-screen/releases/latest/download/AynDualScreen-Minecraft-mc1.21.1.jar) | — | [`minecraft/`](minecraft) | 27302 |
| The Android app *(optional)* | [`.apk`](https://github.com/Abacus1829/ayn-dual-screen/releases/latest/download/AynDualScreen-App.apk) | — | [`android/`](android) | — |

Install steps for each are in the [release notes](https://github.com/Abacus1829/ayn-dual-screen/releases/latest).
Every file there is built from the source in this repository.

The app is **optional** — any browser pointed at the address the mod prints gives you the same second
screen.

Minecraft uses 27302 rather than 27301 deliberately: it and Terraria are likely to be installed on
the same PC, and two mods fighting over one port fails in a way that looks like the app's fault.

---

## The app is not tied to any one game

The companion app has no game-specific code in it at all. It is a WebView pointed at a host and port
you type into its setup screen. There is no game detection, no bundled list of supported titles, no
hardcoded address and no remote endpoint anywhere in it — it does not know, and does not need to
know, what is answering on the other end.

That is why the same build serves every mod without a per-game version, and why it works just as
well against anything else on your network that serves a page worth putting on a second screen.
Each game needs its own mod because each one has to read that game's state; the app does not.

Its on-screen wording still names Stardew in a couple of places (see
[`strings.xml`](android/app/src/main/res/values/strings.xml)), because that is the project it first
shipped alongside. That text is cosmetic. It does not limit what the app can connect to.

---

## What this repository contains

| Folder | What it is | Language / toolchain |
| --- | --- | --- |
| [`android/`](android) | The companion app: a fullscreen WebView shell that can launch itself onto a device's *second* display. | Kotlin / Gradle + Android SDK |
| [`stardew/`](stardew) | The **Stardew Valley** mod (SMAPI). Snapshots the save to JSON every tick and serves it, with the touch commands applied back on the game thread. | C# / .NET 6 |
| [`terraria/`](terraria) | The **Terraria** mod (tModLoader). Same architecture; the minimap is rendered to a PNG rather than shipped as a tile grid, because a Terraria world is millions of tiles. | C# / .NET 8 |
| [`minecraft/`](minecraft) | The **Minecraft** mod (Forge, 1.21.1). Client-side only — nothing to install on a server, since it reads only what your own client already knows. | Java 21 / Gradle |
| each mod's `web/` | The second-screen page that mod serves — plain HTML/CSS/JS, no build step, no dependencies. | HTML, CSS, vanilla JS |

Each mod is self-contained: they share no files, no build and no output, and none needs the others
to be present. Ideas were carried across by hand.

Detailed docs live in [`android/README.md`](android/README.md) (the app) and in each mod's own
README — [`stardew/`](stardew/README.md), [`terraria/`](terraria/README.md),
[`minecraft/`](minecraft/README.md) — covering its HTTP endpoints and config.

---

## Building the app (the APK)

**Requirements**

- JDK 17.
- Android SDK with platform **API 34** and build-tools installed — easiest via
  [Android Studio](https://developer.android.com/studio).
- Gradle is **not** needed separately; the wrapper is checked in and fetches Gradle 8.7 itself.

**Point the build at your SDK.** `android/local.properties` is machine-specific and deliberately not
committed. Either let Android Studio generate it on first open, set the `ANDROID_HOME` environment
variable, or create the file by hand:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

**Build**

```bash
cd android
./gradlew assembleDebug
```

On Windows use `gradlew.bat assembleDebug`. The APK lands at
`app/build/outputs/apk/debug/app-debug.apk`.

Or open the `android/` folder in Android Studio (`File → Open`) and press Run.

For a release build, `./gradlew assembleRelease` — note that the release build type has
`isMinifyEnabled = false`, so **the shipped code is not obfuscated or shrunk** and decompiles
one-to-one against the sources here.

### Build configuration, for cross-checking a distributed APK

From [`android/app/build.gradle.kts`](android/app/build.gradle.kts):

| | |
| --- | --- |
| Application ID | `com.abacus.aynsecondscreen` |
| versionName / versionCode | `0.1.0` / `1` |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| Gradle | 8.7 (via wrapper) |
| Java / JVM target | 17 |
| Minify / ProGuard | disabled |

Dependencies, all from Google's standard AndroidX repositories and nothing else:
`androidx.core:core-ktx:1.13.1`, `androidx.appcompat:appcompat:1.7.0`,
`androidx.activity:activity-ktx:1.9.0`.

The APK in [`android/`](android) is a **debug build**, signed with Android's auto-generated
per-machine debug key. A rebuild on another machine is therefore signed with a different key and
will not install over it without uninstalling first.

## What the app does, and what it doesn't

The entire app is three Kotlin files, ~350 lines total, in
[`android/app/src/main/java/com/abacus/aynsecondscreen/`](android/app/src/main/java/com/abacus/aynsecondscreen):

| File | Role |
| --- | --- |
| `SetupActivity.kt` | Address/port entry, a reachability test, and a display picker. |
| `ScreenActivity.kt` | A fullscreen `WebView` pointed at `http://<address>:<port>/`. |
| `Settings.kt` | Remembers the last address in `SharedPreferences`. |

It requests exactly two permissions, both in
[`AndroidManifest.xml`](android/app/src/main/res/AndroidManifest.xml):

- `INTERNET` — to reach the mod's HTTP server on the local network.
- `ACCESS_NETWORK_STATE` — to tell "no network" apart from "wrong address" in the connection test.

No storage, camera, microphone, location, contacts or background-service permissions. No analytics
SDK, no ad SDK, no crash reporter, no telemetry, no auto-update mechanism, and no third-party
dependency beyond the three AndroidX libraries above.

**Cleartext HTTP is enabled**
([`network_security_config.xml`](android/app/src/main/res/xml/network_security_config.xml)) because
the thing it connects to is a plain HTTP server on your own LAN, which Android blocks by default
from API 28. The only URL ever loaded is the one typed into the setup screen.

---

## Building the game mods

Each one uses its own ecosystem's standard toolchain and nothing else — no build scripts of our own,
no code generation. Each resolves its game's assemblies from your local install, so the game has to
be installed to compile against.

### Stardew Valley — [`stardew/`](stardew)

**Requirements:** [.NET SDK 6.0 or newer](https://dotnet.microsoft.com/download) (developed against
8.0.403; the project targets `net6.0`), and Stardew Valley with [SMAPI 4.0.0+](https://smapi.io/).

```bash
cd stardew
dotnet build
```

The only NuGet dependencies are declared in
[`AynDualScreen.csproj`](stardew/AynDualScreen.csproj):

- `Pathoschild.Stardew.ModBuildConfig` 4.3.2 — the standard SMAPI mod build package. It locates the
  game folder, references the game assemblies, and **copies the built mod into the game's `Mods`
  folder** as part of the build. That copy is the package's normal behaviour, not something this
  project adds.
- `Newtonsoft.Json` 13.0.3, `ExcludeAssets="runtime"` — compile-time only, because SMAPI already
  loads Newtonsoft at runtime.

Output: `AynDualScreen.dll` plus `manifest.json` and the `web/` folder, landing in
`bin/Debug/net6.0/` and in `Mods/AynDualScreen/` in the game directory.

To build without installing to the game folder, or if the game isn't on this machine:

```bash
dotnet build -p:GamePath="C:\Program Files (x86)\Steam\steamapps\common\Stardew Valley" -p:EnableModDeploy=false
```

### Terraria — [`terraria/`](terraria)

**Requirements:** the .NET SDK, plus Terraria with
[tModLoader](https://github.com/tModLoader/tModLoader) on the **1.4.4** branch. The target framework
is `net8.0`, set by tModLoader's own `tMLMod.targets` rather than by this project — the csproj below
declares no `TargetFramework` of its own.

```bash
cd terraria
dotnet build
```

This project deliberately sits outside tModLoader's `ModSources` folder, so
[`AynDualScreen.csproj`](terraria/AynDualScreen.csproj) imports `tMLMod.targets` from the game
install directly rather than via a relative path. If tModLoader isn't in the default Steam location,
set the `tMLSteamPath` property or environment variable.

The trade-off is that tModLoader's in-game **Workshop → Develop Mods** screen won't list it, since
that only scans `ModSources`. Build from the command line instead — the packaged `.tmod` still lands
in the `Mods` folder, so the game picks it up normally.

Version and packaging metadata come from [`build.txt`](terraria/build.txt), which is tModLoader's
own format.

### Minecraft — [`minecraft/`](minecraft)

**Requirements:** JDK 21 (Forge for 1.21.1 needs it anyway). Gradle is not needed separately — the
wrapper is checked in.

```bash
cd minecraft
./gradlew build
```

The jar lands in `build/libs/`. The first build is slow and needs network: ForgeGradle downloads and
decompiles Minecraft itself before it can compile anything against it.

Build targets are pinned in [`gradle.properties`](minecraft/gradle.properties) — Minecraft 1.21.1,
Forge 52.1.0 — rather than left open-ended, so this source rebuilds into the same jar later. They
can be overridden per invocation:

```bash
./gradlew build -Pmc=1.21.11 -Pforge=61.1.14 -Prange="[1.21.9,1.22)"
```

That is a real limit rather than an oversight: **this builds for 1.21.1 only.** ForgeGradle 6 fails
during MCP setup on newer Minecraft, so reaching current Forge needs a migration to a newer
ForgeGradle or to NeoForge's ModDevGradle. [`buildAll.ps1`](minecraft/buildAll.ps1) walks the
targets that are actually known to compile.

## Security note on the mods

A mod's `AllowLanAccess` setting (default `true`) makes its HTTP server reachable from other devices
on the same network, and the second screen can move, drop and destroy in-game items. There is no
authentication yet, so it's intended for a home network. Each mod's config carries per-action
`Allow*` switches to make the screen look-only, and `AllowLanAccess: false` restricts it to the PC
running the game. They are documented in each mod's own README.

The later mods ship tighter defaults. Terraria has `AllowDrop`, `AllowInventoryEdit` and
`AllowShopping` **off** until you turn them on, since shopping spends real coins. Minecraft's
`[control]` section allows hotbar selection and crafting but leaves `allowChat` and `allowDrop`
**off** — the two that can lose you something, or speak as you.

The Minecraft mod is **client-side only**: nothing is installed on a server, and it reads only what
your own client already knows, so it works in single-player, on a LAN world, or on any server you
join. Its crafting goes through the same packets the vanilla recipe book uses, so the server still
does the moving and applies its own rules.

## Game assets

No game assets are redistributed by anything here. Every sprite shown on the second screen is
cropped at runtime from your own installed copy of the game and served only to your own screen.

## Licence

Source-available under the Source-Available Licence v1.0 — see [LICENSE.txt](LICENSE.txt). Publishing the source
here is for transparency and review; it does not grant permission to redistribute, reupload, port or
reuse the code.

These are independent, unofficial projects, not affiliated with or endorsed by any game's developer
or publisher, or by the SMAPI or tModLoader projects.
