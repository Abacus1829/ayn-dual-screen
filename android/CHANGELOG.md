# Changelog

Notable changes to the Android app. Newest first.

Entries before 0.9.0 are reconstructed from the repository history — no changelog was kept at the
time — so they say what the commits show and no more.

---

## 0.29.0 — 2026-09-01

## Quick controls are gone

The handle on the edge of every screen, and the panel behind it. Removed.

Nothing went with it. Volume and brightness are the **Controls** tile; keyboard, notes, macros and the dashboard all have their own tiles already. It was a second way to reach things that already had one.

## The mod list actually shows up now

Added in the last beta, and it turns out it was never going to appear for anybody.

It was reading from the "pending update" slot — which only holds an update that's *actually available*, and gets cleared the second your app is current. So the list only existed for people who happened to be out of date, and vanished the moment they updated. Since 0.28.0 went out, that's everyone. It was invisible from the day it shipped.

Which is backwards twice over, because someone running the newest app is the **most** likely person to be wondering whether a mod has moved on.

The newest release is now remembered on every successful check — including the "you're up to date" one — in its own place rather than borrowed from the update slot.

**Settings → System → Check for updates.** Every mod on the newest release with its version, tap to download, and a ★ NEW when one has changed since you last looked.

If the list is empty, it just hasn't checked yet — hit Check for updates once.

## Known limitations

Unchanged: nothing has run on an AYN Thor, the Home button is still unexplained, no GPU ring unless your device exposes its clock, and fan control, real FPS and the eight quick toggles remain out of reach.

---

## 0.28.0 — 2026-09-01

The beta line promoted unchanged. Everything below in the four betas is in this
release; the notes are consolidated here.

Four betas' worth of work, promoted. If you've been on the beta channel you already have all of this.

## Quick controls, from anywhere

There's a handle on the right edge of every screen. Tap it and a panel comes up over whatever you were doing:

- **Volume and brightness** as live sliders
- **Keep the screen awake**, applied to the screen you're looking at, immediately
- One tap to the **keyboard, notes, macros, dashboard**, or home

It's a panel, not a screen — dismiss it and you're exactly where you were. It's on 22 screens; the game surfaces and full-screen tools deliberately don't get one, since a handle floating over the thing you opened would just be in the way.

Brightness works even without the system permission — it dims the window instead, which is what your eyes are actually judging.

Writing a note, building a macro, moving files: those are still proper screens. A panel you're meant to dismiss is a terrible place to work.

## Settings: 5 categories instead of 32 rows in a trench

The groups already existed as headers — you just had to scroll past all of them. Now they're real destinations, and each renames the header so you know where you are. Nothing was cut or moved: 33 rows before, 33 after.

## The controller works

It genuinely never did. A button written in a layout is D-pad reachable automatically; a **box built in code with a click listener is not**. That's basically every row and tile in this app — tool grid, settings, profiles, notes, macros. All touch-only, on a console with a stick on it.

All reachable now, with a highlight. The first press of the stick lands somewhere sensible. Touch users see no focus ring at all.

## Mod versions and download links

**Settings → System → Check for updates** lists every mod on the newest release with its version. Tap to download. A **★ NEW** appears when a mod has changed since you last looked.

Being straight about that badge: nothing tells the app which mod version is on your PC, so "you need to update" would be invented. "This changed since you last looked" is true and is the actual question.

## The intro

Twelve seconds longer, by *holding* rather than slowing anything down — the same physics keeps running, so there's simply more of the part worth watching. Full intro only; the short one still plays on launches in between.

**And it's scored by its own physics.** It used to have three sounds firing on a timer whether or not anything was happening on screen. Now the simulation reports actual collisions and each is one glass clink, pitched to the impact. Ten across the whole intro. The ending is silent.

## Offline is a normal launch

It used to fail its update check, put "No connection" under the logo, and hold that there for the whole animation. Nothing was broken and there was no way to know that. Offline now says nothing, and the launch check gives up in four seconds instead of fifteen.

## Everything else

- What changed after an update, shown once, after it installs
- **Rearrange your tools** — that setting has been saved and read since forever with nothing able to set it
- **60/120 Hz** on the Dashboard, behind the permission the brightness slider already uses
- **FTP "serve the whole device"** — there'd been a grant button there for ages with nothing behind it
- The dashboard is its own screen and stopped vanishing
- The touchscreen stopped shaking when held
- Temperature reads the CPU zones, not a power regulator that idles at 50°C
- 28 dead strings, a dead setting, a dead drawable

## Known limitations

- **None of this has run on an AYN Thor.** It builds, lints and passes 84 tests on a desk. That's the whole claim.
- **The Home button may still not work** and I don't know why. Developer → Copy info tells me which of five reasons it is — send it over.
- **No GPU ring** unless your device exposes its clock. Same report answers that.
- **Fan control, real FPS, the eight quick toggles** are absent and will stay absent. They're vendor driver calls a sideloaded app cannot make.
- Notes and macros aren't game-specific yet.
- The session concept — remembering game, connection, theme and layout together — isn't built.

---

## 0.28.0-beta.4 — 2026-09-01 (beta)

## The loud noise at the end — actually gone this time

Second time you've reported this, and it turned out to be a completely different cause from the first.

The collision solver runs **six passes every four milliseconds** — roughly two dozen times per frame you see. And a pair of beads that's touching gets *resolved* on every single one of those passes. I was treating each one as a fresh strike, so one collision fired dozens of sounds.

The seat is the worst possible place for that to happen: a spring holds every bead pressed against its neighbour there, so it just fired continuously. That was the noise.

A strike is a **transition** now — apart, then touching. Held contact is silence, which is also what two beads resting against each other actually sound like.

I counted it rather than trusting my ears, since I can't hear it from here:

| | sounds |
|---|---|
| whole introduction | **10** |
| the seat and eject (the ending) | **0** |

So the ending is completely silent now. Every clink left is during the free-play part where the beads are genuinely knocking into each other.

Tests pin all three cases, and I checked they actually catch it by putting the old behaviour back — they fail.

## Known limitations

Unchanged: nothing has run on a Thor, the Home button is still unexplained, no GPU ring unless your device exposes the clock, fan/FPS/quick-toggles remain out of reach.

---

## 0.28.0-beta.3 — 2026-08-26 (beta)

## Mod versions and links, in the app

The app's always been able to update *itself*. The mods are files you have to drop onto a PC by hand, and the app said absolutely nothing about them — you found out a mod had changed by going and reading the release page, assuming it occurred to you to check.

**Settings → System → Check for updates** now lists every mod on the newest release with its version. Tap one and it opens the download.

A mod gets a **★ NEW** next to it when its version has changed since the last time you looked at that list.

To be straight about what that does and doesn't mean: nothing tells the app which mod version is actually sitting on your PC. The companion mod serves game state, not its own version number. So "you need to update" would be me making it up. "This is different from the last one you saw" is true, and it's the thing you actually want to know.

Links are built from the release tag rather than fetched, so this works from cache with no connection — handy, since a list of things to install later is exactly what you'd want offline. Checked against the live release: all four resolve.

## The intro only makes noise when beads hit each other

It had three sounds: a knock as each bead arrived, a tap on the first one, and a five-note glass figure over the top as the mark landed. All fired on a timer whether or not anything was happening on screen. Which is the giveaway — it was a soundtrack, not the sound of the thing.

The physics now reports actual collisions and how hard they were. Each one is a single glass clink, pitched and levelled to the impact. When the beads are just drifting it's silent, because drifting beads don't make a noise.

