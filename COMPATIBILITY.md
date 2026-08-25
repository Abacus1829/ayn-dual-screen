# Compatibility

What each mod implements, what the app does when it does not, and what has actually been verified.

Kept because the app and the mods ship separately and update independently: somebody running last
month's Stardew mod against this week's app should be able to look up what will happen rather than
discover it. It is also the inventory the future plugin architecture has to preserve — every row
here is a contract something already depends on.

## The protocol

Every mod serves a small HTTP server on the local network. Three things matter to the app:

| Path | Who must serve it | What the app does without it |
| --- | --- | --- |
| `GET /state` | everyone | The connection is reported as "answered, but not one of the mods". Detection cannot identify the game. |
| `POST /action` | everyone | Dashboard buttons and macros that target the game stop working. The second screen itself is unaffected. |
| `GET /codes`, `POST /code` | optional | Game codes show "nothing available" for that game. Everything else works normally. |

`/state` is the one that must exist. Everything else in this table is a feature that degrades on its
own rather than taking the connection with it.

## What each mod serves

| Mod | Version | Needs | Endpoints | Game codes |
| --- | --- | --- | --- | --- |
| **Stardew Valley** | 0.5.0 | SMAPI 4.0.0+ | `/state` `/action` `/map` `/worldmap` `/villagers` `/community` `/farm` `/calendar` `/codes` `/code` | **Yes** — off by default (`EnableGameCodes`) |
| **Terraria** | 0.3.1 | tModLoader, client-side | `/state` `/action` `/minimap` `/craftable` `/progress` `/talk` | No |
| **Minecraft** | 0.7.0 | Forge 1.21.1 | `/state` `/action` `/map` `/tile` `/icon` `/head` `/recipes` `/effect` | No |
| **Fallout: New Vegas** | 0.1.0 | xNVSE, launched via `nvse_loader.exe` | `/state` `/action` `/config` | No |
| **Skyrim Special Edition** | unreleased | SKSE64 | `/state` `/action` `/config` | No |

A mod that serves no `/codes` returns 404, `CodeClient` reads that as an empty catalogue, and the
codes screen says nothing is available. That is the same answer it gives for a mod with the feature
switched off, which is deliberate: neither case is an error, and neither should look like one.

## Game codes, on and off

Only the Stardew mod implements them, and it is the reference for how a mod should:

- **Off is total.** With `EnableGameCodes` false, `/codes` and `/code` return 404 exactly as any
  unknown path does. A client cannot tell the mod from a build compiled without the feature, which
  is what lets somebody keep their install strictly local.
- **The switch is re-checked when the code runs**, not only when the request arrives. Codes are
  queued for the game thread — Stardew is not thread-safe — so a code in flight when the feature is
  switched off must not still land. `GameCodes.Apply` checks again before doing anything.
- **Unavailable is described, not hidden.** With no save loaded, codes are still listed and each
  carries `blocked: "Load a save first"`, so the app greys them with a reason instead of failing
  when they are pressed.
- **The app asks nothing when the user has switched codes off.** `CodeClient` checks its own
  settings before opening a socket, so "off" in the app means no request is made — not one made and
  ignored.

Both ends can therefore be off independently, and either being off is sufficient.

## Verified how

Be clear about this, because the difference matters:

**Verified by reading the code** (this session):

- Stardew's `/codes` and `/code` 404 when the config switch is off — `ModEntry.cs`.
- `GameCodes.Apply` re-checks the switch after dequeuing — `GameCodes.cs`.
- The other four mods serve no code endpoints, so the app's empty-catalogue path is what they get.
- `CodeClient` makes no request when the feature is off in the app.
- The app's detection reads `/state`, which every mod serves, so a mod without codes is still
  detected and simply has nothing to offer.

**Not verified — needs a game and a device:**

- Any code actually applying in Stardew with a save loaded.
- Behaviour when the switch is flipped while the app is on the codes screen.
- Whether a mod's own UI misbehaves while the codes screen is open (nothing in the protocol suggests
  it would; the endpoints are independent).
- Everything on the physical AYN Thor. No device has been reachable while this was written.

## Migrating to plugins

When the plugin architecture arrives, these rows are the contract to keep. A plugin should be able
to declare which of the paths above it implements, and the app should degrade for a missing one
exactly as it does today — the point of the table is that every feature already fails softly and on
its own.

The one asymmetry worth fixing at that point: only Stardew implements codes, so the code catalogue
format has been exercised by exactly one implementation. A second one will find whatever is
accidentally Stardew-shaped about it.
