/*
 * Second-screen client. Polls /state, redraws the HUD, minimap and inventory,
 * and posts touch commands back to /action for the mod to run on the game thread.
 */

'use strict';

let POLL_MS = 100;           // matches the mod's default 10 snapshots/second; overridden by settings
const RECONNECT_MS = 1000;

/* ---------- per-device settings (the gear) ---------- */

/**
 * Everything the gear panel can change, with its default.
 *
 * Kept in localStorage rather than in the mod's config: this is per-*screen* preference, and the point
 * is that a phone, a tablet and the Thor's panel can each be laid out differently against one save.
 * The `no*` keys become classes on <body>; the CSS does the hiding.
 */
const SETTING_DEFAULTS = {
  hud: true,
  map: true,
  legend: true,
  forecast: true,
  skills: true,
  quests: true,
  inv: true,
  detail: true,
  actions: true,
  accent: '#c8a066',
  scale: 'medium',
  rate: 10
};

/** Which section each toggle hides, and what to call it in the panel. */
const SECTIONS = [
  ['hud', 'Top bar'],
  ['map', 'Map panel'],
  ['legend', 'Map legend'],
  ['forecast', 'Tomorrow & luck'],
  ['skills', 'Skill levels'],
  ['quests', 'Journal'],
  ['inv', 'Inventory panel'],
  ['detail', 'Selected item'],
  ['actions', 'Action buttons']
];

const ACCENTS = ['#c8a066', '#e0ae68', '#8fd14f', '#6ec1ff', '#ffd166', '#ff8fab', '#c58cff', '#f2e6d5'];

const SCALES = { small: '12px', medium: '14px', large: '17px', huge: '20px' };

/* ---------- the official wiki ---------- */

/**
 * The wiki is opened by navigating this window rather than in a new tab or an iframe.
 *
 * An iframe is out: the wiki refuses to be embedded. A new tab is no good either, because the whole
 * point is that the second screen is a kiosk — in the Android app there is no tab bar to get back
 * from. Navigating in place means the app's own menu can return here, and a plain browser's Back
 * button does the same.
 */
const WIKI_HOME = 'https://stardewvalleywiki.com/Stardew_Valley_Wiki';
const WIKI_SEARCH = 'https://stardewvalleywiki.com/mediawiki/index.php?search=';

function openWiki(query) {
  const url = query ? WIKI_SEARCH + encodeURIComponent(query) : WIKI_HOME;

  // An anchor click is the most compatible way to navigate: some kiosk WebViews and embedded browsers
  // quietly ignore assignments to location.href, and a silent no-op is the worst possible outcome for
  // a button. If we're still here a moment later, say so and leave a tappable link behind.
  try {
    const link = document.createElement('a');
    link.href = url;
    link.rel = 'noreferrer';
    document.body.appendChild(link);
    link.click();
    link.remove();
  } catch (err) {
    /* fall through to the location assignment below */
  }

  try {
    window.location.assign(url);
  } catch (err) {
    /* the watchdog will surface it */
  }

  clearTimeout(wikiWatchdog);
  wikiWatchdog = setTimeout(() => showWikiFallback(url), 1500);
}

let wikiWatchdog = null;

/**
 * Shown when the jump to the wiki didn't happen.
 *
 * Usually that means no internet on this device — the second screen only ever needs the LAN, so a
 * handheld can easily reach the game and not the outside world. The URL is left on screen as a real
 * link so it can still be tapped, or read off and typed elsewhere.
 */
function showWikiFallback(url) {
  let box = document.getElementById('wiki-fallback');
  if (!box) {
    box = document.createElement('div');
    box.id = 'wiki-fallback';
    box.className = 'overlay';
    box.innerHTML =
      '<div class="settings-box">' +
      '<div class="settings-head"><span>Couldn’t open the wiki</span>' +
      '<button class="chip" id="wiki-fallback-close">Close</button></div>' +
      '<p class="settings-note">This screen only needs your local network, but the wiki needs the ' +
      'internet — so this device may be offline, or a VPN may be in the way.</p>' +
      '<p class="settings-note">Try this link, or open it on another device:</p>' +
      '<p><a id="wiki-fallback-link" class="linkish" target="_blank" rel="noreferrer"></a></p>' +
      '</div>';
    document.body.appendChild(box);
    box.addEventListener('click', (event) => {
      if (event.target === box || event.target.id === 'wiki-fallback-close') box.remove();
    });
  }

  const link = box.querySelector('#wiki-fallback-link');
  link.href = url;
  link.textContent = url;
}

function wikiNameFor(item) {
  return item && item.name ? String(item.name).trim() : null;
}


let settings = Object.assign({}, SETTING_DEFAULTS);

function loadSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem('aynDualScreen') || '{}');
    // merged over the defaults, so a setting added in a later version still has a value
    settings = Object.assign({}, SETTING_DEFAULTS, saved);
  } catch (err) {
    settings = Object.assign({}, SETTING_DEFAULTS);
  }
}

