# Ayn Dual Screen — Stardew Valley

A [SMAPI](https://smapi.io/) mod that turns any second display into a 3DS-style bottom screen for
Stardew Valley: live map, live touch inventory, clock, date, season, weather, money, health and
energy. Plus an optional Android companion app for handhelds with a second panel.

**By Abacus.** Contains AI-assisted code, human-reviewed before release.

---

## What this repository contains

The project is two independent halves that talk over plain HTTP on your local network.

| Folder | What it is | Language / toolchain |
| --- | --- | --- |
| [`AynDualScreen/`](AynDualScreen) | The SMAPI mod. Runs inside Stardew Valley, serves the second-screen page and a JSON snapshot of the save over a local HTTP server. | C# / .NET 6, built with `dotnet build` |
| [`AynDualScreen/web/`](AynDualScreen/web) | The second-screen UI itself — a plain HTML/CSS/JS page with no build step and no dependencies. | HTML, CSS, vanilla JS |
| [`android/`](android) | The optional companion app: a fullscreen WebView shell around that page, which can launch itself onto a device's *second* display. | Kotlin / Gradle + Android SDK |

The Android app is **not required**. Any browser pointed at the mod's URL gives the same second
screen. The app exists only to remove browser chrome, keep the display awake, and use
`ActivityOptions.setLaunchDisplayId`, which a web page cannot call.

Detailed docs live in [`AynDualScreen/README.md`](AynDualScreen/README.md) (the mod, its HTTP
endpoints and config) and [`android/README.md`](android/README.md) (the app).

---

## Building the SMAPI mod

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

---

## Building the Android companion app (the APK)

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

### Build configuration, for cross-checking the submitted APK

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

The APK distributed on Nexus is a **debug build**, signed with Android's auto-generated per-machine
debug key. A rebuild on another machine is therefore signed with a different key and will not
install over it without uninstalling first.

---

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
the thing it connects to is a plain HTTP server on the user's own LAN, which Android blocks by
default from API 28. The only URL ever loaded is the one the user types into the setup screen.

## Security note on the mod itself

The mod's `AllowLanAccess` setting (default `true`) makes its HTTP server reachable from other
devices on the same network, and the second screen can move, drop and destroy in-game items. There
is no authentication yet, so it's intended for a home network. `config.json` has per-action
`Allow*` switches (`AllowTrash`, `AllowDrop`, `AllowInventoryEdit`, `AllowEat`) to make the screen
look-only, and `AllowLanAccess: false` restricts it to the PC running the game. This is documented
for users in [`AynDualScreen/README.md`](AynDualScreen/README.md#settings).

## Game assets

No Stardew Valley assets are redistributed. Every sprite shown on the second screen is cropped at
runtime from the tilesheets in the user's own installed copy of the game and served only to that
user's own screen.

## Licence

Source-available, **all rights reserved** — see [LICENSE.txt](LICENSE.txt). Publishing the source
here is for transparency and review; it does not grant permission to redistribute, reupload, port or
reuse the code. This is an independent, unofficial mod, not affiliated with ConcernedApe,
Chucklefish or the SMAPI project.
