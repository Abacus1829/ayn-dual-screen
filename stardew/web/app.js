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
  rate: 10,
  theme: 'stardew',
  followGame: true,
  fadeIdle: true,
  npcHeads: true,
  mapMode: 'local',

  // Which tabs appear in the bar. An object rather than one flat key per page, so a page added later
  // inherits "shown" without anyone having to migrate a settings blob that is already on a device.
  pages: {}
};

/** Behaviour switches. Separate from SECTIONS, which only show and hide parts of the layout. */
const BEHAVIOUR = [
  ['followGame', 'Follow the controller’s selected item'],
  ['fadeIdle', 'Fade the settings button when idle'],
  ['npcHeads', 'Villager faces on the map']
];

const THEMES = { stardew: 'Stardew', plain: 'Plain' };

const MAP_MODES = { local: 'Tile map', world: 'In-game map' };

/**
 * Which part of a page each toggle hides, and what to call it in the panel.
 *
 * Shorter than it was, because most of the old entries hid a whole panel -- and a panel is a tab
 * now, which the Pages list turns off properly instead of leaving an empty frame behind it.
 */
const SECTIONS = [
  ['hud', 'Bottom status bar'],
  ['legend', 'Map legend'],
  ['skills', 'Skill levels'],
  ['detail', 'Selected item'],
  ['actions', 'Action buttons']
];

/** The tabs that may be turned off. Today is home, and Settings is how you turn them back on. */
const HIDEABLE_PAGES = [
  ['map', 'Map'],
  ['farm', 'Farm'],
  ['journal', 'Journal'],
  ['bundles', 'Bundles'],
  ['calendar', 'Calendar'],
  ['village', 'People']
];

const pageShown = (name) => !settings.pages || settings.pages[name] !== false;

const ACCENTS = ['#c8a066', '#e0ae68', '#8fd14f', '#6ec1ff', '#ffd166', '#ff8fab', '#c58cff', '#f2e6d5'];

/*
 * The text scale, relative to the panel rather than fixed in pixels.
 *
 * These were '12px' / '14px' / '17px' / '20px', which is correct on a laptop and wrong on the
 * handheld this exists for. The AYN Thor's lower panel is about 1240x1080 across 3.9 inches, so it
 * reports roughly twice as many CSS pixels per inch as a phone does — and a CSS pixel is a unit of
 * *addressing*, not of size. Fourteen of them there are physically half what fourteen of them are
 * anywhere else, which is why the page came out looking like a screenshot of itself.
 *
 * vmin ties the type to the smaller side of the panel, so the same layout fills a 1240x1080 second
 * screen, a phone, and a browser window without any of them being a special case. The clamps stop
 * it collapsing on a very short window or ballooning on a television.
 *
 * Checked against both references: at 1023x678 (a desktop browser) medium comes out at 13.9px,
 * which is the 14px this used to hard-code — so nothing changes on the screens where it already
 * looked right. At 1240x1080 it comes out at 22px, which is the fix.
 */
const SCALES = {
  small:  'clamp(11px, 1.70vmin, 22px)',
  medium: 'clamp(13px, 2.05vmin, 26px)',
  large:  'clamp(15px, 2.45vmin, 32px)',
  huge:   'clamp(18px, 3.00vmin, 40px)',
};

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

/* ---------- idle fade ---------- */

let idleTimer = null;

/** Any touch or click wakes the chrome back up; it fades again after a few seconds of stillness. */
function markActive() {
  document.body.classList.remove('idle');
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => document.body.classList.add('idle'), 4000);
}

/**
 * Hide the tabs that are turned off, and step off one that has just been turned off.
 *
 * Leaving a tab selected while its button disappears would strand the page on something with no way
 * back to it, so the fallback is Today.
 */
function applyPages() {
  for (const button of document.querySelectorAll('.navtab')) {
    const name = button.dataset.tab;
    const hideable = HIDEABLE_PAGES.some(([key]) => key === name);
    button.classList.toggle('hidden', hideable && !pageShown(name));
  }

  if (HIDEABLE_PAGES.some(([key]) => key === activeTab) && !pageShown(activeTab))
    setTab('today');
}

function applySettings() {
  for (const [key] of SECTIONS)
    document.body.classList.toggle(`no-${key}`, !settings[key]);

  document.body.classList.toggle('theme-plain', settings.theme === 'plain');
  document.body.classList.toggle('fade-idle', !!settings.fadeIdle);
  applyPages();
  updateMapModeButton();
  markActive();

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
  tabToday: el('tab-today'),
  tabMap: el('tab-map'),
  tabFarm: el('tab-farm'),
  tabJournal: el('tab-journal'),
  tabVillage: el('tab-village'),
  tabBundles: el('tab-bundles'),
  tabCalendar: el('tab-calendar'),
  tabSettingsPage: el('tab-settings'),
  todayStrip: el('today-strip'),
  backpackRows: el('backpack-rows'),
  rowPrev: el('row-prev'),
  rowNext: el('row-next'),
  rowText: el('row-text'),
  hotbarLabel: el('hotbar-label'),
  farmSummary: el('farm-summary'),
  farmMachines: el('farm-machines'),
  farmAnimals: el('farm-animals'),
  farmTrees: el('farm-trees'),
  farmMachinesCount: el('farm-machines-count'),
  farmAnimalsCount: el('farm-animals-count'),
  farmTreesCount: el('farm-trees-count'),
  calendarTitle: el('calendar-title'),
  calendarNote: el('calendar-note'),
  calendarWeekdays: el('calendar-weekdays'),
  calendarGrid: el('calendar-grid'),
  settingsPage: el('settings-page'),
  settingsResetPage: el('settings-reset-page'),
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
  settingsPages: el('settings-pages'),
  settingsConnection: el('settings-connection'),
  settingsAllows: el('settings-allows'),
  settingsToggles: el('settings-toggles'),
  settingsAccents: el('settings-accents'),
  settingsScale: el('settings-scale'),
  settingsRate: el('settings-rate'),
  settingsTheme: el('settings-theme'),
  settingsBehaviour: el('settings-behaviour'),
  settingsMap: el('settings-map'),
  mapMode: el('map-mode'),
  chest: el('chest'),
  chestName: el('chest-name'),
  chestHint: el('chest-hint'),
  chestGrid: el('chest-grid'),
  actStore: el('act-store')
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

let activeTab = 'today';
let villagerData = null;
let communityData = null;
let farmData = null;
let calendarData = null;
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
      applyMenuContrast();
      followGameSelection();
      hideOffline();
      render();
      if (state.mapRev !== loadedMapRev) loadMap();
      if (state.worldRev !== loadedWorldRev) loadWorldMap();
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
  await refreshSlow();
  setTimeout(pollSlow, 1000);
}

