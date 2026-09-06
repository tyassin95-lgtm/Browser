#!/usr/bin/env node
// Captures the third-party addresses a set of live sites embed, for the blocking tests to be
// checked against real traffic rather than an invented corpus.
//
//   node tools/capture-site-requests.mjs app/src/test/resources/live-site-requests.json \
//        jav.guru sxyprn.com pimpbunny.com xmoviesforyou.com
//
// This reads the delivered markup rather than driving a browser, so it sees what the page
// embeds up front and not what its scripts request later. That is the honest limit of it: the
// corpus is a real sample of each site's third-party surface, not a complete request log.

import { writeFile } from 'node:fs/promises';

const UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/141.0.0.0 Mobile Safari/537.36';

const [out, ...sites] = process.argv.slice(2);
if (!out || sites.length === 0) {
  process.stderr.write('usage: capture-site-requests.mjs <out.json> <site> [site...]\n');
  process.exit(2);
}

const ATTRIBUTE = /(?:src|href|data-src)\s*=\s*["']([^"']+)["']/g;
const BARE = /https?:\/\/[a-zA-Z0-9.\-]+\.[a-z]{2,}\/[^\s"'<>\\)]{0,120}/g;

const corpus = {};
for (const site of sites) {
  let html;
  try {
    const response = await fetch(`https://${site}/`, { headers: { 'user-agent': UA } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    html = await response.text();
  } catch (e) {
    process.stdout.write(`${site}: skipped (${e.message})\n`);
    continue;
  }
  const found = new Set();
  const add = (raw) => {
    const url = raw.startsWith('//') ? `https:${raw}` : raw;
    if (!url.startsWith('http')) return;
    let host;
    try { host = new URL(url).hostname; } catch { return; }
    if (host === site || host.endsWith(`.${site}`)) return;
    found.add(url);
  };
  for (const m of html.matchAll(ATTRIBUTE)) add(m[1]);
  for (const m of html.matchAll(BARE)) add(m[0]);
  corpus[site] = [...found].sort();
  process.stdout.write(`${site}: ${found.size} third-party addresses\n`);
}

await writeFile(out, JSON.stringify(corpus, null, 1));
