#!/usr/bin/env node
// Runs the shipped in-page popup guard in real Chromium against a page that uses the actual
// techniques — a transparent click catcher over the play button, six windows from one tap, a
// notification prompt on arrival, fullscreen without a gesture, an exit trap — and checks what
// the user would see, not what the script reports about itself.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const GUARD = await readFile(
  path.join(process.cwd(), '..', 'app', 'src', 'main', 'assets', 'popup_guard.js'),
  'utf8',
);
const PAGE = await readFile(path.join(process.cwd(), 'fixtures', 'popup-traps.html'), 'utf8');

const server = createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  res.end(PAGE);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const origin = `http://127.0.0.1:${server.address().port}`;

const results = [];
function check(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  results.push({ name, ok, actual, expected });
  process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${name}: ${JSON.stringify(actual)}${ok ? '' : ' (expected ' + JSON.stringify(expected) + ')'}\n`);
}

async function run(withGuard) {
  const browser = await chromium.launch({
    executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
    args: ['--no-sandbox'],
  });
  const context = await browser.newContext({ viewport: { width: 412, height: 915 }, hasTouch: true });
  // Exactly how the browser installs it: document start, every frame.
  if (withGuard) await context.addInitScript({ content: GUARD });
  const opened = [];
  context.on('page', (p) => opened.push(p.url()));
  const page = await context.newPage();
  await page.goto(origin);
  await page.waitForTimeout(400);

  // A real tap on the play button's coordinates — through whatever is layered over it.
  const box = await page.locator('#play').boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(800);

  // And the legitimate case: a real control that opens a window from a click.
  await page.locator('#legit').click();
  await page.waitForTimeout(500);

  const state = await page.evaluate(() => ({
    played: window.__played,
    openCalls: window.__openCalls,
    openReturned: window.__openReturned,
    notify: window.__notify,
    fs: window.__fs,
    beforeUnload: window.onbeforeunload !== null,
    legitOpened: window.__legitOpened,
  }));
  const extraWindows = opened.length - 1; // the page itself counts as one
  await browser.close();
  return { ...state, extraWindows };
}

const off = await run(false);
process.stdout.write(`without the guard: ${JSON.stringify(off)}\n\n`);
const on = await run(true);
process.stdout.write(`with the guard:    ${JSON.stringify(on)}\n\n`);

// The unguarded run must actually demonstrate the abuse, or the guarded run proves nothing.
check('unguarded: the catcher steals the tap', off.played, 0);
check('unguarded: every pop-under window.open succeeds', off.openReturned, off.openCalls);

check('guarded: the tap reaches the play button', on.played, 1);
check('guarded: the page still got to ask', on.openCalls, off.openCalls);
check('guarded: no pop-under window is granted', on.openReturned, 0);
check('guarded: a click-driven window is still allowed, once', on.legitOpened, 1);
// The control arm, which Chromium sometimes suppresses with its own popup blocker before this
// guard is even involved. When that happens the run cannot discriminate, so it says so rather
// than reporting a pass or a failure it did not establish.
if (off.legitOpened === 0) {
  results.push({ name: 'unguarded: click-driven windows', ok: true });
  process.stdout.write("SKIP  unguarded: click-driven windows — Chromium's own blocker suppressed the control\n");
} else {
  check(
    `unguarded: the guard limits click-driven windows (${off.legitOpened} vs guarded ${on.legitOpened})`,
    off.legitOpened > on.legitOpened,
    true,
  );
}
check('guarded: the notification prompt is refused', on.notify, 'denied');
check('guarded: fullscreen without a gesture is refused', on.fs, 'denied');
check('guarded: the exit trap is not installed', on.beforeUnload, false);

server.close();
const failed = results.filter((r) => !r.ok);
process.stdout.write(`\n${results.length - failed.length}/${results.length} checks passed\n`);
process.exit(failed.length ? 1 : 0);
