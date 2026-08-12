/*
 * The second screen.
 *
 * Polls /state, redraws, and posts taps back to /action. Everything the mod might refuse is still
 * rendered -- the buttons just go disabled -- so a look-only configuration reads as a deliberate
 * setting rather than a broken page.
 *
 * The JSON shapes here must be kept in step with src/Dtos.h and with tools/mockserver.py.
 *
 * Appearance is never hand-themed: every visual choice is a CSS custom property that applySettings
 * writes to :root. Adding a control means adding a variable in style.css and a row in the settings
 * panel, and nothing in between needs to know about it.
 */

"use strict";

// ── settings ──────────────────────────────────────────────────────────────

const SIZES = [12, 13, 14.5, 16, 18, 21, 24];

const FONTS = {
  mono: '"Consolas", "DejaVu Sans Mono", "Menlo", monospace',
  courier: '"Courier New", Courier, monospace',
  system: 'system-ui, -apple-system, "Segoe UI", sans-serif',
  condensed: '"Arial Narrow", "Liberation Sans Narrow", sans-serif',
};

// Presets are just bundles of the same variables the sliders write, so "custom" is not a special
// case -- it is what you get the moment you move anything.
const PRESETS = {
  green:  { fg: "#3cff88", bg: "#0a1a0f" },
  amber:  { fg: "#ffb642", bg: "#1a1206" },
  blue:   { fg: "#54d6ff", bg: "#06141a" },
  white:  { fg: "#e8f4ec", bg: "#101410" },
  red:    { fg: "#ff5f5f", bg: "#1a0808" },
  purple: { fg: "#c48cff", bg: "#12081a" },
};

const ALL_TABS = ["stat", "inv", "data", "map", "radio"];
const TAB_LABELS = { stat: "STAT", inv: "INV", data: "DATA", map: "MAP", radio: "RADIO" };

const DEFAULTS = {
  fg: "#3cff88",
  bg: "#0a1a0f",
  accent: "",          // empty means "follow fg"
  size: 3,             // index into SIZES
  font: "mono",
  glow: 6,
  scan: 20,            // percent
  scanGap: 3,          // px
  vignette: 75,        // percent
  radius: 10,          // px
  split: 38,           // percent width of the detail card
  density: 2,          // index into DENSITIES
  brackets: true,
  bezel: "off",
  // The device's own textures laid over the screen. OFF by default: they are a material for a 3D
  // model, and flattened onto a UI they muddy the text badly. Kept as an option, not a default.
  deviceTex: "off",
  sound: "on",
  boot: "on",
  cards: "on",
  rate: 10,
  tabs: ALL_TABS.slice(),

  // Items pinned to number keys. Keys 1-5 select tabs, so hotkeys live on 6-9 and 0.
  // Stored as { "6": { id, name, bucket } } -- the name is kept so an empty slot can still say
  // what used to be in it once the last one is used up.
  hotkeys: {},

  // Radio stations you have chosen to hide, as { "Bomb Collar Speakers": true }.
  //
  // Keyed by name rather than by form id on purpose: a station with no loaded transmitter has no
  // id to key on, and those are exactly the ones worth keeping -- Radio New Vegas is one of them.
  //
  // This exists because the game's own station list cannot be read. What the mod can enumerate is
  // every activator in the load order that carries a station or a broadcast sound, and that set
  // contains bomb collars, casino lounge music and PA speakers alongside the real stations, with
  // nothing in the data separating them. So the choice is yours to make once, and it is kept.
  hiddenStations: {},
};

const HOTKEY_SLOTS = ["6", "7", "8", "9", "0"];

const DENSITIES = [0.05, 0.12, 0.2, 0.32, 0.45];   // rem of vertical padding per row

let settings = load();

function load() {
  try {
    const raw = localStorage.getItem("aynPipboy");
    if (raw) return Object.assign({}, DEFAULTS, JSON.parse(raw));
  } catch (e) { /* private mode, or corrupt -- defaults are fine */ }
  return Object.assign({}, DEFAULTS);
}

function save() {
  try { localStorage.setItem("aynPipboy", JSON.stringify(settings)); } catch (e) {}
  applySettings();
}

function applySettings() {
  const r = document.documentElement;
  const s = r.style;

  s.setProperty("--fg", settings.fg);
  s.setProperty("--bg", settings.bg);
  s.setProperty("--accent", settings.accent || settings.fg);
  s.setProperty("--ui", SIZES[settings.size] + "px");
  s.setProperty("--font", FONTS[settings.font] || FONTS.mono);
  s.setProperty("--glow-size", settings.glow + "px");
  s.setProperty("--scan-opacity", (settings.scan / 100).toFixed(2));
  s.setProperty("--scan-gap", settings.scanGap + "px");
  s.setProperty("--vignette", (settings.vignette / 100).toFixed(2));
  s.setProperty("--radius", settings.radius + "px");
  s.setProperty("--split", settings.split + "%");
  s.setProperty("--row-pad", DENSITIES[settings.density] + "rem");
  s.setProperty("--bracket", settings.brackets ? "inline" : "none");

  r.dataset.bezel = settings.bezel;
  r.dataset.cards = settings.cards;

  buildTabs();
  if (!settings.tabs.includes(page)) setPage(settings.tabs[0] || "stat");
  restartPolling();
}

// ── page / sub-page routing ───────────────────────────────────────────────

const SUBTABS = {
  stat: [["status", "STATUS"], ["special", "S.P.E.C.I.A.L."], ["skills", "SKILLS"], ["perks", "PERKS"]],
  inv:  [["weapons", "WEAPONS"], ["apparel", "APPAREL"], ["aid", "AID"], ["mods", "MODS"],
         ["misc", "MISC"], ["ammo", "AMMO"]],
  data: [["quests", "QUESTS"], ["notes", "NOTES"], ["stats", "STATS"], ["mods", "PLUGINS"]],
  map:  [],
  radio: [],
};

let page = settings.tabs[0] || "stat";
let sub = { stat: "status", inv: "weapons", data: "quests" };
let selected = { inv: null, quests: null, notes: null, loadorder: null };

function buildTabs() {
  const host = document.getElementById("tabs");
  host.textContent = "";
  for (const key of settings.tabs) {
    const b = el("button", "tab", TAB_LABELS[key]);
    b.dataset.tab = key;
    b.setAttribute("role", "tab");
    b.setAttribute("aria-selected", String(key === page));
    host.appendChild(b);
  }
}

function setPage(next) {
  page = next;
  for (const p of document.querySelectorAll(".page")) p.classList.toggle("on", p.dataset.page === next);
  for (const b of document.querySelectorAll("#tabs .tab")) b.setAttribute("aria-selected", String(b.dataset.tab === next));
  buildSubtabs();
  if (state) render(state);
}

function buildSubtabs() {
  const host = document.getElementById("subtabs");
  host.textContent = "";
  for (const [key, label] of SUBTABS[page] || []) {
    const b = el("button", "subtab", label);
    b.dataset.sub = key;
    b.setAttribute("role", "tab");
    b.setAttribute("aria-selected", String(sub[page] === key));
    b.onclick = () => { sub[page] = key; buildSubtabs(); if (state) render(state); };
    host.appendChild(b);
  }
  for (const p of document.querySelectorAll(`.page[data-page="${page}"] .sub`))
    p.classList.toggle("on", p.dataset.sub === sub[page]);
}

document.getElementById("tabs").addEventListener("click", (e) => {
  const b = e.target.closest(".tab");
  if (b) { click(); setPage(b.dataset.tab); }
});

document.addEventListener("click", (e) => {
  if (e.target.closest(".row, .btn, .subtab, .icon")) click(0.10, 0.03);
}, true);

addEventListener("keydown", (e) => {
  if (e.target.tagName === "INPUT") return;

  // Escape closes the settings panel before anything else looks at the key.
  if (e.key === "Escape") {
    const panel = document.getElementById("settings");
    if (!panel.hidden) { panel.hidden = true; return; }
  }

  // Hotkeys before tabs: 6-9 and 0 are never tab keys, so there is no ambiguity to resolve.
  if (HOTKEY_SLOTS.includes(e.key)) { fireHotkey(e.key); return; }

  const n = parseInt(e.key, 10);
  if (n >= 1 && n <= settings.tabs.length) { setPage(settings.tabs[n - 1]); return; }

  // Q and E step through the sub-tabs, so a whole page can be driven without aiming at anything.
  const subs = SUBTABS[page] || [];
  if ((e.key === "q" || e.key === "e") && subs.length) {
    const at = subs.findIndex(([k]) => k === sub[page]);
    const next = (at + (e.key === "e" ? 1 : subs.length - 1)) % subs.length;
    sub[page] = subs[next][0];
    buildSubtabs();
    if (state) render(state);
    return;
  }

  // Up and down walk the visible list and select as they go, so a page can be driven from a
  // d-pad or a keyboard without touching the panel at all.
  if (e.key === "ArrowUp" || e.key === "ArrowDown") {
    const list = document.querySelector(`.page[data-page="${page}"] .sub.on .list, .page[data-page="${page}"] .list`);
    if (list) {
      const rows = [...list.querySelectorAll(".row")];
      if (rows.length) {
        const at = rows.findIndex((r) => r.getAttribute("aria-selected") === "true");
        const next = e.key === "ArrowDown"
          ? Math.min(rows.length - 1, at + 1)
          : Math.max(0, (at < 0 ? 1 : at) - 1);
        rows[next].click();
        rows[next].scrollIntoView({ block: "nearest" });
        e.preventDefault();
        return;
      }
    }
  }

  // Comma and period nudge the poll rate, for when the link is struggling.
  if (e.key === "," || e.key === ".") {
    const rates = [5, 10, 15, 20, 30];
    const at = rates.indexOf(settings.rate);
    const next = Math.max(0, Math.min(rates.length - 1, (at < 0 ? 1 : at) + (e.key === "." ? 1 : -1)));
    settings.rate = rates[next];
    save();
    toast(settings.rate + " updates/sec");
  }
});

/**
 * A brief message over the screen. Used for things the user did that have no other visible
 * result -- changing the poll rate, a refused action -- so nothing happens silently.
 */
function toast(text) {
  let node = document.getElementById("toast");
  if (!node) {
    node = el("div", null, "");
    node.id = "toast";
    document.getElementById("screen").appendChild(node);
  }
  node.textContent = text;
  node.classList.add("on");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => node.classList.remove("on"), 1600);
}

// ── polling ───────────────────────────────────────────────────────────────

let state = null;
let timer = null;
let lastOk = 0;

function restartPolling() {
  clearInterval(timer);
  timer = setInterval(poll, 1000 / settings.rate);
}

// ── access token ──────────────────────────────────────────────────────────
//
// Kept apart from `settings` on purpose: settings are copied between screens through the profile
// export, and a shared secret should not ride along in something meant to be pasted around.

let token = "";
try { token = localStorage.getItem("aynToken") || ""; } catch (e) {}

/** Every request carries it as a header; images cannot set one, so they get a query parameter. */
function authHeaders() {
  return token ? { "X-Ayn-Token": token } : {};
}

function withToken(url) {
  if (!token) return url;
  return url + (url.includes("?") ? "&" : "?") + "t=" + encodeURIComponent(token);
}

/** Ask once, remember, and reload. A wrong answer just asks again on the next poll. */
function askForToken() {
  if (askForToken.pending) return;
  askForToken.pending = true;

  const given = prompt("This screen needs the access token set in AynDualScreen.ini:", "");
  askForToken.pending = false;
  if (given === null) return;

  token = given.trim();
  try { localStorage.setItem("aynToken", token); } catch (e) {}
  location.reload();
}

