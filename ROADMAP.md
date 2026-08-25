# AYN Dual Screen — Roadmap

**Goal:** a polished, stable, visually cohesive companion app, inspired by the
clean modern feel of Cocoon.

**Governing rule:** stability before features. Each phase should be genuinely
done — tested on the physical Thor — before the next one starts.

Device safety policy lives in `CLAUDE.md` and applies throughout.

---

## Phase 1 — Stability

Three bugs. All three are visible every time the app is opened, which is why
they come first.

### 1.1 Brightness panel opens audio settings

- **Symptom:** opening the brightness panel displays audio controls.
- **Fix:** trace the panel/action mapping; brightness button → brightness panel,
  brightness slider → brightness.
- **Verify:** audio controls still map correctly (this is almost certainly a
  swapped or copy-pasted reference, so check both directions).
- **Test:** touch input and physical controls, on the Thor.

### 1.2 Brightness icon uses the wrong sprite variant

- **Symptom:** the icon renders yellow.
- **Fix:** correct the sprite reference to the default blue variant.
- **Verify:** consistent across all themes and states.

Likely the same copy-paste as 1.1 — check whether both bugs share a root cause
before fixing them separately.

### 1.3 Theme changes only affect text

- **Symptom:** changing the color preference updates text but not the home menu
  or the rest of the UI.
- **Root cause to expect:** colors hardcoded per screen rather than resolved from
  a central source. This is structural, not a one-line fix, and it is the
  largest item in Phase 1.
- **Fix:** build (or consolidate into) a centralized theme system. No screen
  should override theme values locally.
- **Must cover:** home menu, backgrounds, panels, buttons, icons, accent colors,
  text, highlights, selected states.
- **Also:** apply immediately without restart; persist across restarts; add
  "Reset to Default".
- **Test:** every available theme, every screen.

Live preview is a Phase 8 item — do not build it now.

---

## Phase 2 — First-run experience

Boot animation, background initialization, and a permissions flow.

**Boot animation.** The logo is an abacus. Simple 2D/vector style, the abacus
rotates and the beads move. 2–3 seconds. Lightweight — it must not become the
reason startup feels slow.

The animation is functional, not decorative: initialization, settings loading,
and the update check all run behind it. Requirements:

- Never freeze the animation while background work runs.
- Nothing available → straight to home screen.
- Update available → let the animation finish naturally, then prompt.
- Initialization running long → subtle loading indicator, not a frozen frame.
- Optional setting to disable the animation.

**First-run flow:** boot animation → initialization → update check (if enabled)
→ permissions/setup → readiness confirmation → home screen.

**Permissions.** Explain each one before requesting it, and why it is needed.
Request only when actually required. Optional permissions must be skippable and
enableable later in settings. Show current status per permission, offer a button
into Android settings where appropriate, and handle permanent denial gracefully.

Evaluate honestly, and drop any that aren't truly needed:

| Permission | Needed for | Verdict |
|---|---|---|
| Notifications | Update alerts | ? |
| Storage | ? | ? |
| Install unknown apps | APK self-update (Phase 3) | likely required |
| Battery optimization exemption | Background functionality | ? |
| Accessibility | — | only if a real feature demands it |

Do not request unnecessary permissions.

---

## Phase 3 — GitHub auto-update

The point of this phase is to make every *later* phase easy to ship to the Thor.
That makes it worth doing properly and early.

**Check:** on startup during the boot animation, plus a manual "Check for
Updates" in settings. Compare installed version against the latest GitHub
release. No update → continue silently, never interrupt.

**Prompt** (after the animation finishes): current version, latest version,
release notes, and three actions — Update Now, Remind Me Later, Skip This
Version.

**Download:** correct APK, visible progress, retry on failure, cancel if
practical, validate the file before installing.

**Install:** guide the user through the Android "install unknown apps"
permission, then launch the normal package installer. Never bypass Android
security, never install silently with elevated privileges, never modify the
system.

**Handle:** no internet, GitHub API errors, invalid release data, failed
download, corrupt APK, permission denial, installation failure. Each with a
message that says what actually went wrong.

**Settings:** toggle automatic checks; optionally prepare for stable/beta
channels.

**Architecture:** keep it modular and not coupled to a single UI screen. Phase 7
will want to reuse this same mechanism for plugin updates.

---

## Phase 4 — Cheat System 2.0

Replace code entry with a touch interface.

**Game detection:** detect the running supported game, load its cheat profile
automatically, update when the game changes. Status indicator with three clear
states — game detected / no supported game / detection failed. When nothing is
detected, hide unsupported controls and show a friendly message; offer manual
selection as a fallback.

**Cheat UI:** touch-friendly and sized for the Thor. Organized by game, then by
category. Per-cheat enable/disable with descriptions. Search and favorites once
the list justifies them — not before.