Two beads resting against each other technically collide thousands of times a second, so only strikes with real speed behind them count.

## Known limitations

Unchanged: nothing has run on a Thor, the Home button is still unexplained, no GPU ring unless your device exposes the clock, fan/FPS/quick-toggles remain out of reach.

New one: the mod list only appears after a successful update check, since that's where the version list comes from.

---

## 0.28.0-beta.2 — 2026-08-26 (beta)

## "It doesn't work without internet"

It did work. What broke was what it *said*.

This app talks to a game on your own network. The only thing it ever needs the internet for is checking whether there's a new version of itself. But here's what a launch with no connection looked like:

1. Update check fails instantly — no connection, fine, expected
2. The intro puts **"No connection"** under the logo
3. And then holds that message there for the full twelve seconds of the animation

Nothing was broken. There was also no way on earth for anyone watching that to know it. Twelve seconds of a spinning logo telling you there's no connection is the app saying "I need the internet and I can't find it."

**Offline now says nothing at all.** Because it's not a problem. A check that fails for some *other* reason still gets a quiet mention, since that one might actually be worth doing something about.

## Also, the launch check was too patient

`NET_CAPABILITY_INTERNET` means "this network is supposed to reach the internet", not "it does". Router with no line, hotspot with no data, your PC's own ad-hoc network — the check passes and then the request just sits there for the full fifteen second timeout.

Fifteen seconds is fine if you tapped a button and are watching a spinner. It's a rubbish thing to do to someone who just opened the app. **Four seconds for the automatic check now, fifteen for one you asked for.**

Small note on that fix: it compiled first time with the new timeout accepted and then completely ignored — the value stopped at the function signature and never reached the actual request. Would have shipped as a fix that changed nothing. Checked rather than trusting the green build.

## Everything from beta.1 is still here

Control Center, settings as five categories, working controller navigation, what's-new after updates, tool reordering, 60/120 Hz. See the previous beta if you missed it.

## Known limitations

Unchanged from beta.1: nothing has run on a Thor, the Home button is still unexplained, no GPU ring unless your device exposes the clock, and fan/FPS/quick-toggles remain out of reach.

---

## 0.28.0-beta.1 — 2026-08-26 (beta)

> **This is a beta.** You'll only be offered it if you've switched to the beta channel
> (Settings → System → Check for updates → Channel). Stable users won't see it, which
> is deliberate and there's a test making sure of it.

The app grew as a pile of separate destinations — thirteen tiles on the home screen, each one its own screen you had to leave wherever you were to reach. That's a fine way to *build* an app and a rubbish way to *use* one, because the stuff you want mid-game is exactly the stuff you shouldn't have to abandon your game to get to.

## Quick controls, from anywhere

There's a handle on the right edge of every screen now. Tap it and you get a panel over whatever you were already doing:

- **Volume and brightness** as live sliders
- **Keep the screen awake**, applied to the screen you're actually looking at, immediately
- One tap to the **keyboard, notes, macros, dashboard**, or home

It's a panel, not a screen. Dismiss it and you're exactly where you were, nothing reloaded, nothing lost. It's on 22 screens — everywhere except the game surfaces and full-screen tools, where a handle floating over the thing you opened would just be in the way.

Brightness works even without the system permission — it dims the window instead, which is what your eyes are judging anyway. No greyed-out slider with a lecture attached.

**What's deliberately NOT on it:** writing a note, building a macro, arranging a layout, moving files. Anything you'd sit down and *do* stays a proper screen, because a panel you're supposed to dismiss is a terrible place to work. The test was "would you want this while a game is running".

## Settings: 5 categories instead of 32 rows in a trench

The groups were already there as headers — you just had to scroll past all of them to find anything. Now they're actual destinations, and each one renames the header so you know where you are instead of guessing from the back button.

**Nothing moved and nothing was cut.** 33 rows before, 33 after.

## Everything else from this run

- **The controller works now.** It genuinely never did — boxes built in code with a click listener aren't reachable by D-pad, and that's basically every row and tile in the app. Tool grid, settings, profiles, notes, macros: all touch-only, on a console with a stick on it. All reachable now.
- **You find out what an update changed**, after it installs, once.
- **Rearrange your tools** — `toolOrder` has been saved and read since forever with nothing able to set it.
- **60/120 Hz** on the Dashboard. Same permission the brightness slider already uses.
- **FTP "serve whole device"** — there's been a grant-permission button there for ages with nothing behind it.
- 28 dead strings, a dead setting, a dead drawable, all gone.

## Known limitations

- **Nothing here has run on a Thor.** It builds, lints and passes 78 tests on a desk. That's the whole claim.
- **The Home button still might not work** and I still don't know why — Developer → Copy info tells me which of five reasons it is.
- **No GPU ring yet** unless your device exposes its clock. Same report answers that.
- **Fan control, real FPS, the eight quick toggles** are still absent and always will be. They're vendor driver calls.
- **The tool grid is still 13 tiles.** I chose not to cull it: you can hide and reorder them yourself now, and guessing which ones you don't use seemed worse than leaving them.
- Notes and macros aren't game-specific yet. Still on the list.

---

## 0.27.0 — 2026-08-26

A whole pass of quality-of-life, most of it found by writing scripts to audit the codebase rather than by me sitting here trying to remember what's broken. That turned out to be a much better idea than my usual approach.

### You could not use the app with the controller

This is the big one and I feel a bit stupid about it.

A button you write in a layout file is reachable with a D-pad automatically. A **box you build in code and give a click listener to** is not. It's clickable, it looks exactly like a button, it responds to your thumb — and the D-pad walks straight past it like it isn't there.

Almost every row and tile in this app is that second thing. The tool grid, the settings rows, your profiles, notes, macros. All of it. Touch-only. On a console with a perfectly good stick sitting right under your thumbs.

Everything's reachable now, with a highlight so you can see where you are. The first press of the stick puts focus somewhere sensible instead of appearing to do nothing. Touch users won't see any of it — no focus ring shows up unless you actually reach for the stick.

### You never found out what an update changed

The release notes showed up in the "do you want to update" popup. Which is *before* you've updated, when they're basically an advert you skim on the way to hitting Yes — and that was the only time they ever appeared.

Now you get them **after** it installs, once. And they're tied to the version they describe, so if you back out at Android's confirmation screen you don't get told about a version you don't have.

### Rearranging your tools

`toolOrder` has been saved and read since the grid was written, with nothing anywhere able to change it. The home screen has been faithfully honouring a setting that literally no screen could set. Appearance → arrows next to each tool. Arrows and not dragging, because dragging a row on a 3.9 inch screen while the list slides around under your finger is miserable.

### 60/120 Hz actually works

Turns out the refresh rate isn't locked away like the fan is — it sits behind the same permission the brightness slider already uses. No adb, no root.

It's on the Dashboard. If you haven't granted the permission there's a button that takes you to grant it. And every switch is checked afterwards — these keys aren't a documented API and a device is free to accept the change and then ignore it, so if it doesn't take, it says so instead of lighting up and lying.

### Bits and pieces

- **FTP "serve the whole device"** — there's been a grant-permission button on that screen for ages with nothing behind it. The setting it needed was read in two places and written in none, so granting it did precisely nothing. There's a switch now.
- **The Home button rows had explanations** written for them that were never actually shown. They are now.
- **28 unused strings and a dead setting** deleted.
- **An ending I broke.** Making the intro hold for 12 seconds quietly broke the last bit of it — one line was checking the wrong clock, so the final bead let go early instead of waiting for the others to settle.