async function poll() {
  try {
    const res = await fetch("state", { cache: "no-store", headers: authHeaders() });
    if (res.status === 401) { askForToken(); return; }
    if (!res.ok) throw new Error(res.status);
    state = await res.json();
    lastOk = Date.now();
    render(state);
  } catch (e) {
    // Leave the last frame up rather than blanking; the dot tells the story.
  }
  updateDot();
}

function updateDot() {
  const dot = document.getElementById("conn");
  const age = Date.now() - lastOk;
  dot.classList.toggle("live", age < 1000);
  dot.classList.toggle("stale", age >= 1000 && age < 4000);
  dot.title = lastOk === 0 ? "waiting for the game"
    : age < 1000 ? "live" : age < 4000 ? "snapshots are lagging" : "no snapshots — is the game running?";
}

async function act(action, payload) {
  try {
    await fetch("action", {
      method: "POST",
      headers: Object.assign({ "Content-Type": "application/json" }, authHeaders()),
      body: JSON.stringify(Object.assign({ action }, payload || {})),
    });
    poll();
  } catch (e) {}
}

// ── helpers ───────────────────────────────────────────────────────────────

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

const pct = (v, max) => (max > 0 ? Math.max(0, Math.min(1, v / max)) : 0);
const num = (v) => (v == null ? "—" : Math.round(v).toLocaleString());
const severity = (f) => (f > 0.6 ? "" : f > 0.25 ? "warn" : "bad");

function row(onclick) {
  const li = el("li", "row");
  li.setAttribute("role", "option");
  if (onclick) li.onclick = onclick;
  return li;
}

/**
 * An <img> pointed at the mod's /asset route, which decodes the texture out of the game's own
 * archives. Nothing is bundled: if the mod is running with icons disabled, or the texture isn't
 * there, the image simply fails to load and removes itself, leaving the text-only row it had
 * before. That is why every caller can add one unconditionally.
 */
function icon(path, cls) {
  if (!path) return null;
  const url = withToken("asset/" + path.replace(/\\/g, "/").replace(/\.dds$/i, ".png"));

  // Drawn as a CSS mask rather than as a picture, so the art takes the screen's colour.
  //
  // These textures are pure white with a graduated alpha channel -- the alpha carries the whole
  // drawing, shading included, and the colour channel says nothing. Painted as an <img> they come
  // out white regardless of whether the screen is green, amber or blue. Masked, every pixel is the
  // screen's own foreground at the texture's own opacity, so the Vault Boy matches the theme and
  // keeps all his shading.
  const node = el("span", "icon-img" + (cls ? " " + cls : ""));
  node.style.setProperty("--icon-src", `url("${url}")`);

  // A mask that fails to load leaves a solid block of colour, which looks far worse than a missing
  // icon -- so the URL is probed, and the slot is emptied if it is not there. Hidden rather than
  // removed: removing it collapsed the row and left names ragged wherever a texture was missing,
  // which read as a layout bug rather than a missing icon.
  const probe = new Image();
  probe.onerror = () => { node.style.visibility = "hidden"; };
  probe.src = url;

  return node;
}

/** The game's own Vault Boy art for a SPECIAL attribute. */
function specialIcon(name) {
  return icon("interface/icons/pipboyimages/s.p.e.c.i.a.l/special_" + name.toLowerCase() + ".dds", "big");
}

/**
 * The real Pip-Boy 3000's own textures, from textures\pipboy3000\ in the game archives: the
 * casing, the scanline overlay and the screen glare.
 *
 * Each one is probed before it is used. If the mod isn't serving assets -- icons disabled, or the
 * page opened against the mock server -- the probe fails and the CSS keeps its drawn stand-ins,
 * which is why the page never depends on any of this being present.
 */
const DEVICE_TEXTURES = {
  "--tex-casing":    "pipboy3000/pipboy.dds",
  "--tex-scanlines": "pipboy3000/pipboyscanlines.dds",
  "--tex-glare":     "pipboy3000/screenglare.dds",
  // Deliberately NOT greenscreen.dds. Despite the name it is a picture of a whole Pip-Boy screen,
  // not a background map, and tiling it wallpapers the panel with little screenshots.
};

// ── the status figure ─────────────────────────────────────────────────────
//
// The game builds this out of one texture per limb, with a "_broken" variant swapped in when a
// limb is crippled. Same idea here: the layers are stacked absolutely, and the src flips between
// the two variants as condition changes.
//
// The layout is hand-placed because these are UI sprites the game positions itself; the numbers
// below are percentages of the figure box, chosen so the pieces meet at the joints.

// Individual limb sprites, each placed by hand. I tried treating them as one shared canvas -- each
// texture being the whole figure with only its own limb painted -- and it composited into a
// scribble, so they are genuinely separate pieces that carry no position of their own.
//
// These percentages are of the figure box, and they are ESTIMATES. The authoritative numbers are
// in the game's own stats menu XML (menus\ inside Fallout - Misc.bsa), which places each piece
// explicitly; reading those out and pasting them here is the way to make this exact rather than
// close. Until then, nudge these.
// Laid out on a virtual 300x340 canvas, then expressed as percentages of it.
//
// The sizes are NOT guesses -- they are the measured opaque bounds of each texture, so the pieces
// are in their true relative proportions:
//
//   head 83x91   torso 97x92   arms 100x52   legs 70x110 and 85x109
//
// Every sprite is anchored TOP-LEFT on its own canvas (measured: art starts at x0,y0 in all of
// them), which is why these are left/top coordinates and not centres. Centring them was the
// mistake that made the figure come apart.
const FIGURE_BOX = { w: 300, h: 340 };

// Positioned by hand in tools/figure-align.html, against the real art at real size. These are not
// derived from anything and should not be "corrected" by calculation -- the pieces do not share an
// origin, so where they sit is a judgement about how the figure looks, not a number to compute.
// Re-run that tool to change them.
const FIGURE_LAYERS = [
  // key        file          x    y    w    h    z
  ["rightArm", "right_arm",    28,  77,  99,  50,  1],
  ["leftArm",  "left_arm",    163,  81,  91,  46,  1],
  ["rightLeg", "right_leg",    72, 145,  92,  92,  1],
  ["leftLeg",  "left_leg",    136, 148,  84,  84,  1],
  ["torso",    "torso",        97,  81,  97,  97,  2],
  ["head",     "head",        111,  23,  89,  89,  3],
];

// Where each condition bar sits, as percentages of the figure box. Also hand-placed, and kept
// separate from the limb table on purpose: a bar reads best slightly off a limb's exact centre.
const BAR_SPOTS = {
  rightArm: [25.8, 30.0],
  leftArm:  [69.5, 30.6],
  rightLeg: [39.3, 56.2],
  leftLeg:  [59.3, 55.9],
  torso:    [48.5, 38.1],
  head:     [51.8, 19.9],
};

function buildFigure() {
  const host = document.getElementById("figurelayers");
  if (host.childElementCount) return;

  for (const [key, file, x, y, w, h, z] of FIGURE_LAYERS) {
    const img = el("img", "limb-layer");
    img.dataset.limb = key;
    img.dataset.file = file;
    img.alt = "";
    // Canvas coordinates -> percentages, so the whole figure scales as one piece.
    img.style.left = (100 * x / FIGURE_BOX.w).toFixed(3) + "%";
    img.style.top = (100 * y / FIGURE_BOX.h).toFixed(3) + "%";
    img.style.width = (100 * w / FIGURE_BOX.w).toFixed(3) + "%";
    img.style.zIndex = String(z);

    // Each layer reveals the figure itself once it has actually decoded. There is no separate
    // probe: an earlier version used one, and if that single request lost a race at startup the
    // whole figure stayed hidden forever with no way to recover. This way any layer arriving is
    // enough to switch over, and a layer that never arrives costs only itself.
    img.onload = () => {
      host.hidden = false;
      // A class, not the hidden attribute: [hidden] is an HTML attribute and browsers do not
      // reliably apply the UA display:none rule to an <svg>, so the drawn figure was still
      // showing through underneath the real one.
      document.getElementById("doll").classList.add("replaced");
    };
    img.onerror = () => { img.style.visibility = "hidden"; };

    host.appendChild(img);
  }
}

/** textures\interface\stats\<file>.dds -- the mod prepends "textures\" itself. */
function statsAsset(file) {
  return "asset/interface/stats/" + file + ".png";
}

/** Point each layer at its good or broken variant, following the live condition values. */
function updateFigure(condition) {
  for (const img of document.querySelectorAll("#figurelayers .limb-layer")) {
    const value = condition[img.dataset.limb];
    const broken = value != null && value <= 0;
    const wanted = statsAsset(img.dataset.file + (broken ? "_broken" : ""));

    // Only touch src when it actually changes, or the browser re-decodes every frame.
    if (img.dataset.current !== wanted) {
      img.dataset.current = wanted;
      img.src = wanted;
    }

    // Below a quarter condition the game tints the limb; mirror that with a filter so a hurt
    // limb reads at a glance without needing the bar.
    img.classList.toggle("hurt", value != null && value <= 0.25 && value > 0);
  }
}

function loadDeviceTextures() {
  if (settings.deviceTex !== "on") {
    document.documentElement.dataset.deviceTextures = "off";
    return;
  }
  for (const [variable, path] of Object.entries(DEVICE_TEXTURES)) {
    const url = withToken("asset/" + path.replace(/\.dds$/i, ".png"));
    const probe = new Image();
    probe.onload = () => {
      document.documentElement.style.setProperty(variable, `url("${url}")`);
      document.documentElement.dataset.deviceTextures = "on";
    };
    probe.onerror = () => { /* drawn stand-in stays */ };
    probe.src = url;
  }
}

/** Rebuild a list only when its contents changed, so scroll position survives a redraw. */
function fill(node, key, build) {
  if (node.dataset.key === key) return false;
  node.dataset.key = key;
  node.textContent = "";
  build(node);
  return true;
}

function bar(label, value, max, text) {
  const b = el("div", "bar");
  b.appendChild(el("span", "k", label));
  const track = el("div", "track");
  const f = el("div", "fill " + severity(pct(value, max)));
  f.style.width = (pct(value, max) * 100).toFixed(1) + "%";
  track.appendChild(f);
  b.appendChild(track);
  b.appendChild(el("span", "v", text != null ? text : `${num(value)}/${num(max)}`));
  return b;
}

// ── render ────────────────────────────────────────────────────────────────

