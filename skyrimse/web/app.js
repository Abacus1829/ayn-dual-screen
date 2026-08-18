/*
  Ayn Dual Screen — Skyrim Special Edition
  The second screen.

  Polls /state, draws it, and posts taps back to /action. There is no framework here on purpose:
  the page has to start instantly on a handheld browser over Wi-Fi, and the whole thing is a few
  hundred lines of render-from-scratch.

  The one rule that shapes this file: the plugin is the authority on everything. The page never
  predicts what an action will do — it sends the command and waits for the next snapshot to show
  it. An inventory that updates optimistically and then snaps back is worse than one that takes
  100ms to catch up.
*/

'use strict';

// ── state ────────────────────────────────────────────────────────────────

let state = null;          // the last snapshot
let failures = 0;          // consecutive fetch failures, for the waiting screen's message
let modConfig = null;      // the mod's own settings, fetched once and after each change

const ui = {
	tab: 'map',
	itemCat: 'weapons',
	itemSort: 'name',
	itemSel: null,
	magicCat: 'spells',
	magicSel: null,
	questFilter: 'active',
	questSel: null,
	markerSel: null,
	mapZoom: 1,
	mapPan: { x: 0, y: 0 },
};

/* This screen's own settings, in this browser's storage rather than in the mod's ini — so the
   Thor's panel, a phone and a desktop monitor can each be laid out differently against the same
   game, without any of them fighting over one shared file. */
const prefs = Object.assign({
	rate: 10,
	scale: 16,
	detail: true,
	labels: true,
	hidden: [],
}, JSON.parse(localStorage.getItem('ayn-skyrim') || '{}'));

function savePrefs() {
	localStorage.setItem('ayn-skyrim', JSON.stringify(prefs));
	applyPrefs();
}

function applyPrefs() {
	document.documentElement.style.fontSize = prefs.scale + 'px';
	document.body.classList.toggle('no-detail', !prefs.detail);

	document.querySelectorAll('.tab').forEach(tab => {
		tab.hidden = prefs.hidden.includes(tab.dataset.tab);
	});

	// Never leave the page on a tab that was just hidden.
	if (prefs.hidden.includes(ui.tab)) {
		const first = document.querySelector('.tab:not([hidden])');
		if (first) showTab(first.dataset.tab);
	}
}

// ── small helpers ────────────────────────────────────────────────────────

const $ = id => document.getElementById(id);

function el(tag, className, text) {
	const node = document.createElement(tag);
	if (className) node.className = className;
	if (text !== undefined) node.textContent = text;
	return node;
}

const num = (v, dp = 0) => (v === null || v === undefined || isNaN(v)) ? '—' : Number(v).toFixed(dp);

/* Weight is the number people compare, and Skyrim prints it to two decimals. Value is whole
   septims. Neither is ever rounded to something prettier than the game's own answer. */
const weightText = w => (w === null || w === undefined) ? '—' : Number(w).toFixed(2);

function toast(message) {
	const node = $('toast');
	node.textContent = message;
	node.hidden = false;
	clearTimeout(toast.timer);
	toast.timer = setTimeout(() => { node.hidden = true; }, 2400);
}

/* A compass point as well as the number. A bearing is what directions are actually spoken in, and
   it stays readable when the arrow on the map is a few pixels across. */
function compass(deg) {
	const points = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
	return points[Math.round(deg / 45) % 8];
}

// ── talking to the plugin ────────────────────────────────────────────────

/* The token, if the ini sets one. Taken from this page's own URL so that opening
   http://pc:27305/?t=secret is all anybody has to do — including on a device with no keyboard
   worth typing a header into. */
const token = new URLSearchParams(location.search).get('t') || '';
const headers = token ? { 'x-ayn-token': token, 'Content-Type': 'application/json' }
                      : { 'Content-Type': 'application/json' };

async function poll() {
	try {
		const res = await fetch('state' + (token ? '?t=' + encodeURIComponent(token) : ''), { headers });
		if (res.status === 401) throw new Error('This screen needs the access token from the ini.');
		if (!res.ok) throw new Error('The mod answered ' + res.status + '.');

		state = await res.json();
		failures = 0;
		render();
	} catch (err) {
		failures++;
		// One dropped poll on Wi-Fi is normal and must not blank the screen. Three in a row is a
		// real disconnection and worth saying so.
		if (failures >= 3) {
			$('app').hidden = true;
			$('waiting').hidden = false;
			$('waitingError').textContent = failures > 6 ? err.message : '';
		}
	} finally {
		setTimeout(poll, Math.max(50, 1000 / prefs.rate));
	}
}

