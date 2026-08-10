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
 * is that a phone, a tablet and the Thor's panel can each be laid out differently against one game.
 * The `no*` keys become classes on <body>; the CSS does the hiding.
 */
const SETTING_DEFAULTS = {
  hud: true,
  map: true,
  legend: true,
  stats: true,
  buffs: true,
  boss: true,
  inv: true,
  extra: true,
  equip: true,
  detail: true,
  actions: true,
  accent: '#6ec1ff',
  outline: 'normal',
  scale: 'medium',
  rate: 10,
  autoNpc: true
};

/** Which section each toggle hides, and what to call it in the panel. */
const SECTIONS = [
  ['hud', 'Top bar'],
  ['map', 'Map panel'],
  ['legend', 'Map legend'],
  ['stats', 'Defense / coins / moon'],
  ['buffs', 'Buff icons'],
  ['boss', 'Boss health bar'],
  ['inv', 'Inventory panel'],
  ['extra', 'Coin & ammo slots'],
  ['equip', 'Equipment'],
  ['detail', 'Selected item'],
  ['actions', 'Action buttons']
];

/** Toggles that aren't section visibility get listed separately in the panel. */
const OPTIONS = [
  ['autoNpc', 'Switch to the NPC tab when you talk to someone']
];

const ACCENTS = ['#6ec1ff', '#7c95ff', '#4be08b', '#ffd166', '#ff8fab', '#c58cff', '#ff6b6b', '#e8ecff'];

/* ---------- the official wiki ---------- */

/**
 * The wiki is opened by navigating this window rather than in a new tab or an iframe.
 *
 * An iframe is out: wiki.gg sends frame-ancestors, so it refuses to be embedded. A new tab is no good
 * either, because the whole point is that the second screen is a kiosk — in the Android app there is
 * no tab bar to get back from. Navigating in place means the app's own menu can return here, and a
 * plain browser's Back button does the same.
 */
const WIKI_HOME = 'https://terraria.wiki.gg/wiki/Terraria_Wiki';
const WIKI_SEARCH = 'https://terraria.wiki.gg/index.php?search=';

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

/** The plain item name, without the prefix or stack that HoverName carries. */
function wikiNameFor(item) {
  if (!item || !item.name) return null;
  return String(item.name).replace(/^\[.*?\]\s*/, '').replace(/\s*\(\d+\)$/, '').trim();
}

const OUTLINES = {
  subtle: { edge: '#3a4386', width: '1px' },
  normal: { edge: '#6d7cd8', width: '1px' },
  bold: { edge: '#9aa7ff', width: '2px' }
};

const SCALES = { small: '12px', medium: '14px', large: '17px', huge: '20px' };

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
  root.setProperty('--accent', settings.accent);
  root.setProperty('--edge-hot', settings.accent);

  const outline = OUTLINES[settings.outline] || OUTLINES.normal;
  root.setProperty('--slot-edge', outline.edge);
  root.setProperty('--slot-border', outline.width);

  document.documentElement.style.fontSize = SCALES[settings.scale] || SCALES.medium;

  POLL_MS = Math.round(1000 / Math.max(1, Math.min(30, settings.rate)));

  // the map is canvas-drawn, so it has to be told the layout moved under it
  if (state) renderMap();
}

/* ---------- inventory layout (must match the mod's slot indices) ---------- */

const HOTBAR_END = 10;       // 0-9   the hotbar, the only slots that can be held
const MAIN_END = 50;         // 10-49 the main grid
const COIN_END = 54;         // 50-53 coin slots
const AMMO_END = 58;         // 54-57 ammo slots

const ENTITY_COLORS = {
  enemy: '#ff5d5d',
  boss: '#c58cff',
  town: '#ffd166',
  friendly: '#9dff9d',
  player: '#6ec1ff'
};

/*
 * The player marker, as pixel art.
 *
 * Drawn from a grid rather than as a canvas path: a path gets antialiased into soft grey edges, which
 * looks wrong sitting on top of a map made of hard 1-tile pixels. Every cell here becomes one square of
 * the same size, so the arrow stays crisp at any zoom and reads like part of the map.
 *
 *   X = the team colour   o = the dark outline   . = nothing
 */
const PLAYER_ARROW = [
  'ooo.....',
  'oXXoo...',
  'oXXXXoo.',
  'oXXXXXXo',
  'oXXXXoo.',
  'oXXoo...',
  'ooo.....'
];

const ARROW_EDGE = '#0a0d18';

/** The local player when not on a team: no team to correspond to, so keep the established "you" blue. */
const YOU_COLOR = '#6ec1ff';

/* Terraria's rarity tints, as ItemRarity draws them. */
const RARITY_COLORS = {
  '-13': '#ff3200', '-12': '#ff6bd6', '-11': '#ffaf00', '-1': '#969696',
  '0': '#ffffff', '1': '#9696ff', '2': '#96ff96', '3': '#ffc896', '4': '#ff9696',
  '5': '#ff96ff', '6': '#d2a0ff', '7': '#96ff0a', '8': '#ffff0a', '9': '#2dffc8',
  '10': '#ff2d0a', '11': '#b428ff'
};

