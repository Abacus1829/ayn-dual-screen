/*
 * The second screen, in one file.
 *
 * Polls /state fast and /map slowly, because the map is the expensive half on the mod's side and
 * terrain does not move. Everything drawn here comes from a snapshot the game thread already finished,
 * so there is nothing to synchronise and nothing that can half-update.
 */

'use strict';

// the minimap is glanced at while moving, so the page asks more often than it used to
const MAP_MS = 1000;

const WIKI_HOME = 'https://minecraft.wiki/';
const WIKI_SEARCH = 'https://minecraft.wiki/?search=';

const ENTITY_COLORS = {
  hostile: '#e05c5c',
  passive: '#8fd96f',
  player: '#6ec1ff',
  item: '#e0c76a'
};

/*
 * The player marker, as pixel art.
 *
 * Drawn cell by cell rather than as a canvas path: a path antialiases into a soft grey fringe, which
 * looks wrong on a map made of one-block pixels.  X = fill, o = outline, . = nothing.
 */
const ARROW = [
  'ooo.....',
  'oXXoo...',
  'oXXXXoo.',
  'oXXXXXXo',
  'oXXXXoo.',
  'oXXoo...',
  'ooo.....'
];

const ARROW_FILL = '#ffffff';
const ARROW_EDGE = '#101a12';

/*
 * Waypoint markers, drawn the same way as the arrow.
 *
 * Authored here as pixel grids rather than shipped as images: it keeps the mod free of any artwork it
 * would have to have the right to distribute, and it means a marker is legible at any size because it
 * is rasterised at whatever scale it is drawn.
 */
const PIN_ART = {
  banner: {
    palette: { P: '#7a3fa0', p: '#5d2f7c', S: '#4a3a1e', O: '#1a1208' },
    grid: [
      'OOOOOOO..',
      'OPPPPPO..',
      'OPpPPpO..',
      'OPPPPPO..',
      'OPpPPpO..',
      'OPPPPPO..',
      'OO.O.OO..',
      '...OSO...',
      '...OSO...'
    ]
  },
  bed: {
    palette: { R: '#b03636', r: '#8a2626', W: '#e8e4dc', D: '#5a3a20', O: '#1a1208' },
    grid: [
      '.........',
      '.OOOOOOO.',
      'OWWRRRRRO',
      'OWWRRrRRO',
      'OWWRRRRRO',
      'ODDDDDDDO',
      '.O.....O.',
      '.O.....O.',
      '.........'
    ]
  },
  campfire: {
    palette: { F: '#ffb03a', f: '#e0631a', L: '#7a5630', l: '#5a3d20', O: '#1a1208' },
    grid: [
      '....O....',
      '...OFO...',
      '..OFFFO..',
      '.OFfFfFO.',
      '..OFFFO..',
      'OLLLLLLLO',
      'OllLLLllO',
      'OLLLLLLLO',
      '.OOOOOOO.'
    ]
  },
  diamond: {
    palette: { C: '#6f9bf0', c: '#3a63c8', O: '#ffffff' },
    grid: [
      '....O....',
      '...OCO...',
      '..OCcCO..',
      '.OCcccCO.',
      'OCcccccCO',
      '.OCcccCO.',
      '..OCcCO..',
      '...OCO...',
      '....O....'
    ]
  },
  emerald: {
    palette: { C: '#7fe066', c: '#3f9c33', O: '#ffffff' },
    grid: [
      '....O....',
      '...OCO...',
      '..OCcCO..',
      '.OCcccCO.',
      'OCcccccCO',
      '.OCcccCO.',
      '..OCcCO..',
      '...OCO...',
      '....O....'
    ]
  },
  skull: {
    palette: { B: '#e8e4dc', b: '#b8b2a6', O: '#1a1208' },
    grid: [
      '.OOOOOOO.',
      'OBBBBBBBO',
      'OBOBBBOBO',
      'OBOBBBOBO',
      'OBBBOBBBO',
      'OBBBBBBBO',
      '.OBObOBO.',
      '..OOOOO..',
      '.........'
    ]
  }
};

const PIN_TYPES = ['diamond', 'emerald', 'banner', 'bed', 'campfire', 'skull'];

/* Settings live on the handheld, not in the mod: they are about this screen, not about the game. */
const DEFAULTS = {
  showHostiles: true,
  showPassives: true,
  showPlayers: true,
  showItems: false,
  showCoords: true,
  showIcons: true,
  zoom: 3,
  compact: false,

  showHeads: true,
  pinLabels: true,
  tapToPin: true,
  deathPin: true,
  lastPinType: 'diamond',

  theme: 'oak',
  accent: '#7bc96f',
  textScale: 100,
  columns: 9,
  corners: 5,
  mapStyle: 'atlas'
};

let settings = Object.assign({}, DEFAULTS);
let state = null;
let mapData = null;
let lastSnapshot = 0;
let tab = 'gear-tab';

/** The revision we already hold, so the next request can ask only for something newer. */
let stateRev = 0;

/** Where the player was at the previous snapshot, to interpolate away from. */
let previous = null;

/** Consecutive failed requests, used to back off instead of hammering a dead connection. */
let failures = 0;

/** The atlas: one entry per explored tile, keyed "tileX,tileZ". */
const tiles = new Map();
let tileSize = 64;
let atlasDimension = '';

/** Mob head images, one per entity type. */
const heads = new Map();

/** Waypoints for the current world and dimension. */
let pins = [];
let editing = null;
let loadedPinKey = '';

/** The auto-placed death marker has a fixed id, so a new death replaces it rather than piling up. */
const DEATH_PIN = 'death-marker';
let wasDead = false;

/** Where the big map is looking, when it has been dragged off the player. */
let panned = false;
let panX = 0;
let panZ = 0;

/** The last projection the big map drew with, so taps can be turned back into world coordinates. */
let view = null;

/** Recipe search state. */
let craftQuery = '';
let craftOnly = true;
let craftTimer = null;

/** Signature of the slots as last drawn, so an unchanged inventory isn't rebuilt. */
let lastSlots = '';
let lastBench = '';

const dom = {};
let ctx = null;
let miniCtx = null;

/* ---------- boot ---------- */