function saveSettings() {
  try {
    localStorage.setItem('aynDualScreen', JSON.stringify(settings));
  } catch (err) {
    /* private browsing or a full quota: the screen still works, it just won't remember */
  }
}

function applySettings() {
  for (const [key] of SECTIONS)
    document.body.classList.toggle(`no-${key}`, !settings[key]);

  const root = document.documentElement.style;
  root.setProperty('--edge-hot', settings.accent);

  document.documentElement.style.fontSize = SCALES[settings.scale] || SCALES.medium;

  POLL_MS = Math.round(1000 / Math.max(1, Math.min(30, settings.rate)));

  // the map is canvas-drawn, so it has to be told the layout moved under it
  if (state) renderMap();
}

/* ---------- tile palette (keys must match the TileXxx constants in ModEntry.cs) ---------- */

const TILE_COLORS = {
  '.': [0, 0, 0, 0],          // off-map
  'g': [74, 103, 65, 255],    // walkable ground
  'w': [40, 92, 148, 255],    // water
  'b': [92, 78, 64, 255],     // map buildings layer (walls, furniture, cliffs)
  'd': [107, 78, 52, 255],    // tilled soil
  'c': [128, 176, 68, 255],   // growing crop
  't': [39, 74, 44, 255],     // tree or bush
  'f': [138, 122, 100, 255],  // laid flooring / paths
  'r': [96, 138, 70, 255],    // grass
  'o': [196, 160, 96, 255],   // placed object (machine, chest, stone, forage)
  'B': [150, 96, 72, 255]     // player-built building footprint
};

const ENTITY_COLORS = {
  npc: '#ffd166',
  monster: '#ff5d5d',
  animal: '#ffb3d1',
  farmer: '#9dff9d'
};

const SEASON_LABEL = { spring: 'Spring', summer: 'Summer', fall: 'Fall', winter: 'Winter' };
const WEATHER_LABEL = {
  sun: 'Clear', rain: 'Rain', storm: 'Storm',
  snow: 'Snow', wind: 'Windy', greenrain: 'Green Rain'
};

/* ---------- element handles ---------- */

const el = (id) => document.getElementById(id);

const dom = {
  offline: el('offline'),
  offlineDetail: el('offline-detail'),
  date: el('date-text'),
  year: el('year-text'),
  clock: el('clock-text'),
  weather: el('weather-text'),
  money: el('money-text'),
  location: el('location-text'),
  energyBar: el('bar-energy'),
  energyText: el('energy-text'),
  healthBar: el('bar-health'),
  healthText: el('health-text'),
  zoomToggle: el('zoom-toggle'),
  tabMap: el('tab-map'),
  tabToday: el('tab-today'),
  tabVillage: el('tab-village'),
  tabBundles: el('tab-bundles'),
  todayNotes: el('today-notes'),
  villageList: el('village-list'),
  villageCount: el('village-count'),
  villageFilter: el('village-filter'),
  bundleList: el('bundle-list'),
  bundleProgress: el('bundle-progress'),
  linkDot: el('link-dot'),
  zoomIn: el('zoom-in'),
  zoomOut: el('zoom-out'),
  zoomText: el('zoom-text'),
  mapTip: el('map-tip'),
  canvas: el('map-canvas'),
  forecast: el('forecast'),
  questHead: el('quest-head'),
  questCount: el('quest-count'),
  quests: el('quests'),
  skills: el('skills'),
  invGrid: el('inv-grid'),
  detailIcon: el('detail-icon'),
  detailName: el('detail-name'),
  detailMeta: el('detail-meta'),
  detailWiki: el('detail-wiki'),
  actions: el('actions'),
  wikiHome: el('wiki-home'),
  wikiItem: el('wiki-item'),
  gear: el('gear'),
  settings: el('settings'),
  settingsClose: el('settings-close'),
  settingsReset: el('settings-reset'),
  settingsToggles: el('settings-toggles'),
  settingsAccents: el('settings-accents'),
  settingsScale: el('settings-scale'),
  settingsRate: el('settings-rate')
};

const ctx = dom.canvas.getContext('2d');

/* ---------- state ---------- */

let state = null;         // latest snapshot from the mod
let mapData = null;       // latest /map payload
let loadedMapRev = -1;
let mapImage = null;      // offscreen canvas, one pixel per tile
let mapPending = false;

let cursor = 0;           // slot the action buttons apply to
let dragFrom = -1;
let dragActive = false;
let dragOverSlot = null;
let pointerStart = null;
let followMode = false;
let trashArmed = false;
let slots = [];           // slot elements, index-aligned with the inventory

const ZOOM_STEPS = [1, 1.5, 2, 3, 5];
let zoomStep = 0;

let lastSnapshot = 0;     // when a snapshot last arrived, for the connection dot
let lastTick = -1;        // the game's own tick, to notice a paused or hung game
let projection = null;    // last map draw, so a tap can be mapped back to world tiles