/**
 * Fetch whatever the visible tab needs, now.
 *
 * Split out of the polling loop so switching tabs can call it directly. These lists are fetched once
 * a second, which is plenty while you are looking at one — but it used to mean that *arriving* at a
 * tab showed an empty panel until the next tick came round. Up to a second of blank, every time,
 * which reads as the tab being broken rather than as it being a moment behind.
 */
async function refreshSlow() {
  try {
    if (activeTab === 'village') {
      villagerData = await (await fetch('/villagers', { cache: 'no-store' })).json();
      renderVillagers();
    } else if (activeTab === 'bundles') {
      communityData = await (await fetch('/community', { cache: 'no-store' })).json();
      renderBundles();
    } else if (activeTab === 'farm') {
      farmData = await (await fetch('/farm', { cache: 'no-store' })).json();
      renderFarm();
    } else if (activeTab === 'calendar') {
      calendarData = await (await fetch('/calendar', { cache: 'no-store' })).json();
      renderCalendar();
    }
  } catch (err) {
    /* the next tick will retry */
  }
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

/**
 * The journal: what each quest wants, how long is left, and what it pays.
 *
 * It used to be four one-line rows because it shared the screen with the map. As its own page it can
 * carry the whole list and the parts that were cut -- the reward, which is half of why you would
 * choose one errand over another, and a way to drop a quest that has become impossible.
 */
function renderQuests() {
  const quests = state.quests || [];
  const signature = quests.map((q) => `${q.id}|${q.name}|${q.objective}|${q.daysLeft}|${q.complete}`).join(',');
  if (signature === questSignature) return;
  questSignature = signature;

  dom.questCount.textContent = quests.length ? `${quests.length} open` : '';
  dom.quests.textContent = '';

  if (!quests.length) {
    const empty = document.createElement('div');
    empty.className = 'farm-empty';
    empty.textContent = 'Nothing in the journal.';
    dom.quests.appendChild(empty);
    return;
  }

  for (const quest of quests) dom.quests.appendChild(questRow(quest));
}

function questRow(quest) {
  const row = document.createElement('div');
  row.className = 'quest' + (quest.complete ? ' done' : '');

  const text = document.createElement('div');
  text.className = 'quest-text';

  const title = document.createElement('span');
  title.textContent = quest.name;

  const objective = document.createElement('small');
  objective.textContent = quest.objective || '';

  text.append(title, objective);

  const reward = questReward(quest);
  if (reward) {
    const pay = document.createElement('em');
    pay.className = 'quest-reward';
    pay.textContent = reward;
    text.appendChild(pay);
  }

  row.appendChild(text);

  const side = document.createElement('div');
  side.className = 'quest-side';

  const days = document.createElement('i');
  if (quest.complete) {
    days.textContent = 'ready to hand in';
    days.className = 'ready';
  } else if (quest.daysLeft >= 0) {
    days.textContent = quest.daysLeft === 1 ? 'due today' : `${quest.daysLeft} days left`;
    if (quest.daysLeft <= 1) days.className = 'soon';
  } else {
    days.textContent = 'no deadline';
  }
  side.appendChild(days);

  // Only offered where the game itself would offer it; a story quest has no cancel button in-game
  // either, and a button that silently does nothing is worse than no button.
  if (quest.cancellable && !quest.complete) {
    const drop = document.createElement('button');
    drop.className = 'chip tight';
    drop.textContent = 'Drop';
    drop.addEventListener('click', () => {
      if (drop.classList.contains('armed')) {
        send('cancelQuest', quest.id);
        questSignature = null;
        return;
      }
      drop.classList.add('armed');
      drop.textContent = 'Sure?';
      setTimeout(() => {
        drop.classList.remove('armed');
        drop.textContent = 'Drop';
      }, 3000);
    });
    side.appendChild(drop);
  }

  row.appendChild(side);
  return row;
}

function questReward(quest) {
  const bits = [];
  if (quest.rewardGold > 0) bits.push(`${quest.rewardGold.toLocaleString()}g`);
  if (quest.reward) bits.push(quest.reward);
  return bits.join(' \u00b7 ');
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

/** Build `count` empty slot elements into `host`, returning the handles used to refresh them. */
function makeSlots(host, count, hotbar) {
  host.textContent = '';
  const made = [];

  for (let i = 0; i < count; i++) {
    const slot = document.createElement('div');
    slot.className = 'slot' + (hotbar && i < 12 ? ' hotbar' : '');
    slot.dataset.index = String(i);

    const img = document.createElement('img');
    img.alt = '';
    img.style.display = 'none';

    const stack = document.createElement('span');
    stack.className = 'stack';

    const qual = document.createElement('i');
    qual.className = 'qual';
    qual.style.display = 'none';

    // One track serves both readouts: a can is never on cooldown and a sword never holds water,
    // so a second element would be an empty div in every slot on screen.
    const meter = document.createElement('u');
    meter.className = 'meter';
    meter.style.display = 'none';
    const fill = document.createElement('b');
    meter.appendChild(fill);

    slot.append(img, stack, qual, meter);
    host.appendChild(slot);
    made.push({ root: slot, img: img, stack: stack, qual: qual, meter: meter, fill: fill, signature: null });
  }

  return made;
}

/**
 * The hotbar, then every unlocked backpack row directly beneath it.
 *
 * They were one long twelve-wide grid, which read as thirty-six equal slots when only the first twelve
 * are the ones the game will actually let you swing. Splitting them puts the usable row where the eye
 * lands and leaves the rest as what they are: storage you can see without pausing.
 *
 * `slots` stays index-aligned with the inventory across both hosts, so every handler keyed on
 * `dataset.index` keeps working regardless of which grid a slot ended up in.
 */
function buildSlots(count) {
  slots = makeSlots(dom.invGrid, Math.min(12, count), true);

  dom.backpackRows.textContent = '';
  for (let start = 12; start < count; start += 12) {
    const row = document.createElement('div');
    row.className = 'inv-row';

    const made = makeSlots(row, Math.min(12, count - start), false);
    made.forEach((slot, offset) => { slot.root.dataset.index = String(start + offset); });

    dom.backpackRows.appendChild(row);
    slots.push(...made);
  }

  dom.backpackRows.classList.toggle('hidden', count <= 12);
}

/** Repaint one slot from its item, skipping the DOM writes when nothing about it changed. */
function paintSlot(slot, item) {
  const signature = item && item.name
    ? `${item.iconKey}|${item.stack}|${item.quality}|${item.name}|${item.water}/${item.waterMax}|${item.cooldown}/${item.cooldownMax}`
    : '';
  if (slot.signature === signature)
    return;

  slot.signature = signature;

  if (item && item.name) {
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
    paintMeter(slot, item);
  } else {
    slot.img.removeAttribute('src');
    slot.img.style.display = 'none';
    slot.stack.textContent = '';
    slot.qual.style.display = 'none';
    slot.meter.style.display = 'none';
  }
}

/**
 * The strip along the bottom of a slot: how much water is left, or how long until you can swing again.
 *
 * Both are things you otherwise learn by trying and failing -- the can that runs dry two tiles from the
 * end of a row, the dagger that will not come out. Neither is worth a number on a slot this size, so
 * each is a bar and the exact figure goes in the detail line underneath.
 */
function paintMeter(slot, item) {
  if (item.waterMax > 0) {
    slot.meter.className = 'meter water';
    slot.meter.style.display = '';
    slot.fill.style.width = `${Math.max(0, Math.min(1, item.water / item.waterMax)) * 100}%`;
    return;
  }

  if (item.cooldownMax > 0 && item.cooldown > 0) {
    slot.meter.className = 'meter cool';
    slot.meter.style.display = '';
    slot.fill.style.width = `${Math.max(0, Math.min(1, item.cooldown / item.cooldownMax)) * 100}%`;
    return;
  }

  slot.meter.style.display = 'none';
}

/* ---------- the open chest ---------- */

let chestSlots = [];

function renderChest() {
  const chest = state.chest;
  const open = !!(chest && chest.open);

  // the Store button's enabled state is decided in renderDetail with the other actions
  document.body.classList.toggle('chest-open', open);
  dom.chest.classList.toggle('hidden', !open);

  if (!open)
    return;

  dom.chestName.textContent = chest.name || 'Chest';
  dom.chestHint.textContent = chest.canEdit ? 'tap to take' : 'read-only';

  const items = chest.items || [];
  if (chestSlots.length !== items.length)
    chestSlots = makeSlots(dom.chestGrid, items.length, false);

  for (let i = 0; i < items.length; i++)
    paintSlot(chestSlots[i], items[i]);
}

dom.chestGrid.addEventListener('click', (event) => {
  const slot = event.target.closest('.slot');
  if (!slot || !state || !state.chest || !state.chest.canEdit)
    return;

  send('chestTake', Number(slot.dataset.index));
});

/**
 * Pick dark or light ink from the brightness of the game's actual menu box.
 *
 * Recolour mods repaint Maps/MenuTiles, and the mod serves whatever is loaded, so the frame can be
 * light parchment or near-black depending on what's installed. Reading its brightness rather than
 * assuming one keeps the text legible on both without the player configuring anything.
 */
/* ---------- the game's own world map ---------- */

let worldMap = null;        // {region, x, y, width, height}
let worldImage = null;
let loadedWorldRev = -1;
let worldPending = false;

async function loadWorldMap() {
  if (worldPending) return;
  worldPending = true;

  try {
    const next = await (await fetch('/worldmap', { cache: 'no-store' })).json();
    if (next && next.available) {
      worldMap = next;
      loadedWorldRev = state.worldRev;

      // the rev is in the URL because the image is served with a long cache lifetime and the region
      // id doesn't change when only the artwork does — a repainted map would otherwise never appear
      const image = new Image();
      image.src = `/worldmap/${next.region}.png?v=${next.rev}`;
      image.addEventListener('load', () => { worldImage = image; });
      image.addEventListener('error', () => { worldImage = null; });
    } else {
      worldMap = null;
      worldImage = null;
      loadedWorldRev = state.worldRev;
    }
  } catch (err) {
    /* the next poll retries */
  } finally {
    worldPending = false;
  }
}

/** True when the world map can actually be drawn right now. */
function worldReady() {
  return settings.mapMode === 'world' && !!worldMap && !!worldImage && !!state.world;
}

/**
 * Draw the map the game itself draws, with everyone placed on it.
 *
 * Positions arrive in the map's own pixel space, and the image served is exactly the region the mod
 * measured those against, so placing a marker is a straight scale rather than a guess.
 */
function renderWorldMap(cw, ch) {
  const scale = Math.min(cw / worldMap.width, ch / worldMap.height);
  const drawWidth = worldMap.width * scale;
  const drawHeight = worldMap.height * scale;
  const originX = (cw - drawWidth) / 2;
  const originY = (ch - drawHeight) / 2;

  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(worldImage, originX, originY, drawWidth, drawHeight);

  const place = (wx, wy) => [
    originX + (wx - worldMap.x) * scale,
    originY + (wy - worldMap.y) * scale
  ];

  const dot = Math.max(4, scale * 6);

  for (const entity of state.entities || []) {
    if (entity.wx == null || entity.wy == null)
      continue;

    const [ex, ey] = place(entity.wx, entity.wy);
    const face = settings.npcHeads ? npcFace(entity.iconKey) : null;

    if (face) {
      const size = Math.max(16, dot * 2);
      ctx.drawImage(face, ex - size / 2, ey - size / 2, size, size);
      continue;
    }

    ctx.fillStyle = ENTITY_COLORS[entity.kind] || '#ffffff';
    ctx.beginPath();
    ctx.arc(ex, ey, dot / 2, 0, Math.PI * 2);
    ctx.fill();
  }

  const [px, py] = place(state.world.x, state.world.y);
  drawPlayer(px, py, Math.max(9, dot * 1.6));
}

/**
 * Villager faces for the map, loaded once each and drawn straight to the canvas.
 *
 * Returns null until the image has actually decoded, so the caller falls back to a dot rather than
 * drawing nothing on the first frame a character appears.
 */
const npcFaces = new Map();

function npcFace(key) {
  if (!key)
    return null;

  let image = npcFaces.get(key);
  if (image === undefined) {
    image = new Image();
    image.src = `/npc/${key}.png`;
    image.addEventListener('error', () => npcFaces.set(key, null));
    npcFaces.set(key, image);
  }

  return image && image.complete && image.naturalWidth > 0 ? image : null;
}

function applyMenuContrast() {
  if (typeof state.menuLuma !== 'number')
    return;
  document.body.classList.toggle('on-light', state.menuLuma > 128);
}

let lastGameSlot = -1;

/**
 * Move the cursor when the held item changes in-game.
 *
 * Without this the detail line only followed taps on the screen, so scrolling the hotbar on a
 * controller left it describing whatever was tapped last.
 */
function followGameSelection() {
  const slot = state.selectedSlot;
  if (slot === lastGameSlot)
    return;

  const changedInGame = lastGameSlot !== -1;
  lastGameSlot = slot;

  if (changedInGame && settings.followGame && slot >= 0)
    cursor = slot;
}

function renderInventory() {
  const items = state.inventory || [];
  if (slots.length !== items.length) {
    buildSlots(items.length);
    renderRowControl();
  }

  for (let i = 0; i < items.length; i++) {
    paintSlot(slots[i], items[i]);
    slots[i].root.classList.toggle('equipped', i === state.selectedSlot);
    slots[i].root.classList.toggle('cursor', i === cursor);
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
    else if (act === 'chestPut') button.disabled = !hasItem || !(state.chest && state.chest.open && state.chest.canEdit);
    else button.disabled = !allowed || !hasItem;

    button.title = allowed ? '' : 'Turned off in the mod config';
  }

  dom.detailWiki.disabled = !hasItem;

  if (!hasItem) disarmTrash();
}

/* ---------- today ---------- */

const WEEKDAYS = { Mon: 'Monday', Tue: 'Tuesday', Wed: 'Wednesday', Thu: 'Thursday', Fri: 'Friday', Sat: 'Saturday', Sun: 'Sunday' };

/**
 * The line of facts at the top of Today.
 *
 * These are the numbers the footer already carries, restated where the page can give them room --
 * the footer is a glance strip six millimetres tall on the handheld, and reading a luck band off it
 * is not realistic. Here each gets a label and the two bars get to be bars.
 */
function renderTodayStrip() {
  const season = SEASON_LABEL[String(state.season).toLowerCase()] || state.season;
  const weekday = WEEKDAYS[state.dayOfWeek] || state.dayOfWeek || '';
  const energy = state.maxStamina > 0 ? state.stamina / state.maxStamina : 0;
  const health = state.maxHealth > 0 ? state.health / state.maxHealth : 0;

  const facts = [
    ['Day', `${weekday}`, `${season} ${state.dayOfMonth}, Year ${state.year}`],
    ['Time', formatClock(state.timeOfDay), timeLeftLabel(state.timeOfDay)],
    ['Weather', WEATHER_LABEL[state.weather] || state.weather || '--', tomorrowLabel()],
    ['Luck', luckWord(state.dailyLuck), 'today’s fortune'],
    ['Gold', `${(state.money || 0).toLocaleString()}g`, 'in your wallet'],
    ['Shipped', shippedCount(), shippedValue()],
  ];

  ensureStrip(facts.length + 2);

  facts.forEach(([label, value, note], index) => {
    const cell = dom.todayStrip.children[index];
    cell.className = 'fact';
    setText(cell.children[0], label);
    setText(cell.children[1], value);
    setText(cell.children[2], note);
  });

  paintBarFact(dom.todayStrip.children[facts.length], 'Energy',
    `${Math.round(state.stamina)} / ${state.maxStamina}`, energy, 'energy');
  paintBarFact(dom.todayStrip.children[facts.length + 1], 'Health',
    `${state.health} / ${state.maxHealth}`, health, 'health');
}

/** Text writes are the expensive part at ten frames a second, so only write what changed. */
function setText(node, text) {
  const value = text == null ? '' : String(text);
  if (node.textContent !== value) node.textContent = value;
}

function ensureStrip(count) {
  while (dom.todayStrip.children.length < count) {
    const cell = document.createElement('div');
    cell.className = 'fact';
    cell.append(document.createElement('span'), document.createElement('b'), document.createElement('i'));
    dom.todayStrip.appendChild(cell);
  }
}

function paintBarFact(cell, label, value, fraction, kind) {
  cell.className = 'fact bar-fact';
  setText(cell.children[0], label);
  setText(cell.children[1], value);

  let track = cell.querySelector('.mini');
  if (!track) {
    track = document.createElement('u');
    track.className = 'mini';
    track.appendChild(document.createElement('b'));
    cell.children[2].replaceWith(track);
  }

  track.firstChild.className = kind;
  track.firstChild.style.width = `${Math.max(0, Math.min(1, fraction)) * 100}%`;
}

function luckWord(luck) {
  if (typeof luck !== 'number') return '--';
  if (luck <= -0.07) return 'Very unlucky';
  if (luck < 0) return 'Unlucky';
  if (luck >= 0.07) return 'Very lucky';
  if (luck > 0) return 'Lucky';
  return 'Neutral';
}

function tomorrowLabel() {
  if (!state.weatherTomorrow) return '';
  return `tomorrow: ${WEATHER_LABEL[state.weatherTomorrow] || state.weatherTomorrow}`;
}

/*
 * What is already in the bin.
 *
 * This is the half of the day's money that is not in the wallet yet, and it is the number that
 * decides whether it is worth another trip back to the farmhouse before bed.
 */
function shippedCount() {
  const bin = state.shipping;
  if (!bin || !bin.count) return 'nothing';
  return bin.count === 1 ? '1 item' : `${bin.count} items`;
}

function shippedValue() {
  const bin = state.shipping;
  if (!bin || !bin.count) return 'bin is empty';
  return `worth ${(bin.value || 0).toLocaleString()}g`;
}

/**
 * Which twelve are in the hotbar, and the buttons that change it.
 *
 * The count is tracked here rather than read from the save because the game has no notion of a
 * "current row" -- shiftToolbar physically rolls the items round. Pressing Tab in-game therefore
 * drifts this label, so it resets whenever the bag's size changes and never claims more than it knows.
 */
let rowOffset = 0;

function renderRowControl() {
  const rows = Math.max(1, Math.ceil((state && state.inventory ? state.inventory.length : 12) / 12));
  rowOffset = ((rowOffset % rows) + rows) % rows;

  const single = rows <= 1;
  dom.rowPrev.disabled = single;
  dom.rowNext.disabled = single;
  setText(dom.rowText, single ? '' : `Row ${rowOffset + 1} of ${rows}`);
  setText(dom.hotbarLabel, single ? 'Hotbar' : 'Hotbar — the row you can use');
}

function shiftRow(direction) {
  if (!state || !state.inventory || state.inventory.length <= 12) return;

  rowOffset += direction;
  send('shiftRow', direction >= 0 ? 1 : -1);
  renderRowControl();
}

dom.rowPrev.addEventListener('click', () => shiftRow(-1));
dom.rowNext.addEventListener('click', () => shiftRow(1));

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

    for (const bundle of room.bundles || []) {
      if (bundle.complete) continue;
      box.appendChild(bundleRow(bundle));
    }

    dom.bundleList.appendChild(box);
  }
}