function start() {
  for (const id of ['world', 'where', 'time', 'day', 'link', 'gear', 'tabs', 'vitals', 'hotbar',
    'inventory', 'worn', 'canvas', 'mapinfo', 'effects', 'position', 'chat', 'send', 'chatnote',
    'wiki', 'settings', 'settingsBody', 'closeSettings', 'toast', 'mini', 'zoomIn', 'zoomOut',
    'pinRail', 'centreButton', 'addPin', 'pinSheet', 'pinTitle', 'pinName', 'pinTypes', 'pinWhere',
    'savePin', 'deletePin', 'closePin', 'craftSearch', 'craftFilter', 'recipes', 'craftNote',
    'bench', 'benchTitle', 'benchGrid', 'benchResult', 'takeResult',
    'invSearch', 'freeSlots', 'itemSheet', 'itemTitle', 'itemArt', 'itemFacts', 'closeItem']) {
    dom[id] = document.getElementById(id);
  }

  ctx = dom.canvas.getContext('2d');
  miniCtx = dom.mini.getContext('2d');

  loadSettings();
  applyLook();
  buildTabs();
  buildSettings();

  dom.gear.addEventListener('click', () => { dom.settings.hidden = false; });
  dom.closeSettings.addEventListener('click', () => { dom.settings.hidden = true; });
  dom.settings.addEventListener('click', (event) => {
    if (event.target === dom.settings) dom.settings.hidden = true;
  });

  dom.zoomIn.addEventListener('click', () => setZoom(settings.zoom + 1));
  dom.zoomOut.addEventListener('click', () => setZoom(settings.zoom - 1));

  dom.centreButton.addEventListener('click', () => { panned = false; });
  dom.addPin.addEventListener('click', () => {
    const at = here();
    if (!at) return;
    editing = {
      name: '',
      type: settings.lastPinType || 'diamond',
      x: panned ? panX : at.x,
      z: panned ? panZ : at.z,
      y: state ? Math.round(state.y) : 0
    };
    openPinSheet();
  });

  dom.savePin.addEventListener('click', () => {
    settings.lastPinType = editing ? editing.type : settings.lastPinType;
    saveSettings();
    savePin();
  });
  dom.deletePin.addEventListener('click', deletePin);
  dom.closePin.addEventListener('click', () => { dom.pinSheet.hidden = true; editing = null; });
  dom.pinSheet.addEventListener('click', (event) => {
    if (event.target === dom.pinSheet) { dom.pinSheet.hidden = true; editing = null; }
  });

  dom.invSearch.addEventListener('input', applySearch);
  dom.closeItem.addEventListener('click', () => { dom.itemSheet.hidden = true; });
  dom.itemSheet.addEventListener('click', (event) => {
    if (event.target === dom.itemSheet) dom.itemSheet.hidden = true;
  });

  dom.takeResult.addEventListener('click', () => {
    act({ do: 'take' });
    toast('Taken');
  });

  dom.craftSearch.addEventListener('input', () => {
    craftQuery = dom.craftSearch.value;
    clearTimeout(craftTimer);
    craftTimer = setTimeout(loadRecipes, 250);
  });

  dom.craftFilter.classList.toggle('on', craftOnly);
  dom.craftFilter.addEventListener('click', () => {
    craftOnly = !craftOnly;
    dom.craftFilter.classList.toggle('on', craftOnly);
    loadRecipes();
  });

  wirePanning();

  dom.send.addEventListener('click', sendChat);
  dom.chat.addEventListener('keydown', (event) => { if (event.key === 'Enter') sendChat(); });
  dom.wiki.addEventListener('click', () => openWiki(null));

  pumpState();
  pollMap();
  setInterval(pollMap, MAP_MS);
  setInterval(renderLink, 250);
  requestAnimationFrame(frame);
  // the frame loop picks up the new canvas size on its own, so resize only has to redraw the chrome
  window.addEventListener('resize', renderChrome);
}

/* ---------- settings ---------- */

function loadSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem('ayn-mc') || '{}');
    settings = Object.assign({}, DEFAULTS, saved);
  } catch (error) {
    settings = Object.assign({}, DEFAULTS);
  }
}

function saveSettings() {
  try {
    localStorage.setItem('ayn-mc', JSON.stringify(settings));
  } catch (error) {
    // private mode, or a full quota: not worth breaking the page over
  }
}

/**
 * Whole looks, in one tap.
 *
 * Each is a complete palette rather than just an accent, because changing one colour against a fixed
 * background is how you end up with something that clashes. Named after the blocks they came from.
 */
const THEMES = [
  /*
   * The container GUI as the game draws it: mid grey panel, darker grey slots, dark text, and the
   * bevels running light-then-dark rather than the other way round because the panel is raised.
   */
  {
    id: 'vanilla', label: 'Vanilla', accent: '#3f8f3f',
    bg: '#3a3a3a', panel: '#c6c6c6', panelHi: '#d4d4d4', edge: '#8b8b8b',
    slot: '#8b8b8b', slotEmpty: '#8b8b8b',
    slotShadow: '#373737', slotLight: '#ffffff',
    panelLight: '#ffffff', panelShadow: '#555555',
    text: '#3f3f3f', textDim: '#5a5a5a', textFaint: '#6f6f6f'
  },
  { id: 'oak', label: 'Oak', accent: '#7bc96f', bg: '#0e1410', panel: '#18211a', panelHi: '#1f2b21', edge: '#2c3a2f' },
  { id: 'deepslate', label: 'Deepslate', accent: '#8ab4d8', bg: '#0d0f12', panel: '#171a1f', panelHi: '#1e222a', edge: '#2b313b' },
  { id: 'nether', label: 'Nether', accent: '#e0654a', bg: '#150c0c', panel: '#221312', panelHi: '#2c1917', edge: '#3d2320' },
  { id: 'amethyst', label: 'Amethyst', accent: '#b98cf0', bg: '#110d18', panel: '#1c1526', panelHi: '#251c32', edge: '#332745' },
  { id: 'sandstone', label: 'Sandstone', accent: '#e0c073', bg: '#15120b', panel: '#221d13', panelHi: '#2c2519', edge: '#3d3423' },
  { id: 'prismarine', label: 'Prismarine', accent: '#5fd6c0', bg: '#0a1414', panel: '#132020', panelHi: '#1a2b2a', edge: '#26403d' }
];

const ACCENTS = [
  '#7bc96f', '#5fd6c0', '#6ec1ff', '#8ab4d8', '#b98cf0', '#f08cc0',
  '#e0654a', '#e0955a', '#e0c073', '#d8e05a', '#ffffff', '#9aab97'
];

function themeById(id) {
  return THEMES.find((theme) => theme.id === id) || THEMES[1];
}

/** Lighten (positive) or darken (negative) a hex colour, for deriving bevels from a panel. */
function shift(hex, amount) {
  const value = String(hex || '#000000').replace('#', '');
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value;
  const number = parseInt(full, 16);

  const channel = (offset) => {
    const base = (number >> offset) & 0xff;
    const moved = amount >= 0 ? base + (255 - base) * amount : base * (1 + amount);
    return Math.max(0, Math.min(255, Math.round(moved)));
  };

  return `rgb(${channel(16)}, ${channel(8)}, ${channel(0)})`;
}

/**
 * Push the chosen look into the CSS variables the stylesheet already reads.
 *
 * Done as variables rather than swapped stylesheets so a change is instant and nothing has to be
 * re-rendered — the map and the slots keep their state and simply repaint in the new colours.
 */
function applyLook() {
  const root = document.documentElement.style;
  const theme = themeById(settings.theme);

  root.setProperty('--bg', theme.bg);
  root.setProperty('--panel', theme.panel);
  root.setProperty('--panel-hi', theme.panelHi);
  root.setProperty('--edge', theme.edge);
  root.setProperty('--slot-fill', theme.slot || theme.panelHi);
  root.setProperty('--slot-fill-empty', theme.slotEmpty || theme.bg);
  root.setProperty('--slot-edge', theme.edge);

  // Bevels default to a darkened and lightened version of the panel, so every theme gets the container
  // look without having to spell out four more colours; a theme can still override them outright.
  root.setProperty('--slot-shadow', theme.slotShadow || shift(theme.slot || theme.panelHi, -0.45));
  root.setProperty('--slot-light', theme.slotLight || shift(theme.slot || theme.panelHi, 0.35));
  root.setProperty('--panel-light', theme.panelLight || shift(theme.panel, 0.30));
  root.setProperty('--panel-shadow', theme.panelShadow || shift(theme.panel, -0.45));

  root.setProperty('--text', theme.text || '#e8f0e6');
  root.setProperty('--text-dim', theme.textDim || '#9aab97');
  root.setProperty('--text-faint', theme.textFaint || '#6b7a69');

  const accent = settings.accent || theme.accent;
  root.setProperty('--accent', accent);
  root.setProperty('--slot-selected', accent);

  root.setProperty('--text-size', `${Math.round(14 * settings.textScale / 100)}px`);
  root.setProperty('--inv-cols', String(settings.columns));
  root.setProperty('--corner', `${settings.corners}px`);

  // the map is either a parchment atlas page or a plain dark panel, to taste
  const atlas = settings.mapStyle === 'atlas';
  root.setProperty('--parchment', atlas ? '#c7b797' : theme.bg);
  root.setProperty('--atlas-edge', atlas ? '#6b4f2a' : theme.edge);
  root.setProperty('--atlas-band', atlas ? '#9c7b48' : theme.panel);
  root.setProperty('--atlas-pad', atlas ? '7px' : '3px');
}