let activeTab = 'map';
let villagerData = null;
let communityData = null;
let villageFilterText = '';

/* ---------- polling ---------- */

async function poll() {
  try {
    const res = await fetch('/state', { cache: 'no-store' });
    const next = await res.json();

    if (!next.ready) {
      showOffline('Load a save to start the second screen.');
      state = null;
    } else {
      // a snapshot whose tick hasn't moved means the game is paused or hung, which is worth showing
      // differently from a dead connection
      if (next.tick !== lastTick) {
        lastSnapshot = Date.now();
        lastTick = next.tick;
      }

      state = next;
      hideOffline();
      render();
      if (state.mapRev !== loadedMapRev) loadMap();
    }

    setTimeout(poll, POLL_MS);
  } catch (err) {
    showOffline('Lost contact with the game. Retrying…');
    state = null;
    setTimeout(poll, RECONNECT_MS);
  }
}

async function loadMap() {
  if (mapPending) return;
  mapPending = true;
  try {
    const res = await fetch('/map', { cache: 'no-store' });
    const next = await res.json();
    if (next && next.rows && next.rows.length) {
      mapData = next;
      loadedMapRev = next.rev;
      buildMapImage();
    }
  } catch (err) {
    /* the next poll will retry */
  } finally {
    mapPending = false;
  }
}

/**
 * The villager tracker and bundle board have their own slow poll.
 *
 * The mod republishes them twice a second and they're much larger than a snapshot, so fetching them
 * at the state rate would spend most of the bandwidth on data that barely moves. Only the visible tab
 * is fetched.
 */
async function pollSlow() {
  try {
    if (activeTab === 'village') {
      villagerData = await (await fetch('/villagers', { cache: 'no-store' })).json();
      renderVillagers();
    } else if (activeTab === 'bundles') {
      communityData = await (await fetch('/community', { cache: 'no-store' })).json();
      renderBundles();
    }
  } catch (err) {
    /* the next tick will retry */
  }
  setTimeout(pollSlow, 1000);
}

function showOffline(message) {
  dom.offlineDetail.textContent = message;
  dom.offline.classList.remove('hidden');
}

function hideOffline() {
  dom.offline.classList.add('hidden');
}

/* ---------- posting actions ---------- */

function send(type, index, to) {
  const body = { type: type };
  if (index !== undefined) body.index = index;
  if (to !== undefined) body.to = to;

  fetch('/action', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).catch(() => { /* the screen is read-only until the game comes back */ });
}

/* ---------- HUD ---------- */

function formatClock(timeOfDay) {
  let hour = Math.floor(timeOfDay / 100);
  const minute = timeOfDay % 100;
  const suffix = (hour % 24) < 12 ? 'AM' : 'PM';
  let display = hour % 24 % 12;
  if (display === 0) display = 12;
  return `${display}:${String(minute).padStart(2, '0')} ${suffix}`;
}

/**
 * How long until 2am, in in-game minutes.
 *
 * Stardew's clock runs past midnight as 2400, 2500, 2600 rather than wrapping, which is exactly why
 * this is worth computing rather than eyeballing: "2600" does not look like "you have forty minutes
 * before you pass out and lose gold".
 */
function minutesUntilCollapse(timeOfDay) {
  const now = Math.floor(timeOfDay / 100) * 60 + (timeOfDay % 100);
  const collapse = 26 * 60;
  return Math.max(0, collapse - now);
}

function timeLeftLabel(timeOfDay) {
  const left = minutesUntilCollapse(timeOfDay);
  if (left === 0) return 'Past 2am — you are about to collapse';

  const hours = Math.floor(left / 60);
  const minutes = left % 60;
  const span = hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
  return `${span} until 2am`;
}

function renderHud() {
  const season = SEASON_LABEL[String(state.season).toLowerCase()] || state.season;

  dom.date.textContent = `${state.dayOfWeek}. ${season} ${state.dayOfMonth}`;
  dom.year.textContent = `Year ${state.year}`;
  dom.clock.textContent = formatClock(state.timeOfDay);
  dom.clock.classList.toggle('late', minutesUntilCollapse(state.timeOfDay) <= 120);
  dom.clock.title = timeLeftLabel(state.timeOfDay);
  dom.weather.textContent = WEATHER_LABEL[state.weather] || state.weather;
  dom.money.textContent = `${state.money.toLocaleString()}g`;
  dom.location.textContent = state.locationName || state.locationId;

  const energy = state.maxStamina > 0 ? state.stamina / state.maxStamina : 0;
  dom.energyBar.style.width = `${Math.max(0, Math.min(1, energy)) * 100}%`;
  dom.energyText.textContent = `${Math.round(state.stamina)} / ${state.maxStamina}`;

  const health = state.maxHealth > 0 ? state.health / state.maxHealth : 0;
  dom.healthBar.style.width = `${Math.max(0, Math.min(1, health)) * 100}%`;
  dom.healthText.textContent = `${state.health} / ${state.maxHealth}`;
}