/**
 * One unfinished bundle: its name, how far along it is, and the items it is still short of.
 *
 * The names alone were never enough -- "Spring Crops" does not tell you whether you are one parsnip
 * away or have not started. Showing the actual items with their icons makes this a shopping list you
 * can read while standing in the field, which is the only reason to have it on a second screen.
 */
function bundleRow(bundle) {
  const row = document.createElement('div');
  row.className = 'bundle';

  const head = document.createElement('div');
  head.className = 'bundle-head';

  const name = document.createElement('span');
  name.textContent = bundle.name;

  const progress = document.createElement('em');
  progress.textContent = `${bundle.have} / ${bundle.need}`;

  head.append(name, progress);
  row.appendChild(head);

  const items = document.createElement('div');
  items.className = 'bundle-items';

  for (const item of bundle.missing || []) {
    const chip = document.createElement('span');
    chip.className = 'bundle-item' + (item.quality > 0 ? ` q${item.quality}` : '');
    chip.title = qualityWord(item.quality) ? `${qualityWord(item.quality)} ${item.name}` : item.name;

    if (item.iconKey) {
      const img = document.createElement('img');
      img.src = `/icon/${item.iconKey}.png`;
      img.alt = '';
      chip.appendChild(img);
    }

    const label = document.createElement('i');
    label.textContent = item.count > 1 ? `${item.name} x${item.count}` : item.name;
    chip.appendChild(label);

    items.appendChild(chip);
  }

  row.appendChild(items);
  return row;
}

