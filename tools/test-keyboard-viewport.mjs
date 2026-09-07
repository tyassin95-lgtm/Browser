#!/usr/bin/env node
/*
 * What the browser's layout change is for: when the keyboard takes part of the window, the
 * page has to be given the smaller space so the engine can bring the focused field into view.
 *
 * The browser's own half — subtracting the IME inset from the page and the toolbar — is
 * covered by LayoutInsetsTest, which dispatches real window insets and measures the result.
 * This covers the other half, in a real engine: given a viewport that shrinks the way the
 * WebView now shrinks, does the field being typed into actually end up visible? Each case is
 * also run WITHOUT the shrink, which is what the browser used to do, so a check that would
 * pass either way is caught.
 */
import { chromium } from 'playwright';
import { readFile as read2 } from 'node:fs/promises';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const PAGE = await readFile(path.join(process.cwd(), 'fixtures', 'keyboard-fields.html'), 'utf8');
// The shipped script, installed the way the browser installs it: document start, every frame.
const SCRIPT = await readFile(
  path.join(process.cwd(), '..', 'app', 'src', 'main', 'assets', 'focus_visibility.js'),
  'utf8',
);
const server = createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  res.end(PAGE);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const origin = `http://127.0.0.1:${server.address().port}`;

const FULL = { width: 412, height: 915 };
const KEYBOARD = 380; // whatever the IME reports; nothing here assumes a particular size

const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--no-sandbox'],
});

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok });
  process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}\n`);
}

/**
 * Focuses a field, then gives the page the space the keyboard leaves — or, when `shrink` is
 * false, leaves it at full height the way the browser used to. Reports whether the field is
 * visible in the space the user can actually see.
 */
async function run(selector, { shrink, script = true }) {
  const context = await browser.newContext({ viewport: { ...FULL }, hasTouch: true });
  if (script) await context.addInitScript({ content: SCRIPT });
  const page = await context.newPage();
  await page.goto(origin);
  // Start with the field off-screen, as it is on a page you have scrolled to the middle of.
  await page.evaluate(() => window.scrollTo(0, 900));
  await page.locator(selector).scrollIntoViewIfNeeded();
  await page.locator(selector).click();
  await page.waitForTimeout(150);

  if (shrink) {
    await page.setViewportSize({ width: FULL.width, height: FULL.height - KEYBOARD });
    await page.waitForTimeout(400);
  } else {
    // The page keeps its full height and the keyboard simply covers the bottom of it.
    await page.waitForTimeout(400);
  }

  const visibleBottom = shrink ? FULL.height - KEYBOARD : FULL.height - KEYBOARD;
  const state = await page.evaluate(
    ([sel, limit]) => {
      const el = document.querySelector(sel);
      const r = el.getBoundingClientRect();
      return { focused: document.activeElement === el, top: Math.round(r.top), bottom: Math.round(r.bottom), limit };
    },
    [selector, visibleBottom],
  );
  await context.close();
  // Visible means inside the part of the window the keyboard is not covering.
  const visible = state.focused && state.top >= 0 && state.bottom <= visibleBottom;
  return { ...state, visible };
}

const cases = [
  ['#search', 'a search box near the bottom'],
  ['#comment', 'a multiline comment box'],
  ['#user', 'a username field'],
  ['#pass', 'a password field'],
  ['#fixed', 'an input in a fixed-position footer'],
];

for (const [selector, label] of cases) {
  const covered = await run(selector, { shrink: false });
  const given = await run(selector, { shrink: true });
  check(
    `${label}: hidden by the keyboard when the page keeps its full height`,
    !covered.visible,
    `bottom ${covered.bottom} vs visible ${covered.limit}`,
  );
  check(
    `${label}: visible once the page is given the space the keyboard leaves`,
    given.visible,
    `top ${given.top}, bottom ${given.bottom}, visible to ${given.limit}`,
  );
}

await browser.close();
server.close();
const failed = results.filter((r) => !r.ok).length;
process.stdout.write(`\n${results.length - failed}/${results.length} checks passed\n`);
process.exit(failed ? 1 : 0);
