'use strict';
/**
 * The seeking and preview features, exercised end to end in real Chromium.
 *
 * Every check reads the picture — the decoded frame, or the pixels of the preview image the
 * agent actually produced — rather than a property the agent set or a callback it fired. The
 * fixture paints one flat colour per second, so a pixel is a timestamp: if the video did not
 * move, the colour does not change, and the check fails.
 *
 * The command names and arguments are the ones MediaAgent sends, so this drives the same path
 * the fullscreen overlay drives.
 */
const { launch, delay, VISIBLE_SECOND } = require('./e2e-harness');

/**
 * The wire protocol MediaAgent.kt speaks: the Kotlin methods take milliseconds and send
 * seconds under these command names. Mirrored here so the test drives the same messages the
 * fullscreen overlay drives, units and all.
 */
function api(command) {
  return {
    enterFullscreen: () => command('enterFullscreen'),
    seekTo: (ms) => command('seek', ms / 1000),
    seekBy: (ms) => command('seekBy', ms / 1000),
    openScrubPreview: () => command('previewOpen'),
    previewAt: (ms) => command('previewAt', ms / 1000),
    closeScrubPreview: () => command('previewClose'),
  };
}

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok });
  process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}\n`);
}

/** The colour of the preview image the agent sent, converted back to a second. */
async function previewSecond(page, dataUrl) {
  return page.evaluate(
    (src) =>
      new Promise((resolve) => {
        const img = new Image();
        img.onload = () => {
          const c = document.createElement('canvas');
          c.width = 8;
          c.height = 8;
          const ctx = c.getContext('2d');
          ctx.drawImage(img, 0, 0, 8, 8);
          const [r] = ctx.getImageData(4, 4, 1, 1).data;
          resolve(Math.round(r / 8));
        };
        img.onerror = () => resolve(-1);
        img.src = src;
      }),
    dataUrl
  );
}

const PROGRESSIVE = `
  <!doctype html><meta charset="utf-8"><title>clip</title>
  <body style="margin:0;background:#000">
  <video id="v" src="/clip.webm" autoplay muted playsinline
         style="width:100%;height:100vh;object-fit:contain"></video>`;

/**
 * The same clip, but assembled by Media Source from an ArrayBuffer, so `currentSrc` is a blob
 * and no second element can open it. This is the shape a live player has.
 */
const MSE = `
  <!doctype html><meta charset="utf-8"><title>mse</title>
  <body style="margin:0;background:#000">
  <video id="v" autoplay muted playsinline style="width:100%;height:100vh;object-fit:contain"></video>
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

(async () => {
  const h = await launch();
  const { page, events } = h;
  const agent = api(h.command);

  // ---- Progressive source: a second element can be opened, so previews are real frames ----
  h.sameOrigin.pages.set('/clip', PROGRESSIVE);
  await page.goto(h.urlSame('/clip'));
  await page.waitForFunction("document.querySelector('video').readyState >= 2");
  await delay(600);

  await agent.enterFullscreen();
  await delay(400);

  const before = await page.evaluate(VISIBLE_SECOND);

  // A double tap in the fullscreen overlay sends exactly this.
  await agent.seekBy(10000);
  await delay(900);
  const afterForward = await page.evaluate(VISIBLE_SECOND);
  check(
    'double-tap forward moves the picture',
    afterForward >= before + 8 && afterForward <= before + 12,
    `visible second ${before} -> ${afterForward}`
  );

  await agent.seekBy(-10000);
  await delay(900);
  const afterBack = await page.evaluate(VISIBLE_SECOND);
  check(
    'double-tap back moves the picture back',
    Math.abs(afterBack - before) <= 2,
    `visible second ${afterForward} -> ${afterBack}`
  );

  // ---- The scrub preview -----------------------------------------------------------
  events.modes.length = 0;
  events.previews.length = 0;
  await agent.openScrubPreview();
  await delay(1500);
  const mode = events.modes[events.modes.length - 1];
  check('a progressive source offers real frames', mode === 'frames', `mode=${mode}`);

  await agent.previewAt(22000);
  await delay(1500);
  const frame = events.previews[events.previews.length - 1];
  check('dragging the scrubber produces a preview image', !!frame && frame.data.startsWith('data:image/'), frame ? frame.data.slice(0, 24) : 'none');

  if (frame) {
    const second = await previewSecond(page, frame.data);
    check(
      'the preview shows the frame at the requested position',
      Math.abs(second - 22) <= 1,
      `preview pixel reads second ${second}, asked for 22`
    );
  }

  // The preview must not have disturbed what is playing.
  const during = await page.evaluate(VISIBLE_SECOND);
  check('previewing does not move the playing video', Math.abs(during - afterBack) <= 4, `visible second ${during}`);

  await agent.closeScrubPreview();
  await delay(300);

  // Letting go of the scrubber is a real seek.
  await agent.seekTo(25000);
  await delay(1000);
  const seeked = await page.evaluate(VISIBLE_SECOND);
  check('releasing the scrubber seeks the video', Math.abs(seeked - 25) <= 2, `visible second ${seeked}`);

  // ---- Media Source: no second element is possible, so the real video is the preview ----
  h.sameOrigin.pages.set('/mse', MSE);
  await page.goto(h.urlSame('/mse'));
  await page.waitForFunction('window.__mseReady === true', null, { timeout: 15000 });
  await page.waitForFunction("document.querySelector('video').readyState >= 2");
  await delay(600);
  await agent.enterFullscreen();
  await delay(400);

  events.modes.length = 0;
  await agent.openScrubPreview();
  await delay(1200);
  const mseMode = events.modes[events.modes.length - 1];
  check('a Media Source stream scrubs in place instead of pretending', mseMode === 'inplace', `mode=${mseMode}`);

  const mseBefore = await page.evaluate(VISIBLE_SECOND);
  await agent.previewAt(20000);
  await delay(1200);
  const mseAfter = await page.evaluate(VISIBLE_SECOND);
  check(
    'scrubbing a Media Source stream moves the picture itself',
    Math.abs(mseAfter - 20) <= 2 && mseAfter !== mseBefore,
    `visible second ${mseBefore} -> ${mseAfter}`
  );

  await agent.seekBy(-8000);
  await delay(1000);
  const mseNudged = await page.evaluate(VISIBLE_SECOND);
  check('double-tap seeking works on a Media Source stream', Math.abs(mseNudged - (mseAfter - 8)) <= 2, `visible second ${mseAfter} -> ${mseNudged}`);

  await h.close();
  const failed = results.filter((r) => !r.ok).length;
  process.stdout.write(`\n${results.length - failed}/${results.length} checks passed\n`);
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