const QUALITY_WORDS = { 1: 'Silver', 2: 'Gold', 4: 'Iridium' };
const qualityWord = (quality) => QUALITY_WORDS[quality] || '';

/* ---------- the farm ---------- */

/**
 * Three lists, each answering a question that otherwise costs a walk across the farm.
 *
 * They are separate columns rather than one merged feed because they are read at different moments:
 * the machines in the evening, the animals in the morning, the trees when you happen to think of it.
 * Each scrolls on its own so a hundred kegs cannot bury the four cows.
 */
function renderFarm() {
  if (!farmData) return;

  const ready = farmData.machinesReady || 0;
  const unpetted = farmData.animalsUnpetted || 0;
  const fruit = farmData.fruitWaiting || 0;

  const summary = [];
  if (ready) summary.push(`${ready} ready`);
  if (unpetted) summary.push(`${unpetted} unpetted`);
  if (fruit) summary.push(`${fruit} fruit`);
  dom.farmSummary.textContent = summary.length ? summary.join(' \u00b7 ') : 'nothing waiting';

  const machines = farmData.machines || [];
  const animals = farmData.animals || [];
  const trees = farmData.trees || [];

  dom.farmMachinesCount.textContent = machines.length ? String(machines.length) : '';
  dom.farmAnimalsCount.textContent = animals.length ? String(animals.length) : '';
  dom.farmTreesCount.textContent = trees.length ? String(trees.length) : '';

  fillList(dom.farmMachines, machines, machineRow, 'No machines running.');
  fillList(dom.farmAnimals, animals, animalRow, 'No animals yet.');
  fillList(dom.farmTrees, trees, treeRow, 'No fruit trees planted.');
}