function render(s) {
  if (!s || !s.ready) {
    document.getElementById("loc").textContent = "Waiting for a save to load…";
    return;
  }

  const p = s.player || {};

  document.getElementById("hpText").textContent = `HP ${num(p.hp)}/${num(p.hpMax)}`;
  document.getElementById("apText").textContent = `AP ${num(p.ap)}/${num(p.apMax)}`;
  document.getElementById("lvlText").textContent = `LEVEL ${num(p.level)}`;
  document.getElementById("xpfill").style.width =
    (pct((p.xp || 0) - (p.xpBase || 0), (p.xpNext || 0) - (p.xpBase || 0)) * 100).toFixed(1) + "%";

  // The active quest's current objective, on the always-visible line. It is the one thing you
  // would otherwise keep flipping to DATA to read.
  const active = (s.quests || []).find((q) => q.active && !q.completed);
  const objective = active && (active.objectives || []).find((o) => !o.done);
  document.getElementById("gamedate").textContent =
    objective ? "◆ " + objective.text
    : active ? "◆ " + active.name
    : (s.gameTime || "");
  document.getElementById("caps").textContent = `${num(p.caps)} caps`;

  // Weight, coloured as it approaches the cap. Being over-encumbered stops you fast travelling,
  // so knowing you are close matters before you pick the next thing up.
  const wt = document.getElementById("wt");
  const load = pct(p.weight, p.weightMax);
  wt.textContent = `${num(p.weight)}/${num(p.weightMax)} wg`;
  wt.classList.toggle("warn", load >= 0.85 && load < 1);
  wt.classList.toggle("bad", load >= 1);

  // Ammo for whatever is in your hands, on the always-visible line. The weapon knows what it
  // takes; the count comes from the ammo tab.
  const held = ((s.inventory || {}).weapons || []).find((i) => i.equipped);
  const ammoLabel = document.getElementById("ammo");
  if (held && held.ammoName) {
    const round = ((s.inventory || {}).ammo || []).find((a) => a.name === held.ammoName);
    const left = round ? round.count : 0;
    ammoLabel.textContent = `${num(left)} ${held.ammoName}`;
    ammoLabel.classList.toggle("bad", left === 0);
    ammoLabel.classList.toggle("warn", left > 0 && left < (held.clip || 10));
  } else {
    ammoLabel.textContent = "";
    ammoLabel.classList.remove("warn", "bad");
  }
  document.getElementById("loc").textContent =
    [s.map && s.map.cell, s.map && s.map.world].filter(Boolean).join(" — ");

  checkWarnings(p, s);

  if (page === "stat") renderStat(s);
  else if (page === "inv") renderInv(s);
  else if (page === "data") renderData(s);
  else if (page === "map") renderMap(s);
  else if (page === "radio") renderRadio(s);
}

// ── STAT ──────────────────────────────────────────────────────────────────

const LIMBS = [
  ["head", "Head"], ["torso", "Torso"],
  ["leftArm", "Left Arm"], ["rightArm", "Right Arm"],
  ["leftLeg", "Left Leg"], ["rightLeg", "Right Leg"],
];

function renderStat(s) {
  const p = s.player || {};

  if (sub.stat === "status") {
    const cond = p.condition || {};

    buildFigure();
    updateFigure(cond);

    for (const [key] of LIMBS) {
      for (const node of document.querySelectorAll(`#doll [data-limb="${key}"]`)) {
        const v = cond[key] == null ? 1 : cond[key];
        node.classList.toggle("warn", v <= 0.6 && v > 0.25);
        node.classList.toggle("bad", v <= 0.25 && v > 0);
        node.classList.toggle("crippled", v <= 0);
      }
    }

    // The condition bars sit on the figure rather than beside it.
    const host = document.getElementById("limbbars");
    if (!host.childElementCount) {
      // Straight from BAR_SPOTS. These were placed by eye alongside the limbs rather than
      // computed from them -- deriving a bar from its limb's centre put several of them off the
      // figure, because a limb's bounding box centre is not where the limb looks like it is.
      for (const [key] of FIGURE_LAYERS) {
        const [left, top] = BAR_SPOTS[key] || [50, 50];
        const b = el("div", "limbbar");
        b.dataset.limb = key;
        b.style.left = left + "%";
        b.style.top = top + "%";
        b.appendChild(el("div", "fill"));
        host.appendChild(b);
      }
    }
    for (const [key, name] of LIMBS) {
      const b = host.querySelector(`[data-limb="${key}"]`);
      const v = cond[key] == null ? 1 : cond[key];
      const f = b.firstElementChild;
      f.style.width = (Math.max(0, v) * 100).toFixed(0) + "%";
      f.className = "fill " + severity(v);
      b.title = `${name}: ${Math.round(v * 100)}%`;
    }

    // Equipped weapon and armour, under the figure, as the app shows them.
    const equipped = document.getElementById("equippedrow");
    const weapon = ((s.inventory || {}).weapons || []).find((i) => i.equipped);
    const armour = ((s.inventory || {}).apparel || []).find((i) => i.equipped);
    const key = `${weapon ? weapon.name : ""}|${armour ? armour.name : ""}`;
    fill(equipped, key, (n) => {
      const slot = (label, item) => {
        const d = el("div", "slot", label);
        d.appendChild(el("b", null, item ? item.name : "—"));
        n.appendChild(d);
      };
      slot("WEAPON", weapon);
      slot("ARMOUR", armour);
    });

    document.getElementById("playername").textContent = p.name || "";

    const fx = document.getElementById("effects");
    fill(fx, JSON.stringify(s.effects || []), (n) => {
      if (!(s.effects || []).length) { n.appendChild(el("li", "empty", "No active effects.")); return; }
      for (const e of s.effects) {
        const li = row();
        li.appendChild(el("span", "name", e.name));
        li.appendChild(el("span", "val", e.duration || ""));
        n.appendChild(li);
      }
    });

    // Companions. The Pip-Boy will not show you this at all -- their health is only on the
    // companion wheel, which you open mid-fight, which is exactly when you cannot read it.
    const crew = s.companions || [];
    const crewHead = document.getElementById("crewhead");
    const crewList = document.getElementById("companions");
    crewHead.hidden = !crew.length;
    crewList.hidden = !crew.length;

    if (crew.length) {
      fill(crewList, crew.map((c) => `${c.name}:${Math.round(c.hp)}:${Math.round(c.distance / 200)}`).join(","), (n) => {
        for (const mate of crew) {
          const li = row();
          li.appendChild(el("span", "name", mate.name));
          // Far enough away that they have probably lost you.
          if (mate.distance > 4000) li.appendChild(el("span", "tag", distanceText(mate.distance)));
          const hp = el("span", "val", `${num(mate.hp)}/${num(mate.hpMax)}`);
          const f = pct(mate.hp, mate.hpMax);
          if (f <= 0.25) hp.classList.add("bad");
          else if (f <= 0.6) hp.classList.add("warn");
          li.appendChild(hp);
          n.appendChild(li);
        }
      });
    }

    const hc = document.getElementById("hardcore");
    hc.textContent = "";
    document.getElementById("survivalhead").hidden = false;
    if (p.hardcore) {
      // Hardcore counts up towards death, so the bar shows headroom remaining.
      hc.appendChild(bar("Dehydration", p.h2oMax - p.h2o, p.h2oMax, `${num(p.h2o)}/${num(p.h2oMax)}`));
      hc.appendChild(bar("Starvation", p.fodMax - p.fod, p.fodMax, `${num(p.fod)}/${num(p.fodMax)}`));
      hc.appendChild(bar("Sleep Dep.", p.slpMax - p.slp, p.slpMax, `${num(p.slp)}/${num(p.slpMax)}`));
    } else {
      hc.appendChild(el("p", "hint", "Hardcore mode is off."));
    }
    if (p.radsMax)
      hc.appendChild(bar("Rads", p.radsMax - (p.rads || 0), p.radsMax, `${num(p.rads)}`));
  }

  if (sub.stat === "special") {
    const list = document.getElementById("special");
    fill(list, JSON.stringify(s.special || []), (n) => {
      for (const a of s.special || []) {
        const li = row();
        const art = specialIcon(a.name);
        if (art) li.appendChild(art);
        li.appendChild(el("span", "name", a.name));
        li.appendChild(el("span", "val", String(a.value)));
        if (a.value !== a.base) li.appendChild(el("span", "tag", a.value > a.base ? "▲" : "▼"));
        n.appendChild(li);
      }
      if (!(s.special || []).length) n.appendChild(el("li", "empty", "No data."));
    });
  }

  if (sub.stat === "skills") {
    const list = document.getElementById("skills");
    fill(list, JSON.stringify(s.skills || []), (n) => {
      for (const a of s.skills || []) {
        const li = row();
        li.appendChild(el("span", "name", a.name));
        if (a.tag) li.appendChild(el("span", "tag", "TAG"));
        li.appendChild(el("span", "val", String(a.value)));
        n.appendChild(li);
      }
      if (!(s.skills || []).length) n.appendChild(el("li", "empty", "No data."));
    });
  }

  if (sub.stat === "perks") {
    const list = document.getElementById("perks");
    fill(list, JSON.stringify(s.perks || []), (n) => {
      if (!(s.perks || []).length) { n.appendChild(el("li", "empty", "No perks yet.")); return; }
      for (const a of s.perks) {
        const li = row();
        li.appendChild(el("span", "name", a.name));
        if (a.rank > 1) li.appendChild(el("span", "val", "×" + a.rank));
        li.title = a.desc || "";
        n.appendChild(li);
      }
    });
  }
}

// ── INV ───────────────────────────────────────────────────────────────────

let invFilter = "";

// Sorting. The game gives us one order; on a screen you are using to make decisions, "what is
// heaviest" and "what is nearly broken" are the questions you actually have.
const SORTS = [
  ["default", "SORT: GAME",  null],
  ["name",    "SORT: A-Z",   (a, b) => (a.name || "").localeCompare(b.name || "")],
  ["weight",  "SORT: WEIGHT", (a, b) => (b.weight || 0) - (a.weight || 0)],
  ["value",   "SORT: VALUE",  (a, b) => (b.value || 0) - (a.value || 0)],
  ["ratio",   "SORT: VAL/WG", (a, b) => ratio(b) - ratio(a)],
  ["cond",    "SORT: WORN",   (a, b) => (a.health == null ? 2 : a.health) - (b.health == null ? 2 : b.health)],
];

let invSort = 0;

// Quest ordering: the game's own, or nearest outstanding objective first.
let questSort = "game";

// ── hotkeys ───────────────────────────────────────────────────────────────

/** Every item in the snapshot, whatever tab it lives on. Hotkeys ignore tabs. */
function findItem(id) {
  for (const bucket of Object.values((state && state.inventory) || {})) {
    const hit = (bucket || []).find((i) => i.id === id);
    if (hit) return hit;
  }
  return null;
}

/**
 * Use whatever is pinned to a key.
 *
 * Aid gets used, weapons and apparel get equipped -- which is what you would want from a single
 * key in each case, and matches what the tab's own button does.
 */
function fireHotkey(key) {
  const slot = settings.hotkeys[key];
  if (!slot) { toast("Nothing on " + key); return; }

  const item = findItem(slot.id);
  if (!item) { toast(slot.name + " — none left"); return; }

  defaultAction(item, slot.bucket);
}

/**
 * The one obvious thing to do with an item: aid gets used, weapons and apparel get equipped.
 *
 * Shared by the hotkeys and by tapping a row that is already selected, so a key and a tap can
 * never disagree about what "use this" means.
 */
function defaultAction(item, bucket) {
  const perms = (state && state.perms) || {};

  if (bucket === "aid") {
    if (!perms.use) { toast("Using items is off in the mod's config"); return; }
    act("use", { id: item.id });
    toast(item.name + (item.count > 1 ? `  (${item.count - 1} left)` : ""));
    return;
  }

  if (bucket !== "weapons" && bucket !== "apparel") return;   // nothing sensible to do with junk

  if (!perms.equip) { toast("Equipping is off in the mod's config"); return; }
  act("equip", { id: item.id });
  toast((item.equipped ? "Unequipped " : "Equipped ") + item.name);
}

/** Pin the selected item to the first free slot, or clear it if already pinned. */
function toggleHotkey(item) {
  const existing = HOTKEY_SLOTS.find((k) => settings.hotkeys[k] && settings.hotkeys[k].id === item.id);
  if (existing) {
    delete settings.hotkeys[existing];
    save();
    toast(`${item.name} unpinned from ${existing}`);
    return;
  }

  const free = HOTKEY_SLOTS.find((k) => !settings.hotkeys[k]);
  if (!free) { toast("All hotkeys taken — clear one first"); return; }

  settings.hotkeys[free] = { id: item.id, name: item.name, bucket: sub.inv };
  save();
  toast(`${item.name} pinned to ${free}`);
}

