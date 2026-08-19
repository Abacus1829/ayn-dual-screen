# Changelog

Notable changes to the Android app. Newest first.

Entries before 0.9.0 are reconstructed from the repository history — no changelog was kept at the
time — so they say what the commits show and no more.

---

## 0.9.0 — 2026-08-18

The largest release so far. The app stopped being a connect screen with tools bolted on and became a
handheld companion that happens to connect to things.

### Saved connections

- **Connection profiles.** Name, host, port, which screen it opens on, orientation, screen-awake
  policy and connect-on-launch. Create, edit, duplicate, delete, connect, and set a default. Stored
  as a JSON array in preferences — no database, because the whole dataset is a handful of records.
- **A profile is not tied to a game.** The mod presets still supply default ports and are what the
  probe matches when identifying what answered, but a connection pointing at something nobody has
  written a mod for works exactly as well.
- **Addresses from older builds are migrated into profiles on first run.** An empty list after an
  update reads as lost settings whether or not anything was lost.
- **Connection history.** Deduplicated on host and port, newest first, one tap to reconnect, long
  press to keep as a saved connection, and a Clear button.
- **Import and export.** JSON written to `AynDualScreen/profiles.json`, which this app's own FTP
  server already serves, or handed to another app through the share sheet. Malformed entries are
  skipped rather than fatal, and re-importing your own export is a no-op instead of a doubling.

### Finding servers

- **Network scan across several ports**, showing host, port, and whether what answered is a
  companion server with a world loaded, one at its menu, or an open port that is something else.
  Connect from a result, or save it as a profile.
- The ports tried by default are the mod defaults plus every port a saved connection uses, so an
  unusual port is found on the second scan without typing anything.

### Displays

- **Display choices are stored as intent — "second screen", "external", "automatic" — never as a
  display id.** Ids are handed out by the system and change when a dock is unplugged or the device
  reboots, so a profile that remembered "display 2" would be pointing at nothing by tomorrow.
- **Graceful fallback.** A connection wanting a screen that is not there opens on the main display
  rather than failing, and the target is re-checked between resolving it and launching.
- **A session survives its own display disappearing.** Switch the lower panel off or pull a dock
  mid-session and the page reappears on the main display instead of being silently torn down.
- **Ask every time**, when you genuinely swap between screens.

### Sessions

- **A connection state that is shown**: Connecting, Connected, Reconnecting, Disconnected, Failed.
  A small badge opposite the menu button that fades out once the connection is healthy, so a working
  session carries no badge at all.
- **Page controls in the session menu** — Back, Forward, Reload, Reconnect, Reset zoom, Fullscreen —
  and a setting that removes them entirely. They are in the menu rather than on screen because the
  second display is the whole point.
- **Screen-awake is now three-way**: always, while connected, or never. Applied to the window on the
  display the session is actually on.
- **Orientation** per connection: automatic, landscape or portrait.

### Fixed

- **A dead address reported a confident green "Connected" over a blank screen.** Some WebView builds
  call `onPageFinished` before anything has arrived, and it was being trusted. The network decides
  now: `/state` answering means a companion server, the root answering means an ordinary web page,
  neither answering means nothing is there.
- **The retry panel was hidden a moment after the retry raised it**, by that same callback — which is
  why a failing connection often showed nothing at all while it retried.
- **An address without a `/state` endpoint was torn down after about eight seconds.** The health
  check asks for an endpoint an ordinary web page will never have, so a plain address — which the
  custom entry exists to allow — could never stay open. Those are identified once and the health
  check is switched off for them.
- **A dead address sat blank for the length of the WebView's own connect timeout**, close to two
  minutes. A twelve-second stall timer now puts it into the same retry path as a refused connection.
- The session's error panel named a mod preset rather than the connection you actually opened.

### Tools

- **FTP server.** The whole device served over FTP, the way handheld homebrew file servers do it,
  with a terminal-style console showing commands, responses and live transfer progress. Foreground
  service, so it survives the screen going off mid-transfer.
- **Notes became real files.** They were a single unnamed preference string; they are `.txt` files in
  `AynDualScreen/notes` now, which the FTP server serves — so the list on the handheld and a folder
  listing on the PC are the same thing from two ends. Search, three sort orders, pin, duplicate,
  rename, delete and share. The filename is the title, so renaming renames the file.
- **Doodles.** Draw with a finger, type a line, send it to every other device running this app on the
  Wi-Fi. Presence by UDP broadcast so nobody types an address; messages over TCP because a doodle is
  kilobytes. Four rooms, and it works alone — messages are kept as PNGs beside a line-per-message log
  you can browse over FTP.
- **Screen mirroring** and a **macro pad** overlay.
- **Streaming groundwork.** Host discovery and the full pairing handshake against a GameStream host,
  including certificate generation. It does not play anything yet, and the screen says so.
- **Themes.** Eight alternative looks for the home screen, plus a folder where user-made ones live.
  Kept off the home grid and reachable only through Appearance: they are unfinished and they change
  how the whole app looks.

### Notes

- Version name is plain `0.9.0`. It was `0.9.0-ftp` while the FTP server was the only new thing in
  it, which stopped being true several features ago.

---

## 0.8.0 — 2026-08-12

- Version-only release. The repository still said 0.6.0 while 0.7.0 was the build people had
  installed, so an APK built from source carried a lower `versionCode` than the one on the device and
  Android refused to install it. Comparing the 0.7.0 snapshot against the tree first confirmed the
  repository was never behind on features, only on the number.

## 0.7.0

- Released as a snapshot without a matching version bump in the repository, which is what 0.8.0 went
  on to fix. The only source difference from the tree at the time was the session menu button's idle
  fade.

## 0.6.0 — 2026-08-11

- Fallout: New Vegas added as a connection target, and the app source in the repository brought in
  line with the build that had shipped.

## 0.1.0 — 2026-08-05

- First public version: pick a game, enter or scan for an address, and open the mod's page
  full-screen on a chosen display.
