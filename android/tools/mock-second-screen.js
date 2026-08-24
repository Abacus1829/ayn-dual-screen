#!/usr/bin/env node
/**
 * A stand-in for a game mod, so the app can be tested without a game.
 *
 * The app talks to a small HTTP server that a mod runs inside the game: it probes `/state` to work
 * out what is running, and then opens `/` full screen on the second panel. That means testing the
 * app normally requires Stardew Valley, SMAPI and the mod — three things that have nothing to do
 * with whichever part of the app is actually being tested.
 *
 * This serves the same two things from a PC:
 *
 *   /state   the snapshot the app probes. Season and locationName are what identify it as the
 *            Stardew mod (see Probe.identify), and `ready: true` is what makes it a loaded save
 *            rather than a main menu.
 *   /        a second-screen page that shows what it is and updates itself, so it is obvious at a
 *            glance whether the panel is live or frozen.
 *   /map     the endpoint the Stardew mod is recognised by when no save is loaded.
 *
 * Run it with:  node mock-second-screen.js [port]
 * Then enter this PC's address and that port in the app.
 *
 * Deliberately dependency-free and read-only: it serves three routes and holds no state worth
 * losing.
 */

const http = require('http');
const os = require('os');

const port = Number(process.argv[2]) || 27301;
const started = Date.now();

/** Wanders between locations so the page visibly changes on its own. */
const PLACES = ['Farm', 'Pelican Town', 'The Mines', 'Cindersap Forest', 'Beach'];

function snapshot() {
  const seconds = Math.floor((Date.now() - started) / 1000);
  return {
    ready: true,
    game: 'stardew',
    season: 'spring',
    day: 1 + Math.floor(seconds / 30),
    time: 600 + seconds * 10,
    locationName: PLACES[Math.floor(seconds / 10) % PLACES.length],
    player: { name: 'Mock', health: 100, stamina: 270 - (seconds % 200) },
    uptimeSeconds: seconds,
  };
}

const PAGE = `<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mock second screen</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
         background: #07080F; color: #EDF0F8; font: 16px system-ui, sans-serif; }
  .card { text-align: center; padding: 28px 34px; border: 1px solid #262C40; border-radius: 14px;
          background: #11141F; min-width: 260px; }
  h1 { margin: 0 0 4px; font-size: 20px; color: #6EC1FF; }
  p { margin: 2px 0; color: #8E97B4; font-size: 13px; }
  .big { font-size: 30px; font-weight: 700; margin: 14px 0 6px; }
  .beat { display: inline-block; width: 10px; height: 10px; border-radius: 50%;
          background: #4BE08B; animation: pulse 1s infinite ease-in-out; }
  @keyframes pulse { 50% { opacity: .25; } }
  button { font: inherit; margin-top: 16px; padding: 12px 18px; border-radius: 9px; color: #EDF0F8;
           background: #181C2B; border: 1px solid #3A4260; }
</style>
<div class="card">
  <h1>Mock second screen</h1>
  <p>Served from the PC, not from a game.</p>
  <div class="big" id="place">…</div>
  <p><span class="beat"></span> <span id="tick">live</span></p>
  <p id="detail"></p>
  <button id="tap">Tap test</button>
  <p id="taps">taps: 0</p>
</div>
<script>
  let taps = 0;
  document.getElementById('tap').addEventListener('click', () => {
    taps++;
    document.getElementById('taps').textContent = 'taps: ' + taps;
  });

  async function poll() {
    try {
      const state = await (await fetch('/state', { cache: 'no-store' })).json();
      document.getElementById('place').textContent = state.locationName;
      document.getElementById('tick').textContent = 'live · ' + state.uptimeSeconds + 's';
      document.getElementById('detail').textContent =
        state.season + ' day ' + state.day + ' · stamina ' + state.player.stamina;
    } catch (error) {
      document.getElementById('tick').textContent = 'lost the server';
    }
  }
  poll();
  setInterval(poll, 1000);
</script>`;

/** Who has asked for what, so "did the handheld reach the PC at all" is answerable rather than guessed. */
const seen = new Map();

http
  .createServer((request, response) => {
    const path = (request.url || '/').split('?')[0];

    const who = (request.socket.remoteAddress || '?').replace('::ffff:', '');
    const count = (seen.get(who) || 0) + 1;
    seen.set(who, count);
    // Every request from a new client, then one a second at most, so a polling page does not
    // drown the log it is supposed to be evidence in.
    if (count <= 3 || count % 20 === 0) {
      console.log(new Date().toISOString().slice(11, 19) + '  ' + who + '  ' + path + '  #' + count);
    }
    const send = (code, type, body) => {
      response.writeHead(code, {
        'Content-Type': type,
        'Cache-Control': 'no-store',
        'Access-Control-Allow-Origin': '*',
      });
      response.end(body);
    };

    if (path === '/state') return send(200, 'application/json', JSON.stringify(snapshot()));
    if (path === '/map') return send(200, 'application/json', JSON.stringify({ ok: true }));
    if (path === '/' || path === '/index.html') return send(200, 'text/html; charset=utf-8', PAGE);
    return send(404, 'text/plain', 'not found');
  })
  .listen(port, '0.0.0.0', () => {
    const addresses = Object.values(os.networkInterfaces())
      .flat()
      .filter((nic) => nic && nic.family === 'IPv4' && !nic.internal)
      .map((nic) => nic.address);

    console.log('mock second screen on port ' + port);
    console.log('enter one of these in the app:');
    addresses.forEach((address) => console.log('   ' + address + '   port ' + port));
  });
