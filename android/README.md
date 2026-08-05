# Stardew Second Screen — companion app

The Android companion for the **Ayn Dual Screen** SMAPI mod. It's a kiosk-style shell around the
second-screen page the mod serves: full-bleed, no browser chrome, screen kept awake, and — the part
that matters on a dual-panel handheld — it can launch itself onto the *second* display.

> Made by **Abacus**. Contains AI-assisted code.

## It isn't only for Stardew

Nothing in here is Stardew-specific. The app is a WebView pointed at whatever host and port you type
in, so it's the second screen for any of the Ayn Dual Screen mods — including the
[Terraria (tModLoader) port](https://steamcommunity.com/sharedfiles/filedetails/?id=3778092427),
which serves the same kind of page on the same default port `27301`. Same APK, no rebuild, no
setting to change beyond the address.

The wording on screen still says "Stardew" in a few places, because this is the project it shipped
with. That's cosmetic text in `app/src/main/res/values/strings.xml` and doesn't limit what it can
connect to.

## Why an app instead of just a browser

You can absolutely point any browser at the mod and it works. The app exists because a browser on a
handheld gets three things wrong for this use case:

- It shows an address bar and system bars, eating space the inventory grid needs.
- It lets the screen sleep mid-game.
- It gives you no way to say "open this on the other display" — Android will do it, but only if an
  app asks via `ActivityOptions.setLaunchDisplayId`, which is exactly what the setup screen does.

## Installing it

A built APK is already here: **`StardewSecondScreen-0.1.0-debug.apk`**.

Copy it to the device and tap it (you'll need to allow installing from unknown sources), or install
over USB with debugging enabled:

```bash
adb install -r "StardewSecondScreen-0.1.0-debug.apk"
```

`adb` lives in the Android SDK's `platform-tools` folder.

## Rebuilding it

The Gradle wrapper is checked in, and the SDK path comes from `local.properties`, so from
this folder:

```bash
gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

You can also open this folder in [Android Studio](https://developer.android.com/studio)
(`File → Open`) and press Run.

`local.properties` holds the SDK path for this machine and is gitignored — on another machine, let
Android Studio regenerate it or set `ANDROID_HOME`.

### About the debug signature

This APK is signed with Android's auto-generated debug key. That's fine for your own device, but the
key is per-machine, so an APK rebuilt elsewhere won't install over this one without uninstalling
first. If you ever distribute the app, generate a real keystore and build `assembleRelease`.

## Using it

1. On the PC, set `"AllowLanAccess": true` in the mod's `config.json` and restart the game. Without
   this the mod only accepts connections from the PC itself and the phone will be refused.
2. Find the PC's address with `ipconfig` (the IPv4 address on your Wi-Fi adapter).
3. Open the app, type that address, leave the port at `27301`.
4. Hit **Test connection** first — it tells you whether the problem is the address, the mod, or the
   network, instead of leaving you staring at a blank screen.
5. Pick which display to use, then **Open second screen**.

The display picker only appears when Android reports more than one display, so on a single-screen
phone it stays out of the way.

Press Back to return to the setup screen.

## Security note

`AllowLanAccess` means anything on your network can reach the mod, and the second screen can move,
drop and destroy items. There's no authentication yet. Use it on your own network, not a shared or
public one. Adding a shared-secret token to the mod is on the ideas list in the main README.

## Layout

```
android/
  app/src/main/java/com/abacus/aynsecondscreen/
    SetupActivity.kt    address entry, reachability test, display picker
    ScreenActivity.kt   the fullscreen WebView
    Settings.kt         remembers the last address
  app/src/main/res/     layouts, theme, launcher icon
  app/build.gradle.kts  module config (minSdk 26 for the display API)
```