/** Tomorrow's forecast and the day's luck — the two things worth knowing before going to bed. */
function renderForecast() {
  const bits = [];

  if (state.weatherTomorrow) {
    const label = WEATHER_LABEL[state.weatherTomorrow] || state.weatherTomorrow;
    bits.push(`Tomorrow: <b>${label}</b>`);
  }

  const luck = state.dailyLuck;
  if (typeof luck === 'number') {
    // the same bands the Fortune Teller reads from
    let word = 'neutral', cls = '';
    if (luck <= -0.07) { word = 'very unlucky'; cls = 'unlucky'; }
    else if (luck < 0) { word = 'unlucky'; cls = 'unlucky'; }
    else if (luck >= 0.07) { word = 'very lucky'; cls = 'lucky'; }
    else if (luck > 0) { word = 'lucky'; cls = 'lucky'; }
    bits.push(`Luck: <b class="${cls}">${word}</b>`);
  }

  dom.forecast.innerHTML = bits.join(' &middot; ');
}

let questSignature = null;

function renderQuests() {
  const quests = state.quests || [];
  const signature = quests.map((q) => `${q.name}|${q.objective}|${q.daysLeft}|${q.complete}`).join(',');
  if (signature === questSignature) return;
  questSignature = signature;

  dom.questHead.style.display = quests.length ? '' : 'none';
  dom.questCount.textContent = quests.length ? `${quests.length}` : '';
  dom.quests.textContent = '';

  // only the first few fit; the journal in-game is there for the rest
  for (const quest of quests.slice(0, 4)) {
    const row = document.createElement('div');
    row.className = 'quest' + (quest.complete ? ' done' : '');

    const label = document.createElement('span');
    label.textContent = quest.objective || quest.name;
    label.title = quest.name;

    const days = document.createElement('i');
    if (quest.complete) {
      days.textContent = 'done';
    } else if (quest.daysLeft >= 0) {
      days.textContent = quest.daysLeft === 1 ? 'today' : `${quest.daysLeft}d`;
      if (quest.daysLeft <= 1) days.className = 'soon';
    }

    row.append(label, days);
    dom.quests.appendChild(row);
  }
}

const SKILL_ORDER = [
  ['farming', 'Farm'],
  ['mining', 'Mine'],
  ['foraging', 'Forage'],
  ['fishing', 'Fish'],
  ['combat', 'Combat']
];

let skillSignature = null;

function renderSkills() {
  const skills = state.skills;
  if (!skills) return;

  const signature = SKILL_ORDER.map(([key]) => skills[key]).join(',');
  if (signature === skillSignature) return;
  skillSignature = signature;

  dom.skills.textContent = '';
  for (const [key, label] of SKILL_ORDER) {
    const cell = document.createElement('div');
    cell.className = 'skill';

    const level = document.createElement('b');
    level.textContent = skills[key];

    const name = document.createElement('span');
    name.textContent = label;

    cell.append(level, name);
    dom.skills.appendChild(cell);
  }
}

/* ---------- inventory ---------- */

function buildSlots(count) {
  dom.invGrid.textContent = '';
  slots = [];

  for (let i = 0; i < count; i++) {
    const slot = document.createElement('div');
    slot.className = 'slot' + (i < 12 ? ' hotbar' : '');
    slot.dataset.index = String(i);

    const img = document.createElement('img');
    img.alt = '';
    img.style.display = 'none';

    const stack = document.createElement('span');
    stack.className = 'stack';

    const qual = document.createElement('i');
    qual.className = 'qual';
    qual.style.display = 'none';

    slot.append(img, stack, qual);
    dom.invGrid.appendChild(slot);
    slots.push({ root: slot, img: img, stack: stack, qual: qual, signature: null });
  }
}

function renderInventory() {
  const items = state.inventory || [];
  if (slots.length !== items.length) buildSlots(items.length);

  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    const slot = slots[i];
    const signature = item.name ? `${item.iconKey}|${item.stack}|${item.quality}|${item.name}` : '';

    if (slot.signature !== signature) {
      slot.signature = signature;

      if (item.name) {
        if (item.iconKey) {
          slot.img.src = `/icon/${item.iconKey}.png`;
          slot.img.style.display = '';
        } else {
          slot.img.removeAttribute('src');
          slot.img.style.display = 'none';
        }
        slot.stack.textContent = item.stack > 1 ? item.stack : '';
        slot.qual.className = `qual q${item.quality}`;
        slot.qual.style.display = item.quality > 0 ? '' : 'none';
      } else {
        slot.img.removeAttribute('src');
        slot.img.style.display = 'none';
        slot.stack.textContent = '';
        slot.qual.style.display = 'none';
      }
    }

    slot.root.classList.toggle('equipped', i === state.selectedSlot);
    slot.root.classList.toggle('cursor', i === cursor);
  }

  renderDetail(items[cursor]);
}

