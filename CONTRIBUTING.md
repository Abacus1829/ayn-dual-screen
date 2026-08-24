# Contributing

Contributions are welcome. That is the whole reason the licence changed.

## What you can do

- **Read it, change it, run your changes.** No permission needed.
- **Open an issue** for a bug, a crash log, or an idea.
- **Send a pull request.** Small and focused is easier to review than large and sweeping.

GitHub needs you to make your own copy of the repository before you can open a pull request. That copy
is fine — it is what the button is for. What it must not become is a second place people go to get this
project: don't advertise it as an alternative, and tidy it up once your change is merged or closed.

## What you cannot do

- **Upload builds anywhere.** Not to CurseForge, Modrinth, Nexus, the Steam Workshop, a launcher, or a
  file host. The official builds come from here. This is the one rule that keeps the project from being
  reuploaded under someone else's name.
- **Publish the source as your own** repository, download or mirror — modified or not. Changes are for
  your own use, or for sending back here.
- **Sell it**, gate it behind ads you profit from, or bundle it into anything paid.
- **Strip the attribution.**

Modpacks are fine — non-commercial, unmodified build, credit and a link. No need to ask.

See [`LICENSE.txt`](LICENSE.txt) for the actual terms. This is a **source-available** licence, not an
open-source one in the OSI sense, because it restricts redistribution. Please describe it that way.

## By sending a pull request

You keep the copyright in your own work. You are granting permission for it to be shipped as part of
this project, under this licence or a later version of it. You are confirming the code is yours to
give. Contributors are credited unless they ask not to be.

That is the whole agreement — there is no separate document to sign.

## Before you open a PR

Say what you tested. "Built and ran in a 1.21.1 world, walked around, map filled in correctly" tells a
reviewer far more than a green build. **None of these mods has an automated test suite**, so the only
verification that exists is someone actually playing it — which makes your account of what you tried
the most useful part of the PR.

## Building

Each project builds independently; they share no code and no build.

| Project | Build |
| --- | --- |
| Terraria | `dotnet build -c Release` in `AynDualScreen`. Needs tModLoader installed. |
| Stardew | `dotnet build -c Release` in `AynDualScreen`. Needs SMAPI. |
| Minecraft | `gradlew build` in `AynDualScreen`. First run downloads and decompiles Minecraft — several minutes. |
| Fallout: NV | `.\fetch-nvse.ps1` then `.\build.ps1` in `falloutnv`. Visual Studio 2022, **Win32** — the game is 32-bit. |
| Skyrim SE | `.\fetch-deps.ps1` then `.\build.ps1` in `skyrimse`. Visual Studio 2022, **x64**. The first build compiles CommonLibSSE and takes a while. |
| App | `gradlew assembleDebug`. Android SDK, API 34. |

The Minecraft mod targets **1.21.1 only**. It is version-selectable
(`-Pmc=… -Pforge=… -Prange=…`), but ForgeGradle 6 cannot set up newer Minecraft — it fails in MCP setup
with `duplicate entry: mcp/client/Start.class`. Reaching current Forge needs a migration to a newer
ForgeGradle or NeoForge's ModDevGradle. That is a genuinely useful contribution if you want one.

## The one rule that matters in the code

**Game state is only ever touched on the game thread.**

All of them work the same way: the game thread builds a finished JSON snapshot and publishes it to a
`volatile` field; the web server answers on other threads and only ever hands out strings that were
already finished; commands from the page go into a concurrent queue and are drained on the next tick.

Reading the game from a request thread will appear to work on your machine and corrupt somebody else's
save. If a change needs new data on the second screen, add it to the snapshot — do not reach for it
from the server.

## The one rule that matters when releasing

**Every GitHub release must carry all six assets, under exactly these names:**

```
AynDualScreen-Stardew.zip
AynDualScreen-Terraria.tmod
AynDualScreen-Minecraft-mc1.21.1.jar
AynDualScreen-FalloutNV.zip
AynDualScreen-SkyrimSE.zip
AynDualScreen-App.apk
```