const MOON_PHASES = [
  'Full', 'Waning Gibbous', 'Third Quarter', 'Waning Crescent',
  'New', 'Waxing Crescent', 'First Quarter', 'Waxing Gibbous'
];

/* ---------- element handles ---------- */

const el = (id) => document.getElementById(id);

const dom = {
  offline: el('offline'),
  offlineDetail: el('offline-detail'),
  world: el('world-text'),
  difficulty: el('difficulty-text'),
  clock: el('clock-text'),
  events: el('events-text'),
  depth: el('depth-text'),
  place: el('place-text'),
  lifeBar: el('bar-life'),
  lifeText: el('life-text'),
  manaBar: el('bar-mana'),
  manaText: el('mana-text'),
  breathRow: el('breath-row'),
  breathBar: el('bar-breath'),
  breathText: el('breath-text'),
  mapToggle: el('map-toggle'),
  tabMap: el('tab-map'),
  tabBoss: el('tab-boss'),
  tabCraft: el('tab-craft'),
  bossList: el('boss-list'),
  eventList: el('event-list'),
  bossProgress: el('boss-progress'),
  moddedHead: el('modded-head'),
  moddedProgress: el('modded-progress'),
  moddedList: el('modded-list'),
  moddedNote: el('modded-note'),
  modListHead: el('modlist-head'),
  modList: el('mod-list'),
  craftList: el('craft-list'),
  craftCount: el('craft-count'),
  craftFilter: el('craft-filter'),
  tabNpc: el('tab-npc'),
  tabNpcButton: el('tab-npc-button'),
  npcArt: el('npc-art'),
  npcName: el('npc-name'),
  npcDialogue: el('npc-dialogue'),
  shopList: el('shop-list'),
  shopNote: el('shop-note'),
  linkDot: el('link-dot'),
  zoomIn: el('zoom-in'),
  zoomOut: el('zoom-out'),
  zoomText: el('zoom-text'),
  mapTip: el('map-tip'),
  canvas: el('map-canvas'),
  boss: el('boss'),
  bossName: el('boss-name'),
  bossBar: el('bar-boss'),
  bossText: el('boss-text'),
  defense: el('defense-text'),
  coins: el('coins-text'),
  potion: el('potion-text'),
  potionLabel: el('potion-label'),
  moon: el('moon-text'),
  buffs: el('buffs'),
  buffDetail: el('buff-detail'),
  invPanel: el('inv-panel'),
  invHint: el('inv-hint'),
  hotbar: el('inv-hotbar'),
  main: el('inv-main'),
  extra: el('inv-extra'),
  equip: el('inv-equip'),
  equipDefense: el('equip-defense'),
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
  settingsOutline: el('settings-outline'),
  settingsScale: el('settings-scale'),
  settingsRate: el('settings-rate')
};

const ctx = dom.canvas.getContext('2d');

/* ---------- state ---------- */

let state = null;         // latest snapshot from the mod
let mapData = null;       // latest /minimap payload, minus the image itself
let mapImage = null;      // the decoded minimap
let loadedMapRev = -1;
let mapPending = false;

let cursor = 0;           // slot the action buttons apply to
let dragFrom = -1;
let dragActive = false;
let dragOverSlot = null;
let pointerStart = null;
let trashArmed = false;
let worldMode = false;
let slots = [];           // slot elements, index-aligned with the inventory
let equipSlots = [];      // ditto for the armour and accessory row

const ZOOM_STEPS = [1, 1.5, 2, 3, 5];
let zoomStep = 0;

let lastSnapshot = 0;     // when a snapshot last arrived, for the connection dot
let lastTick = -1;        // the game's own tick, to notice a paused or hung game
let projection = null;    // last map draw, so a tap can be mapped back to world tiles

let activeTab = 'map';
let progressData = null;
let craftData = null;
let craftFilterText = '';
let talkData = null;
let wasTalking = false;
let tabBeforeTalk = 'map';

/* ---------- polling ---------- */

