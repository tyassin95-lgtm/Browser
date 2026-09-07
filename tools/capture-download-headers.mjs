#!/usr/bin/env node
// Captures what real servers actually say about the files they serve, so the download naming
// is tested against real metadata — including the misleading kind — rather than invented cases.
//
//   node tools/capture-download-headers.mjs app/src/test/resources/live-download-headers.jsonl <url...>

import { writeFile } from 'node:fs/promises';

const [out, ...urls] = process.argv.slice(2);
if (!out || urls.length === 0) {
  process.stderr.write('usage: capture-download-headers.mjs <out.jsonl> <url...>\n');
  process.exit(2);
}

const lines = [];
for (const url of urls) {
  try {
    const response = await fetch(url, {
      redirect: 'follow',
      headers: { 'user-agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8)' },
    });
    const row = {
      url: response.url,
      contentType: response.headers.get('content-type') || '',
      contentDisposition: response.headers.get('content-disposition') || '',
    };
    lines.push(JSON.stringify(row));
    process.stdout.write(`${row.url}\n  type=${row.contentType}  disposition=${row.contentDisposition}\n`);
  } catch (e) {
    process.stdout.write(`${url}: skipped (${e.message})\n`);
  }
}
await writeFile(out, lines.join('\n') + '\n');