I tried twice to write a test for that last one and it passed both times *with the bug still in*, so I deleted it. A test that passes either way is worse than no test — it just makes you think something's covered.

---

## 0.26.0 — 2026-08-25

### The bang at the end of the intro

Two full sounds were firing on the same frame. `Feedback.success` plays a
confirmation chord at nine tenths volume, and then the glass figure played at nine
tenths on top of it — different keys, both at the top of their range. It was not
one sound being too loud so much as there being two of them.

The chord is gone. The glass beads stay, at a bit under half volume, because the
figure already *is* the arrival and announcing an arrival twice is just louder. The
haptic stays too. The six knocks in the run-up came down as well; at their old level
they were competing with the thing they are supposed to be leading into.

### Twelve seconds longer

By holding, not by slowing anything down — which matters, because a stretched
animation plays at half speed and looks broken. The hold already existed to wait on
the update check, and holding *is* the animation continuing to run: the frame keeps
rocking, the beads keep knocking about, and the wobble is kept at a floor so it
never settles into a still picture. There is simply more of the part worth watching.

Full intro goes from about 4.7 seconds to about 16.7. **Only the full one** — the
short version still plays on the launches in between, because a sixteen-second
introduction every time you open the app between rounds is how an intro stops being
nice.

A release that arrives early is remembered and applied when the minimum is up, so a
fast update check no longer cuts it short. The safety timeout was raised to sit
beyond the hold rather than firing in the middle of it.
---

## 0.25.0 — 2026-08-25

### It stopped shaking and there's only one Dashboard now

**The touchscreen shook when you held it.** Entirely my fault. I put a press animation on the dashboard cards, except those cards aren't buttons. So: press → card shrinks → the scroll view goes "actually that touch is mine" → card springs back and overshoots past its own size → and round it goes again while your thumb just sits there. It was doing a little dance. It no longer does a little dance.

**Found a second wobble on the way to that one.** The four ring gauges share a row, so any time one disappeared the other three grew to fill the gap and then shrank again when it came back. And they *do* disappear — battery current genuinely reads exactly zero the moment charging state flips, and the GPU clock reports nothing at all when the GPU is asleep. So the whole row was hopping about every two seconds for no visible reason. A gauge that's had a reading now just holds the last one instead of blinking out.

**There were two tiles called "Dashboard".** Because I made a new one called Dashboard and completely failed to notice Widgets was *already* called Dashboard. They even showed the same battery and memory numbers. Genuinely excellent work. It's one screen now — the widget rows sit under the ring gauges.

**Volume and Brightness were two tiles that opened the identical screen.** Not similar screens. The same one, which has both sets of sliders on it. That's one tile now, called **Controls**. If you'd reordered your tools, that still works — the old ids didn't go anywhere.

---

## 0.24.0 — 2026-08-25

### The dashboard is its own screen

It was a strip on the home screen, which was the wrong place for it twice over.
The home screen is about connecting to a game and already carries a status card, a
game picker, an address form and a tool grid — a live hardware readout was
competing with all of that. And it is a thing you go and look at deliberately, in
the way you would open the console's own dashboard, rather than something you want
glancing at you while you pick a profile.

So it is a tool now, near the top of the grid where a destination belongs.

### Why it kept disappearing

The tiles were remembered on a view tag and reused on every poll, so the gauges
could animate between readings rather than being rebuilt from zero twice a second.

A tag outlives the views it points at. Anything that empties the host — a theme
change rebuilding the tree, the activity being recreated on a display move, a
configuration change — left the tag holding a set of detached views, which were
then dutifully updated forever while the screen showed nothing. That is exactly
the shape of the reported fault: gone, and not coming back without leaving the
screen and returning.

The cache is now only trusted while its views are still the ones actually in the
host, and rebuilt otherwise.

### Turning sounds off

There has been a switch for this since the interface sounds were added, at
**Settings → Interface sounds**, and it silences everything including the intro.

What was wrong was its description. It still read "silent anyway when your device
has touch sounds off or the ringer on silent" — which described the behaviour
removed in 0.22.0, and was therefore telling people the opposite of the truth
about why they could or could not hear anything. It now says what actually
happens: the sounds play at your media volume, and this switch silences the app
completely.

---

## 0.23.0 — 2026-08-25

Two corrections, both found by running the probe on a Thor and reading what came
back rather than by reasoning about it here.

### The temperature was a power regulator

The Thor reports **fifty-eight** thermal zones. The dashboard was showing the
hottest of them, which on a healthy idle console is `pm8550b_lite_tz` at 50.5°C —
a PMIC, which runs hot by design and tells somebody holding a handheld nothing at
all. It put an alarming figure on screen for a device sitting at 36°C.

The zones that answer the question a dashboard is actually asking are the CPU
ones. On the same dump they read 34–40°C, and their maximum is 40.0°C — which is
exactly what AYN's own dashboard displays. So the choice is explicit now: the
hottest CPU zone, then the hottest GPU zone, then the battery, and nothing else.
Regulators, modems, cameras and the USB port are excluded by not being asked.

This is the kind of thing that cannot be got right from a desk. Every one of those
fifty-eight zones is a plausible temperature; only the device knows which of them
is *the* temperature.

### The CPU had no name

`/proc/cpuinfo` reported "not available" on a console whose SoC the framework
knows perfectly well — modern arm64 builds no longer write a `Hardware` or
`model name` line. [Build.SOC_MODEL] was added in Android 12 for exactly this, so
it is asked first, with the file kept as a fallback for older builds.

### Confirmed from the same report

Two readings match AYN's dashboard exactly, which is the check worth having on
anything computed rather than read:

- the peak core reads 3187 MHz, or **3.19 GHz** — the figure on AYN's CPU ring;
- −384 mA at 3828 mV works out to **−1.47 W**, the same shape as AYN's −1.59 W.

And the cause of the silent interface was confirmed rather than guessed: the
console sits in silent mode, and the sound engine used to refuse to play unless
the ringer was normal. That gate went in 0.22.0.

---

## 0.22.0 — 2026-08-25

Everything here came back from the Thor. 0.21.0 was the first build anybody had
actually run, and three of its four headline changes were wrong on hardware.

### The intro was far too short and the beads did not move

A regression, introduced by the loading-state work in 0.21.0 and missed because
every test written for it held the intro for *longer* than the animation.

The common case is the opposite. An update check on a working connection answers
in about two hundred milliseconds, and releasing the hold moved the phase clock
straight to the end of free play — so the fifteen hundred milliseconds the beads
spend sliding and knocking into each other was skipped outright, and the intro
cut to seating almost immediately. Short, with motionless beads, which is exactly
what came back.

Releasing before free play would have ended anyway now does nothing at all: the
run continues on its own clock and plays exactly as it did before there was a
hold. Two tests cover it, one of which asserts that a fast hold is
indistinguishable from never holding, frame by frame, for the whole run.

### The sounds were going to a channel nobody had turned up

`USAGE_ASSISTANCE_SONIFICATION` routes to `STREAM_SYSTEM`. On a phone that is
reasonable. On a gaming handheld the system stream is very often sitting at zero
while media volume is up — the volume keys on the Thor move media — so every
sound the app made was being sent somewhere inaudible with nothing actually
wrong. It plays on media now.

