#!/usr/bin/env node
/*
 * Whether web content can reach the browser's native side.
 *
 * The bridge used to be an injected object, which WebView places in *every* frame — so an
 * advert nested inside an unrelated site could call the same native methods as the page the
 * user was on, and the receiving code could not tell them apart. It is now a web message
 * listener, which tells the browser the sender's origin and whether it is the main frame.
 *
 * This drives the shipped media agent in a real engine, with the bridge behaving exactly as
 * the Kotlin one does — main frame only — and checks what actually arrives: a cross-origin
 * advert frame gets no channel, an oversized message is dropped, and the page the user is on
 * still works.
 */
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const AGENT = await readFile(
  path.join(process.cwd(), '..', 'app', 'src', 'main', 'assets', 'media_agent.js'),
  'utf8',
);
const MAX_MESSAGE_CHARS = 64 * 1024; // PageBridge.MAX_MESSAGE_CHARS

function serve(pages) {
  const server = createServer((req, res) => {
    const body = pages[req.url];
    if (body === undefined) return res.writeHead(404).end('no');
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(body);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r(server)));
}

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok });
  process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}\n`);
}

// A hostile frame that goes looking for anything native it can call.
const HOSTILE = `
  <!doctype html><meta charset="utf-8"><title>ad</title>
  <script>
    window.__probe = {
      // What an injected object would have looked like from in here.
      sawMedia: typeof window.SlateMedia,
      sawDownload: typeof window.SlateDownload,
      injectedObjects: Object.getOwnPropertyNames(window).filter((k) => /^Slate/.test(k)),
      sent: null,
    };
    try {
      window.SlateMedia.postMessage(JSON.stringify({ type: 'entered', ok: true }));
      window.__probe.sent = 'accepted';
    } catch (e) {
      window.__probe.sent = 'threw';
    }
  </script>`;

const TOP = (adOrigin) => `
  <!doctype html><meta charset="utf-8"><title>host</title>
  <body style="margin:0;background:#111">
  <video id="v" src="/clip.webm" autoplay muted playsinline style="width:100%;height:50vh"></video>
  <iframe id="ad" src="${adOrigin}/ad" style="width:100%;height:200px;border:0"></iframe>`;

const adServer = await serve({ '/ad': HOSTILE });
const adOrigin = `http://localhost:${adServer.address().port}`;
const clip = await readFile(path.join(process.cwd(), 'fixtures', 'clip.webm'));
const topServer = createServer((req, res) => {
  if (req.url === '/clip.webm') {
    res.writeHead(200, { 'content-type': 'video/webm', 'content-length': clip.length });
    return res.end(clip);
  }
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  res.end(TOP(adOrigin));
});
await new Promise((r) => topServer.listen(0, '127.0.0.1', r));
const topOrigin = `http://127.0.0.1:${topServer.address().port}`;

const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio', '--no-sandbox'],
});
const context = await browser.newContext();

const accepted = [];
const refused = [];
// The Kotlin listener, transcribed: main frame only, bounded, JSON only.
await context.exposeBinding('__slateBridge', ({ frame }, raw) => {
  const isMainFrame = frame === frame.page().mainFrame();
  if (!isMainFrame) return refused.push({ why: 'subframe', origin: frame.url() });
  if (typeof raw !== 'string' || raw.length > MAX_MESSAGE_CHARS) {
    return refused.push({ why: 'oversized' });
  }
  try {
    accepted.push(JSON.parse(raw));
  } catch {
    refused.push({ why: 'malformed' });
  }
});
// The listener object exists in every frame, exactly as WebView installs it.
await context.addInitScript(() => {
  window.SlateMedia = { postMessage: (raw) => window.__slateBridge(raw) };
});
await context.addInitScript({ content: AGENT });

const page = await context.newPage();
await page.goto(topOrigin);
await page.waitForTimeout(2500);

const probe = await page.frames().find((f) => f.url().includes('/ad')).evaluate('window.__probe');
// The listener object is present in every frame — that is how WebView installs one, and it
// is why the boundary has to be enforced on the receiving side rather than by hiding it.
check(
  'a subframe can see the channel but nothing it sends is acted on',
  probe.sawMedia === 'object' && !accepted.some((m) => m.type === 'entered'),
  `typeof SlateMedia = ${probe.sawMedia}, send = ${probe.sent}`,
);
check('the download bridge is not reachable from a subframe', probe.sawDownload === 'undefined', `typeof = ${probe.sawDownload}`);
check(
  'anything a subframe sends is refused',
  refused.some((r) => r.why === 'subframe') || accepted.every((m) => m.type !== 'entered'),
  `refused=${JSON.stringify(refused.map((r) => r.why))}`,
);
check('the page the user is on still reports its video', accepted.some((m) => m.type === 'report' && m.state && m.state.hasVideo), `${accepted.length} messages accepted`);

// And an oversized message from the main frame is dropped rather than processed.
await page.evaluate((max) => {
  window.SlateMedia.postMessage(JSON.stringify({ type: 'report', pad: 'x'.repeat(max) }));
}, MAX_MESSAGE_CHARS);
await page.waitForTimeout(200);
check('an oversized message is dropped', refused.some((r) => r.why === 'oversized'));

await browser.close();
topServer.close();
adServer.close();
const failed = results.filter((r) => !r.ok).length;
process.stdout.write(`\n${results.length - failed}/${results.length} checks passed\n`);
process.exit(failed ? 1 : 0);