function fillList(host, rows, build, empty) {
  host.textContent = '';

  if (!rows.length) {
    const note = document.createElement('div');
    note.className = 'farm-empty';
    note.textContent = empty;
    host.appendChild(note);
    return;
  }

  for (const row of rows) host.appendChild(build(row));
}

/** One row: icon, two lines of text, and a state word on the right. */
function farmRow(iconKey, title, subtitle, tagText, tagClass) {
  const row = document.createElement('div');
  row.className = 'farm-row' + (tagClass ? ` ${tagClass}` : '');

  // No placeholder art when there is nothing to show. A column of grey squares reads as a broken
  // page rather than as "this cow has already been milked", so the space is simply left empty.
  if (iconKey) {
    const img = document.createElement('img');
    img.src = `/icon/${iconKey}.png`;
    img.alt = '';
    row.appendChild(img);
  } else {
    row.classList.add('no-icon');
  }

  const text = document.createElement('div');
  const name = document.createElement('span');
  name.textContent = title;
  const note = document.createElement('small');
  note.textContent = subtitle;
  text.append(name, note);
  row.appendChild(text);

  if (tagText) {
    const tag = document.createElement('em');
    tag.textContent = tagText;
    row.appendChild(tag);
  }

  return row;
}

function machineRow(machine) {
  const where = machine.location ? machine.location : '';
  const what = machine.produce ? machine.produce : 'working';

  let tag = '';
  let cls = '';
  if (machine.ready) { tag = 'ready'; cls = 'ready'; }
  else if (machine.minutesLeft > 0) tag = gameMinutes(machine.minutesLeft);

  return farmRow(machine.iconKey, machine.name, [what, where].filter(Boolean).join(' \u00b7 '), tag, cls);
}