function renderDetail(item) {
  const hasItem = !!(item && item.name);

  if (hasItem) {
    dom.detailName.textContent = item.name;
    const bits = [];
    if (item.stack > 1) bits.push(`x${item.stack}`);
    if (item.category) bits.push(item.category);
    if (item.quality > 0) bits.push(['', 'Silver', 'Gold', '', 'Iridium'][item.quality] || '');
    bits.push(`slot ${item.index + 1}`);
    dom.detailMeta.textContent = bits.filter(Boolean).join(' · ');

    if (item.iconKey) {
      dom.detailIcon.src = `/icon/${item.iconKey}.png`;
      dom.detailIcon.style.visibility = 'visible';
    } else {
      dom.detailIcon.style.visibility = 'hidden';
    }
  } else {
    dom.detailName.textContent = 'Empty slot';
    dom.detailMeta.textContent = `slot ${cursor + 1}`;
    dom.detailIcon.style.visibility = 'hidden';
  }

  // Two things gate a button: whether the config allows it at all, and whether there's an item to act on.
  const can = state.can || {};
  for (const button of dom.actions.querySelectorAll('.act')) {
    const act = button.dataset.act;
    let allowed = true;
    if (act === 'eat') allowed = can.eat !== false;
    else if (act === 'drop') allowed = can.drop !== false;
    else if (act === 'trash') allowed = can.trash !== false;
    else if (act === 'sort') allowed = can.edit !== false;

    if (act === 'sort') button.disabled = !allowed;
    else if (act === 'eat') button.disabled = !allowed || !(hasItem && item.edible && cursor < 12);
    else button.disabled = !allowed || !hasItem;

    button.title = allowed ? '' : 'Turned off in the mod config';
  }

  dom.detailWiki.disabled = !hasItem;

  if (!hasItem) disarmTrash();
}

/* ---------- today ---------- */

/** The one-off facts about today that don't fit the forecast line. */
function renderToday() {
  const notes = [];

  if (state.festival)
    notes.push(['festival', state.festival]);

  for (const name of state.birthdays || [])
    notes.push(['birthday', `${name}'s birthday today`]);

  if (state.cartToday)
    notes.push(['', 'Travelling Cart is in the forest']);

  dom.todayNotes.textContent = '';
  for (const [kind, text] of notes) {
    const row = document.createElement('div');
    row.className = 'note' + (kind ? ` ${kind}` : '');
    row.textContent = text;
    dom.todayNotes.appendChild(row);
  }
}

/* ---------- villager tracker ---------- */

function renderVillagers() {
  const all = villagerData || [];
  const needle = villageFilterText.trim().toLowerCase();
  const shown = needle
    ? all.filter((v) => (v.name || '').toLowerCase().includes(needle)
        || (v.location || '').toLowerCase().includes(needle))
    : all;

  dom.villageCount.textContent = `${shown.length}`;
  dom.villageList.textContent = '';

  if (!shown.length) {
    const empty = document.createElement('div');
    empty.className = 'villager';
    empty.textContent = needle ? 'Nobody matches that.' : 'No villagers found.';
    dom.villageList.appendChild(empty);
    return;
  }

  for (const villager of shown) {
    const row = document.createElement('div');
    row.className = 'villager'
      + (villager.here ? ' here' : '')
      + (villager.birthday ? ' birthday' : '');

    const name = document.createElement('b');
    name.textContent = villager.name;

    const where = document.createElement('span');
    where.textContent = villager.here ? 'here with you' : (villager.location || 'unknown');

    // a tick for "already spoken to today", because that's the daily chore
    const talked = document.createElement('i');
    talked.textContent = villager.talked ? '✔' : '';
    talked.className = villager.talked ? 'talked' : '';
    talked.title = villager.talked ? 'Talked to today' : '';

    const hearts = document.createElement('em');
    hearts.textContent = villager.birthday
      ? `🎂 ${villager.hearts}/${villager.maxHearts}`
      : `${villager.hearts}/${villager.maxHearts}`;

    row.append(name, where, talked, hearts);
    dom.villageList.appendChild(row);
  }
}

/* ---------- community centre ---------- */

function renderBundles() {
  dom.bundleList.textContent = '';

  if (!communityData || !communityData.available) {
    dom.bundleProgress.textContent = '';
    const note = document.createElement('div');
    note.className = 'room';
    note.textContent = 'No community centre to track — it may not be unlocked yet, or the Joja route was taken.';
    dom.bundleList.appendChild(note);
    return;
  }

  dom.bundleProgress.textContent = `${communityData.bundlesDone} / ${communityData.bundlesTotal}`;

  for (const room of communityData.rooms || []) {
    const box = document.createElement('div');
    box.className = 'room' + (room.complete ? ' done' : '');

    const head = document.createElement('div');
    head.className = 'room-head';

    const title = document.createElement('span');
    title.textContent = room.name;

    const count = document.createElement('em');
    count.textContent = room.complete ? 'complete' : `${room.done} / ${room.total}`;

    head.append(title, count);
    box.appendChild(head);

    if (!room.complete && (room.remaining || []).length) {
      const rest = document.createElement('small');
      rest.textContent = room.remaining.join(', ');
      box.appendChild(rest);
    }

    dom.bundleList.appendChild(box);
  }
}