/** Which key an item is on, if any. */
function hotkeyFor(id) {
  return HOTKEY_SLOTS.find((k) => settings.hotkeys[k] && settings.hotkeys[k].id === id) || null;
}

/** Value per unit weight — the number that decides what to leave behind when overloaded. */
function ratio(item) {
  const w = item.weight || 0;
  if (!w) return item.value ? Infinity : 0;
  return (item.value || 0) / w;
}

function renderInv(s) {
  const all = (s.inventory || {})[sub.inv] || [];
  const list = document.getElementById("items");

  // Filter on name. A hoarder's misc tab runs to hundreds of lines and scrolling it on a handheld
  // is the single most tedious thing about the screen.
  const needle = invFilter.trim().toLowerCase();
  let bucket = needle
    ? all.filter((i) => (i.name || "").toLowerCase().includes(needle))
    : all.slice();

  // Equipped items stay pinned to the top whatever the sort, because that is where you look for
  // them and they are the ones you are comparing against.
  const compare = SORTS[invSort][2];
  if (compare) {
    bucket.sort((a, b) => {
      if (!!a.equipped !== !!b.equipped) return a.equipped ? -1 : 1;
      return compare(a, b);
    });
  }

  // Hotkey bindings are part of the key: without them, pinning an item changed the settings but
  // the list never rebuilt, so the tag did not appear until something else forced a redraw.
  // Ammo counts appear on weapon rows, so they belong in the key -- firing a few shots must
  // redraw the list, not leave a stale count sitting there.
  const ammoKey = sub.inv === "weapons"
    ? ((s.inventory || {}).ammo || []).map((a) => a.name + a.count).join(",")
    : "";

  const key = sub.inv + "|" + needle + "|" + invSort + "|" + ammoKey
    + "|" + HOTKEY_SLOTS.map((k) => (settings.hotkeys[k] || {}).id || "").join(",")
    + "|" + bucket.map((i) =>
    `${i.id}:${i.count}:${i.equipped ? 1 : 0}:${Math.round((i.health || 0) * 100)}`).join(",");

  fill(list, key, (n) => {
    if (!bucket.length) {
      n.appendChild(el("li", "empty",
        needle ? `Nothing matching "${invFilter}".` : "Nothing here."));
      return;
    }
    for (const item of bucket) {
      // Tap to select, tap the selected one again to equip or use it.
      //
      // Equipping on the FIRST tap was the other option and is worse: the card on the right is
      // how you read an item's stats, and reaching it would mean equipping everything you wanted
      // to look at. Two taps keeps browsing free and still puts equipping a tap away.
      const li = row(() => {
        if (selected.inv === item.id) { defaultAction(item, sub.inv); return; }
        selected.inv = item.id;
        renderInv(state);
      });
      li.classList.toggle("equipped", !!item.equipped);
      li.dataset.id = item.id;
      const art = icon(item.icon);
      if (art) li.appendChild(art);
      li.appendChild(el("span", "name", item.name));

      // How many rounds you have for this weapon, on its own row. Without it, deciding what to
      // carry means selecting each gun in turn and reading its ammo type off the card.
      if (sub.inv === "weapons" && item.ammoName) {
        const round = ((s.inventory || {}).ammo || []).find((a) => a.name === item.ammoName);
        const left = round ? round.count : 0;
        const tag = el("span", "tag", "⁃" + num(left));
        tag.title = `${num(left)} × ${item.ammoName}`;
        if (left === 0) tag.classList.add("bad");
        else if (left < (item.clip || 10)) tag.classList.add("warn");
        li.appendChild(tag);
      }

      const bound = hotkeyFor(item.id);
      if (bound) li.appendChild(el("span", "hotkeytag", bound));
      if (item.count > 1) li.appendChild(el("span", "tag", "(" + item.count + ")"));
      li.appendChild(el("span", "val", item.weight != null ? item.weight.toFixed(1) : ""));
      n.appendChild(li);
    }
  });

  for (const li of list.querySelectorAll(".row"))
    li.setAttribute("aria-selected", String(li.dataset.id === selected.inv));

  const item = bucket.find((i) => i.id === selected.inv);
  renderItemCard(s, item);
  renderInvFoot(s, item, bucket.length);
}

/** The app rates a stat against the rest of your kit; without that comparison the honest version
 *  is a magnitude, so these pips scale off the value itself rather than inventing a ranking. */
function pipsFor(value, ceiling) {
  if (value == null || !ceiling) return "";
  const n = Math.max(0, Math.min(5, Math.round((value / ceiling) * 5)));
  return "+".repeat(n);
}

function renderItemCard(s, item) {
  const card = document.getElementById("itemcard");

  // Rebuild only when the card's contents actually change. It used to be wiped and rebuilt on
  // every poll -- ten times a second -- which recreated the <img> each frame, so the icon
  // re-fetched and the card resized under the pointer. That was the shake.
  const key = item
    ? [item.id, item.count, item.equipped, item.health, item.icon].join("|")
    : "none";
  if (card.dataset.key === key) return;
  card.dataset.key = key;

  card.textContent = "";
  if (!item) { card.appendChild(el("p", "hint", "Select an item.")); return; }

  const art = icon(item.icon, "card-art");
  if (art) card.appendChild(art);

  card.appendChild(el("h4", null, item.name));

  // What you are currently using in the same slot, so a stat can be shown as a difference rather
  // than a number you have to hold in your head and compare.
  const rival = !item.equipped
    ? ((s.inventory || {})[sub.inv] || []).find((i) => i.equipped)
    : null;

  const dl = el("dl");
  const put = (k, v, pips, compareKey) => {
    if (v == null || v === "") return;
    dl.appendChild(el("dt", null, k));
    const dd = el("dd", null, String(v));

    // The delta against the equipped item. Only for stats where bigger is plainly better.
    if (rival && compareKey && typeof v === "number") {
      const theirs = rival[compareKey];
      if (typeof theirs === "number" && theirs !== v) {
        const diff = v - theirs;
        const tag = el("span", "delta " + (diff > 0 ? "up" : "down"),
          (diff > 0 ? " +" : " ") + Math.round(diff * 10) / 10);
        tag.title = `${rival.name}: ${theirs}`;
        dd.appendChild(tag);
      }
    }

    if (pips) dd.appendChild(el("span", "pips", pips));
    dl.appendChild(dd);
  };

  put("Damage", item.damage, pipsFor(item.damage, 120), "damage");
  put("DPS", item.dps, pipsFor(item.dps, 200), "dps");
  put("Clip", item.clip, null, "clip");
  put("Ammo", item.ammoName);
  put("Spread", item.spread);
  put("DT", item.dt, pipsFor(item.dt, 30), "dt");
  put("Effect", item.effect);
  if (item.health != null) put("Condition", Math.round(item.health * 100) + "%");
  put("Weight", item.weight != null ? item.weight.toFixed(1) : null);
  put("Value", item.value != null ? num(item.value) : null);
  put("Count", item.count > 1 ? item.count : null);
  put("From", item.source);
  card.appendChild(dl);

  if (item.desc) card.appendChild(el("p", null, item.desc));

  // Say that a second tap does something, because nothing else on screen would tell you.
  const perms = (s.perms || {});
  const again =
    sub.inv === "aid" && perms.use ? `Tap again to use ${item.name}.` :
    (sub.inv === "weapons" || sub.inv === "apparel") && perms.equip
      ? `Tap again to ${item.equipped ? "unequip" : "equip"} ${item.name}.` : null;
  if (again) card.appendChild(el("p", "hint", again));
}

function renderInvFoot(s, item, shown) {
  const foot = document.getElementById("invfoot");
  const perms = s.perms || {};

  // Same reasoning as the card: rebuilding this every poll threw away the DROP button's armed
  // state mid-confirmation, so "SURE?" could reset itself before you could tap it.
  const key = [
    item ? item.id : "none", item ? item.equipped : "", sub.inv, invSort,
    perms.equip, perms.use, perms.drop,
    ((s.inventory || {})[sub.inv] || []).length,
  ].join("|");
  // The count changes on every keystroke in the search box, but rebuilding the footer would
  // destroy the box being typed into. So the count is updated in place and left out of the key.
  const updateCount = () => {
    const label = foot.querySelector(".invcount");
    if (!label) return;
    const bucket = (s.inventory || {})[sub.inv] || [];
    const total = bucket.length;

    // What this tab is costing you, and what it is worth. Deciding what to drop when you are over
    // the cap means comparing a tab against the others, and the Pip-Boy never totals them for you.
    let wg = 0, val = 0;
    for (const i of bucket) {
      const n = i.count || 1;
      wg += (i.weight || 0) * n;
      val += (i.value || 0) * n;
    }

    const count = invFilter.trim() ? `${shown} of ${total}` : `${total} entries`;
    // num() rounds, and a tab of ten light items reading "0 wg" would look broken, so weight keeps
    // its decimal here even though everywhere else it does not.
    label.textContent = `${count} · ${wg.toFixed(1)} wg · ${num(val)} caps`;
  };

  if (foot.dataset.key === key) { updateCount(); return; }
  foot.dataset.key = key;

  foot.textContent = "";

  const add = (label, allowed, why, fn, cls) => {
    const b = el("button", "btn" + (cls ? " " + cls : ""), label);
    b.disabled = !item || !allowed;
    if (item && !allowed) b.title = why;
    b.onclick = fn;
    foot.appendChild(b);
  };

  if (item && (sub.inv === "weapons" || sub.inv === "apparel"))
    add(item.equipped ? "UNEQUIP" : "EQUIP", perms.equip, "Equipping is off in the mod's config",
        () => act("equip", { id: item.id }));

  if (sub.inv === "aid")
    add("USE", perms.use, "Using items is off in the mod's config", () => act("use", { id: item.id }));

  add("DROP", perms.drop, "Dropping is off in the mod's config — it is off by default",
      () => confirmThen(foot, () => act("drop", { id: item.id, count: 1 })), "danger");

  if (item) {
    const bound = hotkeyFor(item.id);
    const hk = el("button", "btn", bound ? `UNPIN ${bound}` : "HOTKEY");
    hk.title = bound
      ? `Press ${bound} to use this. Click to unpin.`
      : "Pin this to a number key, so it can be used without opening this tab.";
    hk.onclick = () => { toggleHotkey(item); renderInv(state); };
    foot.appendChild(hk);
  }

  const sortBtn = el("button", "btn", SORTS[invSort][1]);
  sortBtn.title = "Cycle how this list is ordered. Equipped items stay at the top.";
  sortBtn.onclick = () => {
    invSort = (invSort + 1) % SORTS.length;
    if (state) renderInv(state);
    toast(SORTS[invSort][1].replace("SORT: ", "Sorted by "));
  };
  foot.appendChild(sortBtn);

  foot.appendChild(el("span", "spacer"));

  // Search. Rebuilt with the footer, so it carries its value across and keeps focus while typing.
  const search = el("input");
  search.type = "search";
  search.placeholder = "search…";
  search.className = "invsearch";
  search.value = invFilter;
  search.oninput = () => {
    invFilter = search.value;
    if (state) renderInv(state);   // the footer survives this; only the count is touched
  };
  foot.appendChild(search);

  foot.appendChild(el("span", "dim invcount", ""));
  updateCount();
}

/** Two-tap confirmation, so a stray touch never throws a weapon on the ground. */
function confirmThen(foot, fn) {
  const btn = foot.querySelector(".danger");
  if (!btn) return fn();
  if (btn.dataset.armed === "1") { btn.dataset.armed = ""; fn(); return; }
  btn.dataset.armed = "1";
  const was = btn.textContent;
  btn.textContent = "SURE?";
  setTimeout(() => { if (btn.dataset.armed === "1") { btn.dataset.armed = ""; btn.textContent = was; } }, 2500);
}