async function poll() {
  try {
    const res = await fetch('/state', { cache: 'no-store' });
    const next = await res.json();

    if (!next.ready) {
      showOffline('Enter a world to start the second screen.');
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

/* The minimap is a rendered image rather than a tile grid: a Terraria world is millions of tiles,
   so the mod sends back only the window it drew, and we place everything else against its origin. */
async function loadMap() {
  if (mapPending) return;
  mapPending = true;
  try {
    const res = await fetch('/minimap', { cache: 'no-store' });
    const next = await res.json();
    if (!next || !next.png) return;

    const image = new Image();
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = reject;
      image.src = next.png;
    });

    mapImage = image;
    mapData = next;
    loadedMapRev = next.rev;
  } catch (err) {
    /* the next poll will retry */
  } finally {
    mapPending = false;
  }
}

/**
 * The checklist and craftable list have their own slow poll.
 *
 * They're published by the mod on a 2Hz beat and are far bigger than a snapshot, so pulling them at
 * the state rate would waste most of the bandwidth on data that rarely changes.
 */
async function pollSlow() {
  try {
    // always polled, not just when its tab is open: it's what decides whether to open that tab
    const talk = await (await fetch('/talk', { cache: 'no-store' })).json();
    handleTalk(talk);

    if (activeTab === 'boss') {
      const res = await fetch('/progress', { cache: 'no-store' });
      progressData = await res.json();
      renderProgress();
    } else if (activeTab === 'craft') {
      const res = await fetch('/craftable', { cache: 'no-store' });
      craftData = await res.json();
      renderCraft();
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

function send(type, extra) {
  fetch('/action', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(Object.assign({ type: type }, extra || {}))
  }).catch(() => { /* the screen is read-only until the game comes back */ });
}

/* ---------- HUD ---------- */

function formatClock(minutes) {
  const total = Math.floor(minutes);
  let hour = Math.floor(total / 60) % 24;
  const minute = total % 60;
  const suffix = hour < 12 ? 'AM' : 'PM';
  hour = hour % 12;
  if (hour === 0) hour = 12;
  return `${hour}:${String(minute).padStart(2, '0')} ${suffix}`;
}

function formatCoins(copper) {
  const platinum = Math.floor(copper / 1000000);
  const gold = Math.floor(copper / 10000) % 100;
  const silver = Math.floor(copper / 100) % 100;
  const bits = [];
  if (platinum) bits.push(`${platinum}p`);
  if (gold) bits.push(`${gold}g`);
  if (silver && !platinum) bits.push(`${silver}s`);
  if (!bits.length) bits.push(`${copper % 100}c`);
  return bits.join(' ');
}

function setBar(bar, text, value, max) {
  const ratio = max > 0 ? value / max : 0;
  bar.style.width = `${Math.max(0, Math.min(1, ratio)) * 100}%`;
  text.textContent = `${value} / ${max}`;
}

function renderHud() {
  dom.world.textContent = state.worldName || 'World';
  dom.difficulty.textContent = state.hardMode ? `${state.difficulty} · Hardmode` : state.difficulty;
  dom.clock.textContent = formatClock(state.timeMinutes);
  dom.events.textContent = (state.events || []).join(' · ');

  const feet = state.depthFeet;
  dom.depth.textContent = `${Math.abs(feet)} ft ${feet < 0 ? 'above' : 'below'}`;
  dom.place.textContent = `${state.layer} · ${state.biome}`;

  setBar(dom.lifeBar, dom.lifeText, state.life, state.lifeMax);
  setBar(dom.manaBar, dom.manaText, state.mana, state.manaMax);

  // breath only matters while it's draining, so keep it out of the way otherwise
  const drowning = state.breathMax > 0 && state.breath < state.breathMax;
  dom.breathRow.classList.toggle('hidden', !drowning);
  if (drowning) setBar(dom.breathBar, dom.breathText, state.breath, state.breathMax);

  dom.defense.textContent = state.defense;
  dom.coins.textContent = formatCoins(state.coins);
  dom.moon.textContent = MOON_PHASES[state.moonPhase] || '—';

  // while Potion Sickness is up the Heal button can't do anything, so show the wait instead of the count
  if (state.potionCooldown > 0) {
    dom.potion.textContent = `${state.potionCooldown}s`;
    dom.potionLabel.textContent = 'Sickness';
  } else {
    dom.potion.textContent = state.healingItems || 0;
    dom.potionLabel.textContent = 'Potions';
  }

  if (state.boss) {
    dom.boss.classList.remove('hidden');
    dom.bossName.textContent = state.boss.source
      ? `${state.boss.name} · ${state.boss.source}`
      : state.boss.name;
    setBar(dom.bossBar, dom.bossText, state.boss.life, state.boss.lifeMax);
  } else {
    dom.boss.classList.add('hidden');
  }
}

/**
 * The full text for a buff: name, time left, what it does, and which mod it came from.
 *
 * Modded buffs are the reason the description is here at all — a vanilla one you can recognise by its
 * icon, but a buff from a content mod is usually a 32x32 image you have never seen before.
 */
function buffTooltip(buff) {
  const lines = [buff.seconds >= 0 ? `${buff.name} — ${buff.seconds}s` : buff.name];
  if (buff.description) lines.push(buff.description);
  if (buff.source) lines.push(`from ${buff.source}`);
  return lines.join('\n');
}

let buffSignature = null;
let selectedBuffType = null;

function showBuffDetail(buff) {
  // tapping the same one again puts the line away
  if (selectedBuffType === buff.type) {
    selectedBuffType = null;
    dom.buffDetail.classList.add('hidden');
    return;
  }

  selectedBuffType = buff.type;
  dom.buffDetail.textContent = buffTooltip(buff).replace(/\n/g, ' — ');
  dom.buffDetail.classList.remove('hidden');
}

function renderBuffs() {
  const buffs = state.buffs || [];
  const signature = buffs.map((b) => `${b.type}:${b.seconds}`).join(',');
  if (signature === buffSignature) return;
  buffSignature = signature;

  // a buff that has run out should not leave its description sitting on screen
  const selected = buffs.find((b) => b.type === selectedBuffType);
  if (selected) {
    dom.buffDetail.textContent = buffTooltip(selected).replace(/\n/g, ' — ');
  } else {
    selectedBuffType = null;
    dom.buffDetail.classList.add('hidden');
  }

  dom.buffs.textContent = '';
  for (const buff of buffs) {
    const cell = document.createElement('div');
    cell.className = buff.debuff ? 'buff debuff' : 'buff';
    cell.title = buffTooltip(buff);

    // there is no hover on a handheld, so the tooltip needs somewhere to go on a tap
    cell.addEventListener('click', () => showBuffDetail(buff));

    if (buff.iconKey) {
      const img = document.createElement('img');
      img.src = `/icon/${buff.iconKey}.png`;
      img.alt = buff.name;
      cell.appendChild(img);
    }

    if (buff.seconds >= 0) {
      const time = document.createElement('i');
      time.textContent = buff.seconds >= 60 ? `${Math.floor(buff.seconds / 60)}m` : `${buff.seconds}`;
      cell.appendChild(time);
    }

    dom.buffs.appendChild(cell);
  }
}

/* ---------- inventory ---------- */

function buildSlots(count) {
  dom.hotbar.textContent = '';
  dom.main.textContent = '';
  dom.extra.textContent = '';
  slots = [];

  for (let i = 0; i < count; i++) {
    const slot = document.createElement('div');
    slot.className = 'slot';
    slot.dataset.index = String(i);

    let parent;
    if (i < HOTBAR_END) {
      parent = dom.hotbar;
    } else if (i < MAIN_END) {
      parent = dom.main;
    } else {
      parent = dom.extra;
      slot.classList.add(i < COIN_END ? 'coin' : 'ammo');
    }

    const img = document.createElement('img');
    img.alt = '';
    img.style.display = 'none';

    const stack = document.createElement('span');
    stack.className = 'stack';

    slot.append(img, stack);
    parent.appendChild(slot);
    slots.push({ root: slot, img: img, stack: stack, signature: null });
  }
}

function renderInventory() {
  const items = state.inventory || [];
  if (slots.length !== items.length) buildSlots(items.length);

  // How much room is left, without counting. The main grid only - the hotbar, coins and ammo are not
  // where you run out, and including them would make the number say nothing about your carrying space.
  const free = items.slice(HOTBAR_END, MAIN_END).filter((item) => !item.name).length;
  dom.invHint.textContent = free === 0
    ? 'inventory full'
    : `${free} free · tap to select · drag to move`;

  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    const slot = slots[i];
    const signature = item.name ? `${item.iconKey}|${item.stack}|${item.name}` : '';

    if (slot.signature !== signature) {
      slot.signature = signature;

      if (item.name && item.iconKey) {
        slot.img.src = `/icon/${item.iconKey}.png`;
        slot.img.style.display = '';
      } else {
        slot.img.removeAttribute('src');
        slot.img.style.display = 'none';
      }
      slot.stack.textContent = item.name && item.stack > 1 ? item.stack : '';
    }

    // an empty slot needs its own look, or the grid just reads as gaps
    slot.root.classList.toggle('empty', !item.name);
    slot.root.classList.toggle('equipped', i === state.selectedSlot && i < HOTBAR_END);
    slot.root.classList.toggle('cursor', i === cursor);
  }

  renderDetail(items[cursor]);
}

function renderDetail(item) {
  const hasItem = !!(item && item.name);

  if (hasItem) {
    dom.detailName.textContent = item.name;
    dom.detailName.style.color = RARITY_COLORS[String(item.rare)] || '#ffffff';

    const bits = [];
    if (item.meta) bits.push(item.meta);
    bits.push(`slot ${item.index + 1}`);
    dom.detailMeta.textContent = bits.join(' · ');

    if (item.iconKey) {
      dom.detailIcon.src = `/icon/${item.iconKey}.png`;
      dom.detailIcon.style.visibility = 'visible';
    } else {
      dom.detailIcon.style.visibility = 'hidden';
    }
  } else {
    dom.detailName.textContent = 'Empty slot';
    dom.detailName.style.color = '';
    dom.detailMeta.textContent = `slot ${cursor + 1}`;
    dom.detailIcon.style.visibility = 'hidden';
  }

  dom.detailWiki.disabled = !hasItem;

  // Two things gate a button: whether the config allows it at all, and whether there's an item to act
  // on. Only the slot-targeted actions care about the second — the quick-use ones pick for themselves.
  const can = state.can || {};
  for (const button of dom.actions.querySelectorAll('.act')) {
    const act = button.dataset.act;
    let allowed = true;
    if (act === 'drop') allowed = can.drop !== false;
    else if (act === 'trash') allowed = can.trash !== false;
    else if (act === 'sort') allowed = can.edit !== false;
    else allowed = can.quickUse !== false;

    button.disabled = !allowed || ((act === 'drop' || act === 'trash') && !hasItem);
    button.title = allowed ? '' : 'Turned off in the mod config';
  }

  if (!hasItem) disarmTrash();
}

/* ---------- equipment ---------- */

function renderEquipment() {
  const items = state.equipment || [];
  if (equipSlots.length !== items.length) {
    dom.equip.textContent = '';
    equipSlots = items.map(() => {
      const slot = document.createElement('div');
      slot.className = 'slot';

      const img = document.createElement('img');
      img.alt = '';
      img.style.display = 'none';

      slot.appendChild(img);
      dom.equip.appendChild(slot);
      return { root: slot, img: img, signature: null };
    });
  }

  let defense = 0;
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    const slot = equipSlots[i];
    defense += item.defense || 0;

    const signature = item.name ? `${item.iconKey}|${item.name}` : '';
    if (slot.signature !== signature) {
      slot.signature = signature;
      if (item.name && item.iconKey) {
        slot.img.src = `/icon/${item.iconKey}.png`;
        slot.img.style.display = '';
      } else {
        slot.img.removeAttribute('src');
        slot.img.style.display = 'none';
      }
      slot.root.classList.toggle('empty', !item.name);
      slot.root.title = item.name || item.slot;
    }
  }

  dom.equipDefense.textContent = defense > 0 ? `${defense} def from gear` : '';
}

/* ---------- boss checklist ---------- */

function renderProgress() {
  if (!progressData) return;

  dom.bossProgress.textContent = `${progressData.bossesDone} / ${progressData.bossesTotal}`;
  fillChecklist(dom.bossList, progressData.bosses || [], true);
  fillChecklist(dom.eventList, progressData.events || [], false);
  renderModdedBosses();
  renderModList();
}

/**
 * The modded half of the checklist.
 *
 * Grouped by the mod each boss came from, because with two content mods installed an interleaved list
 * is unreadable — you want to see how far through Calamity you are, not a merged ladder. The whole
 * section disappears when there are no modded bosses, which is the common case.
 */
function renderModdedBosses() {
  const bosses = progressData.moddedBosses || [];

  dom.moddedHead.classList.toggle('hidden', bosses.length === 0);
  dom.moddedNote.classList.toggle('hidden', bosses.length === 0);
  dom.moddedList.textContent = '';
  if (bosses.length === 0) return;

  dom.moddedProgress.textContent = `${progressData.moddedDone} / ${progressData.moddedTotal}`;

  dom.moddedNote.textContent = progressData.checklistLinked
    ? 'Order and defeats come from Boss Checklist.'
    : 'Ordered by health, and defeats are counted from when this mod was installed. Install Boss Checklist for a proper progression order.';

  let currentSource = null;
  for (const boss of bosses) {
    if (boss.source !== currentSource) {
      currentSource = boss.source;
      const head = document.createElement('div');
      head.className = 'check-group';
      head.textContent = currentSource;
      dom.moddedList.appendChild(head);
    }

    const row = document.createElement('div');
    row.className = 'check ' + (boss.done ? 'done' : 'todo');

    const tick = document.createElement('i');
    tick.textContent = boss.done ? '✔' : '·';

    const name = document.createElement('span');
    name.textContent = boss.name;

    row.append(tick, name);
    dom.moddedList.appendChild(row);
  }
}

function renderModList() {
  const mods = progressData.mods || [];

  dom.modListHead.classList.toggle('hidden', mods.length === 0);
  dom.modList.textContent = '';

  for (const mod of mods) {
    const row = document.createElement('div');
    row.className = 'modrow';

    const name = document.createElement('span');
    name.textContent = mod.name;

    const version = document.createElement('em');
    version.textContent = mod.version ? `v${mod.version}` : '';

    row.append(name, version);
    dom.modList.appendChild(row);
  }
}

/**
 * Draw one checklist.
 *
 * The first undefeated entry gets picked out, because on a boss list that's the only row anyone is
 * really looking for. Hardmode entries are dimmed until the world is in hardmode, since they can't be
 * attempted yet and would otherwise read as things you've simply missed.
 */
function fillChecklist(host, entries, markNext) {
  host.textContent = '';
  let nextMarked = false;

  for (const entry of entries) {
    const row = document.createElement('div');
    row.className = 'check ' + (entry.done ? 'done' : 'todo');

    if (markNext && !entry.done && !nextMarked && (!entry.hardmode || progressData.hardMode)) {
      row.classList.add('next');
      nextMarked = true;
    }

    const tick = document.createElement('i');
    tick.textContent = entry.done ? '✔' : '·';

    const name = document.createElement('span');
    name.textContent = entry.name;

    row.append(tick, name);

    if (entry.hardmode && !progressData.hardMode) {
      const tag = document.createElement('em');
      tag.textContent = 'hardmode';
      row.appendChild(tag);
      row.style.opacity = '0.4';
    }

    host.appendChild(row);
  }
}

/* ---------- craftable list ---------- */

function renderCraft() {
  if (!craftData) return;

  const all = craftData.recipes || [];
  const needle = craftFilterText.trim().toLowerCase();
  const shown = needle
    ? all.filter((r) => r.name.toLowerCase().includes(needle)
        || (r.ingredients || []).some((i) => i.toLowerCase().includes(needle)))
    : all;

  // the mod caps what it sends, so say when there's more than is being shown
  dom.craftCount.textContent = craftData.count > all.length
    ? `${shown.length} of ${craftData.count}`
    : `${shown.length}`;

  dom.craftList.textContent = '';

  if (!shown.length) {
    const empty = document.createElement('div');
    empty.className = 'craft';
    empty.textContent = needle ? 'Nothing matches that.' : 'Nothing craftable here.';
    dom.craftList.appendChild(empty);
    return;
  }

  for (const recipe of shown) {
    const row = document.createElement('div');
    row.className = 'craft';

    if (recipe.iconKey) {
      const img = document.createElement('img');
      img.src = `/icon/${recipe.iconKey}.png`;
      img.alt = '';
      row.appendChild(img);
    }

    const text = document.createElement('div');

    const name = document.createElement('b');
    name.textContent = recipe.stack > 1 ? `${recipe.name} x${recipe.stack}` : recipe.name;
    name.style.color = RARITY_COLORS[String(recipe.rare)] || '#ffffff';

    const parts = document.createElement('small');
    parts.textContent = (recipe.ingredients || []).join(', ');

    text.append(name, parts);
    row.appendChild(text);
    dom.craftList.appendChild(row);
  }
}

/* ---------- talking to an NPC ---------- */

/**
 * React to the conversation starting or ending.
 *
 * Opening the tab is automatic but reversible: the tab you were on is remembered and restored when the
 * conversation ends, so glancing at a shopkeeper doesn't lose your place on the map. Anyone who doesn't
 * want the jump can turn it off in the gear panel.
 */
function handleTalk(talk) {
  talkData = talk;
  const talking = !!talk;

  dom.tabNpcButton.classList.toggle('hidden', !talking);

  if (talking && !wasTalking) {
    if (settings.autoNpc) {
      tabBeforeTalk = activeTab;
      setTab('npc');
    }
  } else if (!talking && wasTalking) {
    dom.tabNpcButton.classList.add('hidden');
    if (activeTab === 'npc') setTab(tabBeforeTalk || 'map');
  }

  wasTalking = talking;
  if (talking && activeTab === 'npc') renderTalk();
}

function renderTalk() {
  if (!talkData) return;

  dom.npcName.textContent = talkData.name || '';
  dom.npcDialogue.textContent = talkData.dialogue || '';

  if (talkData.artKey) {
    dom.npcArt.src = `/icon/${talkData.artKey}.png`;
    dom.npcArt.style.visibility = 'visible';
  } else {
    dom.npcArt.style.visibility = 'hidden';
  }

  const wares = talkData.shop || [];
  dom.shopList.textContent = '';

  if (!talkData.shopOpen) {
    dom.shopNote.textContent = '';
    const note = document.createElement('div');
    note.className = 'wares';
    note.textContent = 'No shop open. Open their shop on the PC and it will appear here.';
    dom.shopList.appendChild(note);
    return;
  }

  dom.shopNote.textContent = talkData.canBuy ? `${wares.length} items` : 'buying is off in the mod config';

  for (const ware of wares) {
    const row = document.createElement('div');
    row.className = 'wares' + (ware.affordable ? '' : ' poor');

    if (ware.iconKey) {
      const img = document.createElement('img');
      img.src = `/icon/${ware.iconKey}.png`;
      img.alt = '';
      row.appendChild(img);
    }

    const text = document.createElement('div');
    const name = document.createElement('b');
    name.textContent = ware.stack > 1 ? `${ware.name} x${ware.stack}` : ware.name;
    name.style.color = RARITY_COLORS[String(ware.rare)] || '#ffffff';
    const price = document.createElement('small');
    price.textContent = ware.priceText;
    text.append(name, price);
    row.appendChild(text);

    const buy = document.createElement('button');
    buy.className = 'buy';
    buy.textContent = 'Buy';
    // the screen only ever names a slot; the mod re-reads the price and the purse before spending
    buy.disabled = !talkData.canBuy || !ware.affordable;
    buy.title = !talkData.canBuy
      ? 'Turn on AllowShopping in the mod config'
      : (ware.affordable ? '' : 'Not enough coins');
    buy.addEventListener('click', () => {
      send('buy', { index: ware.slot });
      buy.disabled = true;
    });
    row.appendChild(buy);

    dom.shopList.appendChild(row);
  }
}

/* ---------- tabs ---------- */

function setTab(tab) {
  activeTab = tab;

  for (const button of document.querySelectorAll('.tab'))
    button.classList.toggle('on', button.dataset.tab === tab);

  dom.tabMap.classList.toggle('hidden', tab !== 'map');
  dom.tabBoss.classList.toggle('hidden', tab !== 'boss');
  dom.tabCraft.classList.toggle('hidden', tab !== 'craft');
  dom.tabNpc.classList.toggle('hidden', tab !== 'npc');

  if (tab === 'npc') renderTalk();

  // the zoom and follow controls only mean anything on the map
  for (const id of ['zoom-in', 'zoom-out', 'zoom-text', 'map-toggle']) {
    const node = document.getElementById(id);
    if (node) node.style.display = tab === 'map' ? '' : 'none';
  }

  if (tab === 'map' && state) renderMap();
}

/* ---------- minimap ---------- */

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

  if (!mapImage || !mapData || !state) return;

  const fit = Math.min(cw / mapImage.width, ch / mapImage.height);
  const scale = fit * ZOOM_STEPS[zoomStep];
  const drawW = mapImage.width * scale;
  const drawH = mapImage.height * scale;

  // at 1x the image is centred; zoomed in, it follows the player and stops at the edges
  let originX, originY;
  if (zoomStep === 0) {
    originX = (cw - drawW) / 2;
    originY = (ch - drawH) / 2;
  } else {
    const step0 = mapData.step || 1;
    const px = ((state.x - mapData.originX) / step0) * scale;
    const py = ((state.y - mapData.originY) / step0) * scale;
    originX = drawW <= cw ? (cw - drawW) / 2 : Math.min(0, Math.max(cw - drawW, cw / 2 - px));
    originY = drawH <= ch ? (ch - drawH) / 2 : Math.min(0, Math.max(ch - drawH, ch / 2 - py));
  }

  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(mapImage, originX, originY, drawW, drawH);

  // world tiles to canvas pixels, through the window the mod actually drew
  const step = mapData.step || 1;
  const toX = (tileX) => originX + ((tileX - mapData.originX) / step) * scale;
  const toY = (tileY) => originY + ((tileY - mapData.originY) / step) * scale;

  // remembered so a tap can be turned back into world coordinates
  projection = { originX: originX, originY: originY, scale: scale, step: step };

  const dot = Math.max(3, scale * 1.5);

  const markerSize = Math.max(7, dot * 1.6);

  for (const entity of state.entities || []) {
    const x = toX(entity.x);
    const y = toY(entity.y);
    if (x < 0 || y < 0 || x > cw || y > ch) continue;

    // other players get the same arrow as you do, so a glance says who is facing where
    if (entity.kind === 'player') {
      drawArrow(x, y, markerSize, entity.direction, teamColorOf(entity, ENTITY_COLORS.player));
      continue;
    }

    ctx.fillStyle = ENTITY_COLORS[entity.kind] || '#ffffff';
    ctx.beginPath();
    ctx.arc(x, y, (entity.kind === 'boss' ? dot : dot * 0.7) / 2, 0, Math.PI * 2);
    ctx.fill();
  }

  drawArrow(toX(state.x), toY(state.y), markerSize, state.direction, teamColorOf(state, YOU_COLOR));
}

