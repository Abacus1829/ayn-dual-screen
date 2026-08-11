/*
 * The second screen.
 *
 * Polls /state, redraws, and posts taps back to /action. Everything the mod might refuse is still
 * rendered -- the buttons just go disabled -- so a look-only configuration reads as a deliberate
 * setting rather than a broken page.
 *
 * The JSON shapes here must be kept in step with src/Dtos.h and with tools/mockserver.py.
 */

"use strict";

// ── settings, per screen, in this browser only ────────────────────────────

const DEFAULTS = {
  hue: "green",
  size: 2,            // index into SIZES
  rate: 10,           // polls per second
  scanlines: "on",
  vignette: "on",
  cards: "on",
  bezel: "off",       // off by default: on a handheld the casing eats real estate
  sound: "on",
  boot: "on",
  autoQuestTab: true,
  tabs: ["stat", "inv", "data", "map", "radio"],
};

const SIZES = [13, 14.5, 16, 18, 21];
const HUES = ["green", "amber", "blue", "white"];
const RATES = [5, 10, 15, 20];

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
  r.dataset.hue = settings.hue;
  r.dataset.scanlines = settings.scanlines;
  r.dataset.vignette = settings.vignette;
  r.dataset.cards = settings.cards;
  r.dataset.bezel = settings.bezel;
  r.style.setProperty("--ui", SIZES[settings.size] + "px");

  for (const b of document.querySelectorAll("#tabs .tab"))
    b.hidden = !settings.tabs.includes(b.dataset.tab);

  if (!settings.tabs.includes(page)) setPage(settings.tabs[0] || "stat");
  restartPolling();
}

// ── page / sub-page routing ───────────────────────────────────────────────

const SUBTABS = {
  stat: [["status", "STATUS"], ["special", "S.P.E.C.I.A.L."], ["skills", "SKILLS"], ["perks", "PERKS"]],
  inv:  [["weapons", "WEAPONS"], ["apparel", "APPAREL"], ["aid", "AID"], ["misc", "MISC"], ["ammo", "AMMO"]],
  data: [["quests", "QUESTS"], ["notes", "NOTES"], ["stats", "STATS"]],
  map:  [],
  radio: [],
};

let page = settings.tabs[0] || "stat";
let sub = { stat: "status", inv: "weapons", data: "quests" };
let selected = { inv: null, quests: null, notes: null };

function setPage(next) {
  page = next;
  for (const el of document.querySelectorAll(".page")) el.classList.toggle("on", el.dataset.page === next);
  for (const b of document.querySelectorAll("#tabs .tab")) b.setAttribute("aria-selected", String(b.dataset.tab === next));
  buildSubtabs();
  if (state) render(state);
}

function buildSubtabs() {
  const host = document.getElementById("subtabs");
  host.textContent = "";
  for (const [key, label] of SUBTABS[page] || []) {
    const b = document.createElement("button");
    b.className = "subtab";
    b.textContent = label;
    b.dataset.sub = key;
    b.setAttribute("role", "tab");
    b.setAttribute("aria-selected", String(sub[page] === key));
    b.onclick = () => { sub[page] = key; buildSubtabs(); if (state) render(state); };
    host.appendChild(b);
  }
  for (const el of document.querySelectorAll(`.page[data-page="${page}"] .sub`))
    el.classList.toggle("on", el.dataset.sub === sub[page]);
}

document.getElementById("tabs").addEventListener("click", (e) => {
  const b = e.target.closest(".tab");
  if (b) { click(); setPage(b.dataset.tab); }
});

// Every row and button gets the same tactile click, without each one asking for it.
document.addEventListener("click", (e) => {
  if (e.target.closest(".row, .btn, .subtab, .icon")) click(0.10, 0.03);
}, true);

// Number keys pick a tab; handy on a screen with a keyboard attached.
addEventListener("keydown", (e) => {
  if (e.target.tagName === "INPUT") return;
  const n = parseInt(e.key, 10);
  if (n >= 1 && n <= 5 && settings.tabs[n - 1]) setPage(settings.tabs[n - 1]);
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
    poll();  // don't wait a whole interval to see the result
  } catch (e) {}
}

// ── small helpers ─────────────────────────────────────────────────────────

const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
};