function buildSettings() {
  dom.settingsBody.innerHTML = '';

  heading('Look');
  segmented('Theme', THEMES.map((t) => [t.id, t.label]), settings.theme, (value) => {
    settings.theme = value;
    // adopt the theme's own accent, otherwise the last one carries over and clashes
    settings.accent = themeById(value).accent;
    commit(true);
  });
  swatches();
  slider('Text size', 'textScale', 85, 140, 5, (v) => `${v}%`);
  segmented('Slots per row', [[6, '6'], [9, '9'], [12, '12']], settings.columns, (value) => {
    settings.columns = Number(value);
    commit(true);
  });
  slider('Corner rounding', 'corners', 0, 14, 1, (v) => `${v}px`);

  heading('Map');
  segmented('Style', [['atlas', 'Atlas'], ['plain', 'Plain']], settings.mapStyle, (value) => {
    settings.mapStyle = value;
    commit(true);
  });
  slider('Zoom', 'zoom', 1, 8, 1, (v) => `${v}x`);
  check('showHostiles', 'Show hostile mobs');
  check('showPassives', 'Show animals');
  check('showPlayers', 'Show players');
  check('showItems', 'Show dropped items');
  check('showHeads', 'Mobs as their own heads');
  check('showCoords', 'Show coordinates');

  heading('Pins');
  check('pinLabels', 'Show pin names on the map');
  check('tapToPin', 'Tap the map to drop a pin');
  check('deathPin', 'Mark where you died');

  heading('Inventory');
  check('showIcons', 'Item textures');
  check('compact', 'Compact item labels');

  const reset = document.createElement('button');
  reset.className = 'wide';
  reset.textContent = 'Reset to defaults';
  reset.style.marginTop = '12px';
  reset.addEventListener('click', () => {
    settings = Object.assign({}, DEFAULTS);
    saveSettings();
    applyLook();
    buildSettings();
    renderChrome();
  });
  dom.settingsBody.appendChild(reset);
}

/** Save, repaint, and rebuild the sheet when a control's own appearance depends on the change. */
function commit(rebuild) {
  saveSettings();
  applyLook();
  renderChrome();
  if (rebuild) buildSettings();
}

function heading(text) {
  const node = document.createElement('h2');
  node.textContent = text;
  dom.settingsBody.appendChild(node);
}

function row(label, extraClass) {
  const node = document.createElement('div');
  node.className = extraClass ? `setting ${extraClass}` : 'setting';
  const text = document.createElement('label');
  text.textContent = label;
  node.appendChild(text);
  dom.settingsBody.appendChild(node);
  return node;
}

function check(key, label) {
  const node = row(label);
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.checked = !!settings[key];
  input.addEventListener('change', () => {
    settings[key] = input.checked;
    commit(false);
  });
  node.appendChild(input);
}

function slider(label, key, min, max, step, format) {
  const node = row(label);

  const value = document.createElement('span');
  value.style.color = 'var(--text-faint)';
  value.style.fontSize = '12px';
  value.textContent = format(settings[key]);

  const input = document.createElement('input');
  input.type = 'range';
  input.min = String(min);
  input.max = String(max);
  input.step = String(step);
  input.value = String(settings[key]);
  input.addEventListener('input', () => {
    settings[key] = Number(input.value);
    value.textContent = format(settings[key]);
    commit(false);
  });

  node.appendChild(value);
  node.appendChild(input);
}

function segmented(label, options, current, onPick) {
  const node = row(label);
  const group = document.createElement('div');
  group.className = 'segments';

  for (const [value, text] of options) {
    const button = document.createElement('button');
    button.textContent = text;
    if (String(value) === String(current)) button.classList.add('on');
    button.addEventListener('click', () => onPick(value));
    group.appendChild(button);
  }

  node.appendChild(group);
}

function swatches() {
  const node = row('Accent', 'stack');
  const strip = document.createElement('div');
  strip.className = 'swatches';

  for (const colour of ACCENTS) {
    const button = document.createElement('button');
    button.className = settings.accent === colour ? 'swatch on' : 'swatch';
    button.style.background = colour;
    button.addEventListener('click', () => {
      settings.accent = colour;
      commit(true);
    });
    strip.appendChild(button);
  }

  node.appendChild(strip);
}

/* ---------- tabs ---------- */

function buildTabs() {
  for (const button of dom.tabs.querySelectorAll('.tab')) {
    button.addEventListener('click', () => {
      tab = button.dataset.tab;
      for (const other of dom.tabs.querySelectorAll('.tab')) {
        other.classList.toggle('active', other === button);
      }
      for (const panel of document.querySelectorAll('.panel')) {
        panel.classList.toggle('active', panel.id === tab);
      }
      // the map draws itself on the frame loop; the recipe list is fetched when you open it
      if (tab === 'craft-tab') loadRecipes();
    });
  }
}

/* ---------- polling ---------- */

/**
 * Ask for the next snapshot, not for the current one.
 *
 * The mod holds the request open until it has something newer than the revision we already have, so a
 * change reaches the screen as fast as the network allows instead of waiting out a poll interval. When
 * nothing happens for ten seconds it answers anyway, which doubles as the keep-alive.
 *
 * Re-issued the moment each response lands, so there is only ever one in flight.
 */
function pumpState() {
  const query = stateRev > 0 ? `/state?since=${stateRev}` : '/state';

  fetch(query, { cache: 'no-store' })
    .then((response) => response.json())
    .then((json) => {
      if (json && typeof json.rev === 'number') stateRev = json.rev;
      const next = json && json.ready ? json : null;

      // keep the previous position to interpolate away from, so movement reads as motion
      if (next && state) {
        previous = { x: state.x, z: state.z, yaw: state.yaw, at: lastSnapshot };
      } else {
        previous = null;
      }

      state = next;
      lastSnapshot = performance.now();
      failures = 0;

      // pins belong to a world and a dimension, so reload them whenever either changes
      const key = pinKey();
      if (key !== loadedPinKey) {
        loadedPinKey = key;
        loadPins();
      }

      renderChrome();
    })
    .catch(() => {
      failures += 1;
    })
    .finally(() => {
      // back off only when it is actually failing; a good connection loops with no gap at all
      setTimeout(pumpState, failures > 0 ? Math.min(2000, 250 * failures) : 0);
    });
}