/**
 * The colour an arrow should be.
 *
 * Team 0 is "no team" in Terraria, not a colour choice, so that case falls back to the caller's default
 * rather than painting everyone the white the game happens to store at index 0.
 */
function teamColorOf(source, fallback) {
  if (!source || !source.team || source.team <= 0) return fallback;
  return source.teamColor || fallback;
}

/** Stamp the arrow grid at (x, y), mirrored when facing left. */
function drawArrow(x, y, size, direction, fill) {
  const rows = PLAYER_ARROW.length;
  const cols = PLAYER_ARROW[0].length;

  // whole pixels only: a fractional cell size puts seams between the squares
  const pixel = Math.max(1, Math.round(size / rows));
  const width = cols * pixel;
  const height = rows * pixel;

  ctx.save();
  ctx.translate(Math.round(x), Math.round(y));
  if (direction < 0) ctx.scale(-1, 1);
  // the tip is the interesting end, so centre on the body rather than the bounding box
  ctx.translate(-width * 0.45, -height / 2);

  for (let row = 0; row < rows; row++) {
    const cells = PLAYER_ARROW[row];
    for (let col = 0; col < cols; col++) {
      const cell = cells[col];
      if (cell === '.') continue;
      ctx.fillStyle = cell === 'X' ? fill : ARROW_EDGE;
      ctx.fillRect(col * pixel, row * pixel, pixel, pixel);
    }
  }

  ctx.restore();
}