/* ---------- tabs ---------- */

function setTab(tab) {
  activeTab = tab;

  for (const button of document.querySelectorAll('.tab'))
    button.classList.toggle('on', button.dataset.tab === tab);

  dom.tabMap.classList.toggle('hidden', tab !== 'map');
  dom.tabToday.classList.toggle('hidden', tab !== 'today');
  dom.tabVillage.classList.toggle('hidden', tab !== 'village');
  dom.tabBundles.classList.toggle('hidden', tab !== 'bundles');

  // the zoom and fit controls only mean anything on the map
  for (const id of ['zoom-in', 'zoom-out', 'zoom-text', 'zoom-toggle']) {
    const node = document.getElementById(id);
    if (node) node.style.display = tab === 'map' ? '' : 'none';
  }

  if (tab === 'map' && state) renderMap();
}

/* ---------- minimap ---------- */

function buildMapImage() {
  const width = mapData.width;
  const height = mapData.height;

  mapImage = document.createElement('canvas');
  mapImage.width = width;
  mapImage.height = height;

  const target = mapImage.getContext('2d');
  const image = target.createImageData(width, height);
  const data = image.data;

  for (let y = 0; y < height; y++) {
    const row = mapData.rows[y] || '';
    for (let x = 0; x < width; x++) {
      const color = TILE_COLORS[row[x]] || TILE_COLORS['.'];
      const offset = (y * width + x) * 4;
      data[offset] = color[0];
      data[offset + 1] = color[1];
      data[offset + 2] = color[2];
      data[offset + 3] = color[3];
    }
  }

  target.putImageData(image, 0, 0);
}

function resizeCanvas() {
  const ratio = window.devicePixelRatio || 1;
  const rect = dom.canvas.getBoundingClientRect();
  const width = Math.max(1, Math.round(rect.width * ratio));
  const height = Math.max(1, Math.round(rect.height * ratio));

  if (dom.canvas.width !== width || dom.canvas.height !== height) {
    dom.canvas.width = width;
    dom.canvas.height = height;
  }
}

function renderMap() {
  resizeCanvas();

  const cw = dom.canvas.width;
  const ch = dom.canvas.height;
  ctx.clearRect(0, 0, cw, ch);

  if (!mapImage || !state) return;

  const fit = Math.min(cw / mapData.width, ch / mapData.height);
  const scale = (followMode ? fit * 3.5 : fit) * ZOOM_STEPS[zoomStep];

  // in fit mode centre the whole map; in follow mode, or once zoomed past 1x, centre the player
  let originX, originY;
  if (followMode || zoomStep > 0) {
    originX = cw / 2 - state.x * scale;
    originY = ch / 2 - state.y * scale;
    originX = Math.min(0, Math.max(cw - mapData.width * scale, originX));
    originY = Math.min(0, Math.max(ch - mapData.height * scale, originY));
    if (mapData.width * scale < cw) originX = (cw - mapData.width * scale) / 2;
    if (mapData.height * scale < ch) originY = (ch - mapData.height * scale) / 2;
  } else {
    originX = (cw - mapData.width * scale) / 2;
    originY = (ch - mapData.height * scale) / 2;
  }

  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(mapImage, originX, originY, mapData.width * scale, mapData.height * scale);

  // remembered so a tap can be turned back into tile coordinates
  projection = { originX: originX, originY: originY, scale: scale };

  const dot = Math.max(3, scale * 0.9);

  for (const warp of mapData.warps || []) {
    ctx.fillStyle = '#c58cff';
    ctx.fillRect(originX + warp.x * scale, originY + warp.y * scale, Math.max(2, scale), Math.max(2, scale));
  }

  for (const entity of state.entities || []) {
    ctx.fillStyle = ENTITY_COLORS[entity.kind] || '#ffffff';
    ctx.beginPath();
    ctx.arc(originX + entity.x * scale, originY + entity.y * scale, dot / 2, 0, Math.PI * 2);
    ctx.fill();
  }

  drawPlayer(originX + state.x * scale, originY + state.y * scale, Math.max(5, dot * 1.6));
}

function drawPlayer(x, y, size) {
  // facing: 0 up, 1 right, 2 down, 3 left
  const angle = [-Math.PI / 2, 0, Math.PI / 2, Math.PI][state.facing] || 0;

  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(angle);
  ctx.beginPath();
  ctx.moveTo(size * 0.7, 0);
  ctx.lineTo(-size * 0.5, -size * 0.55);
  ctx.lineTo(-size * 0.5, size * 0.55);
  ctx.closePath();
  ctx.fillStyle = '#6ec1ff';
  ctx.fill();
  ctx.lineWidth = Math.max(1, size * 0.14);
  ctx.strokeStyle = '#04121f';
  ctx.stroke();
  ctx.restore();
}