async function act(body) {
	try {
		const res = await fetch('action' + (token ? '?t=' + encodeURIComponent(token) : ''), {
			method: 'POST',
			headers,
			body: JSON.stringify(body),
		});
		const answer = await res.json();

		/* "ok" means queued, not done: the plugin applies it on the next game frame and re-checks
		   the permission there. So this says nothing about the outcome — the next snapshot does. */
		if (!answer.ok) toast('The mod would not take that.');
	} catch {
		toast('No answer from the game.');
	}
}

// ── render ───────────────────────────────────────────────────────────────

function render() {
	if (!state || !state.ready) {
		$('app').hidden = true;
		$('waiting').hidden = false;
		$('waitingError').textContent = '';
		return;
	}

	$('waiting').hidden = true;
	$('app').hidden = false;

	renderTop();
	renderVitals();

	switch (ui.tab) {
		case 'map':     renderMap();     break;
		case 'items':   renderItems();   break;
		case 'magic':   renderMagic();   break;
		case 'skills':  renderSkills();  break;
		case 'journal': renderJournal(); break;
		case 'status':  renderStatus();  break;
	}
}

function renderTop() {
	const p = state.player || {};
	$('playerName').textContent = p.name || '—';
	$('playerRace').textContent = p.race || '';
	$('playerLevel').textContent = p.level ?? '—';

	const t = state.time;
	if (t) {
		$('timeText').textContent = t.text || '--:--';
		$('dateText').textContent = t.dayName ? `${t.dayName}, ${t.day} ${t.monthName}` : '';

		// The dial is a 24-hour face: one turn a day, so noon is down and midnight is up. A 12-hour
		// face on a game clock is ambiguous exactly when it matters — vampires, and shops shutting.
		$('dialHand').setAttribute('transform', `rotate(${(t.hour / 24) * 360})`);
		document.body.classList.toggle('is-night', !!t.night);
	}
}

function renderVitals() {
	const p = state.player || {};
	const bar = (fill, text, value, max, label) => {
		const pct = max > 0 ? Math.max(0, Math.min(1, value / max)) * 100 : 0;
		$(fill).style.width = pct + '%';
		$(text).textContent = `${label} ${num(value)} / ${num(max)}`;
	};

	bar('hpFill', 'hpText', p.hp, p.hpMax, 'H');
	bar('mpFill', 'mpText', p.mp, p.mpMax, 'M');
	bar('spFill', 'spText', p.sp, p.spMax, 'S');
}

// ── items ────────────────────────────────────────────────────────────────

const ITEM_CATS = [
	['weapons', 'Weapons'], ['armor', 'Armor'], ['potions', 'Potions'],
	['scrolls', 'Scrolls'], ['food', 'Food'], ['ingredients', 'Ingredients'],
	['books', 'Books'], ['ammo', 'Ammo'], ['keys', 'Keys'], ['misc', 'Misc'],
];