// ── DATA ──────────────────────────────────────────────────────────────────

function renderData(s) {
  if (sub.data === "quests") renderQuests(s);
  else if (sub.data === "notes") renderNotes(s);
  else if (sub.data === "stats") renderStats(s);
  else if (sub.data === "mods") renderLoadOrder(s);
}

/** World units to something readable. The game's unit is about an inch and a half. */
function distanceText(units) {
  if (units == null) return "";
  const metres = units * 0.0143;
  return metres < 1000 ? `${Math.round(metres)}m` : `${(metres / 1000).toFixed(1)}km`;
}

function renderQuests(s) {
  const quests = (s.quests || []).slice();
  const list = document.getElementById("quests");

  // Nearest first among the unfinished, when a distance is known. Standing in the Mojave with
  // twenty quests open, "which of these can I do from here" is the question being asked.
  if (questSort === "near") {
    quests.sort((a, b) => {
      if (!!a.completed !== !!b.completed) return a.completed ? 1 : -1;
      const da = a.distance == null ? Infinity : a.distance;
      const db = b.distance == null ? Infinity : b.distance;
      if (da !== db) return da - db;
      return (a.name || "").localeCompare(b.name || "");
    });
  }

  const key = questSort + "|" + quests.map((q) =>
    `${q.id}:${q.active ? 1 : 0}:${q.completed ? 1 : 0}:${(q.objectives || []).length}:${Math.round((q.distance || 0) / 500)}`).join(",");

  fill(list, key, (n) => {
    if (!quests.length) { n.appendChild(el("li", "empty", "No quests yet.")); return; }

    // The ordering toggle rides at the top of the list, since QUESTS has no footer of its own.
    const head = el("li", "grouphead");
    const toggle = el("button", "btn", questSort === "near" ? "NEAREST FIRST" : "GAME ORDER");
    toggle.title = "Order by the nearest outstanding objective, or leave the game's own order.";
    toggle.onclick = (e) => {
      e.stopPropagation();
      questSort = questSort === "near" ? "game" : "near";
      if (state) renderData(state);
      toast(questSort === "near" ? "Nearest objective first" : "Game order");
    };
    head.appendChild(toggle);
    n.appendChild(head);

    for (const [label, items] of [["Active", quests.filter((q) => !q.completed)],
                                  ["Completed", quests.filter((q) => q.completed)]]) {
      if (!items.length) continue;
      n.appendChild(el("li", "grouphead", label));
      for (const q of items) {
        const li = row(() => { selected.quests = q.id; renderData(state); });
        li.dataset.id = q.id;
        li.classList.toggle("done", !!q.completed);
        li.appendChild(el("span", "name", q.name));
        if (q.active) li.appendChild(el("span", "tag", "◆"));
        if (!q.completed && q.distance != null)
          li.appendChild(el("span", "val", distanceText(q.distance)));
        n.appendChild(li);
      }
    }
  });

  for (const li of list.querySelectorAll(".row"))
    li.setAttribute("aria-selected", String(li.dataset.id === selected.quests));

  const q = quests.find((x) => x.id === selected.quests);
  const card = document.getElementById("questcard");
  card.textContent = "";
  if (!q) { card.appendChild(el("p", "hint", "Select a quest.")); return; }

  card.appendChild(el("h4", null, q.name));
  if (q.completed) card.appendChild(el("p", "hint", "Completed."));
  else if (q.distance != null)
    card.appendChild(el("p", "hint", "Nearest objective " + distanceText(q.distance) + " away."));
  for (const o of q.objectives || [])
    card.appendChild(el("p", o.done ? "dim" : null, (o.done ? "☑ " : "☐ ") + o.text));
  if (!(q.objectives || []).length) card.appendChild(el("p", "hint", "No objectives recorded."));

  if ((s.perms || {}).setQuest && !q.completed) {
    const b = el("button", "btn wide", q.active ? "ACTIVE" : "SET ACTIVE");
    b.disabled = !!q.active;
    b.onclick = () => act("setQuest", { id: q.id });
    card.appendChild(b);
  }
}

function renderNotes(s) {
  const notes = s.notes || [];
  const list = document.getElementById("notes");
  fill(list, notes.map((n) => n.id).join(","), (n) => {
    if (!notes.length) { n.appendChild(el("li", "empty", "No notes.")); return; }
    for (const note of notes) {
      const li = row(() => { selected.notes = note.id; renderData(state); });
      li.dataset.id = note.id;
      li.appendChild(el("span", "name", note.name));
      li.appendChild(el("span", "tag", (note.type || "").toUpperCase()));
      n.appendChild(li);
    }
  });

  for (const li of list.querySelectorAll(".row"))
    li.setAttribute("aria-selected", String(li.dataset.id === selected.notes));

  const note = notes.find((x) => x.id === selected.notes);
  const card = document.getElementById("notecard");
  card.textContent = "";
  if (!note) { card.appendChild(el("p", "hint", "Select a note.")); return; }
  card.appendChild(el("h4", null, note.name));
  card.appendChild(el("p", "hint", note.type === "holotape" ? "Holotape" : "Note"));
  if (note.text) for (const para of note.text.split("\n")) card.appendChild(el("p", null, para));
}

function renderStats(s) {
  const stats = s.stats || [];
  const list = document.getElementById("stats");
  fill(list, stats.map((x) => x.name + "=" + x.value).join(","), (n) => {
    if (!stats.length) { n.appendChild(el("li", "empty", "No data.")); return; }
    let group = null;
    for (const st of stats) {
      if (st.group !== group) { group = st.group; n.appendChild(el("li", "grouphead", group)); }
      const li = el("li", "row");
      li.appendChild(el("span", "name", st.name));
      li.appendChild(el("span", "val", String(st.value)));
      n.appendChild(li);
    }
  });
}

/** The load order. Useful on its own, and it is what makes a bug report legible when a modded
 *  item shows up looking wrong. */
function renderLoadOrder(s) {
  const plugins = s.plugins || [];
  const list = document.getElementById("loadorder");

  fill(list, plugins.map((p) => p.name).join(","), (n) => {
    if (!plugins.length) { n.appendChild(el("li", "empty", "No load order reported.")); return; }
    for (const p of plugins) {
      const li = row(() => { selected.loadorder = p.name; renderData(state); });
      li.dataset.id = p.name;
      li.appendChild(el("span", "tag", p.index));
      li.appendChild(el("span", "name", p.name));
      if (p.master) li.appendChild(el("span", "tag", "ESM"));
      n.appendChild(li);
    }
  });

  for (const li of list.querySelectorAll(".row"))
    li.setAttribute("aria-selected", String(li.dataset.id === selected.loadorder));

  const plugin = plugins.find((x) => x.name === selected.loadorder);
  const card = document.getElementById("loadcard");
  card.textContent = "";
  if (!plugin) { card.appendChild(el("p", "hint", "Select a plugin.")); return; }

  card.appendChild(el("h4", null, plugin.name));
  const dl = el("dl");
  dl.appendChild(el("dt", null, "Load index"));
  dl.appendChild(el("dd", null, plugin.index));
  dl.appendChild(el("dt", null, "Type"));
  dl.appendChild(el("dd", null, plugin.master ? "Master (ESM)" : "Plugin (ESP)"));
  if (plugin.items != null) {
    dl.appendChild(el("dt", null, "Items carried"));
    dl.appendChild(el("dd", null, String(plugin.items)));
  }
  card.appendChild(dl);
}

// ── MAP ───────────────────────────────────────────────────────────────────

let mapMode = "local";
let mapZoom = 1;
let mapHit = [];

const MARKER_GLYPH = {
  Vault: "V", Town: "⌂", Settlement: "⌂", Cave: "◓", Factory: "⚙", Ruin: "⌗",
  Building: "▣", Camp: "▲", Military: "✚", Metro: "═", Monument: "▮", Office: "▤",
  Sewer: "◎", Mine: "◈", Landmark: "◇", City: "★", Unmarked: "·",
};