Two gates also went, and both were wrong on this hardware. **The ringer no longer
decides anything**: `RINGER_MODE_NORMAL` is about incoming calls, and a handheld
with no telephony can sit in vibrate permanently without that meaning it wants
silence. What matters is whether the stream is turned up, so that is what is
checked. And **the system's touch-sounds switch no longer silences the intro** —
it still silences the small per-press cues, which is what it is for, but on a
device where touch sounds ship off by default, honouring it there meant the
branded moment was silent for everybody.

### The dashboard

Built to the shape of the one AYN ships, from a photograph of it: translucent
rounded cards on a dark ground, and the four ring gauges that are most of what
makes that screen recognisable.

`RingGauge` is a real one rather than a progress bar bent into a circle. Three
details do the work — a full-circle track under a partial arc, a dot travelling
at the arc's leading edge, and a distinct hue per ring so the four are told apart
without reading the labels. Nothing snaps: a new reading sweeps the arc and counts
the number over the same interval on the same curve, easing out with no overshoot,
because a gauge that overshoots reports a value that never happened.

The readings behind them are real: CPU clock from cpufreq, GPU clock from the
kgsl nodes, power in watts computed as volts times amps from the battery service,
memory from the activity manager, temperature from the thermal zones. Where a
ceiling is needed for the arc and the kernel will not give one, the highest value
actually observed is used, which is correct within seconds of the device doing
anything.

Four things on the reference are **left out rather than mocked up**, because they
belong to the vendor's own driver: current FPS, fan speed and control, the eight
quick toggles, and choosing a refresh mode. A number labelled FPS that was really
this app's own frame rate would be a lie told in large type.

### Home button diagnostics

"I cannot select it" has at least five distinct causes that look identical from
the outside. `HomeRole.diagnose` now lists every package the system will offer as
a home app, says plainly whether this one is among them, reports what
`RoleManager` thinks and whether either system screen exists — and it goes in the
report that already has a Copy button.

If this app is not in that candidate list, the manifest is the problem. If it is
in the list and the chooser still will not take it, the console is.

---

## 0.21.0 — 2026-08-25

### The intro is the loading screen now

It used to race the update check: both started at launch and whichever finished second decided when
the prompt appeared, which usually meant the update dialog landed on top of a home screen somebody
had already started using.

The animation holds instead. `Beads` gained one idea — it can stay in free play rather than moving
on to seat and eject — so while the check runs the frame keeps rocking, the beads keep knocking, and
no branding appears at all. When the check settles the beads seat, the lockup arrives and the sound
plays. Because it is the *same* run of the *same* simulation, a check that returns instantly costs
nothing and looks like nothing.

Two things stop it becoming a trap. Every terminal state releases the hold, failures included: an
update that could not be checked is a reason to carry on with a message, never a reason to keep
somebody watching a logo. And the hold releases unconditionally after eight seconds regardless,
because network paths fail in ways that never call back at all — a captive portal that accepts the
connection and answers nothing, a DNS lookup into a black hole.

### The intro sound was broken, and it was a data race

Every cue plays on its own short-lived thread, and the intro fires eight of them inside a second:
six bead knocks, a tap, and the figure. All eight called `getOrPut` on a plain `HashMap`, which is a
race on the table's own array — a HashMap resized by two threads at once comes back corrupted or does
not come back. The `runCatching` around the call then swallowed the wreckage, so the symptom was not
a crash but silence, with nothing written down anywhere.

It is a `ConcurrentHashMap` now. Failures are logged under `AynSound` instead of discarded,
`AudioTrack`'s return value is checked rather than assumed, and each of the three switches that can
silence the engine says which one it was.

The figure was also gated on the full intro, so after the first launch of a version there was no
sound at all — which is what "the intro sound doesn't work" looks like from outside on every launch
but one.

### And the sound is the beads you can see

It was a four-note rising arpeggio: pleasant, and belonging to some other app. It sounded like a
notification and had nothing to do with six beads visibly knocking down two rods while it played.

Glass and struck bars are *inharmonic*. Their partials sit at roughly 1 : 2.76 : 5.40 : 8.93 — the
ratios of a free-free bar — and that irrational spacing is exactly what the ear hears as a struck
object rather than as a note. Five strikes on the same pentatonic scale everything else uses, with
gaps that shrink from 150ms to 80ms because objects coming to rest arrive closer and closer together,
over a low bloom that stops five short strikes sounding thin on a handheld speaker.

### A lockup instead of a word

**ABACUS**, then **DUAL SCREEN INTERFACE**, then *Made by Abacus* — each arriving after the one above
has settled rather than all at once.

The timing throughout is slower and more deliberate. The wordmark used to appear 350ms before the
mark had finished arriving and fade 280ms later, which is three things competing for the same half
second. Text now eases at both ends rather than decelerating only, which removes a visible flicker at
low alpha.

### The Home button can open Abacus

Android has one sanctioned route: declare an activity that handles `CATEGORY_HOME` and have the user
choose it as the default home app. There is no permission that grants it and no API that takes it —
an app that could seize the Home button unasked would be malware.

So `HomeActivity` declares the filter, which on its own changes nothing, and Settings offers whichever
route the device has: the `RoleManager` dialog on Android 10+, which is one tap, falling back to the
system's home-app list.

Giving it back needed more thought than taking it, because there is no API to drop the role. The
restore row opens the same system screen, names the AYN dashboard *before* it opens so nobody arrives
at a list of launchers wondering which one they came for, and is shown whenever this app holds the
button rather than nested under the switch that set it. Somebody who wants their dashboard back has
often decided they dislike this one, and making them hunt for the exit through it is a poor way to
treat them.

If the Thor's Home button is wired straight to AYN's package instead of dispatching a standard
intent, nothing an ordinary app can do will intercept it. That case is detected and the screen says
so, rather than showing a switch that quietly does nothing.

### Finding out what the console will say about itself

`DeviceStats` is a capability probe rather than a list of readings, and hangs off the developer
screen's existing report so it can be copied off the device.

Reachable: RAM, battery level, current, voltage and temperature, CPU model and core count, GPU
strings, display refresh rate and supported modes. Sometimes reachable, depending on the build: CPU
frequency per core and component temperatures, both via sysfs. Not reachable by an app like this on
any device, and stated rather than approximated: system-wide CPU load, a running game's frame rate,
fan speed and control, and setting a system-wide refresh mode.

A dashboard that renders a plausible number where it could not get a true one is worse than one with
a gap in it, because a wrong temperature is read as a temperature.

### Note

None of this has been verified on a physical Thor — no device has been reachable from the sessions
that wrote it. It builds, lints and passes its tests on a desk, and that is the whole claim.

---

## 0.20.0 — 2026-08-24

The two things 0.19.0 deferred.

### The home screen is a dashboard

It used to open as a *form*: pick a game, type an address, press connect. That is the right set of
controls and the wrong first thing to say, because nine launches in ten the answer is already known
and the only question is whether it is there.

State comes first now and controls come second.

- **A headline** naming what is actually running, with the status dot beside it. It crossfades rather
  than switching, because it changes while you are looking at it.
- **The line under it** says where and what — the farm, the world, the address.
- **Status chips**: an update waiting, the FTP server running, the mirror on, the macro pad up. Each
  is one tap from the screen that deals with it, and the row disappears entirely when there is
  nothing to say. A dashboard whose status area is permanently empty teaches people to stop reading
  it.
- **The primary button moved into the same card as the state it acts on.** It used to be four cards
  further down, under a status line you had to scroll to — and a status you scroll to is not a
  status.