function renderItems() {
	const inv = state.inventory || {};

	// The category rail, with counts — the thing SkyUI made standard and the vanilla menu never
	// had. An empty category stays in the list rather than disappearing, so the rail does not
	// reshuffle under your thumb every time you drink a potion.
	const rail = $('itemCats');
	rail.textContent = '';
	for (const [key, label] of ITEM_CATS) {
		const rows = inv[key] || [];
		const button = el('button', 'cat' + (ui.itemCat === key ? ' is-on' : '') + (rows.length ? '' : ' is-empty'));
		button.append(el('span', null, label), el('b', null, String(rows.length)));
		button.onclick = () => { ui.itemCat = key; ui.itemSel = null; render(); };
		rail.append(button);
	}

	const search = $('itemSearch').value.trim().toLowerCase();
	let rows = (inv[ui.itemCat] || []).slice();

	if (search) rows = rows.filter(r => (r.name || '').toLowerCase().includes(search));

	rows.sort((a, b) => {
		switch (ui.itemSort) {
			case 'weight': return (b.weight || 0) - (a.weight || 0);
			case 'value':  return (b.value || 0) - (a.value || 0);
			// Value per unit weight: the number that answers "what do I drop to get under the
			// cap", which is the only reason anyone opens an inventory at 300 items.
			case 'ratio':  return ((b.value || 0) / Math.max(0.01, b.weight || 0.01)) -
			                      ((a.value || 0) / Math.max(0.01, a.weight || 0.01));
			default:       return (a.name || '').localeCompare(b.name || '');
		}
	});

	document.querySelectorAll('#itemSorts .sort').forEach(s => {
		s.classList.toggle('is-on', s.dataset.sort === ui.itemSort);
	});

	const list = $('itemRows');
	list.textContent = '';
	for (const item of rows) {
		const row = el('li', 'row' + (ui.itemSel === item.id ? ' is-on' : ''));

		const name = el('span', 'row-name');
		if (item.equipped)  name.append(el('span', 'mark', '◆'));       // worn
		if (item.favorite)  name.append(el('span', 'mark', '★'));       // favourited
		if (item.enchanted) name.append(el('span', 'mark mark-ench', '✦'));
		if (item.stolen)    name.append(el('span', 'mark mark-stolen', '⚑'));
		name.append(document.createTextNode(item.name + (item.count > 1 ? ` (${item.count})` : '')));

		row.append(name,
			el('span', 'row-num', weightText(item.weight)),
			el('span', 'row-num', num(item.value)));

		row.onclick = () => { ui.itemSel = item.id; render(); };
		list.append(row);
	}

	const totalWeight = rows.reduce((sum, r) => sum + (r.weight || 0) * (r.count || 1), 0);
	const totalValue = rows.reduce((sum, r) => sum + (r.value || 0) * (r.count || 1), 0);
	$('itemCount').textContent = `${rows.length} ${rows.length === 1 ? 'entry' : 'entries'}`;
	$('itemTotals').textContent = `${weightText(totalWeight)} weight · ${num(totalValue)} septims`;

	renderItemDetail(rows.find(r => r.id === ui.itemSel));
}

function renderItemDetail(item) {
	const panel = $('itemDetail');
	panel.textContent = '';
	if (!item) return;

	const perms = state.perms || {};

	panel.append(el('h3', null, item.name));
	panel.append(el('p', 'sub', [
		item.slot, item.type, item.stolen ? 'stolen' : null,
	].filter(Boolean).join(' · ')));

	const dl = el('dl');
	const add = (term, value) => { dl.append(el('dt', null, term), el('dd', null, value)); };
	if (item.count > 1)          add('Count', String(item.count));
	if (item.damage != null)     add('Damage', num(item.damage));
	if (item.armorRating != null)add('Armor', num(item.armorRating));
	add('Weight', weightText(item.weight));
	add('Value', num(item.value));
	if (item.weight > 0)         add('Value / weight', num(item.value / item.weight, 1));
	panel.append(dl);

	if (item.desc) panel.append(el('p', 'desc', item.desc));

	const acts = el('div', 'acts');
	const button = (label, enabled, handler, danger) => {
		const b = el('button', 'act' + (danger ? ' act-danger' : ''), label);
		b.disabled = !enabled;
		if (!enabled) b.title = 'Switched off in AynDualScreen.ini';
		b.onclick = handler;
		acts.append(b);
	};

	const consumable = ['potions', 'food', 'scrolls', 'ingredients'].includes(ui.itemCat);
	const wearable = ['weapons', 'armor', 'ammo'].includes(ui.itemCat);

	if (consumable) button('Use', perms.use, () => act({ action: 'use', id: item.id }));

	if (wearable) {
		if (item.equipped) {
			button('Unequip', perms.equip, () => act({ action: 'unequip', id: item.id }));
		} else if (ui.itemCat === 'weapons') {
			// Two buttons rather than one, because which hand is a real decision in Skyrim and a
			// screen that picks for you gets it wrong half the time.
			button('Right hand', perms.equip, () => act({ action: 'equip', id: item.id, hand: 'right' }));
			button('Left hand', perms.equip, () => act({ action: 'equip', id: item.id, hand: 'left' }));
		} else {
			button('Equip', perms.equip, () => act({ action: 'equip', id: item.id }));
		}
	}

	button(item.favorite ? 'Unfavourite' : 'Favourite', perms.favorite,
		() => act({ action: 'favorite', id: item.id, on: !item.favorite }));

	button('Drop', perms.drop, () => {
		// Dropping is the one action here you cannot undo by tapping again, so it asks. The
		// plugin refuses it by default as well; this is the second lock, not the only one.
		if (confirm(`Drop ${item.name}?`)) act({ action: 'drop', id: item.id, count: 1 });
	}, true);

	panel.append(acts);
}

