# Ayn Dual Screen

A second-screen companion for handhelds with two panels. Built for the **AYN Thor**, but the second
screen can be any device with a browser — a phone, a tablet, or a second monitor.

The idea is simple: a small mod runs inside a game and serves a live, touchable UI over your local
network — map, inventory, clock, status. Anything with a browser can display it. The Android app
displays it better: fullscreen with no browser chrome, the panel kept awake, and — the part a web
page cannot do for itself — it can launch onto the device's *second* display.

**By Abacus.** Contains AI-assisted code, human-reviewed before release.

---

## The app is not tied to any one game

The companion app has no game-specific code in it at all. It is a WebView pointed at a host and port
you type into its setup screen. There is no game detection, no bundled list of supported titles, no
hardcoded address and no remote endpoint anywhere in it — it does not know, and does not need to
know, what is answering on the other end.

That is why the same build serves every mod in the series without a per-game version, and why it
works just as well against anything else on your network that serves a page worth putting on a
second screen.

| Game | Get the mod | Mod source |
| --- | --- | --- |
| **Stardew Valley** (SMAPI) | [Nexus Mods](https://www.nexusmods.com/stardewvalley/mods/49903) | in this repository, [`AynDualScreen/`](AynDualScreen) |
| **Terraria** (tModLoader) | [Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3778092427) | a separate project, not in this repository |

Each game needs its own mod because each one has to read that game's state. The app does not.

The app's on-screen wording still names Stardew in a couple of places (see
[`strings.xml`](android/app/src/main/res/values/strings.xml)), because that is the project it first
shipped alongside. That text is cosmetic. It does not limit what the app can connect to.

---

## What this repository contains

| Folder | What it is | Language / toolchain |
| --- | --- | --- |
| [`android/`](android) | The companion app: a fullscreen WebView shell that can launch itself onto a device's *second* display. | Kotlin / Gradle + Android SDK |
| [`AynDualScreen/`](AynDualScreen) | The Stardew Valley mod — the one game mod whose source lives here. Serves the second-screen page and a JSON snapshot of the save over a local HTTP server. | C# / .NET 6, built with `dotnet build` |
| [`AynDualScreen/web/`](AynDualScreen/web) | The second-screen UI itself — a plain HTML/CSS/JS page with no build step and no dependencies. | HTML, CSS, vanilla JS |

The app is **optional**. Any browser pointed at a mod's URL gives the same second screen.

Detailed docs live in [`android/README.md`](android/README.md) (the app) and
[`AynDualScreen/README.md`](AynDualScreen/README.md) (the Stardew mod, its HTTP endpoints and
config).

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

## Building the game mod

The mod in this repository is the Stardew Valley one.

**Requirements**

- [.NET SDK 6.0 or newer](https://dotnet.microsoft.com/download) (developed against 8.0.403; the
  project targets `net6.0`).
- Stardew Valley installed, with [SMAPI 4.0.0+](https://smapi.io/) — the build resolves the game's
  assemblies from the local install.

**Build**

```bash
cd AynDualScreen
dotnet build
```

That's the whole process. There is no build script, no code generation and no post-build tooling of
our own.

The only NuGet dependencies are declared in
[`AynDualScreen.csproj`](AynDualScreen/AynDualScreen.csproj):

- `Pathoschild.Stardew.ModBuildConfig` 4.3.2 — the standard SMAPI mod build package. It locates the
  game folder, references the game assemblies, and **copies the built mod into the game's `Mods`
  folder** as part of the build. That copy is the package's normal behaviour, not something this
  project adds.
- `Newtonsoft.Json` 13.0.3, `ExcludeAssets="runtime"` — compile-time only, because SMAPI already
  loads Newtonsoft at runtime.

Output: `AynDualScreen.dll` plus `manifest.json` and the `web/` folder, landing in
`bin/Debug/net6.0/` and in `Mods/AynDualScreen/` in the game directory.

To build without installing to the game folder, or if the game isn't on this machine, pass the game
path explicitly:

```bash
dotnet build -p:GamePath="C:\Program Files (x86)\Steam\steamapps\common\Stardew Valley" -p:EnableModDeploy=false
```

## Security note on the mods

A mod's `AllowLanAccess` setting (default `true`) makes its HTTP server reachable from other devices
on the same network, and the second screen can move, drop and destroy in-game items. There is no
authentication yet, so it's intended for a home network. Each mod's config carries per-action
`Allow*` switches to make the screen look-only, and `AllowLanAccess: false` restricts it to the PC
running the game. The Stardew mod's are documented in
[`AynDualScreen/README.md`](AynDualScreen/README.md#settings).

## Game assets

No game assets are redistributed by anything here. Every sprite shown on the second screen is
cropped at runtime from your own installed copy of the game and served only to your own screen.

## Licence

Source-available, **all rights reserved** — see [LICENSE.txt](LICENSE.txt). Publishing the source
here is for transparency and review; it does not grant permission to redistribute, reupload, port or
reuse the code.

These are independent, unofficial projects, not affiliated with or endorsed by any game's developer
or publisher, or by the SMAPI or tModLoader projects.