/* ---------- frame ---------- */

function render() {
  renderHud();
  renderForecast();
  renderToday();
  renderQuests();
  renderSkills();
  renderInventory();
  renderMap();
}

/* ---------- connection health ---------- */

/**
 * Colour the dot from how long it's been since the game's tick advanced.
 *
 * Runs on its own timer rather than inside render, so it still turns red when nothing is arriving —
 * which is exactly the case worth showing.
 */
function renderLink() {
  const age = Date.now() - lastSnapshot;
  const health = !state || age > 3000 ? 'dead' : age > 700 ? 'slow' : 'live';
  dom.linkDot.className = `dot ${health}`;
  dom.linkDot.title = state ? `Last update ${(age / 1000).toFixed(1)}s ago` : 'Not connected';
}

setInterval(renderLink, 250);

/* ---------- map zoom and tap-to-identify ---------- */

function setZoom(step) {
  zoomStep = Math.max(0, Math.min(ZOOM_STEPS.length - 1, step));
  dom.zoomText.textContent = `${ZOOM_STEPS[zoomStep]}x`;
  dom.zoomOut.disabled = zoomStep === 0;
  dom.zoomIn.disabled = zoomStep === ZOOM_STEPS.length - 1;
  if (state) renderMap();
}

let tipTimer = null;

function showTip(text) {
  dom.mapTip.textContent = text;
  dom.mapTip.classList.remove('hidden');
  clearTimeout(tipTimer);
  tipTimer = setTimeout(() => dom.mapTip.classList.add('hidden'), 2500);
}

/* ---------- touch interaction ---------- */

function slotFromPoint(x, y) {
  const node = document.elementFromPoint(x, y);
  return node ? node.closest('.slot') : null;
}

function clearDragHighlight() {
  if (dragOverSlot) dragOverSlot.classList.remove('dragover');
  dragOverSlot = null;
}

dom.invGrid.addEventListener('pointerdown', (event) => {
  const slot = event.target.closest('.slot');
  if (!slot) return;

  dragFrom = Number(slot.dataset.index);
  dragActive = false;
  pointerStart = { x: event.clientX, y: event.clientY };
  dom.invGrid.setPointerCapture(event.pointerId);
});

dom.invGrid.addEventListener('pointermove', (event) => {
  if (dragFrom < 0 || !pointerStart) return;

  if (!dragActive) {
    const moved = Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y);
    if (moved < 10) return;
    dragActive = true;
    slots[dragFrom]?.root.classList.add('dragging');
  }

  const over = slotFromPoint(event.clientX, event.clientY);
  if (over !== dragOverSlot) {
    clearDragHighlight();
    if (over && Number(over.dataset.index) !== dragFrom) {
      over.classList.add('dragover');
      dragOverSlot = over;
    }
  }
});

dom.invGrid.addEventListener('pointerup', (event) => {
  if (dragFrom < 0) return;

  const from = dragFrom;
  const wasDrag = dragActive;
  const target = slotFromPoint(event.clientX, event.clientY);

  slots[from]?.root.classList.remove('dragging');
  clearDragHighlight();
  dragFrom = -1;
  dragActive = false;
  pointerStart = null;

  if (wasDrag) {
    if (target) {
      const to = Number(target.dataset.index);
      if (to !== from) send('swap', from, to);
    }
    return;
  }

  // a plain tap moves the cursor, and equips the item if it's reachable from the hotbar
  cursor = from;
  disarmTrash();
  if (from < 12) send('select', from);
  if (state) renderInventory();
});

dom.invGrid.addEventListener('pointercancel', () => {
  slots[dragFrom]?.root.classList.remove('dragging');
  clearDragHighlight();
  dragFrom = -1;
  dragActive = false;
  pointerStart = null;
});

function disarmTrash() {
  trashArmed = false;
  const button = dom.actions.querySelector('[data-act="trash"]');
  if (button) {
    button.classList.remove('armed');
    button.textContent = 'Trash';
  }
}

dom.actions.addEventListener('click', (event) => {
  const button = event.target.closest('.act');
  if (!button || button.disabled) return;

  const act = button.dataset.act;

  if (act === 'sort') {
    send('sort');
    return;
  }

  if (act === 'trash') {
    // destroying an item is unrecoverable, so make it a deliberate two-tap
    if (!trashArmed) {
      trashArmed = true;
      button.classList.add('armed');
      button.textContent = 'Sure?';
      setTimeout(disarmTrash, 3000);
      return;
    }
    disarmTrash();
    send('trash', cursor);
    return;
  }

  disarmTrash();
  send(act, cursor);
});

