'use strict';
/**
 * Fullscreen media, exercised end to end in real Chromium.
 *
 * Every check reads the picture — the decoded frame — rather than a property the agent set or
 * a callback it fired. The fixture paints one flat colour per second, so a pixel is a
 * timestamp: if the video did not move, the colour does not change and the check fails.
 *
 * The command names and arguments are the ones MediaAgent sends, so this drives the same path
 * the fullscreen overlay drives.
 */
const { launch, delay, VISIBLE_SECOND } = require('./e2e-harness');

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok });
  process.stdout.write(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}\n`);
}

/**
 * The wire protocol MediaAgent.kt speaks: the Kotlin methods take milliseconds and send
 * seconds under these command names.
 */
function api(command) {
  return {
    enterFullscreen: () => command('enter', 'contain'),
    exitFullscreen: () => command('exit'),
    seekTo: (ms) => command('seek', ms / 1000),
    togglePlayback: () => command('playPause'),
    setVolume: (v) => command('volume', v),
    setRemote: (on) => command('remote', on ? '1' : '0'),
  };
}

const PROGRESSIVE = `
  <!doctype html><meta charset="utf-8"><title>clip</title>
  <body style="margin:0;background:#000">
  <video id="v" src="/clip.webm" autoplay muted playsinline
         style="width:100%;height:100vh;object-fit:contain"></video>`;

/**
 * The same clip, but assembled by Media Source from an ArrayBuffer, so `currentSrc` is a blob.
 * This is the shape a live player has, and the one seeking used to be worst on.
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

  // ---- A progressive file ---------------------------------------------------------
  h.sameOrigin.pages.set('/clip', PROGRESSIVE);
  await page.goto(h.urlSame('/clip'));
  await page.waitForFunction("document.querySelector('video').readyState >= 2");
  await delay(600);

  await agent.enterFullscreen();
  await delay(400);
  check('the agent takes the video fullscreen', events.entered.includes(true), JSON.stringify(events.entered));

  // Releasing the seek bar sends exactly this.
  await agent.seekTo(22000);
  await delay(1000);
  const seeked = await page.evaluate(VISIBLE_SECOND);
  check('the seek bar moves the video', Math.abs(seeked - 22) <= 2, `visible second ${seeked}, asked for 22`);

  await agent.seekTo(4000);
  await delay(1000);
  const back = await page.evaluate(VISIBLE_SECOND);
  check('the seek bar moves it back', Math.abs(back - 4) <= 2, `visible second ${back}, asked for 4`);

  // Play/pause has to actually stop the picture, not just flip a flag.
  await agent.togglePlayback();
  await delay(700);
  const paused = await page.evaluate(VISIBLE_SECOND);
  await delay(1200);
  const stillPaused = await page.evaluate(VISIBLE_SECOND);
  check('pause stops the picture', paused === stillPaused, `second ${paused} then ${stillPaused}`);

  await agent.togglePlayback();
  await delay(1400);
  const resumed = await page.evaluate(VISIBLE_SECOND);
  check('play starts it again', resumed > stillPaused, `second ${stillPaused} -> ${resumed}`);

  await agent.setVolume(0.5);
  await delay(200);
  const volume = await page.evaluate("document.querySelector('video').volume");
  check('the volume control reaches the element', Math.abs(volume - 0.5) < 0.01, `volume ${volume}`);

  const report = events.reports[events.reports.length - 1];
  check('the transport is told the duration', report && Math.round(report.d) === 30, report ? `d=${report.d}` : 'no report');

  // ---- The handoff to a receiver --------------------------------------------------
  // A real player, in a real browser engine, arguing with the browser about who is playing.
  await agent.setRemote(true);
  await delay(400);
  const handedOver = await page.evaluate(VISIBLE_SECOND);
  await delay(1200);
  const stayedStopped = await page.evaluate(VISIBLE_SECOND);
  check(
    'handing over to a receiver stops the picture here',
    handedOver === stayedStopped,
    `second ${handedOver} then ${stayedStopped}`,
  );
  check(
    'and silences it, so the room does not hear both',
    await page.evaluate("document.querySelector('video').muted === true"),
    'muted',
  );

  // The page fights back, the way every real player does.
  await page.evaluate("document.querySelector('video').play()");
  await delay(900);
  const stillHeld = await page.evaluate("document.querySelector('video').paused === true");
  check("the page's own play() cannot take the video back", stillHeld, 'still paused');

  await agent.setRemote(false);
  await agent.seekTo(12000);
  await delay(300);
  await page.evaluate("document.querySelector('video').play()");
  await delay(1200);
  const afterHandBack = await page.evaluate(VISIBLE_SECOND);
  check(
    'taking playback back resumes where the receiver had got to',
    afterHandBack >= 12 && afterHandBack <= 15,
    `visible second ${afterHandBack}, handed back at 12`,
  );

  await agent.exitFullscreen();
  await delay(300);
  const restored = await page.evaluate("document.querySelector('video').getAttribute('style') || ''");
  check('leaving fullscreen puts the page back', !restored.includes('fixed'), `style="${restored}"`);

  // ---- A Media Source stream ------------------------------------------------------
  h.sameOrigin.pages.set('/mse', MSE);
  await page.goto(h.urlSame('/mse'));
  await page.waitForFunction('window.__mseReady === true', null, { timeout: 15000 });
  await page.waitForFunction("document.querySelector('video').readyState >= 2");
  await delay(600);
  await agent.enterFullscreen();
  await delay(400);

  await agent.seekTo(20000);
  await delay(1200);
  const mse = await page.evaluate(VISIBLE_SECOND);
  check('the seek bar moves a Media Source stream too', Math.abs(mse - 20) <= 3, `visible second ${mse}, asked for 20`);

  // ---- A player in a cross-origin iframe ------------------------------------------
  h.crossOrigin.pages.set('/inner', PROGRESSIVE);
  h.sameOrigin.pages.set('/outer', `
    <!doctype html><meta charset="utf-8"><title>outer</title>
    <body style="margin:0;background:#111">
    <iframe src="${h.urlCross('/inner')}" style="width:100%;height:60vh;border:0"
            allow="autoplay"></iframe>`);
  await page.goto(h.urlSame('/outer'));
  await page.waitForTimeout(1500);
  await agent.enterFullscreen();
  await delay(700);
  await agent.seekTo(18000);
  await delay(1200);
  const framed = await page.frames().find((f) => f.url().includes('/inner')).evaluate(VISIBLE_SECOND);
  check('seeking crosses into a cross-origin player', Math.abs(framed - 18) <= 3, `visible second ${framed}, asked for 18`);

  await h.close();
  const failed = results.filter((r) => !r.ok).length;
  process.stdout.write(`\n${results.length - failed}/${results.length} checks passed\n`);
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
