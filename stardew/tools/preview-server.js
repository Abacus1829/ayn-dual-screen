#!/usr/bin/env node
/**
 * Serves the second-screen page with a canned save, so its layout can be looked at without the game.
 *
 * The page is static files plus one polled endpoint, which means the only thing standing between a
 * developer and "does this fit on the handheld" is a running copy of Stardew Valley. That is a long
 * way to go to check a stylesheet, and it is why a layout bug reached a real device: the panel it is
 * for is 1240x1080 and nothing anybody tests on is that shape.
 *
 * This serves `web/` and answers `/state` with a plausible snapshot. Point a browser at it and size
 * the window to the panel you care about:
 *
 *     node preview-server.js            # then open http://localhost:27311
 *     node preview-server.js 27311
 *
 * It is a development tool. It ships in the repository and never in the mod zip.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const port = Number(process.argv[2]) || 27311;
const root = path.join(__dirname, '..', 'web');

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.png': 'image/png',
  '.json': 'application/json',
};

/** Enough of a save to lay the page out: a full hotbar, a part-full backpack, skills, quests. */
function snapshot() {
  const seconds = Math.floor(Date.now() / 1000);

  const item = (index, name, stack, category, edible = false) => ({
    index, name, stack, quality: 0, category, iconKey: '', edible,
  });

  const inventory = [
    item(0, 'Axe', 1, 'Tool'),
    item(1, 'Pickaxe', 1, 'Tool'),
    Object.assign(item(2, 'Watering Can', 1, 'Tool'), { water: 17, waterMax: 40 }),
    item(3, 'Hoe', 1, 'Tool'),
    item(4, 'Scythe', 1, 'Tool'),
    item(5, 'Parsnip Seeds', 15, 'Seed'),
    item(6, 'Stone', 87, 'Resource'),
    item(7, 'Wood', 143, 'Resource'),
    item(8, 'Fiber', 22, 'Resource'),
    item(9, 'Salmonberry', 12, 'Forage', true),
    item(10, 'Stone Floor', 10, 'Decor'),
    item(11, 'Copper Ore', 33, 'Resource'),
    item(12, 'Cave Carrot', 6, 'Forage', true),
    item(13, 'Bomb', 3, 'Crafting'),
    item(14, 'Gold Bar', 24, 'Resource'),
    Object.assign(item(15, 'Wooden Blade', 1, 'Weapon'), { cooldown: 900, cooldownMax: 3000 }),
    item(16, 'Cheese', 4, 'Cooking', true),
    item(17, 'Wild Horseradish', 9, 'Forage', true),
  ];

  // Two full rows plus a bit, so the backpack rows and the L/R control have something to show.
  while (inventory.length < 24) inventory.push({ index: inventory.length, name: '', stack: 0, quality: 0, category: '', iconKey: '', edible: false });

  return {
    ready: true,
    tick: seconds,
    locationId: 'Farm',
    locationName: 'Ram Ranch Farm',
    mapRev: 1,
    menuLuma: 200,
    worldRev: 1,
    timeOfDay: 620,
    dayOfMonth: 6,
    dayOfWeek: 'Sat',
    season: 'fall',
    year: 1,
    weather: 'Windy',
    money: 59336,
    stamina: 270,
    maxStamina: 270,
    health: 100,
    maxHealth: 100,
    x: 64,
    y: 15,
    facing: 2,
    selectedSlot: 0,
    hotbarSize: 12,
    weatherTomorrow: 'Sunny',
    dailyLuck: 0.02,
    birthdays: ['Abigail'],
    festival: '',
    cartToday: false,
    backpackRows: 2,
    shipping: { count: 14, value: 2380 },
    can: { move: true, use: true, menu: true, edit: true, drop: true, trash: true, eat: true },
    inventory,
    entities: [],
    quests: [
      { id: 1, name: 'Getting Started', objective: 'Plant 15 parsnip seeds', daysLeft: -1, complete: false, rewardGold: 0, reward: '', cancellable: false },
      { id: 2, name: 'Raising Animals', objective: 'Build a coop', daysLeft: -1, complete: false, rewardGold: 0, reward: '', cancellable: false },
      { id: 3, name: 'Robin\u2019s Lost Axe', objective: 'Find the axe in Cindersap Forest', daysLeft: 2, complete: false, rewardGold: 250, reward: '', cancellable: true },
      { id: 4, name: 'Pam Is Thirsty', objective: 'Give Pam a Pale Ale', daysLeft: 1, complete: false, rewardGold: 350, reward: '1 friendship heart', cancellable: true },
      { id: 5, name: 'Jodi\u2019s Request', objective: 'Bring Jodi a Cauliflower', daysLeft: -1, complete: true, rewardGold: 350, reward: '', cancellable: true },
    ],
    skills: { farming: 10, mining: 5, foraging: 9, fishing: 10, combat: 4 },
  };
}