dom.zoomToggle.addEventListener('click', () => {
  followMode = !followMode;
  dom.zoomToggle.textContent = followMode ? 'Follow' : 'Fit';
  if (state) renderMap();
});

dom.zoomIn.addEventListener('click', () => setZoom(zoomStep + 1));
dom.zoomOut.addEventListener('click', () => setZoom(zoomStep - 1));

/** Tap the map to name what's there: the nearest character, or failing that the tile coordinates. */
dom.canvas.addEventListener('pointerdown', (event) => {
  if (!projection || !state) return;

  const rect = dom.canvas.getBoundingClientRect();
  const ratio = dom.canvas.width / rect.width;
  const tileX = ((event.clientX - rect.left) * ratio - projection.originX) / projection.scale;
  const tileY = ((event.clientY - rect.top) * ratio - projection.originY) / projection.scale;

  let best = null;
  let bestDistance = 3; // tiles

  for (const entity of state.entities || []) {
    const distance = Math.hypot(entity.x - tileX, entity.y - tileY);
    if (distance < bestDistance) {
      bestDistance = distance;
      best = entity;
    }
  }

  if (Math.hypot(state.x - tileX, state.y - tileY) < bestDistance)
    best = { kind: 'you', name: 'You' };

  showTip(best
    ? `${best.name} · ${best.kind}`
    : `${Math.round(tileX)}, ${Math.round(tileY)}`);
});

window.addEventListener('resize', () => {
  if (state) renderMap();
});

// keep the screen from doing browser-y things under a thumb
document.addEventListener('contextmenu', (event) => event.preventDefault());
document.addEventListener('dblclick', (event) => event.preventDefault());

/* ---------- the settings panel ---------- */

function buildSettingsPanel() {
  dom.settingsToggles.textContent = '';
  for (const [key, label] of SECTIONS) {
    const row = document.createElement('label');

    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = !!settings[key];
    box.addEventListener('change', () => {
      settings[key] = box.checked;
      saveSettings();
      applySettings();
    });

    const text = document.createElement('span');
    text.textContent = label;

    row.append(box, text);
    dom.settingsToggles.appendChild(row);
  }

  dom.settingsAccents.textContent = '';
  for (const colour of ACCENTS) {
    const swatch = document.createElement('button');
    swatch.className = 'swatch' + (colour === settings.accent ? ' on' : '');
    swatch.style.background = colour;
    swatch.title = colour;
    swatch.addEventListener('click', () => {
      settings.accent = colour;
      saveSettings();
      applySettings();
      for (const other of dom.settingsAccents.children)
        other.classList.toggle('on', other.style.background === swatch.style.background);
    });
    dom.settingsAccents.appendChild(swatch);
  }

  const current = (state && state.inventory) ? state.inventory[cursor] : null;
  const name = wikiNameFor(current);
  dom.wikiItem.disabled = !name;
  dom.wikiItem.textContent = name ? `Look up "${name}"` : 'Look up the selected item';

  buildSegmented(dom.settingsScale, Object.keys(SCALES), 'scale', (v) => v);
  buildSegmented(dom.settingsRate, [5, 10, 15, 20], 'rate', (v) => `${v}/s`);
}

/** A row of mutually exclusive buttons bound to one setting. */
function buildSegmented(host, values, key, label) {
  host.textContent = '';
  for (const value of values) {
    const button = document.createElement('button');
    button.textContent = label(value);
    button.classList.toggle('on', settings[key] === value);
    button.addEventListener('click', () => {
      settings[key] = value;
      saveSettings();
      applySettings();
      for (const other of host.children) other.classList.remove('on');
      button.classList.add('on');
    });
    host.appendChild(button);
  }
}

dom.gear.addEventListener('click', () => {
  buildSettingsPanel();
  dom.settings.classList.remove('hidden');
});

for (const button of document.querySelectorAll('.tab'))
  button.addEventListener('click', () => setTab(button.dataset.tab));

dom.villageFilter.addEventListener('input', () => {
  villageFilterText = dom.villageFilter.value;
  renderVillagers();
});

dom.detailWiki.addEventListener('click', () => {
  const item = (state && state.inventory) ? state.inventory[cursor] : null;
  openWiki(wikiNameFor(item));
});

dom.wikiHome.addEventListener('click', () => openWiki(null));

dom.wikiItem.addEventListener('click', () => {
  const item = (state && state.inventory) ? state.inventory[cursor] : null;
  openWiki(wikiNameFor(item));
});

dom.settingsClose.addEventListener('click', () => dom.settings.classList.add('hidden'));

dom.settings.addEventListener('click', (event) => {
  if (event.target === dom.settings) dom.settings.classList.add('hidden'); // tap the backdrop to close
});

dom.settingsReset.addEventListener('click', () => {
  settings = Object.assign({}, SETTING_DEFAULTS);
  saveSettings();
  applySettings();
  buildSettingsPanel();
});

loadSettings();
applySettings();
setZoom(0);
setTab('map');
renderLink();
poll();
pollSlow();
