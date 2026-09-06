#!/usr/bin/env node
// Builds the browser's bundled filter assets from the published lists.
//
//   node tools/build-filters.mjs [--fetch] [--dir <downloads>]
//
// With --fetch the lists are downloaded first; otherwise they are read from --dir. The output
// is the same Adblock Plus syntax the lists are published in, split into the network half and
// the element-hiding half, which is what ContentBlocker loads. Keeping the
// published format means updating the blocking is re-running this script, not editing rules.

import { createWriteStream } from 'node:fs';
import { mkdir, readdir, readFile, writeFile, stat } from 'node:fs/promises';
import path from 'node:path';

const SOURCES = [
  ['easylist.txt', 'https://easylist.to/easylist/easylist.txt'],
  ['easyprivacy.txt', 'https://easylist.to/easylist/easyprivacy.txt'],
  ['adguard-mobile.txt', 'https://filters.adtidy.org/extension/ublock/filters/11.txt'],
];

const args = process.argv.slice(2);
const fetchFirst = args.includes('--fetch');
const dirAt = args.indexOf('--dir');
const dir = dirAt >= 0 ? args[dirAt + 1] : path.join(process.cwd(), 'build', 'filter-lists');
const outDir = path.join(process.cwd(), 'app', 'src', 'main', 'assets', 'filters');

await mkdir(dir, { recursive: true });
await mkdir(outDir, { recursive: true });

if (fetchFirst) {
  for (const [name, url] of SOURCES) {
    process.stdout.write(`fetching ${url}\n`);
    const response = await fetch(url);
    if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
    await writeFile(path.join(dir, name), await response.text());
  }
}

const network = new Set();
const cosmetic = new Set();
let read = 0;

for (const name of (await readdir(dir)).filter((f) => f.endsWith('.txt')).sort()) {
  const text = await readFile(path.join(dir, name), 'utf8');
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line || line[0] === '!' || line[0] === '[') continue;
    read++;
    // The extended cosmetic syntaxes are not CSS and are not carried.
    if (line.includes('#?#') || line.includes('#$#') || line.includes('#%#')) continue;
    if (line.includes('##') || line.includes('#@#')) cosmetic.add(line);
    else network.add(line);
  }
}

// Written as plain text: the Android asset pipeline decompresses a `.gz` asset at merge time
// anyway, and the APK's own deflate gets the same saving without a second layer.
function emit(name, lines) {
  const body = [...lines].join('\n') + '\n';
  return writeFile(path.join(outDir, name), body).then(() => {
    process.stdout.write(`${name}: ${lines.size} rules, ${(body.length / 1024).toFixed(0)}KB\n`);
  });
}

await emit('network.txt', network);
await emit('cosmetic.txt', cosmetic);
process.stdout.write(`read ${read} lines from ${dir}\n`);