// ── magic ────────────────────────────────────────────────────────────────

const MAGIC_CATS = [['spells', 'Spells'], ['powers', 'Powers'], ['shouts', 'Shouts']];

function renderMagic() {
	const magic = state.magic || {};

	const rail = $('magicCats');
	rail.textContent = '';
	for (const [key, label] of MAGIC_CATS) {
		const rows = magic[key] || [];
		const button = el('button', 'cat' + (ui.magicCat === key ? ' is-on' : '') + (rows.length ? '' : ' is-empty'));
		button.append(el('span', null, label), el('b', null, String(rows.length)));
		button.onclick = () => { ui.magicCat = key; ui.magicSel = null; render(); };
		rail.append(button);
	}

	const search = $('magicSearch').value.trim().toLowerCase();
	let rows = (magic[ui.magicCat] || []).slice();
	if (search) rows = rows.filter(r => (r.name || '').toLowerCase().includes(search));

	// Spells group by school the way the game's own magic menu does; shouts and powers are short
	// enough lists to leave alphabetical.
	rows.sort((a, b) => (a.school || '').localeCompare(b.school || '') || a.name.localeCompare(b.name));

	const list = $('magicRows');
	list.textContent = '';
	for (const spell of rows) {
		const row = el('li', 'row' + (ui.magicSel === spell.id ? ' is-on' : ''));
		const name = el('span', 'row-name');
		if (spell.equipped) name.append(el('span', 'mark', '◆'));
		name.append(document.createTextNode(spell.name));

		row.append(name,
			el('span', 'row-num', spell.school || (spell.words ? `${spell.words.filter(w => w.unlocked).length}/3` : '')),
			el('span', 'row-num', spell.cost != null ? num(spell.cost) : ''));

		row.onclick = () => { ui.magicSel = spell.id; render(); };
		list.append(row);
	}

	$('magicCount').textContent = `${rows.length} known`;
	renderMagicDetail(rows.find(r => r.id === ui.magicSel));
}

function renderMagicDetail(spell) {
	const panel = $('magicDetail');
	panel.textContent = '';
	if (!spell) return;

	const perms = state.perms || {};

	panel.append(el('h3', null, spell.name));
	panel.append(el('p', 'sub', [spell.level, spell.school].filter(Boolean).join(' · ')));

	if (spell.cost != null) {
		const dl = el('dl');
		dl.append(el('dt', null, 'Magicka'), el('dd', null, num(spell.cost)));
		panel.append(dl);
	}

	if (spell.desc) panel.append(el('p', 'desc', spell.desc));

	// A shout's words are its progression, so they are the detail card's main content: which have
	// been found on a wall, and which have been paid for with a dragon soul. Those are different
	// things and the game shows them differently too.
	if (spell.words) {
		const list = el('div');
		for (const word of spell.words) {
			const line = el('div', 'effect' + (word.unlocked ? ' effect-buff' : ''));
			line.append(el('span', null, word.unlocked ? word.text
				: word.known ? word.text + ' — not yet unlocked'
				: '— undiscovered —'));
			list.append(line);
		}
		panel.append(list);
	}

	const acts = el('div', 'acts');
	const button = (label, enabled, handler) => {
		const b = el('button', 'act', label);
		b.disabled = !enabled;
		if (!enabled) b.title = 'Switched off in AynDualScreen.ini';
		b.onclick = handler;
		acts.append(b);
	};

	if (ui.magicCat === 'shouts') {
		button('Equip shout', perms.equipSpell, () => act({ action: 'equipShout', id: spell.id }));
	} else {
		button('Right hand', perms.equipSpell, () => act({ action: 'equipSpell', id: spell.id, hand: 'right' }));
		button('Left hand', perms.equipSpell, () => act({ action: 'equipSpell', id: spell.id, hand: 'left' }));
	}

	panel.append(acts);
}