/**
 * Keep the tile index up to date, and fetch any tile whose revision has moved.
 *
 * The index is small — a few bytes per tile — so it comes down whole and the page decides what it
 * actually needs. Images are only refetched when their revision changes, which after the first pass
 * over an area is almost never.
 */
function pollMap() {
  fetch('/map', { cache: 'no-store' })
    .then((response) => response.json())
    .then((json) => {
      if (!json || !json.ready) return;

      // a new dimension is a different atlas; keeping the old images would paint the wrong world
      if (json.dimension !== atlasDimension) {
        atlasDimension = json.dimension;
        tiles.clear();
      }

      mapData = json;
      tileSize = json.tile || 64;

      for (const entry of json.tiles || []) {
        const id = `${entry.x},${entry.z}`;
        const have = tiles.get(id);
        if (have && have.rev === entry.rev) continue;

        const image = new Image();
        image.onload = () => { tiles.set(id, { rev: entry.rev, image: image, x: entry.x, z: entry.z }); };
        image.src = `/tile?x=${entry.x}&z=${entry.z}&rev=${entry.rev}`;
      }
    })
    .catch(() => {});
}

function act(params) {
  const query = new URLSearchParams(params).toString();
  fetch('/action?' + query, { cache: 'no-store' }).catch(() => {});
}

/* ---------- rendering ---------- */

/**
 * The parts made of DOM, redrawn once per snapshot.
 *
 * Kept apart from the map because these are comparatively expensive — rebuilding forty slots of HTML
 * twenty times a second would cost far more than it buys, and none of it moves smoothly anyway.
 */
function renderChrome() {
  renderHeader();
  renderVitals();
  renderSlots();
  renderEffects();
  renderPosition();
  renderChatState();
  renderBench();
  checkDeath();
}

/**
 * The map, redrawn every frame.
 *
 * Snapshots arrive at the game's tick rate; a display runs faster than that. Drawing on the frame clock
 * and interpolating between the last two positions is what turns twenty discrete jumps a second into
 * something that reads as movement.
 */
function frame() {
  if (tab === 'map-tab') renderMap();
  else if (tab === 'gear-tab') renderMini();
  requestAnimationFrame(frame);
}

/**
 * Where to draw the player right now.
 *
 * Interpolates towards the newest snapshot over roughly one tick. Clamped at 1 so a late packet slides
 * into place rather than overshooting past it, which would read as a stutter in the other direction.
 */
function here() {
  if (!state) return null;
  if (!previous) return { x: state.x, z: state.z, yaw: state.yaw };

  const span = Math.max(1, lastSnapshot - previous.at);
  const t = Math.max(0, Math.min(1, (performance.now() - lastSnapshot) / span));

  return {
    x: previous.x + (state.x - previous.x) * t,
    z: previous.z + (state.z - previous.z) * t,
    yaw: previous.yaw + shortestTurn(previous.yaw, state.yaw) * t
  };
}

/** Turning past 180 must go the short way round, or the arrow spins the long way once per lap. */
function shortestTurn(from, to) {
  let delta = ((Number(to) || 0) - (Number(from) || 0)) % 360;
  if (delta > 180) delta -= 360;
  if (delta < -180) delta += 360;
  return delta;
}

function renderHeader() {
  if (!state) {
    dom.world.textContent = 'Not connected';
    dom.where.textContent = 'Waiting for the game…';
    dom.time.textContent = '';
    dom.day.textContent = '';
    return;
  }

  dom.world.textContent = state.worldName || 'Minecraft';
  dom.where.textContent = `${titleCase(state.dimension)} · ${titleCase(state.biome)}${weatherText()}`;
  dom.time.textContent = clockText(state.timeOfDay);
  dom.day.textContent = `Day ${state.day}`;
}

function weatherText() {
  if (!state) return '';
  if (state.thundering) return ' · storm';
  if (state.raining) return ' · rain';
  return '';
}

/** Minecraft ticks start the day at 06:00, which is why this is offset rather than a plain division. */
function clockText(ticks) {
  const total = ((Number(ticks) || 0) + 6000) % 24000;
  const hours = Math.floor(total / 1000);
  const minutes = Math.floor((total % 1000) / 1000 * 60);
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
}

function renderVitals() {
  if (!state) { dom.vitals.innerHTML = ''; return; }

  const items = [
    ['Health', `${round(state.health)} / ${round(state.healthMax)}`, state.health / state.healthMax, 'var(--health)'],
    ['Food', `${state.food} / 20`, state.food / 20, 'var(--food)'],
    ['Armour', String(state.armor), state.armor / 20, 'var(--armor)'],
    ['XP', `Level ${state.xpLevel}`, state.xpProgress, 'var(--xp)']
  ];

  // air only matters underwater, and a permanently full bar is just noise
  if (state.air < state.airMax) {
    items.push(['Air', `${Math.ceil(state.air / 20)}s`, state.air / state.airMax, 'var(--air)']);
  }

  // A number you have to read is no use across the room. Anything into the last quarter pulses, so low
  // health or a food bar about to stop you sprinting catches the eye without being stared at.
  dom.vitals.innerHTML = items.map(([label, value, fraction, colour]) => {
    const low = fraction <= 0.25 && label !== 'XP';
    return `
    <div class="vital${low ? ' low' : ''}">
      <div class="label">${escapeHtml(label)}</div>
      <div class="value">${escapeHtml(value)}</div>
      <div class="meter"><span style="width:${clampPercent(fraction)}%;background:${colour}"></span></div>
    </div>`;
  }).join('');
}

function renderSlots() {
  if (!state) {
    dom.hotbar.innerHTML = '';
    dom.inventory.innerHTML = '';
    dom.worn.innerHTML = '';
    lastSlots = '';
    return;
  }

  /*
   * Only rebuild when something actually changed.
   *
   * Snapshots arrive many times a second and the inventory almost never differs between them. Rebuilding
   * the HTML anyway threw away forty img elements and made forty fresh requests each time, which showed
   * up as the icons visibly flickering. Comparing a cheap signature first makes the common case free.
   */
  const signature = slotSignature();
  if (signature === lastSlots) return;
  lastSlots = signature;

  const inventory = state.inventory || [];

  // slots 0-8 are the hotbar; the rest is the backpack, in the order the game stores it
  dom.hotbar.innerHTML = inventory.slice(0, 9)
    .map((item, index) => slotHtml(item, index === state.selectedSlot)).join('');
  dom.inventory.innerHTML = inventory.slice(9).map((item) => slotHtml(item, false)).join('');

  const worn = (state.armorSlots || []).slice();
  worn.push(state.offhand || { empty: true });
  dom.worn.innerHTML = worn.map((item) => slotHtml(item, false)).join('');

  for (const container of [dom.hotbar, dom.inventory, dom.worn]) {
    wireIcons(container);
  }

  // tapping any slot explains what is in it; the hotbar additionally changes what you are holding
  wireDetails(dom.hotbar, inventory.slice(0, 9));
  wireDetails(dom.inventory, inventory.slice(9));
  wireDetails(dom.worn, worn);

  if (state.can && state.can.hotbar) {
    dom.hotbar.querySelectorAll('.slot').forEach((node, index) => {
      node.style.cursor = 'pointer';
      node.addEventListener('click', () => act({ do: 'hotbar', slot: index }));
    });
  }

  renderFree();
  applySearch();
}

function wireDetails(container, items) {
  container.querySelectorAll('.slot').forEach((node, index) => {
    const item = items[index];
    if (!item || item.empty) return;
    node.style.cursor = 'pointer';
    node.addEventListener('click', () => showItem(item));
  });
}