const pct = (v, max) => (max > 0 ? Math.max(0, Math.min(1, v / max)) : 0);
const num = (v) => (v == null ? "—" : Math.round(v).toLocaleString());

function severity(fraction) {
  return fraction > 0.6 ? "" : fraction > 0.25 ? "warn" : "bad";
}

function row(onclick) {
  const li = el("li", "row");
  li.setAttribute("role", "option");
  if (onclick) li.onclick = onclick;
  return li;
}

/** Fill a <ul> only when its contents changed, so scroll position and taps survive a redraw. */
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

  document.getElementById("hp").textContent = num(p.hp);
  document.getElementById("hpmax").textContent = "/" + num(p.hpMax);
  document.getElementById("ap").textContent = num(p.ap);
  document.getElementById("apmax").textContent = "/" + num(p.apMax);

  document.getElementById("loc").textContent = [s.map && s.map.cell, s.map && s.map.world].filter(Boolean).join(" — ");
  document.getElementById("lvl").textContent = `LVL ${num(p.level)}`;
  document.getElementById("caps").textContent = `${num(p.caps)} caps`;
  document.getElementById("wt").textContent = `${num(p.weight)}/${num(p.weightMax)} wg`;

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
      const node = document.querySelector(`#doll [data-limb="${key}"]`);
      if (!node) continue;
      const v = cond[key] == null ? 1 : cond[key];
      node.classList.toggle("warn", v <= 0.6 && v > 0.25);
      node.classList.toggle("bad", v <= 0.25 && v > 0);
      node.classList.toggle("crippled", v <= 0);
    }

    const crippled = LIMBS.filter(([k]) => (cond[k] != null && cond[k] <= 0)).map(([, n]) => n);
    document.getElementById("dolllegend").textContent =
      crippled.length ? "Crippled: " + crippled.join(", ") : "All limbs intact";

    const bars = document.getElementById("condbars");
    bars.textContent = "";
    bars.appendChild(bar("Health", p.hp, p.hpMax));
    bars.appendChild(bar("Action Pts", p.ap, p.apMax));
    if (p.radsMax) bars.appendChild(bar("Rads", p.radsMax - (p.rads || 0), p.radsMax, `${num(p.rads)} (${p.radsText || "Minor"})`));
    for (const [key, name] of LIMBS)
      bars.appendChild(bar(name, (cond[key] || 0) * 100, 100, Math.round((cond[key] || 0) * 100) + "%"));

    const fx = document.getElementById("effects");
    fill(fx, JSON.stringify(s.effects || []), (n) => {
      if (!s.effects || !s.effects.length) { n.appendChild(el("li", "empty", "No active effects.")); return; }
      for (const e of s.effects) {
        const li = row();
        li.appendChild(el("span", "name", e.name));
        li.appendChild(el("span", "val", e.duration || ""));
        n.appendChild(li);
      }
    });

    const hc = document.getElementById("hardcore");
    hc.textContent = "";
    if (p.hardcore) {
      // Hardcore counts up towards death, so the bar shows headroom remaining.
      hc.appendChild(bar("Dehydration", p.h2oMax - p.h2o, p.h2oMax, `${num(p.h2o)}/${num(p.h2oMax)}`));
      hc.appendChild(bar("Starvation", p.fodMax - p.fod, p.fodMax, `${num(p.fod)}/${num(p.fodMax)}`));
      hc.appendChild(bar("Sleep Dep.", p.slpMax - p.slp, p.slpMax, `${num(p.slp)}/${num(p.slpMax)}`));
    } else {
      hc.appendChild(el("p", "hint", "Hardcore mode is off."));
    }
  }

  if (sub.stat === "special") {
    const list = document.getElementById("special");
    fill(list, JSON.stringify(s.special || []), (n) => {
      for (const a of s.special || []) {
        const li = row();
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

  const key = sub.inv + "|" + bucket.map((i) => `${i.id}:${i.count}:${i.equipped ? 1 : 0}:${Math.round((i.health || 0) * 100)}`).join(",");
  fill(list, key, (n) => {
    if (!bucket.length) { n.appendChild(el("li", "empty", "Nothing here.")); return; }
    for (const item of bucket) {
      const li = row(() => { selected.inv = item.id; renderInv(state); });
      li.classList.toggle("equipped", !!item.equipped);
      li.dataset.id = item.id;
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

function renderItemCard(s, item) {
  const card = document.getElementById("itemcard");
  card.textContent = "";
  if (!item) { card.appendChild(el("p", "hint", "Select an item.")); return; }

  card.appendChild(el("h4", null, item.name));

  const dl = el("dl");
  const put = (k, v) => { if (v == null || v === "") return; dl.appendChild(el("dt", null, k)); dl.appendChild(el("dd", null, String(v))); };

  put("Weight", item.weight != null ? item.weight.toFixed(1) : null);
  put("Value", item.value != null ? num(item.value) : null);
  if (item.health != null) put("Condition", Math.round(item.health * 100) + "%");
  put("DAM", item.damage);
  put("DPS", item.dps);
  put("Clip", item.clip);
  put("Ammo", item.ammoName);
  put("Spread", item.spread);
  put("DT", item.dt);
  put("Effect", item.effect);
  put("Count", item.count > 1 ? item.count : null);
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
    return b;
  };

  const equippable = item && (sub.inv === "weapons" || sub.inv === "apparel");
  if (equippable)
    add(item && item.equipped ? "UNEQUIP" : "EQUIP", perms.equip, "Equipping is off in the mod's config",
        () => act("equip", { id: item.id }));

  if (sub.inv === "aid")
    add("USE", perms.use, "Using items is off in the mod's config", () => act("use", { id: item.id }));

  add("DROP", perms.drop, "Dropping is off in the mod's config — it is off by default",
      () => confirmThen(foot, () => act("drop", { id: item.id, count: 1 })), "danger");

  foot.appendChild(el("span", "spacer"));

  const totals = (s.inventory || {})[sub.inv] || [];
  foot.appendChild(el("span", "dim", `${totals.length} entries`));
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
  if (sub.data === "quests") {
    const quests = s.quests || [];
    const list = document.getElementById("quests");
    const key = quests.map((q) => `${q.id}:${q.active ? 1 : 0}:${q.completed ? 1 : 0}:${(q.objectives || []).length}`).join(",");

    fill(list, key, (n) => {
      if (!quests.length) { n.appendChild(el("li", "empty", "No quests yet.")); return; }
      const groups = [["Active", quests.filter((q) => !q.completed)], ["Completed", quests.filter((q) => q.completed)]];
      for (const [label, items] of groups) {
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
    for (const o of q.objectives || []) {
      const p = el("p", o.done ? "dim" : null, (o.done ? "☑ " : "☐ ") + o.text);
      card.appendChild(p);
    }
    if (!(q.objectives || []).length) card.appendChild(el("p", "hint", "No objectives recorded."));

    if ((s.perms || {}).setQuest && !q.completed) {
      const b = el("button", "btn wide", q.active ? "ACTIVE" : "SET ACTIVE");
      b.disabled = !!q.active;
      b.onclick = () => act("setQuest", { id: q.id });
      card.appendChild(b);
    }
  }

  if (sub.data === "notes") {
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

  if (sub.data === "stats") {
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
}

// ── MAP ───────────────────────────────────────────────────────────────────

let mapMode = "local";
let mapZoom = 1;
let mapHit = [];      // marker hit-boxes in canvas pixels, rebuilt each frame

const MARKER_GLYPH = {
  Vault: "V", Town: "⌂", Settlement: "⌂", Cave: "◓", Factory: "⚙", Ruin: "⌗",
  Building: "▣", Camp: "▲", Military: "✚", Metro: "═", Monument: "▮", Office: "▤",
  Sewer: "◎", Mine: "◈", Landmark: "◇", City: "★", Unmarked: "·",
};

function renderMap(s) {
  const m = s.map || {};
  const canvas = document.getElementById("mapcanvas");
  const view = document.getElementById("mapview");

  // Match the backing store to the CSS box so nothing is blurry on a hidpi panel.
  const dpr = Math.min(devicePixelRatio || 1, 2);
  const w = Math.max(1, Math.round(view.clientWidth * dpr));
  const h = Math.max(1, Math.round(view.clientHeight * dpr));
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }

  const ctx = canvas.getContext("2d");
  const style = getComputedStyle(document.documentElement);
  const fg = style.getPropertyValue("--fg").trim() || "#3cff88";
  const dim = style.getPropertyValue("--fg-dim").trim() || "#2aa85c";

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

  // Fit the region to the canvas, preserving aspect, then apply the zoom about the player.
  const bw = bounds.maxX - bounds.minX || 1;
  const bh = bounds.maxY - bounds.minY || 1;
  const scale = Math.min(w / bw, h / bh) * mapZoom;

  const cx = mapZoom > 1 && m.x != null ? m.x : (bounds.minX + bounds.maxX) / 2;
  const cy = mapZoom > 1 && m.y != null ? m.y : (bounds.minY + bounds.maxY) / 2;

  // World Y grows north; screen Y grows down.
  const px = (wx) => w / 2 + (wx - cx) * scale;
  const py = (wy) => h / 2 - (wy - cy) * scale;

  // Grid.
  ctx.strokeStyle = dim;
  ctx.globalAlpha = 0.18;
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

  // Border of the mapped region.
  ctx.strokeStyle = dim;
  ctx.globalAlpha = 0.5;
  ctx.strokeRect(px(bounds.minX), py(bounds.maxY), bw * scale, bh * scale);
  ctx.globalAlpha = 1;

  // Markers.
  mapHit = [];
  ctx.font = `${12 * dpr}px monospace`;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";

  for (const mk of m.markers || []) {
    const x = px(mk.x), y = py(mk.y);
    if (x < -20 || y < -20 || x > w + 20 || y > h + 20) continue;

    ctx.globalAlpha = mk.visited ? 1 : 0.45;
    ctx.fillStyle = fg;
    ctx.strokeStyle = fg;

    ctx.beginPath();
    ctx.arc(x, y, 5 * dpr, 0, Math.PI * 2);
    mk.visited ? ctx.fill() : ctx.stroke();

    ctx.fillStyle = mk.visited ? "#000" : fg;
    ctx.fillText(MARKER_GLYPH[mk.type] || "•", x, y + 0.5 * dpr);

    if (mapZoom >= 1.5 || (m.markers || []).length < 24) {
      ctx.fillStyle = fg;
      ctx.globalAlpha = mk.visited ? 0.85 : 0.4;
      ctx.fillText(mk.name, x, y + 14 * dpr);
    }
    ctx.globalAlpha = 1;

    mapHit.push({ x, y, r: 12 * dpr, marker: mk });
  }

  // The player: a triangle pointing where they face.
  if (m.x != null) {
    const x = px(m.x), y = py(m.y);
    const a = ((m.angle || 0) * Math.PI) / 180;
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(a);
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

  // Side panel.
  document.getElementById("mapwhere").textContent = "";
  const where = document.getElementById("mapwhere");
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
    showTip(`Fast travel is off in the mod's config.`);
    return;
  }
  if (!mk.canFastTravel) { showTip(`${mk.name} can't be fast travelled to.`); return; }

  // Fast travel moves the character and burns hours; two taps, always.
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
  if (b.dataset.mapzoom) {
    mapZoom = Math.max(1, Math.min(6, mapZoom + Number(b.dataset.mapzoom) * 0.5));
  }
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
        act("radio", { id: st.active ? "" : st.id });   // tapping the active one turns it off
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
    // Off-air is a flat line with a little noise; on-air is a rolling carrier.
    const amp = on ? (h / 2 - 4 * dpr) * (0.35 + 0.65 * Math.abs(Math.sin(u * 3 + t * 0.35))) : 1.5 * dpr;
    const y = h / 2 + Math.sin(u * 42 + t) * amp * (on ? 1 : 1) * (on ? Math.sin(u * Math.PI) : 1);
    x === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
  }
  ctx.stroke();
}

// ── settings panel ────────────────────────────────────────────────────────

const TAB_LABELS = { stat: "STAT", inv: "INV", data: "DATA", map: "MAP", radio: "RADIO" };

function buildSettings() {
  const host = document.getElementById("settingsbody");
  host.textContent = "";

  const addRow = (label, node) => {
    const r = el("div", "setrow");
    r.appendChild(el("label", null, label));
    r.appendChild(node);
    host.appendChild(r);
    return r;
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

  addRow("Colour", choices(HUES, settings.hue, (v) => v[0].toUpperCase() + v.slice(1), (v) => { settings.hue = v; }));
  addRow("Size", choices([0, 1, 2, 3, 4], settings.size, (v) => ["XS", "S", "M", "L", "XL"][v], (v) => { settings.size = v; }));
  addRow("Updates / sec", choices(RATES, settings.rate, String, (v) => { settings.rate = v; }));
  addRow("Scanlines", choices(["on", "off"], settings.scanlines, (v) => v.toUpperCase(), (v) => { settings.scanlines = v; }));
  addRow("Vignette", choices(["on", "off"], settings.vignette, (v) => v.toUpperCase(), (v) => { settings.vignette = v; }));
  addRow("Detail panel", choices(["on", "off"], settings.cards, (v) => v.toUpperCase(), (v) => { settings.cards = v; }));
  addRow("Casing", choices(["on", "off"], settings.bezel, (v) => v.toUpperCase(), (v) => { settings.bezel = v; }));
  addRow("Sound", choices(["on", "off"], settings.sound, (v) => v.toUpperCase(), (v) => {
    settings.sound = v;
    if (v === "off") stopHum(); else startHum();
  }));
  addRow("Boot sequence", choices(["on", "off"], settings.boot, (v) => v.toUpperCase(), (v) => { settings.boot = v; }));

  const grid = el("div", "checkgrid");
  for (const [key, label] of Object.entries(TAB_LABELS)) {
    const lab = el("label");
    const cb = el("input");
    cb.type = "checkbox";
    cb.checked = settings.tabs.includes(key);
    cb.onchange = () => {
      settings.tabs = Object.keys(TAB_LABELS).filter((k) =>
        k === key ? cb.checked : settings.tabs.includes(k));
      if (!settings.tabs.length) { settings.tabs = [key]; cb.checked = true; }
      save();
      buildSettings();
    };
    lab.appendChild(cb);
    lab.appendChild(document.createTextNode(label));
    grid.appendChild(lab);
  }
  host.appendChild(el("h3", null, "Tabs"));
  host.appendChild(grid);
}

document.getElementById("gear").onclick = () => {
  buildSettings();
  document.getElementById("settings").hidden = false;
};
document.getElementById("closesettings").onclick = () => { document.getElementById("settings").hidden = true; };
document.getElementById("settings").onclick = (e) => {
  if (e.target.id === "settings") e.currentTarget.hidden = true;
};
document.getElementById("resetsettings").onclick = () => {
  settings = Object.assign({}, DEFAULTS);
  save();
  buildSettings();
  setPage(settings.tabs[0]);
};

// ── sound ─────────────────────────────────────────────────────────────────
//
// Synthesised, not sampled. The Pip-Boy's clicks and hum are Bethesda's audio; these are a few
// oscillators and a burst of noise, which costs nothing to ship and nothing to licence.
//
// Browsers refuse to start audio until the user has interacted with the page, so the context is
// created lazily on the first tap and the hum starts then.

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

/** A short filtered noise burst — the tactile click under a tab change. */
function click(level = 0.18, duration = 0.045) {
  const ctx = sound();
  if (!ctx) return;

  const frames = Math.floor(ctx.sampleRate * duration);
  const buffer = ctx.createBuffer(1, frames, ctx.sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < frames; i++) {
    // Decaying noise: loud at the strike, gone almost immediately.
    data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / frames, 3);
  }

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

/** The valve hum that sits under everything while the screen is on. */
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
  gain.gain.value = 0.012;          // felt more than heard

  osc.connect(gain);
  harmonic.connect(gain);
  gain.connect(ctx.destination);
  osc.start();
  harmonic.start();

  hum = { osc, harmonic, gain };
}

function stopHum() {
  if (!hum) return;
  try { hum.osc.stop(); hum.harmonic.stop(); } catch (e) {}
  hum = null;
}

// Any tap anywhere is the interaction browsers wait for.
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
  "AYN DUAL SCREEN — READY",
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

// ── go ────────────────────────────────────────────────────────────────────

applySettings();
setPage(page);
runBoot();
poll();
setInterval(updateDot, 500);
setInterval(() => { if (page === "radio" && state) drawWave(!!(state.radio || []).find((r) => r.active)); }, 90);
addEventListener("resize", () => { if (state && page === "map") renderMap(state); });
