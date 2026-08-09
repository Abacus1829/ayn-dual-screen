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
| App | `gradlew assembleDebug`. Android SDK, API 34. |

The Minecraft mod targets **1.21.1 only**. It is version-selectable
(`-Pmc=… -Pforge=… -Prange=…`), but ForgeGradle 6 cannot set up newer Minecraft — it fails in MCP setup
with `duplicate entry: mcp/client/Start.class`. Reaching current Forge needs a migration to a newer
ForgeGradle or NeoForge's ModDevGradle. That is a genuinely useful contribution if you want one.

## The one rule that matters in the code

**Game state is only ever touched on the game thread.**

All three mods work the same way: the game thread builds a finished JSON snapshot and publishes it to a
`volatile` field; the web server answers on other threads and only ever hands out strings that were
already finished; commands from the page go into a concurrent queue and are drained on the next tick.

Reading the game from a request thread will appear to work on your machine and corrupt somebody else's
save. If a change needs new data on the second screen, add it to the snapshot — do not reach for it
from the server.