/**
 * Fall back to the item's name if its texture doesn't arrive.
 *
 * The mod resolves art a few items per tick, so a slot can legitimately be ahead of its icon for a
 * moment. A broken-image glyph in a 9-wide grid reads as a bug, whereas the name reads as information.
 */
function wireIcons(container) {
  if (!container) return;
  for (const image of container.querySelectorAll('img.art')) {
    image.addEventListener('error', () => {
      const text = document.createElement('span');
      text.className = 'abbr';
      text.textContent = image.dataset.label || '';
      image.replaceWith(text);
    }, { once: true });
  }
}

/**
 * Show what an item actually is.
 *
 * Slots carry a `title`, which is useless on a handheld — there is no hover on a touchscreen, so the
 * name, id and durability were all information the page had and never showed. Tapping a slot opens it
 * instead, which is the only gesture available.
 */
function showItem(item) {
  if (!item || item.empty) return;

  dom.itemTitle.textContent = item.name;
  dom.itemArt.className = 'slot';
  dom.itemArt.innerHTML = item.icon
    ? `<img class="art" src="/icon?id=${encodeURIComponent(item.id)}" alt="">`
    : `<span class="abbr">${escapeHtml(item.name)}</span>`;

  const facts = [`<div><span class="k">Count</span> ${item.count}</div>`];
  if (item.durabilityMax) {
    const left = Math.round(item.durability / item.durabilityMax * 100);
    facts.push(`<div><span class="k">Durability</span> ${item.durability} / ${item.durabilityMax} (${left}%)</div>`);
  }
  if (item.enchanted) facts.push('<div><span class="k">Enchanted</span> yes</div>');
  facts.push(`<div><span class="k">Id</span> ${escapeHtml(item.id)}</div>`);

  dom.itemFacts.innerHTML = facts.join('');
  dom.itemSheet.hidden = false;
}

/**
 * Dim everything that doesn't match the search.
 *
 * Applied as a class pass over the existing slots rather than by rebuilding them, so searching costs
 * nothing and doesn't re-request a single icon.
 */
function applySearch() {
  const needle = (dom.invSearch.value || '').trim().toLowerCase();
  const all = [...dom.hotbar.children, ...dom.inventory.children, ...dom.worn.children];

  all.forEach((node) => {
    node.classList.remove('dimmed', 'hit');
    if (!needle) return;

    const name = (node.getAttribute('title') || '').toLowerCase();
    if (!name) { node.classList.add('dimmed'); return; }
    node.classList.add(name.includes(needle) ? 'hit' : 'dimmed');
  });
}

/** Empty slots in the backpack, so you know how much room is left without counting. */
function renderFree() {
  if (!state) { dom.freeSlots.textContent = ''; return; }

  const inventory = state.inventory || [];
  const free = inventory.filter((item) => !item || item.empty).length;
  dom.freeSlots.textContent = free === 0
    ? 'full'
    : `${free} free of ${inventory.length}`;
}

/** Everything about the slots that would change how they draw, as one short string. */
function slotSignature() {
  const parts = [state.selectedSlot, settings.showIcons ? 1 : 0, settings.compact ? 1 : 0];

  const add = (item) => {
    if (!item || item.empty) { parts.push('.'); return; }
    parts.push(`${item.id}|${item.count}|${item.durability || 0}|${item.icon ? 1 : 0}`);
  };

  (state.inventory || []).forEach(add);
  (state.armorSlots || []).forEach(add);
  add(state.offhand);

  return parts.join(',');
}

function slotHtml(item, selected) {
  if (!item || item.empty) {
    return `<div class="slot empty${selected ? ' selected' : ''}"></div>`;
  }

  // The real texture when the mod has resolved one, the name when it hasn't. The fallback is wired up
  // in JS afterwards rather than with an inline onerror, whose quoting would fight the attribute.
  const label = settings.compact ? shorten(item.name) : item.name;
  const body = item.icon && settings.showIcons
    ? `<img class="art" src="/icon?id=${encodeURIComponent(item.id)}"
         alt="${escapeHtml(item.name)}" data-label="${escapeHtml(label)}">`
    : `<span class="abbr">${escapeHtml(label)}</span>`;

  const count = item.count > 1 ? `<span class="count">${item.count}</span>` : '';

  let wear = '';
  if (item.durabilityMax) {
    const left = Math.max(0, Math.min(1, item.durability / item.durabilityMax));
    const colour = left > 0.5 ? '#7bc96f' : left > 0.2 ? '#d9a03a' : '#e04a4a';
    wear = `<span class="wear"><span style="width:${Math.round(left * 100)}%;background:${colour}"></span></span>`;
  }

  const classes = ['slot'];
  if (selected) classes.push('selected');
  if (item.enchanted) classes.push('enchanted');

  return `<div class="${classes.join(' ')}" title="${escapeHtml(item.name)}">
    ${body}${count}${wear}
  </div>`;
}

/** "Diamond Pickaxe" -> "Dia Pic": enough to recognise at a glance in a 9-wide grid. */
function shorten(name) {
  return String(name || '')
    .split(/\s+/)
    .map((word) => word.slice(0, 3))
    .join(' ');
}

function renderEffects() {
  if (!state) { dom.effects.innerHTML = ''; return; }

  const effects = state.effects || [];
  if (effects.length === 0) {
    dom.effects.innerHTML = '<p class="note">Nothing active.</p>';
    return;
  }

  dom.effects.innerHTML = effects.map((effect) => {
    const level = effect.amplifier > 0 ? ` ${roman(effect.amplifier + 1)}` : '';
    const left = effect.seconds < 0 ? '∞' : formatSeconds(effect.seconds);
    const icon = effect.icon
      ? `<img class="effecticon" src="/effect?id=${encodeURIComponent(effect.id)}" alt="">`
      : '';
    return `<div class="effect ${effect.beneficial ? 'good' : 'bad'}">
      ${icon}<span class="name">${escapeHtml(effect.name)}${level}</span>
      <span class="left">${left}</span>
    </div>`;
  }).join('');
}

function roman(value) {
  return ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'][value] || String(value);
}

function formatSeconds(seconds) {
  const total = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(total / 60);
  return `${minutes}:${String(total % 60).padStart(2, '0')}`;
}

function renderPosition() {
  if (!state) { dom.position.innerHTML = ''; return; }

  dom.position.innerHTML = `
    <div><span class="k">Position</span> ${round(state.x)}, ${round(state.y)}, ${round(state.z)}</div>
    <div><span class="k">Facing</span> ${facing(state.yaw)}</div>
    <div><span class="k">Biome</span> ${escapeHtml(titleCase(state.biome))}</div>
    <div><span class="k">Dimension</span> ${escapeHtml(titleCase(state.dimension))}</div>`;
}

/** Minecraft's yaw is 0 at south and grows clockwise, so the compass order starts there. */
function facing(yaw) {
  const points = ['South', 'South-west', 'West', 'North-west', 'North', 'North-east', 'East', 'South-east'];
  const index = Math.round((((Number(yaw) || 0) % 360) + 360) % 360 / 45) % 8;
  return points[index];
}

function renderChatState() {
  const allowed = !!(state && state.can && state.can.chat);
  dom.chat.disabled = !allowed;
  dom.send.disabled = !allowed;
  dom.chatnote.textContent = allowed
    ? 'Sent as you, exactly as typed. A leading / runs it as a command.'
    : 'Turned off. Set allowChat = true in the mod config to enable it.';
}