**Mod compatibility:** every mod must work with cheats enabled *and* disabled,
disabling cheats must fully disable cheat functionality, and mods must not
conflict with cheats.

**Konami code:** no longer an unlock mechanism — the touch UI replaced that job.
It survives as a cosmetic easter egg only, in Phase 8.

Deferred to later: advanced cheat profiles, saveable configurations, per-game
compatibility metadata.

---

## Phase 5 — Existing mod compatibility

For every mod:

- [ ] Test with cheats disabled
- [ ] Test with cheats enabled
- [ ] Cheat UI does not break the mod
- [ ] Mod does not break cheat functionality
- [ ] Tested on the physical Thor
- [ ] Compatibility issues documented
- [ ] Dependencies recorded
- [ ] Migration notes for the plugin architecture

---

## Phase 6 — Plugin architecture (separate branch)

**Target:** adding support for a new game or mod means *creating a plugin*, not
*modifying the core app*.

**Split into:** core app / plugin API+SDK / individual plugins.

**Plugin needs:** a standard interface, metadata (name, version, compatible app
versions, dependencies, author), enable/disable, auto-discovery, compatibility
checks, clear error reporting.

**Stability:** a crashing plugin must not take the app down. Isolate failures,
disable incompatible plugins safely, log errors.

**Developer support:** plugin template, documentation, debugging tools.

**Order of work:** analyze the existing mod architecture → propose the plugin
architecture → identify migration risks → create the branch → implement
incrementally → ship a working example plugin.

**Migration:** the current mod system stays functional throughout. Migrate
gradually. Do not merge until stable and tested.

---

## Phase 7 — Plugin manager

A dashboard that makes the plugin system understandable to normal users:
installed plugins, enable/disable, versions, compatibility, dependencies,
errors, update checks, install and safe removal.

Health states: Healthy · Update available · Incompatible · Missing dependency ·
Disabled · Error

A plugin browser/store is a *later* idea, sourced from GitHub releases or a
controlled repository. Do not start it before the architecture is complete.

---

## Phase 8 — Visual polish

**Main goal:** less chaotic, less janky. Consistency over new features.

**Design system** — write it down as a small internal guide: color palette,
theme tokens, fonts and sizes, spacing, corner radius, button styles, icon
styles, animation duration, page transitions, panel styles.

**Consistency pass:** spacing, rounded corners, typography, icon sizing, button
styles, accent colors, animations, visual hierarchy.

**Home screen:** consider a dashboard — detected game, update status, plugin
status, quick access to cheats and mods, settings.

**Theme polish:** live preview, immediate refresh, verified coverage across all
screens and icons. (The centralized system itself was built in Phase 1; this is
the polish on top.)

**Micro-animations:** button press feedback, panel and page transitions, toggle
animations, status changes. Subtle. Do not overanimate.

**Konami code easter egg** — moved here from Phase 1. Works on desktop, not on
the Thor. Investigate physical button mapping, input events, timing, focus, and
the differences between keyboard and hardware controls. Add diagnostic logging.
Do not assume keyboard input exists. Low priority: nothing depends on it.

---

## Phase 9 — Future

- Backup and restore (settings, themes, cheat configs, plugin configuration)
  with manual backup, confirmed restore, and a clear explanation of what gets
  replaced.
- Advanced cheat compatibility: generic compatibility layer, value search,
  game-specific plugins, saveable profiles.
  **Constraints:** offline/single-player focus, no interference with online or
  anti-cheat-protected games, nothing built to bypass security or anti-cheat.
  Clearly identify unsupported games.
- Community plugins.
- Developer tools: debug logging, plugin diagnostics, input diagnostics, device
  capability info, version info, game detection diagnostics, exportable logs.
  These should diagnose issues on the Thor without requiring changes to the
  console.

---

## Release checklist

- [ ] Version number updated
- [ ] Release notes written
- [ ] Build tested
- [ ] Fresh install tested
- [ ] Update-over-existing-install tested
- [ ] Update system tested
- [ ] Installation failure behavior tested
- [ ] Permissions tested
- [ ] Theme switching tested
- [ ] Brightness controls tested
- [ ] Audio controls tested
- [ ] Cheat system tested
- [ ] Game detection tested
- [ ] Existing mods tested
- [ ] Plugin compatibility checked (if applicable)
- [ ] Physical AYN Thor tested
- [ ] No critical errors in logs

**GitHub release:** build final APK → tag the release → upload the APK → write
release notes → verify the app detects it → test downloading it on the Thor.

---

## Development log template

```
DATE:
Version:
Branch:

COMPLETED:
FILES MODIFIED:
BUGS FIXED:
FEATURES ADDED:
TESTS PERFORMED:
PHYSICAL DEVICE TESTS:
KNOWN ISSUES:
NEXT STEPS:
NOTES:
```