// ── skills ───────────────────────────────────────────────────────────────

const SCHOOLS = [['combat', 'The Warrior'], ['magic', 'The Mage'], ['stealth', 'The Thief']];

function renderSkills() {
	const cols = $('skillCols');
	cols.textContent = '';

	for (const [key, label] of SCHOOLS) {
		const col = el('div', 'skill-col');
		col.append(el('h3', null, label));

		for (const skill of (state.skills || []).filter(s => s.school === key)) {
			const row = el('div', 'skill');
			row.append(el('span', null, skill.name));

			// Fortified and drained skills are coloured, because a number that is not your real
			// skill is worth noticing — especially when a potion is about to wear off mid-lockpick.
			const value = el('span', 'skill-val', String(skill.value));
			if (skill.value > skill.base) value.classList.add('is-up');
			if (skill.value < skill.base) value.classList.add('is-down');
			row.append(value);

			const track = el('div', 'skill-track');
			const bar = el('div', 'skill-bar');
			bar.style.width = ((skill.progress || 0) * 100).toFixed(1) + '%';
			track.append(bar);
			row.append(track);

			row.onclick = () => renderPerksFor(skill);
			col.append(row);
		}

		cols.append(col);
	}
}

function renderPerksFor(skill) {
	const panel = $('perkPanel');
	panel.textContent = '';
	panel.append(el('h3', null, skill.name));
	panel.append(el('p', 'sub', `${skill.value} — base ${skill.base}`));

	// The plugin sends the skill tree the perk actually hangs off, worked out by walking the
	// game's own perk trees — so this is an exact match, not a guess from the perk's name. A perk
	// belonging to no tree (a quest reward, or one a mod hands out directly) has an empty tree and
	// simply doesn't appear under any skill.
	const perks = (state.perks || []).filter(p => p.tree === skill.name);

	if (!perks.length) {
		panel.append(el('p', 'empty', 'No perks taken in this tree.'));
		return;
	}

	for (const perk of perks) {
		const card = el('div', 'effect effect-blessing');
		card.append(el('div', null, perk.name));
		if (perk.desc) card.append(el('div', 'time', perk.desc));
		panel.append(card);
	}
}

// ── journal ──────────────────────────────────────────────────────────────

function renderJournal() {
	document.querySelectorAll('#questFilters .sort').forEach(s => {
		s.classList.toggle('is-on', s.dataset.filter === ui.questFilter);
	});

	const search = $('questSearch').value.trim().toLowerCase();
	let rows = (state.quests || []).slice();

	if (ui.questFilter === 'active') rows = rows.filter(q => !q.completed);
	if (ui.questFilter === 'done')   rows = rows.filter(q => q.completed);
	if (search) rows = rows.filter(q => (q.name || '').toLowerCase().includes(search));

	rows.sort((a, b) => (b.active ? 1 : 0) - (a.active ? 1 : 0) || a.name.localeCompare(b.name));

	const list = $('questRows');
	list.textContent = '';
	for (const quest of rows) {
		const row = el('li', 'row' + (ui.questSel === quest.id ? ' is-on' : ''));
		const name = el('span', 'row-name');
		if (quest.active) name.append(el('span', 'mark', '◆'));
		name.append(document.createTextNode(quest.name));

		const done = (quest.objectives || []).filter(o => o.done).length;
		row.append(name,
			el('span', 'row-num', quest.type || ''),
			el('span', 'row-num', quest.objectives && quest.objectives.length ? `${done}/${quest.objectives.length}` : ''));

		row.onclick = () => { ui.questSel = quest.id; render(); };
		list.append(row);
	}

	$('questCount').textContent = `${rows.length} ${rows.length === 1 ? 'quest' : 'quests'}`;
	renderQuestDetail(rows.find(q => q.id === ui.questSel));
}