function sendChat() {
  const text = dom.chat.value.trim();
  if (!text) return;
  act({ do: 'chat', text: text });
  dom.chat.value = '';
  toast('Sent');
}

/* ---------- map ---------- */

/**
 * Draw the atlas page onto a canvas.
 *
 * One routine for both the big map and the glanceable one, because they differ only in size and zoom —
 * and two copies of this would drift apart the first time either was touched.
 *
 * Unexplored ground is left as bare parchment rather than filled in: the mod sends transparent pixels
 * for chunks it hasn't got, so the edge of what you have actually walked is visible on the page the way
 * it would be on a real map.
 */
function paintAtlas(canvas, context, blocksPerPixel, interactive) {
  const ratio = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  const cw = Math.max(1, Math.round(rect.width * ratio));
  const ch = Math.max(1, Math.round(rect.height * ratio));

  if (canvas.width !== cw || canvas.height !== ch) {
    canvas.width = cw;
    canvas.height = ch;
  }

  context.clearRect(0, 0, cw, ch);
  const at = here();
  if (!at) return false;

  // one block becomes this many canvas pixels
  const scale = blocksPerPixel * ratio;

  // the big map can be dragged away from the player; the small one always follows
  const centre = interactive && panned
    ? { x: panX, z: panZ }
    : { x: at.x, z: at.z };

  const toX = (worldX) => cw / 2 + (worldX - centre.x) * scale;
  const toZ = (worldZ) => ch / 2 + (worldZ - centre.z) * scale;

  context.imageSmoothingEnabled = false;

  // Only tiles that actually intersect the view. The atlas can hold thousands after a long session,
  // and drawing all of them every frame would cost far more than the arithmetic to skip them.
  const span = tileSize * scale;
  for (const tile of tiles.values()) {
    const x = toX(tile.x * tileSize);
    const z = toZ(tile.z * tileSize);
    if (x + span < 0 || z + span < 0 || x > cw || z > ch) continue;
    context.drawImage(tile.image, x, z, span, span);
  }

  const projection = { toX: toX, toZ: toZ, scale: scale, cw: cw, ch: ch };
  if (interactive) view = projection;

  drawWaypoints(context, projection);

  // Markers have a floor as well as scaling with zoom. Tied purely to zoom they vanish into single
  // pixels when zoomed out, which is exactly when you most want to see where things are.
  const dot = Math.max(7 * ratio, scale * 1.2);
  for (const entity of state.entities || []) {
    if (!wanted(entity.kind)) continue;

    const x = toX(entity.x);
    const z = toZ(entity.z);
    if (x < 0 || z < 0 || x > cw || z > ch) continue;

    drawEntity(context, entity, x, z, dot);
  }

  drawArrow(context, toX(at.x), toZ(at.z), Math.max(20 * ratio, dot * 2.6), at.yaw);
  drawPinLabels(context, projection);
  return true;
}

/** A mob's own head where one could be cut out, a coloured dot where it couldn't. */
function drawEntity(context, entity, x, z, dot) {
  if (settings.showHeads && entity.head) {
    const head = headFor(entity.type);
    if (head && head.complete && head.naturalWidth > 0) {
      const size = Math.max(16, dot * 2.4);
      const half = size / 2;

      // A soft shadow rather than the hard dark rectangle this used to draw. That plate read as a
      // black box round every mob; a shadow lifts the head off the terrain without outlining it.
      context.save();
      context.shadowColor = 'rgba(0, 0, 0, 0.55)';
      context.shadowBlur = Math.max(2, size * 0.22);
      context.shadowOffsetY = Math.max(1, size * 0.06);
      context.drawImage(head, x - half, z - half, size, size);
      context.restore();
      return;
    }
  }

  // same treatment as the heads: lifted off the page by a shadow rather than ringed in dark
  context.save();
  context.shadowColor = 'rgba(0, 0, 0, 0.5)';
  context.shadowBlur = Math.max(2, dot * 0.5);
  context.shadowOffsetY = 1;
  context.fillStyle = ENTITY_COLORS[entity.kind] || '#ffffff';
  context.beginPath();
  context.arc(x, z, dot / 2, 0, Math.PI * 2);
  context.fill();
  context.restore();
}

/** Heads are fetched once per mob type and kept; the mod serves them immutable. */
function headFor(type) {
  if (!type) return null;
  let image = heads.get(type);
  if (image) return image;

  image = new Image();
  image.src = `/head?type=${encodeURIComponent(type)}`;
  heads.set(type, image);
  return image;
}

function renderMap() {
  const drawn = paintAtlas(dom.canvas, ctx, settings.zoom, true);

  if (!drawn) {
    dom.mapinfo.textContent = 'Waiting for the map…';
    return;
  }

  // the plate under the book reads out wherever the map is centred, like the reference does
  const at = here();
  dom.mapinfo.textContent = panned
    ? `${Math.round(panX)}, ${Math.round(panZ)}`
    : settings.showCoords
      ? `${round(at.x)}, ${round(state.y)}, ${round(at.z)}`
      : facing(at.yaw);
}

/** The corner map. Always follows the player and ignores panning, since it's for glancing at. */
function renderMini() {
  if (!dom.mini) return;
  paintAtlas(dom.mini, miniCtx, Math.max(1, Math.min(4, settings.zoom - 1)), false);
}

/** The frame loop repaints on its own, so this only has to record the change. */
function setZoom(value) {
  settings.zoom = Math.max(1, Math.min(8, value));
  saveSettings();
}

function wanted(kind) {
  if (kind === 'hostile') return settings.showHostiles;
  if (kind === 'passive') return settings.showPassives;
  if (kind === 'player') return settings.showPlayers;
  if (kind === 'item') return settings.showItems;
  return true;
}

/**
 * Stamp the arrow grid, rotated to the way the player is looking.
 *
 * The grid points east at zero rotation, and Minecraft's yaw is zero at south, so the offset here is
 * what makes the arrow on screen agree with the direction the player is actually facing.
 */
function drawArrow(context, x, z, size, yaw) {
  const rows = ARROW.length;
  const cols = ARROW[0].length;
  const pixel = Math.max(1, Math.round(size / rows));
  const width = cols * pixel;
  const height = rows * pixel;

  context.save();
  context.translate(Math.round(x), Math.round(z));
  context.rotate(((Number(yaw) || 0) - 90) * Math.PI / 180);
  context.translate(-width * 0.45, -height / 2);

  for (let row = 0; row < rows; row++) {
    const cells = ARROW[row];
    for (let col = 0; col < cols; col++) {
      const cell = cells[col];
      if (cell === '.') continue;
      context.fillStyle = cell === 'X' ? ARROW_FILL : ARROW_EDGE;
      context.fillRect(col * pixel, row * pixel, pixel, pixel);
    }
  }

  context.restore();
}

/* ---------- crafting ---------- */

/**
 * Show whatever crafting grid is actually open.
 *
 * The size follows the game rather than being fixed: your own inventory gives two by two, a table gives
 * three by three, and anything else gives nothing to craft with. Rebuilt only when the contents change,
 * for the same reason the inventory is.
 */