function animalRow(animal) {
  const bits = [animal.type, animal.building].filter(Boolean);
  const wants = [];
  if (!animal.pet) wants.push('needs petting');
  if (!animal.fed) wants.push('not fed');

  return farmRow(
    animal.iconKey,
    animal.name,
    bits.join(' \u00b7 '),
    animal.produce ? animal.produce : wants.join(' · '),
    animal.produce ? 'ready' : (wants.length ? 'wants' : '')
  );
}

function treeRow(tree) {
  const growing = tree.daysToMature > 0;
  const subtitle = growing
    ? `${tree.location} \u00b7 ${tree.daysToMature} day${tree.daysToMature === 1 ? '' : 's'} to mature`
    : tree.location;

  return farmRow(
    tree.iconKey,
    tree.name,
    subtitle,
    tree.fruit > 0 ? String(tree.fruit) : (growing ? 'growing' : ''),
    tree.fruit > 0 ? 'ready' : ''
  );
}

/** Machine timers arrive in game minutes, which run ten to the real second. */
function gameMinutes(minutes) {
  if (minutes >= 60) {
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest ? `${hours}h ${rest}m` : `${hours}h`;
  }
  return `${minutes}m`;
}

/* ---------- the calendar ---------- */

const WEEKDAY_LETTERS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/**
 * The season, as the four-by-seven grid the game itself uses.
 *
 * Drawn as a whole month rather than as a list of upcoming events, because the question this answers
 * is usually "how long have I got" -- and counting squares is how anyone answers that.
 */
function renderCalendar() {
  if (!calendarData) return;

  const season = SEASON_LABEL[String(calendarData.season).toLowerCase()] || calendarData.season;
  dom.calendarTitle.textContent = `${season}, Year ${calendarData.year}`;

  const days = calendarData.days || [];
  const upcoming = days.filter((day) => !day.past && (day.festival || (day.birthdays || []).length));
  dom.calendarNote.textContent = upcoming.length ? `${upcoming.length} still to come` : '';

  if (!dom.calendarWeekdays.children.length) {
    for (const label of WEEKDAY_LETTERS) {
      const head = document.createElement('span');
      head.textContent = label;
      dom.calendarWeekdays.appendChild(head);
    }
  }

  dom.calendarGrid.textContent = '';
  for (const day of days) dom.calendarGrid.appendChild(calendarCell(day));
}

function calendarCell(day) {
  const cell = document.createElement('div');
  cell.className = 'cal-day';
  if (day.today) cell.classList.add('today');
  if (day.past) cell.classList.add('past');
  if (day.festival) cell.classList.add('festival');

  const number = document.createElement('b');
  number.textContent = day.day;
  cell.appendChild(number);

  const names = day.birthdays || [];
  for (let i = 0; i < names.length; i++) {
    const badge = document.createElement('span');
    badge.className = 'cal-birthday';

    // Loaded straight rather than through npcFace, which only hands back an image once it has
    // already decoded -- fine for a canvas that redraws ten times a second, useless for a grid drawn
    // once when the tab opens. A portrait that fails to resolve removes itself.
    const key = (day.portraits || [])[i];
    if (key) {
      const img = document.createElement('img');
      img.src = `/npc/${key}.png`;
      img.alt = '';
      img.addEventListener('error', () => img.remove());
      badge.appendChild(img);
    }

    const name = document.createElement('i');
    name.textContent = names[i];
    badge.appendChild(name);
    cell.appendChild(badge);
  }

  if (day.festival) {
    const tag = document.createElement('small');
    tag.className = 'cal-festival';
    tag.textContent = day.festival;
    cell.appendChild(tag);
  }

  if (day.cart) {
    const cart = document.createElement('small');
    cart.className = 'cal-cart';
    cart.textContent = 'Cart';
    cell.appendChild(cart);
  }

  return cell;
}