The README's download links are `releases/latest/download/<name>`, which GitHub serves from whichever
release is newest. So a release that carries only the project you happened to change becomes "latest"
and **every download link in the README breaks at once** — including the four projects you didn't
touch. There is no warning; the links simply start returning 404.

This has happened. Publishing three Stardew-only releases in a row took down all five links until
somebody tried to download one.

Version numbers go in the release title and the notes, never in these filenames — a version-stamped
filename can't be linked to from a README that doesn't know the version yet. Keep an extra
version-stamped copy alongside if you want one, but the stable name has to be there too.

Re-uploading the unchanged four is cheap: pull them off the previous release with
`gh release download <previous-tag>` and upload them again with the new one.

## The second rule: the app updates itself from these releases

Since app 0.15.0 the Android app checks this repository on startup and offers to install what it
finds. That makes a release something a program reads as well as a person, and there are two things
it needs.

### Carry `AynDualScreen-App.json` whenever the APK changes

Write it from the built APK — never by hand, because every field in it is a fact about that file:

```powershell
cd android\tools
.\make-update-manifest.ps1 -Apk ..\app\build\outputs\apk\release\app-release.apk
```

It prints the version, size and SHA-256 it read, and writes a small JSON next to the APK. Upload it
under exactly that name:

```
AynDualScreen-App.json
```

With it there, the app knows the version, the versionCode and the digest outright. Without it, the
app falls back to reading the version out of the release title, which works but is guesswork — and
guesswork that fails *silently*, by simply never offering an update again.

### Name the app version in the release title

Keep writing titles the way they already are written:

```
Ayn Dual Screen — app 0.15.0, Stardew 0.4.1, Terraria 0.3.1, Minecraft 0.7.0, Fallout NV 0.1.0
```

The number **next to the word "app"** is what the fallback reads. Two things it deliberately does
not read:

- **The tag.** `v2026.08.19` is a perfectly good version number — it is just not the app's, and
  treating it as one would mean every release looks newer than every installed build, forever.
- **The first number in the title.** Older releases list Stardew first, and offering Stardew's
  version number as an app update would be worse than offering nothing.

A release that changes no app code needs neither: carrying the previous APK forward is fine, and the
app recognises the unchanged file by its digest and stays quiet even if the title still names an
older app version.

### Publishing a release, end to end

```powershell
# 1. Build the APK, and check the version is the one you mean.
cd android
.\gradlew assembleRelease

# 2. Write the update manifest from that exact file.
cd tools
.\make-update-manifest.ps1 -Apk ..\app\build\outputs\apk\release\app-release.apk

# 3. Collect the unchanged mod binaries from the previous release.
gh release download <previous-tag> --dir dist\carry

# 4. Publish, with every asset under its stable name.
gh release create <new-tag> --title "Ayn Dual Screen — app <version>, Stardew …" --notes-file notes.md `
    app-release.apk#AynDualScreen-App.apk `
    AynDualScreen-App.json `
    dist\carry\AynDualScreen-Stardew.zip `
    dist\carry\AynDualScreen-Terraria.tmod `
    dist\carry\AynDualScreen-Minecraft-mc1.21.1.jar `
    dist\carry\AynDualScreen-FalloutNV.zip
```

Then check it from the other end: open the app on a device running the *previous* version and let it
find the release. If it does not appear, the manifest or the title is wrong, and the six other
downloads on this page will not tell you.

### Signing, and why it is worth fixing

The app refuses to install an update signed with a different key than the copy already running —
Android would refuse it too, and this way the refusal comes with an explanation.

That check is only as strong as the key. **The releases so far are signed with the Android SDK's
debug key**, which is the same key on every machine with the SDK installed, so anybody can produce
an APK that passes it. Creating a real keystore and pointing `keystore.properties` at it makes
signature continuity mean something. Do it on a release where an uninstall is acceptable: changing
keys means existing installs cannot be updated in place and have to be removed first.

### Testing the updater without publishing anything

Build the same code labelled as an older release, install that, and let it find the real one:

```powershell
.\gradlew assembleDebug -PtestVersionCode=14 -PtestVersionName=0.13.0
```

Never publish a build made that way — it claims to be a version it is not.