function renderQuestDetail(quest) {
	const panel = $('questDetail');
	panel.textContent = '';
	if (!quest) return;

	panel.append(el('h3', null, quest.name));
	panel.append(el('p', 'sub', [quest.type, quest.completed ? 'completed' : null].filter(Boolean).join(' · ')));

	if (!(quest.objectives || []).length) {
		panel.append(el('p', 'empty', 'No objectives shown for this one.'));
	} else {
		for (const objective of quest.objectives) {
			const line = el('div', 'effect' + (objective.done ? ' effect-buff' : ''));
			line.append(el('span', null, (objective.done ? '✓ ' : '○ ') + objective.text));
			panel.append(line);
		}
	}

	const acts = el('div', 'acts');
	const b = el('button', 'act', quest.active ? 'Already active' : 'Make active');
	b.disabled = !(state.perms || {}).setQuest || quest.active || quest.completed;
	b.onclick = () => act({ action: 'setQuest', id: quest.id });
	acts.append(b);
	panel.append(acts);
}

// ── status ───────────────────────────────────────────────────────────────

function renderStatus() {
	const p = state.player || {};
	const grid = $('statusGrid');
	grid.textContent = '';

	const card = (title, pairs) => {
		const node = el('div', 'card');
		node.append(el('h3', null, title));
		const dl = el('dl');
		for (const [term, value] of pairs) {
			if (value === null || value === undefined) continue;
			dl.append(el('dt', null, term), el('dd', null, String(value)));
		}
		node.append(dl);
		grid.append(node);
	};

	card('Character', [
		['Race', p.race],
		['Level', p.level],
		['To next level', p.xpMax ? `${num(p.xp)} / ${num(p.xpMax)}` : null],
		['Beast form', p.beast && p.beast !== 'none' ? p.beast : null],
	]);

	card('Combat', [
		['Armor rating', num(p.armorRating)],
		['Weapon damage', p.damage != null ? num(p.damage) : 'unarmed'],
		['In combat', p.inCombat ? 'yes' : 'no'],
		['Sneaking', p.sneaking ? 'yes' : 'no'],
	]);

	card('Carrying', [
		['Weight', `${weightText(p.weight)} / ${weightText(p.weightMax)}`],
		['Over the cap', p.overEncumbered ? 'yes' : 'no'],
		['Gold', num(p.gold)],
	]);

	if (state.time) {
		card('The calendar', [
			['Time', state.time.text],
			['Date', `${state.time.day} ${state.time.monthName} ${state.time.year}`],
			['Day', state.time.dayName],
			['Days played', num(state.time.daysPassed, 1)],
		]);
	}

	const panel = $('effectPanel');
	panel.textContent = '';
	panel.append(el('h3', null, 'Active effects'));

	const effects = state.effects || [];
	if (!effects.length) {
		panel.append(el('p', 'empty', 'Nothing is acting on you.'));
		return;
	}

	for (const effect of effects) {
		const node = el('div', 'effect effect-' + (effect.kind || 'buff'));
		node.append(el('div', null, effect.name));
		if (effect.duration > 0) {
			const seconds = Math.round(effect.duration);
			node.append(el('div', 'time', seconds > 90 ? `${Math.round(seconds / 60)} min left` : `${seconds}s left`));
		}
		panel.append(node);
	}
}

// ── map ──────────────────────────────────────────────────────────────────

/* The map is drawn, not photographed. Skyrim's world map is a rendered 3D scene — there is no
   image file to serve — so the page lays the markers out in the worldspace's own coordinates and
   puts you on top of them. It is a chart, and the page says so rather than passing it off as the
   game's map. */