function renderMap(s) {
  const m = s.map || {};
  const canvas = document.getElementById("mapcanvas");
  const view = document.getElementById("mapview");

  const dpr = Math.min(devicePixelRatio || 1, 2);
  const w = Math.max(1, Math.round(view.clientWidth * dpr));
  const h = Math.max(1, Math.round(view.clientHeight * dpr));
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }

  const ctx = canvas.getContext("2d");
  const css = getComputedStyle(document.documentElement);
  const fg = css.getPropertyValue("--fg").trim() || "#3cff88";
  const dim = css.getPropertyValue("--fg-dim").trim() || fg;

  ctx.clearRect(0, 0, w, h);

  // Follow the player indoors and back out again.
  //
  // WORLD is meaningless in an interior, so entering one switches to LOCAL and leaving restores
  // whatever mode you were in outside. Only automatic switches are undone -- if you picked LOCAL
  // yourself out in the Mojave, walking through a door and back out leaves you on LOCAL.
  const insideNow = !(m.world && m.world.length);
  if (insideNow !== renderMap.wasInside) {
    if (insideNow) {
      renderMap.restoreTo = mapMode;
      mapMode = "local";
    } else if (renderMap.restoreTo) {
      mapMode = renderMap.restoreTo;
      renderMap.restoreTo = null;
    }
    renderMap.wasInside = insideNow;
  }

  const bounds = mapMode === "world" ? m.worldBounds : m.localBounds;

  // The worldspace map, from the game's own 2048px texture. Loaded once and reused; if the mod
  // is not serving assets it simply never arrives and the grid below stands in.
  if (!renderMap.art) {
    renderMap.art = new Image();
    renderMap.art.onload = () => { if (state) renderMap(state); };
    // The 1024 mip, not the 2048. The larger one decodes to 16 MB and the encoder needs roughly
    // triple that at once, which a 32-bit game cannot reliably find -- the mod refuses it above a
    // megapixel for that reason. Scaled up to a panel this size the difference is invisible.
    renderMap.art.src = withToken("asset/interface/worldmap/wasteland_nv_1024_no_map.png");
  }
  if (!bounds) {
    ctx.fillStyle = dim;
    ctx.font = `${14 * dpr}px monospace`;
    ctx.textAlign = "center";
    ctx.fillText("No map data for this area.", w / 2, h / 2);
    mapHit = [];
    return;
  }

  const bw = bounds.maxX - bounds.minX || 1;
  const bh = bounds.maxY - bounds.minY || 1;
  const scale = Math.min(w / bw, h / bh) * mapZoom;

  const cx = mapZoom > 1 && m.x != null ? m.x : (bounds.minX + bounds.maxX) / 2;
  const cy = mapZoom > 1 && m.y != null ? m.y : (bounds.minY + bounds.maxY) / 2;

  const px = (wx) => w / 2 + (wx - cx) * scale;
  const py = (wy) => h / 2 - (wy - cy) * scale;

  // Draw the map itself first, stretched across the worldspace bounds so markers plotted from
  // world coordinates land where they belong on it. Tinted to the current phosphor rather than
  // left sepia, so a custom colour scheme still holds.
  // Only in WORLD mode, and only when there is a worldspace to be the map of.
  //
  // It was drawn in both modes and everywhere, so walking into a building put the Mojave behind a
  // room-sized local view -- a world map claiming to be a floor plan. Interiors report no
  // worldspace name, which is how the page knows.
  const art = renderMap.art;
  const hasWorld = !!(m.world && m.world.length);
  if (mapMode === "world" && hasWorld && art && art.complete && art.naturalWidth) {
    const ax = px(bounds.minX), ay = py(bounds.maxY);
    ctx.save();
    ctx.globalAlpha = 0.55;
    ctx.drawImage(art, ax, ay, bw * scale, bh * scale);
    // Multiply a flat phosphor over the top: the source is a sepia photograph and the panel is
    // monochrome, so this keeps it one colour with the rest of the screen.
    ctx.globalCompositeOperation = "multiply";
    ctx.fillStyle = fg;
    ctx.globalAlpha = 0.5;
    ctx.fillRect(ax, ay, bw * scale, bh * scale);
    ctx.restore();
  }

  ctx.strokeStyle = dim;
  ctx.globalAlpha = 0.16;
  ctx.lineWidth = 1;
  const step = niceStep(bw / 8);
  ctx.beginPath();
  for (let gx = Math.ceil(bounds.minX / step) * step; gx <= bounds.maxX; gx += step) {
    ctx.moveTo(px(gx), 0); ctx.lineTo(px(gx), h);
  }
  for (let gy = Math.ceil(bounds.minY / step) * step; gy <= bounds.maxY; gy += step) {
    ctx.moveTo(0, py(gy)); ctx.lineTo(w, py(gy));
  }
  ctx.stroke();
  ctx.globalAlpha = 1;

  ctx.strokeStyle = dim;
  ctx.globalAlpha = 0.45;
  ctx.strokeRect(px(bounds.minX), py(bounds.maxY), bw * scale, bh * scale);
  ctx.globalAlpha = 1;

  // The local sketch, drawn under the markers. Doors and containers are what you navigate by, so
  // they carry a glyph; furniture is just dots, enough to imply the shape of the room.
  const localRefs = m.localRefs || [];
  if (mapMode === "local" && localRefs.length) {
    ctx.save();
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.font = `${10 * dpr}px monospace`;

    for (const ref of localRefs) {
      const x = px(ref.x), y = py(ref.y);
      if (x < 0 || y < 0 || x > w || y > h) continue;

      if (ref.kind === "furniture") {
        ctx.globalAlpha = 0.3;
        ctx.fillStyle = dim;
        ctx.fillRect(x - 1.5 * dpr, y - 1.5 * dpr, 3 * dpr, 3 * dpr);
        continue;
      }

      ctx.globalAlpha = ref.kind === "actor" ? 0.85 : 0.6;
      ctx.fillStyle = fg;
      ctx.fillText(ref.kind === "door" ? "▯" : ref.kind === "actor" ? "●" : "□", x, y);
    }
    ctx.restore();
  }

  mapHit = [];
  ctx.font = `${12 * dpr}px monospace`;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";

  for (const mk of m.markers || []) {
    const x = px(mk.x), y = py(mk.y);
    if (x < -20 || y < -20 || x > w + 20 || y > h + 20) continue;

    ctx.globalAlpha = mk.visited ? 1 : 0.4;
    ctx.fillStyle = fg;
    ctx.strokeStyle = fg;

    ctx.beginPath();
    ctx.arc(x, y, 5 * dpr, 0, Math.PI * 2);
    mk.visited ? ctx.fill() : ctx.stroke();

    ctx.fillStyle = mk.visited ? css.getPropertyValue("--bg").trim() || "#000" : fg;
    ctx.fillText(MARKER_GLYPH[mk.type] || "•", x, y + 0.5 * dpr);

    if (mapZoom >= 1.5 || (m.markers || []).length < 24) {
      ctx.fillStyle = fg;
      ctx.globalAlpha = mk.visited ? 0.85 : 0.4;
      ctx.fillText(mk.name, x, y + 14 * dpr);
    }
    ctx.globalAlpha = 1;

    mapHit.push({ x, y, r: 12 * dpr, marker: mk });
  }

  // You. Drawn last so nothing covers it, and deliberately unlike a marker: a marker is a filled
  // disc with a glyph, so an arrow the same colour and size sitting on one reads as "the marker
  // became the player" -- which is exactly how this looked after fast travelling onto a location.
  // The punched-out ring is what separates the two at a glance.
  if (m.x != null) {
    const x = px(m.x), y = py(m.y);
    ctx.save();
    ctx.translate(x, y);

    ctx.rotate(((m.angle || 0) * Math.PI) / 180);

    ctx.fillStyle = fg;
    ctx.strokeStyle = css.getPropertyValue("--bg").trim() || "#000";
    ctx.lineWidth = 2.5 * dpr;
    ctx.beginPath();
    ctx.moveTo(0, -12 * dpr);
    ctx.lineTo(8 * dpr, 9 * dpr);
    ctx.lineTo(0, 5 * dpr);
    ctx.lineTo(-8 * dpr, 9 * dpr);
    ctx.closePath();
    ctx.stroke();
    ctx.fill();
    ctx.restore();
  }

  const where = document.getElementById("mapwhere");
  where.textContent = "";
  where.appendChild(el("h4", null, m.cell || m.world || "Unknown"));
  if (m.x != null) where.appendChild(el("p", "hint", `${Math.round(m.x)}, ${Math.round(m.y)}`));
  // Which way you are facing, in words. The arrow on the map already shows it, but a bearing is
  // what directions are actually given in -- and it stays readable when the arrow is a few pixels.
  if (m.angle != null)
    where.appendChild(el("p", "hint", `facing ${heading(m.angle)} · ${Math.round(m.angle)}°`));

  const visited = (m.markers || []).filter((x) => x.visited);
  const mlist = document.getElementById("markers");
  fill(mlist, visited.map((x) => x.name).join(",") + "|" + String((s.perms || {}).fastTravel), (n) => {
    if (!visited.length) { n.appendChild(el("li", "empty", "Nothing discovered here.")); return; }
    for (const mk of visited.slice().sort((a, b) => a.name.localeCompare(b.name))) {
      const li = row(() => tryFastTravel(mk));
      li.appendChild(el("span", "name", mk.name));
      if (mk.canFastTravel && (s.perms || {}).fastTravel) li.appendChild(el("span", "tag", "GO"));
      n.appendChild(li);
    }
  });

  // Inside a building there is no world map to show, so say so rather than presenting an empty
  // grid that looks like a failure. The local view still plots you and anything nearby.
  if (!hasWorld) {
    ctx.fillStyle = dim;
    ctx.font = `${12 * dpr}px monospace`;
    ctx.textAlign = "center";
    ctx.fillText(mapMode === "world"
      ? "No world map indoors — " + (m.cell || "interior")
      : (m.cell || "Interior"), w / 2, 18 * dpr);

    // Say what this is. It is a sketch from the objects in the room, not the game's own local
    // map, and it should never be mistaken for one.
    ctx.font = `${10 * dpr}px monospace`;
    ctx.globalAlpha = 0.65;
    ctx.fillText(localRefs.length
      ? "approximate — plotted from objects in the cell, not the game's local map"
      : "no local map — enable it in the gear under MOD", w / 2, h - 10 * dpr);
    ctx.globalAlpha = 1;
  }

  for (const b of document.querySelectorAll("[data-mapmode]")) {
    b.setAttribute("aria-pressed", String(b.dataset.mapmode === mapMode));
    // WORLD is meaningless in an interior; grey it out rather than letting it show nothing.
    b.disabled = (b.dataset.mapmode === "world") && !hasWorld;
  }
  document.getElementById("mapzoom").textContent = mapZoom.toFixed(1) + "x";
}

function niceStep(raw) {
  const pow = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1))));
  const n = raw / pow;
  return (n < 1.5 ? 1 : n < 3.5 ? 2 : n < 7.5 ? 5 : 10) * pow;
}

let pendingTravel = null;

function tryFastTravel(mk) {
  if (!(state && state.perms && state.perms.fastTravel)) {
    showTip("Fast travel is off in the mod's config.");
    return;
  }
  if (!mk.canFastTravel) { showTip(`${mk.name} can't be fast travelled to.`); return; }

  if (pendingTravel === mk.name) {
    pendingTravel = null;
    // The mod re-checks this ID against the real marker before moving anywhere, so sending it is
    // not the same as being trusted with it.
    act("fastTravel", { id: mk.id, marker: mk.name });
    return;
  }
  pendingTravel = mk.name;
  showTip(`Tap again to travel to ${mk.name}.`);
  setTimeout(() => { if (pendingTravel === mk.name) pendingTravel = null; }, 3000);
}

function showTip(text, x, y) {
  const tip = document.getElementById("maptip");
  tip.textContent = text;
  tip.hidden = false;
  tip.style.left = (x != null ? x : 8) + "px";
  tip.style.top = (y != null ? y : 8) + "px";
  clearTimeout(showTip.t);
  showTip.t = setTimeout(() => { tip.hidden = true; }, 2600);
}

document.getElementById("mapcanvas").addEventListener("click", (e) => {
  const canvas = e.currentTarget;
  const box = canvas.getBoundingClientRect();
  const sx = (e.clientX - box.left) * (canvas.width / box.width);
  const sy = (e.clientY - box.top) * (canvas.height / box.height);

  let best = null, bestD = Infinity;
  for (const hit of mapHit) {
    const d = Math.hypot(hit.x - sx, hit.y - sy);
    if (d < hit.r && d < bestD) { best = hit; bestD = d; }
  }
  if (!best) return;

  const mk = best.marker;
  if (!mk.visited) { showTip(`${mk.name} — undiscovered`, e.clientX - box.left, e.clientY - box.top); return; }
  tryFastTravel(mk);
});

document.getElementById("mapfoot").addEventListener("click", (e) => {
  const b = e.target.closest("button");
  if (!b) return;
  if (b.dataset.mapmode) { mapMode = b.dataset.mapmode; mapZoom = 1; }
  if (b.dataset.mapzoom) mapZoom = Math.max(1, Math.min(6, mapZoom + Number(b.dataset.mapzoom) * 0.5));
  if (state) renderMap(state);
});

// ── RADIO ─────────────────────────────────────────────────────────────────

/** Whether the RADIO tab is in "choose what to list" mode rather than "tune something" mode. */
let radioEdit = false;

function renderRadioFoot(total, shown) {
  const foot = document.getElementById("radiofoot");
  const key = `${radioEdit}|${total}|${shown}`;
  if (foot.dataset.key === key) return;
  foot.dataset.key = key;
  foot.textContent = "";

  const edit = el("button", "btn", radioEdit ? "DONE" : "EDIT LIST");
  edit.title = "Choose which stations this screen lists.";
  edit.onclick = () => { radioEdit = !radioEdit; if (state) renderRadio(state); };
  foot.appendChild(edit);

  if (radioEdit) {
    const all = el("button", "btn", "SHOW ALL");
    all.onclick = () => {
      settings.hiddenStations = {};
      save();
      if (state) renderRadio(state);
      toast("All stations shown");
    };
    foot.appendChild(all);
  }

  foot.appendChild(el("span", "spacer"));
  foot.appendChild(el("span", "dim",
    shown === total ? `${total} stations` : `${shown} of ${total} shown`));
}