function renderBench() {
  const craft = state && state.craft;

  if (!craft || !craft.open) {
    if (lastBench === 'shut') return;
    lastBench = 'shut';
    dom.bench.classList.add('shut');
    dom.benchTitle.textContent = 'No crafting grid open';
    dom.benchGrid.innerHTML = '';
    dom.benchResult.className = 'slot empty';
    dom.benchResult.innerHTML = '';
    dom.takeResult.disabled = true;
    return;
  }

  const signature = JSON.stringify(craft);
  if (signature === lastBench) return;
  lastBench = signature;

  dom.bench.classList.remove('shut');
  dom.benchTitle.textContent = craft.table ? 'Crafting table' : 'Crafting';
  dom.bench.style.setProperty('--bench-cols', String(craft.width));
  dom.benchGrid.innerHTML = (craft.grid || []).map((item) => slotHtml(item, false)).join('');
  wireIcons(dom.benchGrid);

  const result = craft.result || { empty: true };
  dom.benchResult.outerHTML = slotHtml(result, false).replace('class="slot', 'id="benchResult" class="slot');
  dom.benchResult = document.getElementById('benchResult');
  wireIcons(dom.benchResult.parentElement);
  dom.takeResult.disabled = !!result.empty || !(state.can && state.can.crafting !== false);
}

/**
 * Fetch the recipe list.
 *
 * The mod builds this on its own thread when asked, so the first response after a new search is the
 * previous one and the next is the real answer. Asking twice, a beat apart, is far simpler than making
 * the request wait — and at a quarter of a second nobody notices.
 */
function loadRecipes() {
  const query = `/recipes?q=${encodeURIComponent(craftQuery)}&craftable=${craftOnly ? 1 : 0}`;

  fetch(query, { cache: 'no-store' })
    .then((response) => response.json())
    .then((json) => {
      if (json && json.ready) renderRecipes(json.recipes || []);
      // the answer that reflects this search lands on the second ask
      clearTimeout(craftTimer);
      craftTimer = setTimeout(() => {
        fetch(query, { cache: 'no-store' })
          .then((response) => response.json())
          .then((json2) => { if (json2 && json2.ready) renderRecipes(json2.recipes || []); })
          .catch(() => {});
      }, 300);
    })
    .catch(() => {});
}

function renderRecipes(recipes) {
  if (recipes.length === 0) {
    dom.recipes.innerHTML = '';
    dom.craftNote.textContent = craftOnly
      ? 'Nothing here can be made from what you are carrying.'
      : 'No recipes match that.';
    return;
  }

  dom.craftNote.textContent = 'Tap one to lay it into an open crafting grid.';
  dom.recipes.innerHTML = recipes.map((recipe) => {
    const ingredients = (recipe.grid || [])
      .filter((id) => id)
      .map((id) => prettyItem(id));
    const unique = [...new Set(ingredients)].slice(0, 6).join(', ');

    return `<div class="recipe ${recipe.craftable ? '' : 'cannot'}" data-id="${escapeHtml(recipe.id)}">
      <img class="art" src="/icon?id=${encodeURIComponent(recipe.item)}" alt="">
      <span class="who">
        <b>${escapeHtml(recipe.name)}</b>
        <span class="of">${escapeHtml(unique)}</span>
      </span>
      <span class="yield">x${recipe.count}</span>
    </div>`;
  }).join('');

  for (const node of dom.recipes.querySelectorAll('.recipe')) {
    node.addEventListener('click', () => {
      act({ do: 'craft', id: node.dataset.id, all: 0 });
      toast('Sent to the crafting grid');
    });
  }
}

/** "minecraft:oak_planks" -> "Oak Planks", without asking the mod for a display name per ingredient. */
function prettyItem(id) {
  return titleCase(String(id).split(':').pop());
}

/* ---------- waypoints ---------- */

/**
 * Pins are the handheld's, not the game's.
 *
 * Kept in the browser rather than in the mod's config, and keyed by world and dimension so the Nether
 * doesn't inherit the Overworld's markers. That also means dropping a pin needs no permission and can't
 * touch the save — the second screen stays something that reads the game rather than edits it.
 */
function pinKey() {
  const world = (state && state.worldName) || 'world';
  const dimension = (state && state.dimension) || 'overworld';
  return `ayn-mc-pins::${world}::${dimension}`;
}

function loadPins() {
  try {
    pins = JSON.parse(localStorage.getItem(pinKey()) || '[]');
  } catch (error) {
    pins = [];
  }
  if (!Array.isArray(pins)) pins = [];
  buildRail();
}

/**
 * Drop a marker where you died.
 *
 * Worked out here rather than in the mod, because the page already has everything it needs: health
 * crossing to zero, and the position from the snapshot before it. Doing it client-side means no new
 * permission, nothing touching the save, and it works against a mod build that knows nothing about it.
 *
 * The pin is replaced rather than accumulated — the interesting death is the one you have not walked
 * back to yet, and a map speckled with every death you have ever had is worse than no map.
 */
function checkDeath() {
  if (!state || !settings.deathPin) return;

  const dead = state.health <= 0;
  if (dead && !wasDead) {
    // the snapshot that reports zero health still carries the position you died at
    const at = { x: Math.round(state.x), y: Math.round(state.y), z: Math.round(state.z) };

    pins = pins.filter((pin) => pin.id !== DEATH_PIN);
    pins.push({
      id: DEATH_PIN,
      name: `Died at ${at.y}`,
      type: 'skull',
      x: at.x,
      y: at.y,
      z: at.z
    });

    savePins();
    toast('Death marked on the map');
  }

  wasDead = dead;
}

/** How far a pin is from the player, for the rail tooltips. */
function distanceTo(pin) {
  if (!state) return '';
  const blocks = Math.round(Math.hypot(pin.x - state.x, pin.z - state.z));
  return blocks >= 1000 ? `${(blocks / 1000).toFixed(1)}k blocks` : `${blocks} blocks`;
}

function savePins() {
  try {
    localStorage.setItem(pinKey(), JSON.stringify(pins));
  } catch (error) {
    toast('Could not save the pin.');
  }
  buildRail();
}

function drawWaypoints(context, projection) {
  const size = Math.max(22 * (window.devicePixelRatio || 1), projection.scale * 2.6);

  for (const pin of pins) {
    const x = projection.toX(pin.x);
    const z = projection.toZ(pin.z);
    if (x < -40 || z < -40 || x > projection.cw + 40 || z > projection.ch + 40) continue;
    drawPin(context, pin.type, x, z, size);
  }
}

/**
 * Names, drawn after everything else.
 *
 * Separated from the markers so a mob standing near a pin can't end up on top of its label — the label
 * is the part that stops being useful the moment any of it is covered.
 */
function drawPinLabels(context, projection) {
  if (!settings.pinLabels) return;

  const size = Math.max(22 * (window.devicePixelRatio || 1), projection.scale * 2.6);
  context.font = `${Math.max(11, size * 0.62)}px system-ui, sans-serif`;
  context.textAlign = 'center';
  context.textBaseline = 'bottom';

  for (const pin of pins) {
    if (!pin.name) continue;

    const x = projection.toX(pin.x);
    const z = projection.toZ(pin.z);
    if (x < -40 || z < -40 || x > projection.cw + 40 || z > projection.ch + 40) continue;

    const width = context.measureText(pin.name).width;
    const top = z - size * 0.72;

    context.fillStyle = 'rgba(24, 16, 6, 0.78)';
    context.fillRect(x - width / 2 - 5, top - size * 0.7, width + 10, size * 0.78);
    context.fillStyle = '#f2e6cc';
    context.fillText(pin.name, x, top);
  }
}