The chips refresh when you come back to the screen *and* when the update check finishes, which on a
startup check is usually while you are still looking at it.

Nothing was removed: the game picker, the manual address, the display choice and the behaviour
toggles are all still there, below, exactly as they were.

### The layouts use the scale

0.19.0 put a spacing and type scale in `dimens.xml` and wired the shared styles to it, but left the
27 layouts on the literal values they had drifted to. **399 values across 26 layouts** now read from
the scale, so cards are one distance apart everywhere and a caption is one size everywhere.

Deliberately narrow: only margins, padding and text sizes were converted. Widths, heights, stroke
widths and corner radii were left alone — a 9dp status dot is 9dp because it is a dot, and feeding
it through a spacing scale would be a rename rather than a decision. Values not in the scale were
left as they were rather than snapped to the nearest one; anything unusual is unusual on purpose
until somebody says otherwise.

One token was added while doing it: **primary buttons** are taller than ordinary ones, which the
conversion first rendered as "section spacing" because the number happened to match. They now say
what they are.

---

## 0.19.0 — 2026-08-24

A pass over how the whole app looks, moves, sounds and is organised. No feature was removed; several
were put where they belong.

### It has a voice now

Every sound in the app is **synthesised at runtime** — sine partials, an envelope, a little filtered
noise for the wooden ones. No audio file ships in the APK, which is both a licensing answer and a
practical one: a handful of oscillators weighs nothing and is tuned by changing a number.

- **One pentatonic scale for everything**, so no two sounds can clash whatever order they play in.
- **Direction is audible.** Going deeper into the app steps up, coming back out steps down, a toggle
  moves the way the switch moves. After a day you know what happened without looking.
- **Three ways to be silent**, checked in this order: the app's switch, the system's *touch sounds*
  setting, and the ringer not being silent. Somebody who has already told Android they dislike
  interface noise never hears a thing.
- Unit tests render every cue and check it is audible, unclipped, short enough to be feedback, and
  starts with an attack rather than a click — which is how the press sound was caught reaching nine
  tenths of its peak in its first millisecond.

### Haptics with a vocabulary

Press, select, back, toggle, confirm and error are now six distinguishable things rather than one
buzz used for all of them, and they are paired with the sounds in a single place so the two cannot
drift apart. All of it still routes through the system's own touch-feedback setting.

### Motion

One place decides how the app moves: 120ms for a press, 200ms for a panel, 320ms for something big,
26ms between staggered rows. Reduced motion is honoured properly — the system's animator duration
scale switches all of it off and lands on the end state.

- Screens arrive with a staggered fade-and-lift rather than appearing all at once.
- Presses follow the finger, scaling down on the way down and springing back on release.
- Panels fade, sections expand by their measured height, status colours crossfade rather than
  switching between frames, failures shake, successes pulse.
- **Except on the second screen.** A window opening on the *other panel* is not arriving from
  anywhere you are looking, so the session has no transition at all — it appears, the way a second
  monitor wakes.

### The intro

- **Full length on a first launch and after an update; brief every other time.** The short version
  is the same physics run faster with the dwell removed, not a different animation — a repeat launch
  should feel like the same app in a hurry, not a cheaper one.
- **Sound and haptics are driven by the animation**, not scheduled alongside it: each bead knocks on
  the frame it actually reaches its stop, because the physics decides when that is. The rising
  figure plays as the mark lands, and only on the full version — a two-second phrase over a
  one-second animation is a phrase that gets cut off.
- A tap still skips it, silently. Somebody skipping an intro has said what they think of it.

### Menus that stop repeating themselves

Settings listed **eight things that are also home-screen tiles**. Opening Settings and finding a
second copy of the app's navigation is exactly what made this feel like separate tools sharing an
icon.

- **Settings holds preferences. The home screen holds tools.** A tool is listed here only when the
  screen genuinely *is* its own settings, like the keyboard.
- **Themes** folded into Appearance — they are one subject, and Appearance already owned the link.
- **Layouts** folded into Macros, which owns them.
- Regrouped into what it connects to, what happens on the second screen, how it looks and sounds,
  controls, and the things you set once. A line at the bottom says where the tools went, because
  somebody who came looking for the FTP server deserves better than concluding it was deleted.

### Components instead of fourteen near-identical cards

Fourteen screens each built their own card out of a LinearLayout and a padding value. They were all
*nearly* the same, which is worse than being different. There is now one card, one section heading,
one link row, one toggle row, one empty state and one loading indicator, and screens compose them.

Empty states in particular were a single grey sentence; they now say what is empty, why, and what to
do about it.

### Dialogs

The last stock-Android surface: a light rounded rectangle arriving in the middle of a dark accented
interface. They are the app's own card now, and wide enough to read at arm's length.

---

## 0.18.0 — 2026-08-24

### A first run that explains itself

The permissions were spread across five screens, each asking at the moment it needed something. That
is the right *time* to ask and the wrong place to keep the list: nobody could see what the app wanted
in total, and somebody could use it for a week without learning that the macro pad and the mirror
were behind a permission they refused in passing.

- **One screen, shown once**, between the boot animation and the home screen, and reachable
  afterwards from **Settings → Permissions**.
- Built on a claim that happens to be true: **none of it is required**. The second screen works with
  everything switched off. So each row is an offer with a stated benefit *and* a stated cost, Skip is
  a first-class answer, and finishing with nothing granted is a fine outcome the app does not nag
  about.
- **Refusals are handled honestly.** Android will not show a runtime prompt a third time, so the
  button becomes a way to the app's settings page rather than one that silently does nothing.
- The update prompt waits for setup rather than arriving on top of it. Two unfamiliar dialogs before
  somebody has seen the app is not a first run worth having.
- There is no accessibility service and there will not be one for convenience. The screen says so.

### Cheat system: it now knows which game you are playing

The codes screen took the first saved connection whose host and port were filled in and asked that
one for its catalogue. That is not the same as the game you are playing — with three connections
saved it was usually the wrong one, and the screen never said which it had picked.

- **Detection.** Every saved connection is probed at once and the answer is whichever is actually
  running, preferring one with a save loaded, because a companion at its main menu will list codes it
  then refuses. The screen says what it found.
- **Disagree with it.** A picker appears when there is more than one connection to choose between,
  and *Rescan* drops any manual choice — "look again" while pinned to a hand-picked target would be
  a lie.
- **Search**, once the list reaches eight, over name, description and the typed code. People remember
  any of the three.
- **Favourites**, per game, pinned above the categories and each appearing once: the point of a
  favourite is not having to know which category somebody filed it under. The star is its own touch
  target on the left, because the row itself runs the code.

### The hidden sequence gets a door beside it

The sequence needs the handheld to report its d-pad in a way the app recognises. When it does not,
the feature is not hidden — it is **unreachable**, and from the outside those are indistinguishable.

**Show the tile without the sequence** is off by default and lives in the codes settings. The
sequence is still the intended way in. Switching the feature off, or hiding the tab, clears it too,
so the tile can never be hidden and shown at once.

### Compatibility, written down

[`COMPATIBILITY.md`](../COMPATIBILITY.md) records what each mod serves, what the app does when it
does not, and — kept separate on purpose — what has been verified by reading the code versus what
still needs a game and a device.

The short version: only `/state` is required of a mod. Every other endpoint is a feature that
degrades on its own rather than taking the connection with it. Only the Stardew mod implements game
codes, its off switch is total (both paths 404, indistinguishable from a build without the feature),
and it re-checks that switch after dequeuing because a queued code can outlive the request.

