#!/usr/bin/env node
/*
 * What the browser learns about a page's media, which is what decides whether it can be cast.
 *
 * A Cast receiver fetches the stream itself, so the only thing worth sending is an address the
 * receiver could open. This checks that the agent reports the real one in a real engine, and —
 * just as importantly — that it reports honestly when there isn't one: a Media Source stream
 * has a blob address that means nothing outside the document that made it, and saying so is
 * what lets the browser explain rather than send something that fails on the television.
 */
const { launch, delay } = require('./e2e-harness');

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok });
  process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}\n`);
}

const PROGRESSIVE = `
  <!doctype html><meta charset="utf-8"><title>Holiday film</title>
  <body style="margin:0;background:#000">
  <video id="v" src="/clip.webm" poster="/poster.jpg" autoplay muted playsinline
         style="width:100%;height:100vh"></video>`;

const MSE = `
  <!doctype html><meta charset="utf-8"><title>Stream</title>
  <body style="margin:0;background:#000">
  <video id="v" autoplay muted playsinline style="width:100%;height:100vh"></video>
  <script>
    (async () => {
      const buffer = await (await fetch('/clip.webm')).arrayBuffer();
      const source = new MediaSource();
      document.getElementById('v').src = URL.createObjectURL(source);
      source.addEventListener('sourceopen', () => {
        const sb = source.addSourceBuffer('video/webm; codecs="vp8"');
        sb.addEventListener('updateend', () => {
          if (source.readyState === 'open') source.endOfStream();
          window.__mseReady = true;
        });
        sb.appendBuffer(buffer);
      });
    })();
  </script>`;

const AUDIO_ONLY = `
  <!doctype html><meta charset="utf-8"><title>The podcast</title>
  <body><h1>Episode 4</h1>
  <audio id="a" src="/clip.webm" autoplay controls></audio>`;

const SWAPPED = `
  <!doctype html><meta charset="utf-8"><title>Swapper</title>
  <body style="margin:0;background:#000">
  <video id="v" src="/clip.webm" autoplay muted playsinline style="width:100%;height:60vh"></video>
  <script>
    // The kind of player that throws its element away and builds another.
    setTimeout(() => {
      const old = document.getElementById('v');
      const next = document.createElement('video');
      next.id = 'v2';
      next.src = '/second.webm';
      next.autoplay = true; next.muted = true; next.playsInline = true;
      next.setAttribute('style', 'width:100%;height:60vh');
      old.replaceWith(next);
      next.play();
    }, 1200);
  </script>`;

(async () => {
  const h = await launch();
  const { page, events } = h;

  async function report(path, body, { wait = 1800, frameUrl = null } = {}) {
    h.sameOrigin.pages.set(path, body);
    events.reports.length = 0;
    await page.goto(h.urlSame(path));
    await delay(wait);
    // A scan is what the browser does when it wants to know; the same command it sends.
    await h.command('scan');
    await delay(900);
    return events.reports[events.reports.length - 1] || null;
  }

  // ---- An ordinary file: an address a receiver can fetch ---------------------------
  const progressive = await report('/progressive', PROGRESSIVE);
  check(
    'a progressive video reports an absolute address',
    !!progressive && /^http:\/\/127\.0\.0\.1:\d+\/clip\.webm$/.test(progressive.src),
    progressive ? progressive.src : 'no report',
  );
  check('it is not reported as page-assembled or protected', progressive && !progressive.mse && !progressive.drm);
  check('the page title travels with it, for the receiver to show', progressive && progressive.title === 'Holiday film', progressive && progressive.title);
  check('the poster travels with it', !!progressive && progressive.poster.endsWith('/poster.jpg'), progressive && progressive.poster);

  // ---- Media Source: honestly unreportable -----------------------------------------
  const mse = await report('/mse', MSE, { wait: 2500 });
  check(
    'a Media Source stream is reported as assembled in the page',
    !!mse && mse.mse === true && mse.src.startsWith('blob:'),
    mse ? `${mse.src.slice(0, 24)}… mse=${mse.mse}` : 'no report',
  );

  // ---- Audio with no picture --------------------------------------------------------
  const audio = await report('/audio', AUDIO_ONLY, { wait: 1500 });
  check(
    'an audio-only page still reports its source',
    !!audio && audio.audioOnly === true && audio.src.endsWith('/clip.webm'),
    audio ? `audioOnly=${audio.audioOnly} src=${audio.src}` : 'no report',
  );

  // ---- A player that replaces its element -------------------------------------------
  const swapped = await report('/swapped', SWAPPED, { wait: 2600 });
  check(
    'a replaced video element reports the new source, not the old one',
    !!swapped && swapped.src.endsWith('/second.webm'),
    swapped ? swapped.src : 'no report',
  );

  // ---- A player inside a cross-origin iframe ------------------------------------------
  h.crossOrigin.pages.set('/inner', PROGRESSIVE);
  h.sameOrigin.pages.set('/outer', `
    <!doctype html><meta charset="utf-8"><title>Outer</title>
    <body style="margin:0;background:#111">
    <iframe src="${h.urlCross('/inner')}" style="width:100%;height:60vh;border:0" allow="autoplay"></iframe>`);
  events.reports.length = 0;
  await page.goto(h.urlSame('/outer'));
  await delay(2200);
  await h.command('scan');
  await delay(1000);
  const framed = events.reports[events.reports.length - 1];
  check(
    'a video in a cross-origin iframe reports its own address',
    !!framed && /^http:\/\/localhost:\d+\/clip\.webm$/.test(framed.src),
    framed ? framed.src : 'no report',
  );

  await h.close();
  const failed = results.filter((r) => !r.ok).length;
  process.stdout.write(`\n${results.length - failed}/${results.length} checks passed\n`);
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