/* ---------- frame ---------- */

function render() {
  renderHud();
  renderBuffs();
  renderInventory();
  renderEquipment();
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
  dom.linkDot.title = state
    ? `Last update ${(age / 1000).toFixed(1)}s ago`
    : 'Not connected';
}

setInterval(renderLink, 250);

/* ---------- touch interaction ---------- */

function slotFromPoint(x, y) {
  const node = document.elementFromPoint(x, y);
  return node ? node.closest('.slot') : null;
}

function clearDragHighlight() {
  if (dragOverSlot) dragOverSlot.classList.remove('dragover');
  dragOverSlot = null;
}

// bound to the whole panel so a drag can cross between the hotbar, main and coin/ammo grids
dom.invPanel.addEventListener('pointerdown', (event) => {
  const slot = event.target.closest('.slot');
  if (!slot) return;

  dragFrom = Number(slot.dataset.index);
  dragActive = false;
  pointerStart = { x: event.clientX, y: event.clientY };
  dom.invPanel.setPointerCapture(event.pointerId);
});

dom.invPanel.addEventListener('pointermove', (event) => {
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

dom.invPanel.addEventListener('pointerup', (event) => {
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
      if (to !== from) send('swap', { index: from, to: to });
    }
    return;
  }

  // a plain tap moves the cursor, and equips the item if it's reachable from the hotbar
  cursor = from;
  disarmTrash();
  if (from < HOTBAR_END) send('select', { index: from });
  if (state) renderInventory();
});