---

## 0.17.0 — 2026-08-24

Consistency, mostly. Nothing here is a new feature; all of it is the app disagreeing with itself in
ways that add up to feeling unfinished.

### Icons

- **Every tool has an icon in every style.** The four icon styles covered eight of the eighteen
  tools, so switching style changed half the grid and left the rest on their default glyph — two
  styles on one screen at once, which reads as a rendering fault rather than as a setting.
- **Characters that Android may draw as colour emoji are now asked not to be.** The brightness sun
  was the reported case: a yellow picture in a grid of accent-coloured symbols, ignoring every
  attempt to tint it, because a colour emoji is a bitmap and not text. The keyboard, gear, pen and
  play glyphs were all one font update away from the same fate; they now carry a text-presentation
  request, which fonts that were never going to use colour ignore.

### One transition for the whole app

Screens arrived with whatever the platform default was, which differs by Android version and by
manufacturer skin — so the app moved differently on the handheld than on an emulator, and
differently again on somebody else's device. Every screen now enters with the same short fade and
lift: 160ms, which is about the shortest a movement can be and still read as movement. It plays
every time anybody opens anything, so it is deliberately restrained.

### A spacing and type scale

Cards were 12dp or 14dp apart, padding was 13dp here and 14dp there, captions 11sp or 12sp depending
on the screen. None of it wrong, all of it slightly inconsistent, which is most of what "it feels a
bit janky" means when you look closely.

`res/values/dimens.xml` now holds the scale — deliberately few values, named for what they are for
rather than for their size. The shared styles use them, so every button, label, caption and field on
every screen moves together. Existing layouts keep their own literals and can be converted a screen
at a time.

Buttons and fields also gained a 48dp minimum height, Android's own floor for something you hit with
a thumb.

### The status dot

Two screens had each built their own oval and they had drifted — one tinted the drawable, the other
replaced it, so the same status looked different depending on where you saw it. There is one now, and
its colour **crossfades** rather than switching between frames: this is the one piece of the interface
whose entire job is to be noticed changing, and on a handheld at arm's length an instant switch is
easy to miss.

---

## 0.16.0 — 2026-08-24

### The app no longer takes the controller away from your game

The worst bug in the app, and the one hardest to describe until you know the cause: open a second
screen while a game is running and the game stops responding to the pad until you touch it again.

Android gives **one window the input focus at a time, across every display**. Per-display focus
exists from Android 10 but is off unless the manufacturer enables it, and on these handhelds it is
off. So a session window appearing on the lower panel took the buttons, exactly as the system is
documented to do, and the game sat there ignoring them.

- Second-screen sessions now ask for no focus at all. They stay visible and they still take taps —
  they simply no longer take the buttons.
- The screen mirror does the same. It is a picture; it had no business holding the pad.
- **Settings → Let the game keep the controller** turns it off, for the rare page with a text field
  in it, and there is a switch in the session's own menu that flips it without leaving the session.
- One consequence, stated because it is real: a window that receives no key events receives no back
  button either. The session's on-screen menu is the way out, as it already was.

### Brightness opens brightness

The Brightness tile opened a page headed "Quick controls" with four audio sliders at the top and the
brightness sliders below the fold. Nothing was miswired — the screen simply never acknowledged which
half you came for, which from the outside is indistinguishable from the wrong screen opening. Each
tile now opens on its own half, and names the screen after it. The other half stays below, because
they are two halves of one page.

### The brightness icon is the right colour

It rendered as a yellow sun while every tile around it followed the accent. The cause is that `☀`
has an *emoji presentation* in the system font, and a colour emoji is a picture rather than a glyph —
so no amount of tinting was ever going to work. It is a drawn vector now, like the mirror icon, and
takes the accent like everything else.

### Changing the accent changes the app

Picking a new accent repainted the headings and the tool grid and left every control on every screen
the same blue: checkboxes, sliders, spinners, the ordinary buttons. They were taking their colour
from the theme's `colorAccent`, which is a fixed resource compiled into the APK and cannot change at
runtime.

They are painted centrally now, **by type rather than by tag** — a checkbox is themed because it is a
checkbox, not because somebody remembered to mark it up, so a screen added next month is themed
without being told to be. A view that wants to keep its own look says so with a `plain` tag.

Ordinary buttons gained a ripple while this was being done, in the accent. A button that does not
acknowledge a press feels broken long before anybody works out why.

### The hidden sequence, on real hardware

It worked on a desktop emulator and did nothing on the handheld. Two causes, both found by reading
rather than by testing, and both the same mistake in different places:

- **Motion events were watched with `onGenericMotionEvent`**, which is only reached for events no
  view consumed — and a scrolling list or a spinner on the home screen consumes joystick movement
  first. It is `dispatchGenericMotionEvent` now, which sees everything and still passes it on. This
  is precisely the bug that was fixed for keys in 0.14.0 and left in place for axes.
- **Only the hat axes were read.** Which axis a d-pad reports is a per-device decision; the left
  stick is read as a fallback, with edge detection so a device reporting both does not count twice.
- Each step of the sequence now accepts **every code that button might arrive as** — the pad code,
  the letter, and Enter — instead of there being a pad sequence and a keyboard sequence that a
  device reporting one of each could satisfy neither of.

### Developer tools

New section in Settings, listed plainly rather than hidden behind a gesture, because this app is
sideloaded by the people who work on it.

- **Connect over Wi-Fi.** The device's address, the exact `adb pair` and `adb connect` commands, and
  whether wireless debugging appears to be on — with a button to the system page. The app cannot
  switch it on and should not be able to: that is the switch that lets another machine on the
  network install software.
- **Input test.** Every key, axis and gesture this device sends, as it sends it, with the *same*
  sequence watcher the home screen uses running underneath — so its progress display answers the
  real question rather than a proxy for it. A copy button puts the whole log, plus the device and
  its displays, on the clipboard.
- **This device**: version, build, ABIs, and every display the system reports with its size and
  density. The first question every second-screen bug turns into.

---

## 0.15.0 — 2026-08-24

### Updates, from GitHub, without a store

- **The app can now update itself.** It asks GitHub what the latest release is, compares it with what
  is installed, and — when there is something newer — downloads the APK, checks it over, and hands it
  to Android's own installer. There is no silent install and there cannot be one: that needs a
  permission granted only to system apps. Every install still goes through the system's confirmation
  screen.
- **Checked on startup, while the boot animation plays.** The request leaves on a background thread
  as the app opens and takes as long as it takes; nothing waits for it. If it finds something, the
  prompt appears once the animation has finished rather than interrupting it. If it finds nothing, or
  the network is not there, the app opens exactly as it always did and says nothing.
- **Throttled to once every six hours**, and an unchanged release list costs nothing at all: GitHub's
  ETag is sent back and a 304 is free. This app is opened many times an evening and GitHub allows
  sixty anonymous requests an hour per address.
- **Three answers, and all three mean something.** *Update now* downloads. *Remind me later* is a
  day, not a launch. *Skip this version* is about that version only — the next release asks again,
  because refusing one build is not the same as switching updates off. That switch is in Settings →
  Updates, where somebody looking for it can find it.
- **Settings → Updates** shows what is installed, what is published, the release notes, and a Check
  for updates button that ignores the throttle.

### Reading a version out of a release written for people

This repository's releases are not shaped for a machine, and the updater had to cope with that
rather than ask the releases to change:

- **The tag is a date.** `v2026.08.19` parses perfectly well as version 2026.8.19, which is newer
  than everything forever. The tag is never consulted; if it were, the update prompt would never
  stop appearing.
- **One release carries five projects.** The title says *"app 0.14.0, Stardew 0.4.1, …"*, and in
  older releases another project is named first — so the version is read from the number next to the
  word *app*, not the first number in the line.
- **The APK filename deliberately has no version in it**, so the README's
  `releases/latest/download/…` links keep working. Nothing to read there either.
- **From 0.15.0 a release may carry `AynDualScreen-App.json`**, which states the version, the
  versionCode, the file and its SHA-256 outright. When it is there it is believed over anything
  written in prose. `android/tools/make-update-manifest.ps1` writes it from the built APK.
- **An unchanged APK carried forward is recognised as unchanged.** A Stardew-only release re-uploads
  the identical app APK under a new tag; if its digest matches the installed APK it is the same
  build, whatever the title says, and nothing is offered.

### Before anything is installed

Four checks, each with a message that says what to do about it:

- the SHA-256 of the download against the digest GitHub holds for the asset;
- that the file is an APK Android can read at all;
- that it is *this* application id;
- that its versionCode is genuinely higher, read from the file rather than trusted from the notes;
- and that it was signed with the same key as the copy already installed. Android refuses that one
  anyway, with an error nobody can act on; checking first means the app can say what happened.

A file that fails any of them is deleted rather than kept, so a retry cannot find it and offer the
same broken thing again.

### Downloads that survive a handheld's Wi-Fi

- Resumed, not restarted: the part already on disk goes out as a `Range` header, so a dropped
  connection costs the kilobytes it dropped rather than four megabytes.
- Cancellable, and cancelling keeps what arrived.
- Progress in bytes and percent, and it keeps running while you go elsewhere in the app — the state
  lives outside the screen, so rotating the device or wandering into a tool does not restart it.
- Free space is checked before starting, with room for the installer to unpack.

### Errors that say what to do

No internet, GitHub rate-limiting this address, a 5xx, a release with no APK, a release that never
says what version it is, a download that stopped, a checksum that does not match, a corrupt APK, a
different signing key, install permission not granted, no installer on the device — each is its own
sentence rather than "update failed".

### The boot animation

- **The logo, drawn rather than played back — and it ends *on* the logo.** The mark itself: a thick
  black rounded frame on white, two rods, three beads each, one red at the top right. The last frame
  is that image exactly, rather than a gesture at it.
- **The beads are simulated, not animated.** A small 1-D physics model — six particles, elastic
  collisions, fixed 4 ms sub-steps so a 60 Hz panel and a 120 Hz one run the identical simulation.
  Gravity along a rod is `g·sin(θ)` for the very θ the frame is drawn rotated by, so the beads slide
  *because* the frame is spinning rather than being keyframed to look as though they do.
- **The red goes with the momentum.** On each collision it passes to whichever bead comes out
  travelling faster — for equal masses, the one that was struck. Where it ends up is decided by the
  collisions. Each hand-off is a short crossfade, because a colour that teleports reads as a bug and
  one that travels reads as a pass; a 150 ms cooldown keeps one pulse reading as one pass rather
  than as the four contacts a rattling stack actually makes.
- **The last two-thirds of a second is choreographed, and the code says so.** Free dynamics do not
  come to rest on a fixed pose — several thousand parameter combinations were tried and the closest
  landed a fifth of a rod out with the beads still moving. So friction takes over: each bead is drawn
  home by a critically damped spring, the red is handed outward one neighbour at a time as the stack
  compresses, and that outer bead slides on to the far stop. It lands on the mark to within 0.0006 of
  a rod width.
- Canvas primitives — two rounded rectangles, two bars, six circles. No bitmaps, no frames to
  decode, nothing to load, because this is the first thing that happens when the app opens.
- **The launcher icon is the same mark**, so the icon you tap and the thing that appears a moment
  later are the same object. Sized to fit the safe *circle* rather than the safe square: a square
  mark is the worst case for a circular mask, because its diagonal is what gets clipped.
- The mark keeps its own colours; the accent you have chosen becomes the halo behind it, so the
  screen still belongs to your theme without repainting the logo.
- About two and a half seconds, and **a tap ends it immediately**. The home screen builds underneath
  while it plays, so nothing is waiting on it.
- **Honours the system animator duration scale**: a device with animations turned off never sees it,
  whatever the setting says. There is a switch of its own in Settings → Updates as well.
- Plays once per launch, not once per screen.

### Under the hood

- The update system is a package of its own (`update/`) written against an `UpdateSource` interface
  rather than against the app: a repository, an artefact name, and a way to read a version out of a
  release. The checker, downloader, retry, cancellation and error vocabulary know nothing about
  APKs — only the installer does, and a plugin would bring its own. That is the plugin-update
  mechanism the roadmap wants, built in advance rather than retrofitted.
- Stable and beta channels are already in place. Beta includes pre-releases; stable refuses them
  twice over — by GitHub's own flag and by a `-beta` suffix on the version.
- `-PtestVersionCode=14 -PtestVersionName=0.13.0` builds this same code labelled as an older
  release, which is the only way to exercise "an update is available" without publishing one. Never
  ship a build made that way.

---

## 0.14.0 — 2026-08-19

### Settings

- **One screen for everything the app remembers.** The switches were spread across four places --
  some on the home page, some in Appearance, some behind a menu on the saved-connections list. Each
  made sense where it was and none was findable if you did not already know.
- Grouped: connection behaviour, how a session opens, and links to the screens that own a whole
  subject. It owns none of them -- every switch writes the same preference the original screen
  writes, and anything with a real editor is linked rather than rebuilt, because two editors for one
  setting is how they drift apart.
- The Game Codes row appears only once the feature has been found. Listing it otherwise would give
  away that there is something to find.

### The hidden sequence, fixed

- **It never fired.** onKeyDown is only reached for keys the view hierarchy did not consume, and the
  home screen consumes the arrow keys to move focus between the dropdown, the address fields and the
  buttons. Watched from dispatchKeyEvent now, which sees every key first and still passes it on.
- **Swipes work too, and that is the path that actually works everywhere.** A desktop emulator's own
  key-mapping layer takes the arrow keys before the app sees them, and a handheld d-pad may arrive as
  motion axes rather than key codes. A touch has nothing in between: eight swipes then two taps.
- Held keys no longer race through the sequence -- one hold counts once. Gamepad hat axes are read as
  directions. The window between presses went from three seconds to five.
- **A step display**, in the style of the arrow games: after four correct inputs a row appears
  showing how far you have got, the one just hit slightly larger. Silent before four, because showing
  it on the first press would give the secret away to anybody who nudged the stick.
- **The same sequence hides it again**, so there is one thing to remember rather than two.

### Game codes

- **Hide the tab** without switching the feature off -- the tile goes, everything else stays, and the
  sequence brings it back. Distinct from disabling, which stops the app asking any companion about
  codes at all.

---

## 0.13.0 — 2026-08-19

### Game codes (hidden, optional, off at the game end by default)

- **A hidden feature, found rather than shown.** Nothing appears on the home screen until a button
  sequence is entered there. Found once, it stays found.
- **Three states, and "off" means off**: disabled entirely (no listener, no tile, no request ever
  sent to any companion), enabled but not yet found (machinery present, still no tile), or found
  (the tile appears). Turning it off also re-hides it, because off should not mean merely hidden.