/* ---------- tabs ---------- */

/**
 * Show one panel and hide the rest.
 *
 * Every tab now owns the whole screen rather than sharing it with a fixed neighbour, so this is the
 * only thing deciding what is visible. The panels are listed in one place because adding a seventh
 * should be one line here and one button in the markup, not a hunt through four toggles.
 */
const TABS = {
  today: () => dom.tabToday,
  map: () => dom.tabMap,
  farm: () => dom.tabFarm,
  journal: () => dom.tabJournal,
  bundles: () => dom.tabBundles,
  calendar: () => dom.tabCalendar,
  village: () => dom.tabVillage,
  settings: () => dom.tabSettingsPage,
};

function setTab(tab) {
  if (!TABS[tab]) tab = 'today';
  activeTab = tab;

  for (const button of document.querySelectorAll('.navtab'))
    button.classList.toggle('on', button.dataset.tab === tab);

  for (const [name, node] of Object.entries(TABS))
    node().classList.toggle('hidden', name !== tab);

  // The zoom and fit controls live in the map's own header now, so they go with it. Kept as an
  // explicit hide because the world-map button is shared with the settings screen's map section.
  for (const id of ['zoom-in', 'zoom-out', 'zoom-text', 'zoom-toggle']) {
    const node = document.getElementById(id);
    if (node) node.style.display = tab === 'map' ? '' : 'none';
  }

  // Reopen where you left off. A second screen is glanced at, put down and picked up again, and
  // being returned to the backpack every time you look away is a small tax on every glance.
  try { localStorage.setItem('ayn.tab', tab); } catch (error) { /* private mode; not worth failing over */ }

  if (tab === 'map' && state) renderMap();
  if (tab === 'settings') buildSettingsPanel();

  // The villager and bundle lists live behind their own slower endpoints. Ask straight away rather
  // than letting the tab sit empty until the next poll.
  if (tab === 'village' || tab === 'bundles' || tab === 'farm' || tab === 'calendar') refreshSlow();
}