/** Stamp one pixel-art marker, centred on its point. */
function drawPin(context, type, x, z, size) {
  const art = PIN_ART[type] || PIN_ART.diamond;
  const rows = art.grid.length;
  const cols = art.grid[0].length;
  const pixel = Math.max(1, Math.round(size / rows));

  context.save();
  context.translate(Math.round(x - cols * pixel / 2), Math.round(z - rows * pixel / 2));

  for (let row = 0; row < rows; row++) {
    const cells = art.grid[row];
    for (let col = 0; col < cols; col++) {
      const colour = art.palette[cells[col]];
      if (!colour) continue;
      context.fillStyle = colour;
      context.fillRect(col * pixel, row * pixel, pixel, pixel);
    }
  }

  context.restore();
}

/** The tab strip: one tab per pin, plus the marker itself drawn into a little canvas. */
function buildRail() {
  if (!dom.pinRail) return;
  dom.pinRail.innerHTML = '';

  for (const pin of pins) {
    const button = document.createElement('button');
    button.className = 'tab-pin';
    button.title = `${pin.name || 'Pin'} — ${distanceTo(pin)}`;

    const icon = document.createElement('canvas');
    icon.width = 22;
    icon.height = 22;
    drawPin(icon.getContext('2d'), pin.type, 11, 11, 20);
    button.appendChild(icon);

    button.addEventListener('click', () => {
      panX = pin.x;
      panZ = pin.z;
      panned = true;
      editing = pin;
      openPinSheet();
    });

    dom.pinRail.appendChild(button);
  }
}

function openPinSheet() {
  const pin = editing;
  if (!pin) return;

  dom.pinTitle.textContent = pin.id ? 'Edit pin' : 'New pin';
  dom.pinName.value = pin.name || '';
  dom.pinWhere.textContent = `At ${Math.round(pin.x)}, ${Math.round(pin.z)}`;
  dom.deletePin.style.display = pin.id ? '' : 'none';

  dom.pinTypes.innerHTML = '';
  for (const type of PIN_TYPES) {
    const button = document.createElement('button');
    button.className = pin.type === type ? 'pintype on' : 'pintype';

    const icon = document.createElement('canvas');
    icon.width = 30;
    icon.height = 30;
    drawPin(icon.getContext('2d'), type, 15, 15, 27);
    button.appendChild(icon);

    button.addEventListener('click', () => {
      pin.type = type;
      openPinSheet();
    });
    dom.pinTypes.appendChild(button);
  }

  dom.pinSheet.hidden = false;
}

function savePin() {
  const pin = editing;
  if (!pin) return;

  pin.name = dom.pinName.value.trim() || 'Pin';
  if (!pin.id) {
    pin.id = `${Date.now()}-${Math.round(Math.random() * 1e6)}`;
    pins.push(pin);
  }

  savePins();
  dom.pinSheet.hidden = true;
  editing = null;
  toast('Pin saved');
}

function deletePin() {
  if (!editing || !editing.id) return;
  pins = pins.filter((pin) => pin.id !== editing.id);
  savePins();
  dom.pinSheet.hidden = true;
  editing = null;
  toast('Pin deleted');
}

/* ---------- panning ---------- */

/**
 * Drag the page around, and treat a tap that didn't move as a tap.
 *
 * Pointer events rather than touch events, so the same code covers a finger on the handheld and a
 * mouse when it's open on a desktop browser for a look.
 */
function wirePanning() {
  let down = false;
  let moved = false;
  let lastX = 0;
  let lastY = 0;

  dom.canvas.addEventListener('pointerdown', (event) => {
    down = true;
    moved = false;
    lastX = event.clientX;
    lastY = event.clientY;
    dom.canvas.setPointerCapture(event.pointerId);
  });

  dom.canvas.addEventListener('pointermove', (event) => {
    if (!down || !view) return;

    const dx = event.clientX - lastX;
    const dy = event.clientY - lastY;
    if (!moved && Math.hypot(dx, dy) < 4) return;

    moved = true;
    if (!panned) {
      const at = here();
      panX = at ? at.x : 0;
      panZ = at ? at.z : 0;
      panned = true;
    }

    // screen pixels back into blocks; devicePixelRatio is already inside view.scale
    const ratio = window.devicePixelRatio || 1;
    panX -= dx * ratio / view.scale;
    panZ -= dy * ratio / view.scale;

    lastX = event.clientX;
    lastY = event.clientY;
  });

  dom.canvas.addEventListener('pointerup', (event) => {
    down = false;
    if (moved || !view) return;

    // a tap on empty map drops a pin there; a tap on an existing one opens it
    const rect = dom.canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    const px = (event.clientX - rect.left) * ratio;
    const pz = (event.clientY - rect.top) * ratio;

    const hit = pins.find((pin) =>
      Math.hypot(view.toX(pin.x) - px, view.toZ(pin.z) - pz) < Math.max(16, view.scale * 1.6));

    if (hit) {
      editing = hit;
    } else {
      if (!settings.tapToPin) return;
      const at = here();
      editing = {
        name: '',
        type: settings.lastPinType || 'diamond',
        x: (panned ? panX : (at ? at.x : 0)) + (px - view.cw / 2) / view.scale,
        z: (panned ? panZ : (at ? at.z : 0)) + (pz - view.ch / 2) / view.scale,
        y: at ? Math.round(state.y) : 0
      };
    }
    openPinSheet();
  });
}

/* ---------- connection health ---------- */

function renderLink() {
  // performance.now, to match lastSnapshot: mixing the two clocks makes the dot lie
  const age = performance.now() - lastSnapshot;
  // the mod answers a held request within 10s even when nothing changes, so silence past that is real
  const health = !state || failures > 0 || age > 12000 ? 'dead' : age > 1500 ? 'slow' : 'live';
  dom.link.className = `dot ${health}`;
  dom.link.title = state ? `Last update ${(age / 1000).toFixed(1)}s ago` : 'Not connected';
}

/* ---------- wiki ---------- */

/**
 * Leave the page for the wiki.
 *
 * Goes through a real anchor click as well as a location assign: inside the app's WebView one of the
 * two is reliably allowed where the other silently does nothing, and a watchdog covers the case where
 * neither lands so the button never just looks broken.
 */
function openWiki(query) {
  const url = query ? WIKI_SEARCH + encodeURIComponent(query) : WIKI_HOME;
  let left = false;
  window.addEventListener('pagehide', () => { left = true; }, { once: true });

  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.target = '_self';
    anchor.rel = 'noreferrer';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } catch (error) {
    // fall through to the assign below
  }

  try {
    window.location.assign(url);
  } catch (error) {
    // same
  }

  setTimeout(() => {
    if (!left) toast('Couldn’t open the wiki. It needs an internet connection.');
  }, 1500);
}

/* ---------- bits ---------- */

let toastTimer = null;

function toast(message) {
  dom.toast.textContent = message;
  dom.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { dom.toast.hidden = true; }, 2600);
}

function round(value) {
  return Math.round(Number(value) || 0);
}

function clampPercent(fraction) {
  return Math.max(0, Math.min(100, Math.round((Number(fraction) || 0) * 100)));
}

function titleCase(text) {
  return String(text || '')
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function escapeHtml(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

document.addEventListener('DOMContentLoaded', start);