function renderRadio(s) {
  const stations = s.radio || [];
  const list = document.getElementById("stations");
  const key = stations.map((r) =>
    `${r.id}:${r.active ? 1 : 0}:${r.inRange ? 1 : 0}:${r.canTune ? 1 : 0}:${r.name}`).join(",");

  // Alphabetical. Sorting by "in range" was tried and dropped: that flag is derived from data the
  // mod cannot read reliably, so ordering by it moved rows around for no reason the screen could
  // justify.
  const all = stations.slice().sort((a, b) => (a.name || "").localeCompare(b.name || ""));
  const ordered = radioEdit ? all : all.filter((st) => !settings.hiddenStations[st.name]);

  fill(list, key + "|" + (radioEdit ? "edit" : "") + "|" + Object.keys(settings.hiddenStations).join(","), (n) => {
    if (!all.length) { n.appendChild(el("li", "empty", "No stations found.")); return; }
    if (!ordered.length) {
      n.appendChild(el("li", "empty", "Every station is hidden — tap EDIT LIST."));
      return;
    }
    for (const st of all) {
      const hidden = !!settings.hiddenStations[st.name];
      if (hidden && !radioEdit) continue;

      const li = row(() => {
        if (radioEdit) {
          if (hidden) delete settings.hiddenStations[st.name];
          else settings.hiddenStations[st.name] = true;
          save();
          renderRadio(state);
          return;
        }
        if (!(s.perms || {}).radio) return;
        if (!st.canTune) { toast(st.name + " — no transmitter loaded to tune"); return; }
        act("radio", { id: st.active ? "" : st.id });
      });
      li.dataset.id = st.id;
      li.setAttribute("aria-selected", String(!radioEdit && !!st.active));
      li.appendChild(el("span", "name", st.name));

      if (radioEdit) li.appendChild(el("span", "tag", hidden ? "HIDDEN" : "SHOWN"));
      if (hidden) li.classList.add("dim");
      else if (!st.canTune) li.classList.add("dim");
      n.appendChild(li);
    }
  });

  renderRadioFoot(all.length, ordered.length);

  const active = stations.find((r) => r.active);
  const now = document.getElementById("nowplaying");
  now.textContent = "";
  now.appendChild(el("h4", null, active ? active.name : "Radio off"));
  if (!(s.perms || {}).radio) now.appendChild(el("p", "hint", "Radio control is off in the mod's config."));
  else if (active) now.appendChild(el("p", "hint", "Tap again to switch it off."));

  // Where this list came from. It matters: one of these is the game's own answer and the other is
  // everything the mod could find, and they are not close to the same thing.
  if (stations.length && stations[0].fromMenu)
    now.appendChild(el("p", "hint", "Read from the Pip-Boy's own dial."));
  else if (stations.length)
    now.appendChild(el("p", "hint",
      "Open DATA → Radio in game once — the Pip-Boy's own list is read from there, and it is " +
      "the only accurate one. Until then this is every station the mod can find."));

  if (radioEdit)
    now.appendChild(el("p", "hint",
      "Tap a station to show or hide it, kept per screen."));

  drawWave(!!active);
}

function drawWave(on) {
  const canvas = document.getElementById("wave");
  const box = canvas.parentElement.getBoundingClientRect();
  const dpr = Math.min(devicePixelRatio || 1, 2);
  const w = Math.max(1, Math.round((box.width - 20) * dpr));
  const h = Math.round(90 * dpr);
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  canvas.style.width = "100%";

  const ctx = canvas.getContext("2d");
  const fg = getComputedStyle(document.documentElement).getPropertyValue("--fg").trim() || "#3cff88";
  ctx.clearRect(0, 0, w, h);
  ctx.strokeStyle = fg;
  ctx.lineWidth = 1.5 * dpr;
  ctx.beginPath();

  const t = Date.now() / 220;
  for (let x = 0; x <= w; x++) {
    const u = x / w;
    const amp = on ? (h / 2 - 4 * dpr) * (0.35 + 0.65 * Math.abs(Math.sin(u * 3 + t * 0.35))) : 1.5 * dpr;
    const y = h / 2 + Math.sin(u * 42 + t) * amp * (on ? Math.sin(u * Math.PI) : 1);
    x === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
  }
  ctx.stroke();
}

// ── sound ─────────────────────────────────────────────────────────────────
//
// Synthesised, not sampled. A few oscillators and a burst of noise, which costs nothing to ship
// and nothing to licence.

let audio = null;
let hum = null;

function sound() {
  if (settings.sound !== "on") return null;
  if (!audio) {
    try { audio = new (window.AudioContext || window.webkitAudioContext)(); }
    catch (e) { return null; }
  }
  if (audio.state === "suspended") audio.resume();
  return audio;
}

function click(level = 0.18, duration = 0.045) {
  const ctx = sound();
  if (!ctx) return;

  const frames = Math.floor(ctx.sampleRate * duration);
  const buffer = ctx.createBuffer(1, frames, ctx.sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < frames; i++)
    data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / frames, 3);

  const src = ctx.createBufferSource();
  src.buffer = buffer;

  const band = ctx.createBiquadFilter();
  band.type = "bandpass";
  band.frequency.value = 1600;
  band.Q.value = 0.9;

  const gain = ctx.createGain();
  gain.gain.value = level;

  src.connect(band).connect(gain).connect(ctx.destination);
  src.start();
}

/** A short tone. Used for warnings, so they carry when you are looking at the game, not the panel. */
function beep(frequency = 440, duration = 0.18, level = 0.06) {
  const ctx = sound();
  if (!ctx) return;

  const osc = ctx.createOscillator();
  osc.type = "square";
  osc.frequency.value = frequency;

  const gain = ctx.createGain();
  // Ramped rather than switched, because an abrupt stop on a square wave clicks.
  gain.gain.setValueAtTime(level, ctx.currentTime);
  gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + duration);

  osc.connect(gain).connect(ctx.destination);
  osc.start();
  osc.stop(ctx.currentTime + duration);
}

// ── warnings ──────────────────────────────────────────────────────────────
//
// Only on the transition, never on the state. A tone every tenth of a second while you happen to
// be hurt would be unbearable and would train you to ignore it.

const warned = { health: false, limbs: {}, gear: {} };

function checkWarnings(p, s) {
  const fraction = pct(p.hp, p.hpMax);

  if (fraction > 0 && fraction <= 0.25) {
    if (!warned.health) { warned.health = true; beep(320, 0.5, 0.07); toast("Health critical"); }
  } else if (fraction > 0.35) {
    warned.health = false;                     // hysteresis, so hovering at the line is not a siren
  }

  const cond = p.condition || {};
  for (const [key, label] of LIMBS) {
    const crippled = cond[key] != null && cond[key] <= 0;
    if (crippled && !warned.limbs[key]) {
      warned.limbs[key] = true;
      beep(220, 0.35, 0.07);
      toast(label + " crippled");
    } else if (!crippled) {
      warned.limbs[key] = false;
    }
  }

  // Equipped gear wearing out. A weapon that breaks mid-fight is worse than a weapon you knew was
  // about to, and the game itself says nothing until it has already jammed.
  //
  // Keyed by item id and not by slot, so swapping to a second worn-out gun warns again rather than
  // staying silent because the slot was already flagged.
  for (const slot of ["weapons", "apparel"]) {
    // The worst of what is worn, not the first -- apparel is several slots at once, and the piece
    // about to fall apart is rarely the one the list happens to start with.
    const item = (((s || {}).inventory || {})[slot] || [])
      .filter((i) => i.equipped && i.health != null)
      .sort((a, b) => a.health - b.health)[0];
    if (!item) { warned.gear[slot] = null; continue; }

    if (item.health <= 0.25) {
      if (warned.gear[slot] !== item.id) {
        warned.gear[slot] = item.id;
        beep(180, 0.3, 0.06);
        toast(`${item.name} at ${Math.round(item.health * 100)}%`);
      }
    } else if (item.health > 0.35) {
      // Whatever is worn now is fine, so forget what was flagged. This has to clear regardless of
      // which item is worn: keying the reset on the flagged id meant swapping to a healthy weapon
      // left the flag set, and swapping back to the broken one then warned about nothing.
      warned.gear[slot] = null;
    }
  }
}

/** Sixteen-point compass label for a heading in degrees, as the game's own compass reads. */
const COMPASS = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                 "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];

function heading(degrees) {
  const d = ((degrees % 360) + 360) % 360;
  return COMPASS[Math.round(d / 22.5) % 16];
}

function startHum() {
  const ctx = sound();
  if (!ctx || hum) return;

  const osc = ctx.createOscillator();
  osc.type = "sine";
  osc.frequency.value = 60;

  const harmonic = ctx.createOscillator();
  harmonic.type = "sine";
  harmonic.frequency.value = 120;

  const gain = ctx.createGain();
  gain.gain.value = 0.012;

  osc.connect(gain);
  harmonic.connect(gain);
  gain.connect(ctx.destination);
  osc.start();
  harmonic.start();

  hum = { osc, harmonic };
}

function stopHum() {
  if (!hum) return;
  try { hum.osc.stop(); hum.harmonic.stop(); } catch (e) {}
  hum = null;
}

addEventListener("pointerdown", () => { startHum(); }, { once: true });

// ── keeping the panel awake ───────────────────────────────────────────────
//
// A second screen that blanks after thirty seconds is not a second screen. The Android app holds
// its own wake lock, but a plain browser will not, and this page is meant to work in either.
//
// The lock is dropped by the browser whenever the tab is hidden, so it has to be retaken when the
// tab comes back rather than acquired once at startup.

let wakeLock = null;

async function keepAwake() {
  if (!("wakeLock" in navigator)) return;      // Safari, older Android, any desktop browser
  try {
    wakeLock = await navigator.wakeLock.request("screen");
    wakeLock.addEventListener("release", () => { wakeLock = null; });
  } catch (e) {
    // Refused -- usually because the tab is not visible. Retried on the next visibility change.
  }
}

addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && !wakeLock) keepAwake();
});

// ── boot sequence ─────────────────────────────────────────────────────────

// Deliberately NOT the game's own terminal boot text, which is Bethesda's writing. This says the
// same kind of thing in its own words, so nothing copyrighted ships in the page.
const BOOT_LINES = [
  "************ AYN DUAL SCREEN ************",
  "",
  "SECOND-SCREEN TERMINAL",
  "UNIFIED DISPLAY LINK — BUILD 0.1",
  "",
  "INITIALISING SECOND SCREEN LINK...",
  "SCANNING FOR HOST.....................",
  "",
  "LOADING STAT MODULE...............[ OK ]",
  "LOADING INV MODULE................[ OK ]",
  "LOADING DATA MODULE...............[ OK ]",
  "LOADING MAP MODULE................[ OK ]",
  "LOADING RADIO MODULE..............[ OK ]",
  "",
  "READY",
];

function runBoot() {
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (settings.boot !== "on" || reduced) return Promise.resolve();

  const host = document.getElementById("boot");
  const log = document.getElementById("bootlog");
  host.hidden = false;
  log.textContent = "";

  return new Promise((done) => {
    let line = 0;
    const next = () => {
      if (line >= BOOT_LINES.length) {
        setTimeout(() => {
          host.classList.add("done");
          setTimeout(() => { host.hidden = true; host.classList.remove("done"); done(); }, 500);
        }, 260);
        return;
      }
      log.textContent += BOOT_LINES[line++] + "\n";
      if (BOOT_LINES[line - 1]) click(0.05, 0.02);
      setTimeout(next, 55 + Math.random() * 45);
    };
    next();
  });
}

// ── settings panel ────────────────────────────────────────────────────────

let setSection = "colour";

const SECTIONS = [["colour", "COLOUR"], ["layout", "LAYOUT"], ["screen", "SCREEN"], ["tabs", "TABS"],
                  ["mod", "MOD"]];

// The mod's own ini, fetched from /config. Distinct from `settings` above, which never leaves this
// browser: these change the plugin for everyone connected, and are saved to disk by the mod.
let modConfig = null;

async function loadModConfig() {
  try {
    const res = await fetch("config", { cache: "no-store", headers: authHeaders() });
    modConfig = (await res.json()).settings || [];
  } catch (e) {
    modConfig = null;      // mod not reachable, or an older build without the endpoint
  }
}