function renderMap() {
	const map = state.map || {};
	const svg = $('mapSvg');

	$('whereCell').textContent = map.cell || '—';
	$('whereWorld').textContent = map.world || '';
	$('bearingText').textContent = map.angle != null
		? `Facing ${compass(map.angle)} · ${num(map.angle)}°` : '';

	$('mapEmpty').hidden = !map.interior;
	$('mapCellName').textContent = map.interior ? (map.cell || '') : '';
	svg.style.opacity = map.interior ? 0.25 : 1;

	const bounds = map.worldBounds;
	if (!bounds) return;

	const width = Math.max(1, bounds.maxX - bounds.minX);
	const height = Math.max(1, bounds.maxY - bounds.minY);

	// World units to the 1000×1000 viewBox. Y is flipped because world Y grows north and SVG Y
	// grows down — getting this backwards puts Riften in the Sea of Ghosts, which is how it was
	// found.
	const px = x => ((x - bounds.minX) / width) * 1000;
	const py = y => 1000 - ((y - bounds.minY) / height) * 1000;

	const layer = $('mapMarkers');
	layer.textContent = '';

	const markers = (map.markers || []).slice();
	for (const marker of markers) {
		const group = document.createElementNS('http://www.w3.org/2000/svg', 'g');
		group.setAttribute('class', 'marker' +
			(marker.visited ? ' is-visited' : '') +
			(ui.markerSel === marker.id ? ' is-on' : ''));
		group.setAttribute('transform', `translate(${px(marker.x)},${py(marker.y)})`);

		const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
		dot.setAttribute('class', 'marker-dot');
		dot.setAttribute('r', ui.markerSel === marker.id ? 6 : 4);
		group.append(dot);

		// Labels only on what you have been to, and only when there is room. Every name at once on
		// a handheld panel is an unreadable smear.
		if (prefs.labels && (marker.visited || ui.markerSel === marker.id)) {
			const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
			label.setAttribute('class', 'marker-label');
			label.setAttribute('x', 8);
			label.setAttribute('y', 4);
			label.textContent = marker.name;
			group.append(label);
		}

		group.onclick = () => { ui.markerSel = marker.id; render(); };
		layer.append(group);
	}

	const you = $('mapPlayer');
	if (map.interior) {
		you.setAttribute('opacity', 0.3);
	} else {
		you.setAttribute('opacity', 1);
		you.setAttribute('transform',
			`translate(${px(map.x)},${py(map.y)}) rotate(${map.angle || 0})`);
	}

	renderMarkerList(markers, map);
}

function renderMarkerList(markers, map) {
	const search = $('markerSearch').value.trim().toLowerCase();
	let rows = markers.filter(m => !search || m.name.toLowerCase().includes(search));

	// Nearest first: on a map you are looking at because you are lost, distance is the sort that
	// answers the question.
	if (!map.interior) {
		rows.sort((a, b) => Math.hypot(a.x - map.x, a.y - map.y) - Math.hypot(b.x - map.x, b.y - map.y));
	}

	const list = $('markerList');
	list.textContent = '';

	for (const marker of rows.slice(0, 120)) {
		const li = el('li');
		li.append(el('span', null, marker.name));

		// Distance in metres, which is what Skyrim's units convert to at about 70 per metre. It is
		// a rough figure and reads as one — nobody needs a survey, they need "far or near".
		if (!map.interior) {
			const metres = Math.hypot(marker.x - map.x, marker.y - map.y) / 70;
			li.append(el('span', 'far', metres > 1000 ? `${(metres / 1000).toFixed(1)} km` : `${Math.round(metres)} m`));
		}

		li.onclick = () => {
			ui.markerSel = marker.id;
			if ((state.perms || {}).fastTravel && marker.canFastTravel) {
				if (confirm(`Travel to ${marker.name}?`)) act({ action: 'fastTravel', id: marker.id });
			}
			render();
		};
		list.append(li);
	}
}

// ── settings sheet ───────────────────────────────────────────────────────