http
  .createServer((request, response) => {
    const url = (request.url || '/').split('?')[0];

    const send = (code, type, body) => {
      response.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
      response.end(body);
    };

    if (url === '/state') return send(200, 'application/json', JSON.stringify(snapshot()));

    // Endpoints the page may poll that need an answer rather than a 404 in the console.
    // A bare array, which is what the mod serves: ModEntry keeps VillagerJson as a serialised list.
    // Wrapping it in an object here made the page render 'No villagers found' against a healthy
    // endpoint -- a good reminder that a mock is only worth what its shape is.
    if (url === '/villagers') return send(200, 'application/json', JSON.stringify([
      { name: 'Robin', location: 'Mountain', x: 20, y: 12, hearts: 6, maxHearts: 10, birthday: false, talked: true, here: false },
      { name: 'Abigail', location: 'SeedShop', x: 8, y: 9, hearts: 4, maxHearts: 10, birthday: true, talked: false, here: false },
      { name: 'Alex', location: 'Town', x: 30, y: 40, hearts: 2, maxHearts: 10, birthday: false, talked: false, here: false },
      { name: 'Caroline', location: 'SeedShop', x: 6, y: 11, hearts: 3, maxHearts: 10, birthday: false, talked: false, here: false },
      { name: 'Clint', location: 'Blacksmith', x: 12, y: 14, hearts: 1, maxHearts: 10, birthday: false, talked: false, here: false },
      { name: 'Demetrius', location: 'Mountain', x: 22, y: 10, hearts: 5, maxHearts: 10, birthday: false, talked: true, here: false }
    ]));
    if (url === '/community') return send(200, 'application/json', JSON.stringify({
      available: true, complete: false, bundlesDone: 3, bundlesTotal: 11,
      rooms: [
        {
          name: 'Pantry', complete: false, done: 2, total: 6,
          bundles: [
            { name: 'Spring Crops', complete: false, have: 2, need: 4, missing: [
              { name: 'Cauliflower', count: 1, quality: 0, iconKey: '' },
              { name: 'Potato', count: 1, quality: 0, iconKey: '' }
            ] },
            { name: 'Quality Crops', complete: false, have: 0, need: 3, missing: [
              { name: 'Parsnip', count: 5, quality: 2, iconKey: '' },
              { name: 'Melon', count: 5, quality: 2, iconKey: '' },
              { name: 'Pumpkin', count: 5, quality: 2, iconKey: '' }
            ] },
            { name: 'Animal', complete: true, have: 5, need: 5, missing: [] }
          ]
        },
        {
          name: 'Fish Tank', complete: false, done: 9, total: 19,
          bundles: [
            { name: 'River Fish', complete: false, have: 3, need: 4, missing: [
              { name: 'Shad', count: 1, quality: 0, iconKey: '' }
            ] },
            { name: 'Lake Fish', complete: false, have: 1, need: 4, missing: [
              { name: 'Largemouth Bass', count: 1, quality: 0, iconKey: '' },
              { name: 'Carp', count: 1, quality: 0, iconKey: '' },
              { name: 'Sturgeon', count: 1, quality: 0, iconKey: '' }
            ] }
          ]
        },
        { name: 'Crafts Room', complete: true, done: 6, total: 6, bundles: [] }
      ]
    }));

    if (url === '/farm') return send(200, 'application/json', JSON.stringify({
      machinesReady: 3, animalsUnpetted: 2, produceWaiting: 2, fruitWaiting: 5,
      machines: [
        { name: 'Keg', location: 'Farm', produce: 'Pale Ale', iconKey: '', ready: true, minutesLeft: -1 },
        { name: 'Keg', location: 'Farm', produce: 'Pale Ale', iconKey: '', ready: true, minutesLeft: -1 },
        { name: 'Preserves Jar', location: 'Farm', produce: 'Pickled Beet', iconKey: '', ready: true, minutesLeft: -1 },
        { name: 'Furnace', location: 'Farm', produce: 'Copper Bar', iconKey: '', ready: false, minutesLeft: 30 },
        { name: 'Cask', location: 'Cellar', produce: 'Wine', iconKey: '', ready: false, minutesLeft: 1440 },
        { name: 'Bee House', location: 'Farm', produce: 'Honey', iconKey: '', ready: false, minutesLeft: 620 },
        { name: 'Crab Pot', location: 'Beach', produce: 'Lobster', iconKey: '', ready: false, minutesLeft: 95 }
      ],
      animals: [
        { name: 'Bessie', type: 'Cow', building: 'Deluxe Barn', friendship: 780, pet: true, fed: true, produce: 'Milk', iconKey: '' },
        { name: 'Clucky', type: 'Chicken', building: 'Big Coop', friendship: 420, pet: false, fed: true, produce: 'Large Egg', iconKey: '' },
        { name: 'Wooly', type: 'Sheep', building: 'Deluxe Barn', friendship: 210, pet: false, fed: false, produce: null, iconKey: null },
        { name: 'Nibbles', type: 'Goat', building: 'Deluxe Barn', friendship: 640, pet: true, fed: true, produce: null, iconKey: null }
      ],
      trees: [
        { name: 'Apple', location: 'Farm', fruit: 3, daysToMature: 0, iconKey: '' },
        { name: 'Peach', location: 'Greenhouse', fruit: 2, daysToMature: 0, iconKey: '' },
        { name: 'Banana', location: 'Island West', fruit: 0, daysToMature: 12, iconKey: '' }
      ]
    }));

    if (url === '/calendar') {
      const festivals = { 16: 'Stardew Valley Fair', 27: 'Spirit\u2019s Eve' };
      const birthdays = { 4: ['Penny'], 11: ['Marnie'], 15: ['Abigail'], 18: ['Sandy'], 21: ['Elliott'], 26: ['Jodi'] };
      const days = [];
      for (let day = 1; day <= 28; day++) {
        days.push({
          day,
          today: day === 6,
          past: day < 6,
          cart: day % 7 === 5 || day % 7 === 0,
          festival: festivals[day] || null,
          birthdays: birthdays[day] || [],
          portraits: (birthdays[day] || []).map(() => '')
        });
      }
      return send(200, 'application/json', JSON.stringify({ season: 'fall', year: 1, today: 6, days }));
    }
    if (url === '/codes') return send(404, 'text/plain', 'not found');

    const file = url === '/' ? 'index.html' : url.replace(/^\/+/, '');
    const target = path.join(root, path.normalize(file).replace(/^(\.\.[\\/])+/, ''));

    fs.readFile(target, (error, data) => {
      if (error) return send(404, 'text/plain', 'not found: ' + file);
      send(200, TYPES[path.extname(target).toLowerCase()] || 'application/octet-stream', data);
    });
  })
  .listen(port, '0.0.0.0', () => {
    console.log('second screen preview on http://localhost:' + port);
    console.log('serving ' + root);
    console.log('');
    console.log("the shapes worth checking, because they are the ones that broke:");
    console.log('  1240 x 1080   the AYN Thor lower panel — nearly square');
    console.log('  1023 x  678   a desktop browser, where it already looked right');
  });