async function setModSetting(key, value) {
  try {
    const res = await fetch("config", {
      method: "POST",
      headers: Object.assign({ "Content-Type": "application/json" }, authHeaders()),
      body: JSON.stringify({ key, value: String(value) }),
    });
    const out = await res.json();
    if (!out.ok) return false;
    // Re-read rather than assume: the mod clamps values, so what it stored may not be what we sent.
    await loadModConfig();
    return true;
  } catch (e) {
    return false;
  }
}

function buildSetNav() {
  const nav = document.getElementById("setnav");
  nav.textContent = "";
  for (const [key, label] of SECTIONS) {
    const b = el("button", "btn", label);
    b.setAttribute("aria-pressed", String(key === setSection));
    b.onclick = () => { setSection = key; buildSetNav(); buildSettings(); };
    nav.appendChild(b);
  }
}

function buildSettings() {
  const host = document.getElementById("settingsbody");
  host.textContent = "";

  const addRow = (label, node) => {
    const r = el("div", "setrow");
    r.appendChild(el("label", null, label));
    r.appendChild(node);
    host.appendChild(r);
  };

  const choices = (values, current, labelFor, onPick) => {
    const box = el("div", "choices");
    for (const v of values) {
      const b = el("button", "btn", labelFor(v));
      b.setAttribute("aria-pressed", String(v === current));
      b.onclick = () => { onPick(v); save(); buildSettings(); if (state) render(state); };
      box.appendChild(b);
    }
    return box;
  };

  /** A slider plus its number, both writing the same setting. */
  const slider = (key, min, max, step, suffix) => {
    const wrap = el("div", "setrow");
    wrap.style.flex = "1";
    wrap.style.margin = "0";
    const input = el("input");
    input.type = "range";
    input.min = min; input.max = max; input.step = step;
    input.value = settings[key];
    const out = el("span", "num", settings[key] + (suffix || ""));
    input.oninput = () => {
      settings[key] = Number(input.value);
      out.textContent = settings[key] + (suffix || "");
      applySettings();          // live, without rebuilding the panel under the finger
    };
    input.onchange = () => save();
    wrap.appendChild(input);
    wrap.appendChild(out);
    return wrap;
  };

  const colour = (key, fallback) => {
    const input = el("input");
    input.type = "color";
    input.value = settings[key] || fallback;
    input.oninput = () => { settings[key] = input.value; applySettings(); };
    input.onchange = () => save();
    return input;
  };

  if (setSection === "colour") {
    addRow("Preset", choices(Object.keys(PRESETS), null,
      (v) => v[0].toUpperCase() + v.slice(1),
      (v) => { Object.assign(settings, PRESETS[v]); settings.accent = ""; }));

    addRow("Phosphor", colour("fg", "#3cff88"));
    addRow("Background", colour("bg", "#0a1a0f"));

    const acc = el("div", "choices");
    acc.appendChild(colour("accent", settings.accent || settings.fg));
    const same = el("button", "btn", "MATCH");
    same.setAttribute("aria-pressed", String(!settings.accent));
    same.onclick = () => { settings.accent = ""; save(); buildSettings(); };
    acc.appendChild(same);
    addRow("Selection", acc);

    addRow("Glow", slider("glow", 0, 20, 1, "px"));
    addRow("Scanlines", slider("scan", 0, 60, 1, "%"));
    addRow("Scanline gap", slider("scanGap", 2, 8, 1, "px"));
    addRow("Vignette", slider("vignette", 0, 100, 5, "%"));
  }

  if (setSection === "layout") {
    addRow("Text size", choices([0, 1, 2, 3, 4, 5, 6], settings.size,
      (v) => ["XXS", "XS", "S", "M", "L", "XL", "XXL"][v], (v) => { settings.size = v; }));

    addRow("Font", choices(Object.keys(FONTS), settings.font,
      (v) => v[0].toUpperCase() + v.slice(1), (v) => { settings.font = v; }));

    addRow("Row spacing", choices([0, 1, 2, 3, 4], settings.density,
      (v) => ["TIGHT", "SNUG", "NORMAL", "AIRY", "WIDE"][v], (v) => { settings.density = v; }));

    addRow("Detail panel", choices(["on", "off"], settings.cards,
      (v) => v.toUpperCase(), (v) => { settings.cards = v; }));

    addRow("Panel width", slider("split", 20, 60, 1, "%"));
    addRow("Corner radius", slider("radius", 0, 32, 1, "px"));
    addRow("Tab brackets", choices([true, false], settings.brackets,
      (v) => (v ? "ON" : "OFF"), (v) => { settings.brackets = v; }));
  }

  if (setSection === "screen") {
    addRow("Casing", choices(["on", "off"], settings.bezel, (v) => v.toUpperCase(), (v) => { settings.bezel = v; }));
    addRow("Device textures", choices(["on", "off"], settings.deviceTex, (v) => v.toUpperCase(), (v) => {
      settings.deviceTex = v;
      if (v === "on") loadDeviceTextures();
      else document.documentElement.dataset.deviceTextures = "off";
    }));
    addRow("Boot sequence", choices(["on", "off"], settings.boot, (v) => v.toUpperCase(), (v) => { settings.boot = v; }));
    addRow("Sound", choices(["on", "off"], settings.sound, (v) => v.toUpperCase(), (v) => {
      settings.sound = v;
      if (v === "off") stopHum(); else startHum();
    }));
    addRow("Updates / sec", choices([5, 10, 15, 20, 30], settings.rate, String, (v) => { settings.rate = v; }));
    const replay = el("button", "btn", "REPLAY BOOT");
    replay.onclick = () => { document.getElementById("settings").hidden = true; runBoot(); };
    addRow("", replay);
  }

  if (setSection === "mod") {
    if (modConfig === null) {
      host.appendChild(el("p", "hint",
        "Can't reach the mod's settings. Either the game isn't running, or this is an older build."));
      return;
    }

    host.appendChild(el("p", "hint",
      "These change the mod itself and are saved to AynDualScreen.ini. Everything above only "
      + "affects this browser; these affect every screen."));

    for (const setting of modConfig) {
      const control = setting.type === "bool"
        ? choicesFor([true, false], setting.value, (v) => (v ? "ON" : "OFF"),
            (v) => setModSetting(setting.key, v ? "1" : "0"))
        : numberFor(setting);

      const r = el("div", "setrow");
      const label = el("label", null, setting.label + (setting.restart ? " *" : ""));
      label.title = setting.help + (setting.restart ? "  (needs a game restart)" : "");
      r.appendChild(label);
      r.appendChild(control);
      host.appendChild(r);
    }

    host.appendChild(el("p", "hint", "* needs the game restarted before it takes effect."));
  }

  /** Like `choices`, but the pick is async and the panel rebuilds once the mod confirms. */
  function choicesFor(values, current, labelFor, onPick) {
    const box = el("div", "choices");
    for (const v of values) {
      const b = el("button", "btn", labelFor(v));
      b.setAttribute("aria-pressed", String(v === current));
      b.onclick = async () => {
        b.disabled = true;
        await onPick(v);
        buildSettings();
      };
      box.appendChild(b);
    }
    return box;
  }

  /** A number box that only commits on blur or Enter, so typing "20" isn't read as "2". */
  function numberFor(setting) {
    const wrap = el("div", "choices");
    const input = el("input");
    input.type = "number";
    input.min = setting.min;
    input.max = setting.max;
    input.value = setting.value;
    input.style.width = "6rem";
    const commit = async () => {
      if (String(input.value) === String(setting.value)) return;
      await setModSetting(setting.key, input.value);
      buildSettings();
    };
    input.onblur = commit;
    input.onkeydown = (e) => { if (e.key === "Enter") input.blur(); };
    wrap.appendChild(input);
    return wrap;
  }

  if (setSection === "tabs") {
    host.appendChild(el("p", "hint", "Which tabs appear, and in what order."));

    for (let i = 0; i < settings.tabs.length; i++) {
      const key = settings.tabs[i];
      const r = el("div", "orderrow");
      r.appendChild(el("span", "name", TAB_LABELS[key]));

      const up = el("button", "btn", "▲");
      up.disabled = i === 0;
      up.onclick = () => {
        [settings.tabs[i - 1], settings.tabs[i]] = [settings.tabs[i], settings.tabs[i - 1]];
        save(); buildSettings();
      };

      const down = el("button", "btn", "▼");
      down.disabled = i === settings.tabs.length - 1;
      down.onclick = () => {
        [settings.tabs[i + 1], settings.tabs[i]] = [settings.tabs[i], settings.tabs[i + 1]];
        save(); buildSettings();
      };

      const off = el("button", "btn", "HIDE");
      off.disabled = settings.tabs.length === 1;
      off.onclick = () => {
        settings.tabs = settings.tabs.filter((t) => t !== key);
        save(); buildSettings();
      };

      r.append(up, down, off);
      host.appendChild(r);
    }

    const hidden = ALL_TABS.filter((t) => !settings.tabs.includes(t));
    if (hidden.length) {
      host.appendChild(el("h3", null, "Hidden"));
      for (const key of hidden) {
        const r = el("div", "orderrow");
        r.appendChild(el("span", "name", TAB_LABELS[key]));
        const on = el("button", "btn", "SHOW");
        on.onclick = () => { settings.tabs.push(key); save(); buildSettings(); };
        r.appendChild(on);
        host.appendChild(r);
      }
    }
  }
}

document.getElementById("gear").onclick = async () => {
  buildSetNav();
  buildSettings();
  document.getElementById("settings").hidden = false;
  // Fetch the mod's own settings in the background; the panel redraws when they land.
  await loadModConfig();
  if (setSection === "mod") buildSettings();
};
document.getElementById("closesettings").onclick = () => { document.getElementById("settings").hidden = true; };
document.getElementById("settings").onclick = (e) => {
  if (e.target.id === "settings") e.currentTarget.hidden = true;
};

document.getElementById("resetsettings").onclick = () => {
  settings = Object.assign({}, DEFAULTS);
  settings.tabs = ALL_TABS.slice();
  save();
  buildSettings();
  setPage(settings.tabs[0]);
};

// A profile is just the settings object as JSON, so it can be carried between screens by hand.
document.getElementById("exportsettings").onclick = async () => {
  const text = JSON.stringify(settings);
  try {
    await navigator.clipboard.writeText(text);
    showToast("Profile copied to the clipboard.");
  } catch (e) {
    prompt("Copy this profile:", text);
  }
};

document.getElementById("importsettings").onclick = () => {
  const text = prompt("Paste a profile:");
  if (!text) return;
  try {
    const incoming = JSON.parse(text);
    if (!incoming || typeof incoming !== "object") throw new Error("not an object");
    settings = Object.assign({}, DEFAULTS, incoming);
    if (!Array.isArray(settings.tabs) || !settings.tabs.length) settings.tabs = ALL_TABS.slice();
    save();
    buildSettings();
    setPage(settings.tabs[0]);
  } catch (e) {
    showToast("That wasn't a profile.");
  }
};

function showToast(text) {
  const host = document.getElementById("settingsbody");
  const p = el("p", "hint", text);
  host.prepend(p);
  setTimeout(() => p.remove(), 2200);
}

// ── go ────────────────────────────────────────────────────────────────────

applySettings();
setPage(page);
loadDeviceTextures();
runBoot();
poll();
setInterval(updateDot, 500);
setInterval(() => { if (page === "radio" && state) drawWave(!!(state.radio || []).find((r) => r.active)); }, 90);
addEventListener("resize", () => { if (state && page === "map") renderMap(state); });