function buildSettings() {
	const rows = $('settingsRows');
	rows.textContent = '';

	const choose = (label, values, current, onPick, format = String) => {
		const row = el('div', 'setting');
		row.append(el('span', null, label));
		const group = el('div', 'setting-choices');
		for (const value of values) {
			const button = el('button', 'choice' + (value === current ? ' is-on' : ''), format(value));
			button.onclick = () => { onPick(value); buildSettings(); };
			group.append(button);
		}
		row.append(group);
		rows.append(row);
	};

	choose('Updates / sec', [5, 10, 15, 20], prefs.rate, v => { prefs.rate = v; savePrefs(); });
	choose('Size', [13, 15, 16, 18, 21], prefs.scale, v => { prefs.scale = v; savePrefs(); },
		v => ({ 13: 'XS', 15: 'S', 16: 'M', 18: 'L', 21: 'XL' })[v]);
	choose('Detail panel', [true, false], prefs.detail, v => { prefs.detail = v; savePrefs(); },
		v => v ? 'Show' : 'Hide');
	choose('Map labels', [true, false], prefs.labels, v => { prefs.labels = v; savePrefs(); },
		v => v ? 'On' : 'Off');

	// Hiding tabs is per-screen too: a phone clipped to a desk does not need the same six as the
	// handheld panel in your other hand.
	const row = el('div', 'setting');
	row.append(el('span', null, 'Tabs'));
	const group = el('div', 'setting-choices');
	for (const tab of document.querySelectorAll('.tab')) {
		const key = tab.dataset.tab;
		const on = !prefs.hidden.includes(key);
		const button = el('button', 'choice' + (on ? ' is-on' : ''), tab.textContent);
		button.onclick = () => {
			prefs.hidden = on ? prefs.hidden.concat(key) : prefs.hidden.filter(h => h !== key);
			savePrefs();
			buildSettings();
		};
		group.append(button);
	}
	row.append(group);
	rows.append(row);

	buildModSettings();
}

/* The mod's own settings, which live in the ini and are shared by every screen. Kept visually
   separate from this screen's because the consequences are different: one changes a layout, the
   other changes what any device on the network is allowed to do to your save. */
async function buildModSettings() {
	const rows = $('modSettingsRows');
	rows.textContent = '';

	if (!modConfig) {
		try {
			const res = await fetch('config' + (token ? '?t=' + encodeURIComponent(token) : ''), { headers });
			modConfig = await res.json();
		} catch {
			rows.append(el('p', 'dim', 'Could not read the mod settings.'));
			return;
		}
	}

	const toggles = ['AllowEquip', 'AllowUse', 'AllowFavorite', 'AllowSetQuest',
		'AllowDrop', 'AllowFastTravel', 'AllowWait', 'EnableDescriptions'];

	for (const key of toggles) {
		const row = el('div', 'setting');
		row.append(el('span', null, key.replace(/^(Allow|Enable)/, '$1 ').replace(/([a-z])([A-Z])/g, '$1 $2')));

		const group = el('div', 'setting-choices');
		for (const value of [true, false]) {
			const button = el('button', 'choice' + (!!modConfig[key] === value ? ' is-on' : ''), value ? 'On' : 'Off');
			button.onclick = async () => {
				await fetch('config' + (token ? '?t=' + encodeURIComponent(token) : ''), {
					method: 'POST', headers,
					body: JSON.stringify({ key, value: value ? '1' : '0' }),
				});
				modConfig = null;
				buildModSettings();
			};
			group.append(button);
		}
		row.append(group);
		rows.append(row);
	}
}

// ── wiring ───────────────────────────────────────────────────────────────

function showTab(name) {
	ui.tab = name;
	document.querySelectorAll('.tab').forEach(t => t.classList.toggle('is-on', t.dataset.tab === name));
	document.querySelectorAll('.page').forEach(p => p.classList.toggle('is-on', p.dataset.page === name));
	render();
}

document.querySelectorAll('.tab').forEach(tab => {
	tab.onclick = () => showTab(tab.dataset.tab);
});

document.querySelectorAll('#itemSorts .sort').forEach(sort => {
	sort.onclick = () => { ui.itemSort = sort.dataset.sort; render(); };
});

document.querySelectorAll('#questFilters .sort').forEach(filter => {
	filter.onclick = () => { ui.questFilter = filter.dataset.filter; render(); };
});

for (const id of ['itemSearch', 'magicSearch', 'questSearch', 'markerSearch']) {
	$(id).oninput = () => render();
}

$('gearButton').onclick = () => { buildSettings(); $('settingsSheet').hidden = false; };
$('settingsClose').onclick = () => { $('settingsSheet').hidden = true; };

// Number keys jump between tabs, the way the Fallout mod's do. A handheld with a d-pad and no
// touch is a real way people use this.
document.addEventListener('keydown', event => {
	if (event.target.tagName === 'INPUT') return;
	const tabs = [...document.querySelectorAll('.tab:not([hidden])')];
	const index = parseInt(event.key, 10) - 1;
	if (index >= 0 && index < tabs.length) showTab(tabs[index].dataset.tab);
});

applyPrefs();
showTab('map');
poll();