dom.invPanel.addEventListener('pointercancel', () => {
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
    send('trash', { index: cursor });
    return;
  }

  disarmTrash();
  if (act === 'drop') send('drop', { index: cursor });
  else send(act);
});

dom.mapToggle.addEventListener('click', () => {
  worldMode = !worldMode;
  dom.mapToggle.textContent = worldMode ? 'World' : 'Follow';
  send('mapmode', { mode: worldMode ? 'world' : 'local' });
});

/* ---------- map zoom and tap-to-identify ---------- */

function setZoom(step) {
  zoomStep = Math.max(0, Math.min(ZOOM_STEPS.length - 1, step));
  dom.zoomText.textContent = `${ZOOM_STEPS[zoomStep]}x`;
  dom.zoomOut.disabled = zoomStep === 0;
  dom.zoomIn.disabled = zoomStep === ZOOM_STEPS.length - 1;
  if (state) renderMap();
}

dom.zoomIn.addEventListener('click', () => setZoom(zoomStep + 1));
dom.zoomOut.addEventListener('click', () => setZoom(zoomStep - 1));

let tipTimer = null;

/** Tap the map to name what's there: the nearest entity, or failing that the tile coordinates. */
dom.canvas.addEventListener('pointerdown', (event) => {
  if (!projection || !state) return;

  const rect = dom.canvas.getBoundingClientRect();
  const ratio = dom.canvas.width / rect.width;
  const cx = (event.clientX - rect.left) * ratio;
  const cy = (event.clientY - rect.top) * ratio;

  const tileX = mapData.originX + ((cx - projection.originX) / projection.scale) * projection.step;
  const tileY = mapData.originY + ((cy - projection.originY) / projection.scale) * projection.step;

  // generous in world tiles rather than pixels, so the radius feels the same at every zoom level
  const reach = 12 * projection.step;
  let best = null;
  let bestDistance = reach;

  for (const entity of state.entities || []) {
    const distance = Math.hypot(entity.x - tileX, entity.y - tileY);
    if (distance < bestDistance) {
      bestDistance = distance;
      best = entity;
    }
  }

  const playerDistance = Math.hypot(state.x - tileX, state.y - tileY);
  if (playerDistance < bestDistance) best = { kind: 'you', name: 'You' };

  showTip(best
    ? `${best.name} · ${best.kind}`
    : `${Math.round(tileX)}, ${Math.round(tileY)}`);
});