/** The tab this device was last on, or Today. */
function rememberedTab() {
  try {
    const saved = localStorage.getItem('ayn.tab');
    return TABS[saved] ? saved : 'today';
  } catch (error) {
    return 'today';
  }
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

  if (!state) return;

  if (worldReady()) {
    renderWorldMap(cw, ch);
    return;
  }

  if (!mapImage) return;

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
    const x = originX + entity.x * scale;
    const y = originY + entity.y * scale;
    const face = settings.npcHeads ? npcFace(entity.iconKey) : null;

    if (face) {
      // big enough to recognise regardless of map zoom, and centred on the tile like the dot was
      const size = Math.max(14, dot * 2.4);
      ctx.drawImage(face, x - size / 2, y - size / 2, size, size);
      continue;
    }

    ctx.fillStyle = ENTITY_COLORS[entity.kind] || '#ffffff';
    ctx.beginPath();
    ctx.arc(x, y, dot / 2, 0, Math.PI * 2);
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
  updateMapModeButton();
  renderHud();
  renderForecast();
  renderToday();
  renderTodayStrip();
  renderQuests();
  renderSkills();
  renderInventory();
  renderChest();
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

/*
 * Tap to pick a slot, hold to use what is in it.
 *
 * Bound to both grids rather than to one, because the hotbar and the backpack rows are separate hosts
 * now and a drag that starts in one and ends in the other still has to mean "swap these two". Pointer
 * capture stays on whichever host the gesture began in; the drop target is resolved by hit-testing, so
 * crossing between them costs nothing.
 */
const HOLD_MS = 420;

let holdTimer = null;
let holdFired = false;

function cancelHold() {
  if (holdTimer !== null) clearTimeout(holdTimer);
  holdTimer = null;
  slots[dragFrom]?.root.classList.remove('holding');
}

/** What a hold does depends on what is being held: food is eaten, everything else is swung. */
function holdSlot(index) {
  const item = state && state.inventory ? state.inventory[index] : null;
  if (!item || !item.name || index >= 12) return;

  const can = (state && state.can) || {};
  const edible = item.edible && can.eat !== false;

  if (edible) send('eat', index);
  else if (can.use !== false) send('use', index);
}

function onSlotDown(host, event) {
  const slot = event.target.closest('.slot');
  if (!slot) return;

  dragFrom = Number(slot.dataset.index);
  dragActive = false;
  holdFired = false;
  pointerStart = { x: event.clientX, y: event.clientY };
  host.setPointerCapture(event.pointerId);

  const index = dragFrom;
  slot.classList.add('holding');
  holdTimer = setTimeout(() => {
    holdTimer = null;
    if (dragActive || dragFrom !== index) return;
    holdFired = true;
    cursor = index;
    holdSlot(index);
    if (state) renderInventory();
  }, HOLD_MS);
}

function onSlotMove(event) {
  if (dragFrom < 0 || !pointerStart) return;

  if (!dragActive) {
    const moved = Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y);
    if (moved < 10) return;
    dragActive = true;
    cancelHold();
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
}

function onSlotUp(event) {
  if (dragFrom < 0) return;

  const from = dragFrom;
  const wasDrag = dragActive;
  const wasHeld = holdFired;
  const target = slotFromPoint(event.clientX, event.clientY);

  cancelHold();
  slots[from]?.root.classList.remove('dragging');
  clearDragHighlight();
  dragFrom = -1;
  dragActive = false;
  holdFired = false;
  pointerStart = null;

  if (wasDrag) {
    if (target) {
      const to = Number(target.dataset.index);
      if (to !== from) send('swap', from, to);
    }
    return;
  }

  // A hold already acted; letting go of it must not also count as a tap.
  if (wasHeld) return;

  // a plain tap moves the cursor, and equips the item if it's reachable from the hotbar
  cursor = from;
  disarmTrash();
  if (from < 12) send('select', from);
  if (state) renderInventory();
}

function onSlotCancel() {
  cancelHold();
  slots[dragFrom]?.root.classList.remove('dragging');
  clearDragHighlight();
  dragFrom = -1;
  dragActive = false;
  holdFired = false;
  pointerStart = null;
}

for (const host of [dom.invGrid, dom.backpackRows]) {
  host.addEventListener('pointerdown', (event) => onSlotDown(host, event));
  host.addEventListener('pointermove', onSlotMove);
  host.addEventListener('pointerup', onSlotUp);
  host.addEventListener('pointercancel', onSlotCancel);
}

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

/**
 * Label the toggle with what it will switch *to*, and say when the world map can't show this spot.
 *
 * Plenty of places aren't on the world map at all — mine levels, most interiors, anything a mod adds
 * without world map data. Rather than show an empty canvas, the drawing falls back to the tile map,
 * so the button says so instead of looking broken.
 */
function updateMapModeButton() {
  const wantWorld = settings.mapMode === 'world';
  const unmapped = wantWorld && state && !state.world;

  dom.mapMode.textContent = unmapped ? 'Not mapped' : (wantWorld ? 'Tiles' : 'World');
  dom.mapMode.classList.toggle('on', wantWorld && !unmapped);
  dom.mapMode.title = unmapped
    ? 'This place has no spot on the world map, so the tile map is shown'
    : 'Switch between the tile map and the game\'s world map';
}

dom.mapMode.addEventListener('click', () => {
  settings.mapMode = settings.mapMode === 'world' ? 'local' : 'world';
  saveSettings();
  applySettings();
  if (state) renderMap();
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

/** A grid of checkboxes bound to boolean settings. */
function buildCheckboxes(host, entries) {
  host.textContent = '';
  for (const [key, label] of entries) {
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
    host.appendChild(row);
  }
}

function buildSettingsPanel() {
  buildSegmented(dom.settingsTheme, Object.keys(THEMES), 'theme', (v) => THEMES[v]);
  buildSegmented(dom.settingsMap, Object.keys(MAP_MODES), 'mapMode', (v) => MAP_MODES[v]);
  buildCheckboxes(dom.settingsBehaviour, BEHAVIOUR);

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

  buildPageToggles();
  buildConnectionFacts();
}

/** The Pages list: one checkbox per tab that is allowed to be hidden. */
function buildPageToggles() {
  dom.settingsPages.textContent = '';

  for (const [key, label] of HIDEABLE_PAGES) {
    const row = document.createElement('label');

    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = pageShown(key);
    box.addEventListener('change', () => {
      if (!settings.pages) settings.pages = {};
      settings.pages[key] = box.checked;
      saveSettings();
      applySettings();
    });

    const text = document.createElement('span');
    text.textContent = label;

    row.append(box, text);
    dom.settingsPages.appendChild(row);
  }
}

/**
 * Where this page is connected and what the mod on the other end is willing to do.
 *
 * Worth stating plainly, because both are invisible failure modes: a second device pointed at a
 * stale address looks identical to a mod that is not running, and a greyed-out Trash button looks
 * like a bug rather than like a line in config.json.
 */
function buildConnectionFacts() {
  const link = lastSnapshot ? Math.round((Date.now() - lastSnapshot) / 1000) : -1;
  const linkText = !lastSnapshot
    ? 'never connected'
    : link <= 2 ? 'connected' : `last heard ${link}s ago`;

  fillFacts(dom.settingsConnection, [
    ['Address', location.host || 'this device'],
    ['Status', linkText],
    ['Asking for', `${Math.round(1000 / POLL_MS)} snapshots a second`],
    ['Game', state ? (state.locationName || state.locationId || 'in a save') : 'not reporting'],
  ]);

  const can = (state && state.can) || {};
  const yesNo = (value) => (value === false ? 'no' : 'yes');

  fillFacts(dom.settingsAllows, [
    ['Rearranging the bag', yesNo(can.edit)],
    ['Dropping items', yesNo(can.drop)],
    ['Trashing items', yesNo(can.trash)],
    ['Eating', yesNo(can.eat)],
    ['Holding a slot to use it', yesNo(can.use)],
  ]);
}

function fillFacts(host, rows) {
  host.textContent = '';
  for (const [label, value] of rows) {
    const row = document.createElement('div');

    const name = document.createElement('span');
    name.textContent = label;

    const text = document.createElement('b');
    text.textContent = value;

    row.append(name, text);
    host.appendChild(row);
  }
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

// The gear is a shortcut to the tab now rather than a way of covering the screen with a modal.
dom.gear.addEventListener('click', () => setTab('settings'));

for (const button of document.querySelectorAll('.navtab'))
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

dom.settingsResetPage.addEventListener('click', () => {
  settings = Object.assign({}, SETTING_DEFAULTS, { pages: {} });
  saveSettings();
  applySettings();
  buildSettingsPanel();
});

// capture, so a touch anywhere wakes the chrome even when the target stops the event
document.addEventListener('pointerdown', markActive, true);
document.addEventListener('pointermove', markActive, true);
document.addEventListener('keydown', markActive, true);

loadSettings();
applySettings();
setZoom(0);
// Opens where this device was left, rather than always on the map. See rememberedTab().
setTab(rememberedTab());
renderLink();
poll();
pollSlow();
