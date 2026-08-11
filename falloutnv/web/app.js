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
  sound: "on",
  boot: "on",
  cards: "on",
  rate: 10,
  tabs: ALL_TABS.slice(),
};

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
  const n = parseInt(e.key, 10);
  if (n >= 1 && n <= settings.tabs.length) setPage(settings.tabs[n - 1]);
});

// ── polling ───────────────────────────────────────────────────────────────

let state = null;
let timer = null;
let lastOk = 0;

function restartPolling() {
  clearInterval(timer);
  timer = setInterval(poll, 1000 / settings.rate);
}

async function poll() {
  try {
    const res = await fetch("state", { cache: "no-store" });
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
      headers: { "Content-Type": "application/json" },
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
  const img = el("img", "icon-img" + (cls ? " " + cls : ""));
  img.loading = "lazy";
  img.decoding = "async";
  img.alt = "";
  img.src = "asset/" + path.replace(/\\/g, "/").replace(/\.dds$/i, ".png");
  img.onerror = () => img.remove();
  return img;
}

/** The game's own Vault Boy art for a SPECIAL attribute. */
function specialIcon(name) {
  return icon("interface/icons/pipboyimages/s.p.e.c.i.a.l/special_" + name.toLowerCase() + ".dds", "big");
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

  document.getElementById("gamedate").textContent = s.gameTime || "";
  document.getElementById("caps").textContent = `${num(p.caps)} caps`;
  document.getElementById("wt").textContent = `${num(p.weight)}/${num(p.weightMax)} wg`;
  document.getElementById("loc").textContent =
    [s.map && s.map.cell, s.map && s.map.world].filter(Boolean).join(" — ");

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
      for (const [key] of LIMBS) {
        const b = el("div", "limbbar");
        b.dataset.limb = key;
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

function renderInv(s) {
  const bucket = (s.inventory || {})[sub.inv] || [];
  const list = document.getElementById("items");

  const key = sub.inv + "|" + bucket.map((i) =>
    `${i.id}:${i.count}:${i.equipped ? 1 : 0}:${Math.round((i.health || 0) * 100)}`).join(",");

  fill(list, key, (n) => {
    if (!bucket.length) { n.appendChild(el("li", "empty", "Nothing here.")); return; }
    for (const item of bucket) {
      const li = row(() => { selected.inv = item.id; renderInv(state); });
      li.classList.toggle("equipped", !!item.equipped);
      li.dataset.id = item.id;
      const art = icon(item.icon);
      if (art) li.appendChild(art);
      li.appendChild(el("span", "name", item.name));
      if (item.count > 1) li.appendChild(el("span", "tag", "(" + item.count + ")"));
      li.appendChild(el("span", "val", item.weight != null ? item.weight.toFixed(1) : ""));
      n.appendChild(li);
    }
  });

  for (const li of list.querySelectorAll(".row"))
    li.setAttribute("aria-selected", String(li.dataset.id === selected.inv));

  const item = bucket.find((i) => i.id === selected.inv);
  renderItemCard(s, item);
  renderInvFoot(s, item);
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
  card.textContent = "";
  if (!item) { card.appendChild(el("p", "hint", "Select an item.")); return; }

  const art = icon(item.icon, "card-art");
  if (art) card.appendChild(art);

  card.appendChild(el("h4", null, item.name));

  const dl = el("dl");
  const put = (k, v, pips) => {
    if (v == null || v === "") return;
    dl.appendChild(el("dt", null, k));
    const dd = el("dd", null, String(v));
    if (pips) dd.appendChild(el("span", "pips", pips));
    dl.appendChild(dd);
  };

  put("Damage", item.damage, pipsFor(item.damage, 120));
  put("DPS", item.dps, pipsFor(item.dps, 200));
  put("Clip", item.clip);
  put("Ammo", item.ammoName);
  put("Spread", item.spread);
  put("DT", item.dt, pipsFor(item.dt, 30));
  put("Effect", item.effect);
  if (item.health != null) put("Condition", Math.round(item.health * 100) + "%");
  put("Weight", item.weight != null ? item.weight.toFixed(1) : null);
  put("Value", item.value != null ? num(item.value) : null);
  put("Count", item.count > 1 ? item.count : null);
  put("From", item.source);
  card.appendChild(dl);

  if (item.desc) card.appendChild(el("p", null, item.desc));
}

function renderInvFoot(s, item) {
  const foot = document.getElementById("invfoot");
  foot.textContent = "";
  const perms = s.perms || {};

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

  foot.appendChild(el("span", "spacer"));
  foot.appendChild(el("span", "dim", `${((s.inventory || {})[sub.inv] || []).length} entries`));
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

function renderQuests(s) {
  const quests = s.quests || [];
  const list = document.getElementById("quests");
  const key = quests.map((q) => `${q.id}:${q.active ? 1 : 0}:${q.completed ? 1 : 0}:${(q.objectives || []).length}`).join(",");

  fill(list, key, (n) => {
    if (!quests.length) { n.appendChild(el("li", "empty", "No quests yet.")); return; }
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

  const bounds = mapMode === "world" ? m.worldBounds : m.localBounds;
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

  if (m.x != null) {
    const x = px(m.x), y = py(m.y);
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(((m.angle || 0) * Math.PI) / 180);
    ctx.fillStyle = fg;
    ctx.beginPath();
    ctx.moveTo(0, -9 * dpr);
    ctx.lineTo(6 * dpr, 7 * dpr);
    ctx.lineTo(0, 4 * dpr);
    ctx.lineTo(-6 * dpr, 7 * dpr);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  }

  const where = document.getElementById("mapwhere");
  where.textContent = "";
  where.appendChild(el("h4", null, m.cell || m.world || "Unknown"));
  if (m.x != null) where.appendChild(el("p", "hint", `${Math.round(m.x)}, ${Math.round(m.y)}`));

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

  for (const b of document.querySelectorAll("[data-mapmode]"))
    b.setAttribute("aria-pressed", String(b.dataset.mapmode === mapMode));
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
    act("fastTravel", { marker: mk.name });
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

function renderRadio(s) {
  const stations = s.radio || [];
  const list = document.getElementById("stations");
  const key = stations.map((r) => `${r.id}:${r.active ? 1 : 0}:${r.inRange ? 1 : 0}`).join(",");

  fill(list, key, (n) => {
    if (!stations.length) { n.appendChild(el("li", "empty", "No stations in range.")); return; }
    for (const st of stations) {
      const li = row(() => {
        if (!(s.perms || {}).radio) return;
        act("radio", { id: st.active ? "" : st.id });
      });
      li.dataset.id = st.id;
      li.setAttribute("aria-selected", String(!!st.active));
      li.appendChild(el("span", "name", st.name));
      if (!st.inRange) li.appendChild(el("span", "tag", "WEAK"));
      n.appendChild(li);
    }
  });

  const active = stations.find((r) => r.active);
  const now = document.getElementById("nowplaying");
  now.textContent = "";
  now.appendChild(el("h4", null, active ? active.name : "Radio off"));
  if (!(s.perms || {}).radio) now.appendChild(el("p", "hint", "Radio control is off in the mod's config."));
  else if (active) now.appendChild(el("p", "hint", "Tap again to switch it off."));

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

const SECTIONS = [["colour", "COLOUR"], ["layout", "LAYOUT"], ["screen", "SCREEN"], ["tabs", "TABS"]];

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

document.getElementById("gear").onclick = () => {
  buildSetNav();
  buildSettings();
  document.getElementById("settings").hidden = false;
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
runBoot();
poll();
setInterval(updateDot, 500);
setInterval(() => { if (page === "radio" && state) drawWave(!!(state.radio || []).find((r) => r.active)); }, 90);
addEventListener("resize", () => { if (state && page === "map") renderMap(state); });