function showTip(text) {
  dom.mapTip.textContent = text;
  dom.mapTip.classList.remove('hidden');
  clearTimeout(tipTimer);
  tipTimer = setTimeout(() => dom.mapTip.classList.add('hidden'), 2500);
}

window.addEventListener('resize', () => {
  if (state) renderMap();
});

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

  for (const [key, label] of OPTIONS) {
    const row = document.createElement('label');

    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = !!settings[key];
    box.addEventListener('change', () => {
      settings[key] = box.checked;
      saveSettings();
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

  const item = (state && state.inventory) ? state.inventory[cursor] : null;
  const name = wikiNameFor(item);
  dom.wikiItem.disabled = !name;
  dom.wikiItem.textContent = name ? `Look up "${name}"` : 'Look up the selected item';

  buildSegmented(dom.settingsOutline, Object.keys(OUTLINES), 'outline', (v) => v);
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

for (const button of document.querySelectorAll('.tab'))
  button.addEventListener('click', () => setTab(button.dataset.tab));

dom.craftFilter.addEventListener('input', () => {
  craftFilterText = dom.craftFilter.value;
  renderCraft();
});

dom.gear.addEventListener('click', () => {
  buildSettingsPanel();
  dom.settings.classList.remove('hidden');
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

// keep the screen from doing browser-y things under a thumb
document.addEventListener('contextmenu', (event) => event.preventDefault());
document.addEventListener('dblclick', (event) => event.preventDefault());

loadSettings();
applySettings();
setZoom(0);
setTab('map');
renderLink();
poll();
pollSlow();
