#!/usr/bin/env node
/*
 * Whether a failure status is worth replacing the page with.
 *
 * The browser used to cover any 5xx on the main frame with its own error panel and a Retry
 * button. Anti-bot challenges and rate limiters answer with exactly that status and a real,
 * working page that resolves itself — so a page that was loading fine became one the user had
 * to keep pressing Retry on, and each press started the challenge again.
 *
 * The rule is now that the page itself decides: a document with nothing in it is a failure, a
 * document with content is the site talking to its visitor. This runs the browser's actual
 * probe, character for character, against real challenge bodies captured from live sites and
 * against the empty responses it still has to catch.
 */
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

// The exact expression from SlateWebViewClient.HAS_CONTENT.
const CLIENT = await readFile(
  path.join(process.cwd(), '..', 'app', 'src', 'main', 'java', 'com', 'slate', 'browser', 'web', 'SlateWebViewClient.kt'),
  'utf8',
);
const HAS_CONTENT = (() => {
  const start = CLIENT.indexOf('const val HAS_CONTENT');
  const body = CLIENT.slice(start, CLIENT.indexOf('\n    }', start));
  // Reassemble the concatenated Kotlin string literals into the script that actually runs.
  return [...body.matchAll(/"([^"]*)"/g)].map((m) => m[1]).join('');
})();
process.stdout.write(`probe: ${HAS_CONTENT.slice(0, 60)}...\n\n`);

const dir = path.join(process.cwd(), 'fixtures', 'challenge');
const pages = {
  '/cloudflare': [503, await readFile(path.join(dir, 'cloudflare-challenge.html'), 'utf8'), true],
  '/secure': [503, await readFile(path.join(dir, 'secure-connection-challenge.html'), 'utf8'), true],
  '/maintenance': [503, '<!doctype html><title>Down</title><h1>Back in an hour</h1><p>Sorry.</p>', true],
  // Chromium substitutes its own error document for a body-less response, so this case cannot
  // be observed here; Android WebView leaves the page blank instead. Asserted as "substituted"
  // to record why, rather than passed off as a result it did not produce.
  '/empty': [503, '', 'chromium-substitutes'],
  '/blank-shell': [503, '<!doctype html><html><head><title>x</title></head><body></body></html>', false],
  '/whitespace': [502, '<!doctype html><body>   \n\t  </body>', false],
};

const server = createServer((req, res) => {
  const entry = pages[req.url];
  if (!entry) return res.writeHead(404).end('no');
  res.writeHead(entry[0], { 'content-type': 'text/html; charset=utf-8' });
  res.end(entry[1]);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const origin = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--no-sandbox'],
});
const page = await browser.newPage({ viewport: { width: 412, height: 915 } });

const results = [];
for (const [route, [status, , expected]] of Object.entries(pages)) {
  await page.goto(origin + route, { waitUntil: 'load' }).catch(() => {});
  await page.waitForTimeout(300);
  const hasContent = await page.evaluate(HAS_CONTENT);
  if (expected === 'chromium-substitutes') {
    const substituted = await page.evaluate("document.body.innerText.includes('HTTP ERROR')");
    results.push(substituted);
    process.stdout.write(
      `${substituted ? 'SKIP' : 'FAIL'}  ${status} ${route.padEnd(14)} -> not observable: Chromium served its own error page\n`,
    );
    continue;
  }
  const ok = hasContent === expected;
  results.push(ok);
  const verdict = hasContent ? 'show the page' : 'show the error panel';
  process.stdout.write(
    `${ok ? 'PASS' : 'FAIL'}  ${status} ${route.padEnd(14)} -> ${verdict}${ok ? '' : ` (expected ${expected ? 'the page' : 'the panel'})`}\n`,
  );
}

await browser.close();
server.close();
const failed = results.filter((r) => !r).length;
process.stdout.write(`\n${results.length - failed}/${results.length} checks passed\n`);
process.exit(failed ? 1 : 0);