- **Per-game switches** on top of the global one.
- **Codes are advertised by the companion, never assumed.** The app holds no game logic: it asks what
  a companion offers and draws that. A mod with codes off advertises none and the screen says so
  rather than showing buttons that would silently fail.
- **Separate endpoints.** `/codes` and `/code` are their own paths, not extra cases on the action
  queue the dashboard uses -- which is what lets a mod refuse them and stay an ordinary local
  dashboard companion.
- **Refusals are reported as refusals.** A companion that says no is shown saying no.
- Supports toggles, one-shot actions, numbers, text, item and entity choices, presets, confirmation
  for anything drastic, and typed codes.

### Stardew Valley mod

- **EnableGameCodes, off by default.** With it off the mod serves no code endpoints at all -- they
  404 like any unknown path, so it is indistinguishable from a build without the feature. Telemetry,
  the map, the inventory panel and every existing action are untouched either way.
- Codes when enabled: restore health, restore energy, add gold, set gold, set time, set tomorrow's
  weather. All run on the game thread through their own queue, because the game is not thread-safe
  and the web server answers on another thread.
- Both switches are re-checked on the game thread, so a code in flight when the feature is switched
  off does not still land.

---

## 0.12.0 — 2026-08-19

### Wild theme

- **A new selectable theme**: near-black blues, a horizontal row of categories, glassy translucent
  panels, and ribbons of light drifting behind everything. Drawn rather than painted from a bitmap,
  so it scales to any panel and orientation without stretching, and it costs four paths a frame.
- **Motion respects the system.** The ribbons stop when the view leaves the window, and they honour
  the animator duration scale -- animations turned off anywhere means the ribbons are drawn once and
  left still, without the app inventing its own reduced-motion switch.
- Original throughout: no trademarked name, logo or console asset appears in the theme, its code or
  its text. Existing themes are untouched and the selection persists as before.

### Game profiles and companions

- **One definition per supported game**, in companion/Companion.kt: id, port, how to recognise it,
  what it can do, which pages it has, and where its saved connection, control profile and last page
  live. The picker, discovery, the profile list and the dashboard all read that list, so adding a
  game is one entry rather than a search for every place a game is mentioned.
- **Capabilities are declared**, so the app decides what to offer before it has spoken to anything.
  A capability a companion does not have is simply not offered -- never an error.
- **TelemetrySource** polls /state and reports a snapshot, reusing the identification rules that
  already live in Probe rather than writing a second answer that could disagree.
- **Automatic switching can be turned off.** Detection still reports what it found; with the setting
  off it does not move you off the game you picked. Manual selection always works.

### Dashboard

- **A live card from the running companion**, showing which game answered and where the player is.
  It appears only once something has actually answered and disappears when it stops -- no game
  running is the ordinary state of that screen and must not look like a failure.

---

## 0.11.0 — 2026-08-19

Control profiles gain gestures and per-game assignment, the app gains a dashboard that needs no game
at all, and every tool gains a visible way back.

### Remote control profiles

- **Buttons have more than one gesture.** A long press and a double tap can each run a saved macro,
  and a tap can be a toggle -- holding its key down and releasing it on the next press, which is how
  a sprint or a crouch is actually used. A toggling button dims while it holds, and anything held is
  released when the pad closes.
- **A layout can be assigned to a game**, from More -> Use for a game. The assigned layout is what
  `profileFor` returns for that game; the active layout is the general one everything falls back to.
- Gestures are stored as trigger id to macro id, so a swipe or a gamepad button later is one enum
  entry and no format change. An older build reading a binding it does not recognise ignores it.
- Layouts and buttons saved by every earlier build load unchanged, and a button with no extra
  gestures still serialises to exactly what it always did.

### Dashboard

- **A new tool that works with nothing running**: clock and date, battery with charging state and
  temperature, network type and this device's address, free storage, free memory, device and Android
  version, and a stopwatch with a countdown timer.
- The stopwatch measures against elapsed-realtime and keeps its state in preferences, so it survives
  the screen closing, the app being killed, and the device sleeping -- and cannot be thrown off by
  the wall clock changing.
- No new permissions. Battery comes from the sticky broadcast; the address comes from the interface
  list rather than WifiManager, which would now require a location permission.
- Another widget is one object and one list entry.

### Getting back

- **Every tool screen now has a visible Back control.** Three had none at all -- saved connections,
  the connection editor, and the FTP console -- and the rest said four different things. They all go
  through one component now.
- Relying on the system back gesture alone was the gap: it is easy to miss on a handheld, and on the
  second panel there may be no gesture area at all. The system back button still behaves as before.

### Touch feedback

- **One place decides how the app feels.** Haptics go through performHapticFeedback, which honours
  the system touch-feedback setting, needs no VIBRATE permission, and stays silent on a device with
  haptics off.
- Status wording separates **sent** from **succeeded**, because a request that left the device is not
  a request that worked.

---

## 0.10.0 — 2026-08-19

Macros, layouts, and layout sharing. All three are optional: the app behaves exactly as before for
anyone who never opens them, and nothing else depends on one existing.

### Macro builder

- **Macros are sequences you build and run**: press a key, hold a key down, release it, hold it for a
  set time, type text, wait, send a game action, tap the second screen, open an app, open a tool.
  Create, rename, duplicate, delete; reorder steps; run from the builder or from a pad button.
- Reached from **Macros -> Macro builder**, and stored in their own preference file so wiping a
  layout does not take every macro on the device with it.
- **Keys now have a down and an up**, not just a tap, which is what a macro that holds one while
  doing something else needs. Anything a macro pressed is released when it ends or is cancelled.
- **Game steps POST to the companion mod** in the same `{type, index, to}` shape the second screen's
  own buttons already use, so a macro reaches any of the mods without knowing which is running.
- One macro runs at a time; starting another cancels the first rather than interleaving keystrokes.

### Layout editor

- **Several named layouts** for the macro pad, arranged on a canvas shaped like the screen: drag a
  button to move it, drag its corner to resize. New, rename, duplicate, delete, set active, and reset
  to the starting set.
- **Buttons can run a macro**, holding its id rather than a copy of its steps -- so editing the macro
  once changes every button on every layout that runs it.
- The editor edits the same layouts the overlay has always drawn, at the same coordinates.

### Layout sharing

- **Export a layout** to `AynDualScreen/layouts/`, or hand it to another app through the share sheet.
  The file carries the layout and the macros its buttons actually need -- not every macro on the
  device.
- **Import** from that folder or from pasted text. Malformed files are refused with a reason rather
  than half-read: not JSON, not a layout, made by a newer version, or empty. Buttons from a
  differently shaped screen are clamped onto this one instead of landing off the edge.
- Versioned, so the format can change later without older files becoming unreadable.

### Connection recovery

- **Reconnecting waits for the host instead of reloading blind.** A retry used to reload the WebView
  whether or not anything was there, throwing the page away and showing a browser error. It now asks
  with one cheap request first and reloads once, at the moment that will work -- and if the page is
  still fine it is left alone, so a recovered session keeps its state.
- **Backoff** rather than a fixed delay: 1.5s for the first couple of attempts, then 4s, 10s, and 30s,
  stopping after eight. The whole budget is about two and a half minutes.

### Fixed

- The layout editor's spinner re-entered its own callback forever, redrawing the canvas hundreds of
  times a second and clearing the selected button every frame -- which presented as tapping a button
  doing nothing at all.
- Adding, removing and moving a button in the editor wrote to a freshly parsed copy of the layout
  rather than the one on screen, so the change was lost on the next read.

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
