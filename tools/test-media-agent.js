'use strict';
/**
 * Behavioural tests for the in-page media agent.
 *
 * These cover the parts that decide whether fullscreen actually works on a hostile page: which
 * video gets picked, whether the CSS that crops it is defeated, and whether the page is left
 * exactly as it was on the way out.
 */
const assert = require('assert');
const { createPage, describeVideo, define, delay, addFrame } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const ENTER_SETTLE_MS = 320;

function styleOf(el, prop) {
  return el.style.getPropertyValue(prop);
}

// ---------------------------------------------------------------- selection

test('picks the playing stream over a larger paused video', async () => {
  const { win, doc } = createPage('<video id="a"></video><video id="b"></video>');
  describeVideo(doc.getElementById('a'), { width: 1200, height: 700, paused: true });
  describeVideo(doc.getElementById('b'), { width: 400, height: 240, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  assert.strictEqual(styleOf(doc.getElementById('b'), 'position'), 'fixed');
  assert.strictEqual(styleOf(doc.getElementById('a'), 'position'), '');
});

test('ignores thumbnail-sized videos', async () => {
  const { win, doc, entered } = createPage('<video id="tiny"></video>');
  describeVideo(doc.getElementById('tiny'), { width: 32, height: 24, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  assert.deepStrictEqual(entered, [false], 'a tracking-pixel video is not worth fullscreen');
});

test('prefers a live stream over a recorded clip of the same size', async () => {
  const { win, doc } = createPage('<video id="vod"></video><video id="live"></video>');
  describeVideo(doc.getElementById('vod'), { width: 640, height: 360, paused: false });
  describeVideo(doc.getElementById('live'), { width: 640, height: 360, paused: false, live: true });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  assert.strictEqual(styleOf(doc.getElementById('live'), 'position'), 'fixed');
});

// ------------------------------------------------------------- maximisation

test('pins the video to the viewport without stretching it', async () => {
  const { win, doc } = createPage('<video id="v"></video>');
  describeVideo(doc.getElementById('v'), { width: 320, height: 180, paused: false, videoWidth: 1920, videoHeight: 1080 });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  const v = doc.getElementById('v');
  assert.strictEqual(styleOf(v, 'position'), 'fixed');
  assert.strictEqual(styleOf(v, 'width'), '100vw');
  assert.strictEqual(styleOf(v, 'height'), '100vh');
  assert.strictEqual(styleOf(v, 'object-fit'), 'contain', 'contain is what preserves the aspect ratio');
  assert.strictEqual(v.style.getPropertyPriority('width'), 'important', 'site CSS must not win');
  assert.strictEqual(styleOf(v, 'z-index'), '2147483647');
});

test('defeats the ancestor styles that crop a video into its box', async () => {
  const { win, doc } = createPage(`
    <div id="outer" style="transform: translateZ(0); overflow: hidden; width: 300px">
      <div id="inner" style="contain: paint; clip-path: inset(10px); max-height: 180px">
        <video id="v"></video>
      </div>
    </div>`);
  describeVideo(doc.getElementById('v'), { width: 300, height: 170, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  // Any of these would have become the containing block for position:fixed and re-trapped the
  // video inside the very box we are trying to escape.
  for (const id of ['outer', 'inner']) {
    const el = doc.getElementById(id);
    assert.strictEqual(styleOf(el, 'transform'), 'none', `${id} transform`);
    assert.strictEqual(styleOf(el, 'overflow'), 'visible', `${id} overflow`);
    assert.strictEqual(styleOf(el, 'contain'), 'none', `${id} contain`);
    assert.strictEqual(styleOf(el, 'clip-path'), 'none', `${id} clip-path`);
  }
});

test('hides the page furniture instead of covering it with an overlay', async () => {
  const { win, doc } = createPage(
    '<div id="player"><video id="v"></video><div id="controls">tip menu</div></div>' +
    '<div id="chat">chat</div>'
  );
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  // Nothing of ours is painted on top: an opaque overlay can land in front of a hardware video
  // surface, which is exactly the black-screen-with-audio failure.
  assert.strictEqual(doc.querySelector('[data-slate-backdrop]'), null);

  assert.strictEqual(styleOf(doc.getElementById('controls'), 'display'), 'none');
  assert.strictEqual(styleOf(doc.getElementById('chat'), 'display'), 'none');
  assert.strictEqual(styleOf(doc.getElementById('v'), 'display'), 'block', 'the video stays');

  const sheet = doc.querySelector('style[data-slate-theater]');
  assert.ok(sheet.textContent.includes('overflow:hidden'), 'the page must not scroll underneath');
  assert.ok(sheet.textContent.includes('background:#000'), 'letterbox bars should be black');
});

test('prefers the stream that is making the sound over a bigger silent one', async () => {
  // A large muted teaser next to the real stream is how a viewer ends up watching a black box
  // while the audio plays on somewhere else.
  const { win, doc } = createPage('<video id="teaser"></video><video id="stream"></video>');
  const teaser = describeVideo(doc.getElementById('teaser'), { width: 1000, height: 600, paused: false });
  teaser.muted = true;
  const stream = describeVideo(doc.getElementById('stream'), { width: 320, height: 180, paused: false });
  stream.muted = false;
  stream.volume = 1;

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  assert.strictEqual(styleOf(doc.getElementById('stream'), 'position'), 'fixed');
  assert.strictEqual(styleOf(doc.getElementById('teaser'), 'position'), '');
});

test('ignores a video the page has hidden', async () => {
  const { win, doc } = createPage(
    '<video id="decoy" style="visibility:hidden"></video><video id="real"></video>'
  );
  describeVideo(doc.getElementById('decoy'), { width: 1200, height: 700, paused: false });
  describeVideo(doc.getElementById('real'), { width: 320, height: 180, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  assert.strictEqual(styleOf(doc.getElementById('real'), 'position'), 'fixed');
});

test('promotes a canvas the player draws the picture into', async () => {
  // Some players decode into a canvas and keep the media element only for audio and timing.
  const { win, doc } = createPage(
    '<div id="player"><video id="v"></video><canvas id="c"></canvas></div>'
  );
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });
  doc.getElementById('c').__rect = { width: 640, height: 360 };

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  const canvas = doc.getElementById('c');
  assert.strictEqual(styleOf(canvas, 'position'), 'fixed', 'the canvas is the picture');
  assert.strictEqual(styleOf(canvas, 'object-fit'), 'contain', 'and must not be stretched');
  assert.ok(
    Number(styleOf(canvas, 'z-index')) >= Number(styleOf(doc.getElementById('v'), 'z-index')),
    'the canvas has to sit on top of the media element'
  );
  assert.strictEqual(styleOf(canvas, 'display'), 'block', 'and must not be hidden as a sibling');
});

test('a canvas unrelated to the video is left alone', async () => {
  const { win, doc } = createPage('<video id="v"></video><canvas id="ad"></canvas>');
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });
  doc.getElementById('ad').__rect = { width: 300, height: 250 };
  // jsdom reports every rect at the origin, so overlap is decided by size alone here.
  doc.getElementById('ad').__rect = { width: 10, height: 10 };

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  assert.strictEqual(styleOf(doc.getElementById('ad'), 'position'), '');
});

test('hides the site player controls while fullscreen and restores them after', async () => {
  const { win, doc } = createPage('<video id="v" controls></video>');
  const v = describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });
  v.controls = true;

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  assert.strictEqual(v.controls, false);

  win.__slateMedia.command('exit');
  assert.strictEqual(v.controls, true);
});

// ------------------------------------------------------------------ restore

test('exit puts every touched element back exactly as it was', async () => {
  const { win, doc } = createPage(`
    <div id="wrap" style="transform: scale(1); overflow: hidden">
      <video id="v" style="width: 320px; border-radius: 8px"></video>
    </div>
    <video id="untouched"></video>`);
  describeVideo(doc.getElementById('v'), { width: 320, height: 180, paused: false });
  describeVideo(doc.getElementById('untouched'), { width: 10, height: 10 });

  const before = {
    wrap: doc.getElementById('wrap').getAttribute('style'),
    v: doc.getElementById('v').getAttribute('style'),
  };

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  win.__slateMedia.command('exit');

  assert.strictEqual(doc.getElementById('wrap').getAttribute('style'), before.wrap);
  assert.strictEqual(doc.getElementById('v').getAttribute('style'), before.v);
  assert.strictEqual(doc.getElementById('untouched').hasAttribute('style'), false);
  assert.strictEqual(doc.querySelector('style[data-slate-theater]'), null);
  assert.strictEqual(doc.querySelector('[data-slate-keep]'), null, 'no markers left behind');
  assert.strictEqual(win.__slateMedia.isActive(), false);
});

test('hidden siblings are all restored on the way out', async () => {
  const { win, doc } = createPage(
    '<div id="wrap"><video id="v"></video><div id="a" style="color:red">a</div></div>' +
    '<div id="b">b</div>'
  );
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  assert.strictEqual(styleOf(doc.getElementById('a'), 'display'), 'none');

  win.__slateMedia.command('exit');
  assert.strictEqual(doc.getElementById('a').getAttribute('style'), 'color:red');
  assert.strictEqual(doc.getElementById('b').hasAttribute('style'), false);
});

test('an element with no inline style keeps having none', async () => {
  const { win, doc } = createPage('<div id="wrap"><video id="v"></video></div>');
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  win.__slateMedia.command('exit');

  assert.strictEqual(doc.getElementById('wrap').hasAttribute('style'), false);
  assert.strictEqual(doc.getElementById('v').hasAttribute('style'), false);
});

// -------------------------------------------------------------------- modes

test('fill switches to cover and back without losing the pin', async () => {
  const { win, doc } = createPage('<video id="v"></video>');
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  win.__slateMedia.command('fill', 'cover');
  const v = doc.getElementById('v');
  assert.strictEqual(styleOf(v, 'object-fit'), 'cover');

  win.__slateMedia.command('fill', 'contain');
  assert.strictEqual(styleOf(v, 'object-fit'), 'contain');
  assert.strictEqual(styleOf(v, 'position'), 'fixed');
});

test('play, pause and mute act on the video that is fullscreen', async () => {
  const { win, doc } = createPage('<video id="v"></video>');
  const v = describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  win.__slateMedia.command('playPause');
  assert.strictEqual(v.paused, true);
  win.__slateMedia.command('playPause');
  assert.strictEqual(v.paused, false);

  assert.strictEqual(v.muted, false);
  win.__slateMedia.command('mute');
  assert.strictEqual(v.muted, true);
});

// ---------------------------------------------------------------- reporting

test('reports what is playing so the browser can offer the button', async () => {
  const { win, doc, reports } = createPage('<video id="v"></video>');
  const v = describeVideo(doc.getElementById('v'), {
    width: 640, height: 360, paused: false, live: true, videoWidth: 1280, videoHeight: 720,
  });

  v.dispatchEvent(new win.Event('playing', { bubbles: true }));
  await delay(ENTER_SETTLE_MS);

  const last = reports[reports.length - 1];
  assert.ok(last, 'a play event must reach the browser');
  assert.strictEqual(last.hasVideo, true);
  assert.strictEqual(last.playing, true);
  assert.strictEqual(last.live, true);
  assert.strictEqual(last.w, 1280);
  assert.strictEqual(last.h, 720);
});

test('reports no video on a page that has none', async () => {
  const { win, reports } = createPage('<p>just text</p>');
  win.__slateMedia.command('scan');
  await delay(ENTER_SETTLE_MS);

  const last = reports[reports.length - 1];
  assert.ok(last);
  assert.strictEqual(last.hasVideo, false);
});

// --------------------------------------------------------------- resilience

test('re-asserts the pin after a player rewrites the video style', async () => {
  const { win, doc } = createPage('<video id="v"></video>');
  const v = describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  // What video.js and friends do on every layout change.
  v.style.cssText = 'width: 320px; height: 180px; position: relative';
  assert.strictEqual(styleOf(v, 'position'), 'relative');

  await delay(600);
  assert.strictEqual(styleOf(v, 'position'), 'fixed', 'the watchdog must win the argument');
  assert.strictEqual(styleOf(v, 'width'), '100vw');
});

test('adopts the replacement when a player swaps its media element', async () => {
  const { win, doc } = createPage('<div id="host"><video id="v"></video></div>');
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  doc.getElementById('v').remove();
  const next = doc.createElement('video');
  next.id = 'v2';
  doc.getElementById('host').appendChild(next);
  describeVideo(next, { width: 640, height: 360, paused: false });

  await delay(600);
  assert.strictEqual(styleOf(next, 'position'), 'fixed', 'fullscreen must survive a source swap');
});

test('ignores messages that are not from its own parent or children', async () => {
  const { win, doc } = createPage('<video id="v"></video>');
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.postMessage({ ns: 'slate.media.v1', type: 'enter', fill: 'cover' }, '*');
  await delay(120);

  assert.strictEqual(styleOf(doc.getElementById('v'), 'position'), '',
    'a page must not be able to drive the agent by posting to itself');
});

test('pins the viewport so a zoomed or desktop-width page cannot crop the video', async () => {
  const { win, doc } = createPage(
    '<meta name="viewport" content="width=1024, initial-scale=0.4"><video id="v"></video>'
  );
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  const meta = doc.querySelector('meta[name="viewport"]').getAttribute('content');
  assert.ok(meta.includes('width=device-width'), '100vw must mean the actual screen');
  assert.ok(meta.includes('initial-scale=1'), 'a page left zoomed in must be reset');
  assert.ok(meta.includes('viewport-fit=cover'), 'the picture should reach into the cutout');

  win.__slateMedia.command('exit');
  assert.strictEqual(
    doc.querySelector('meta[name="viewport"]').getAttribute('content'),
    'width=1024, initial-scale=0.4',
    'the page gets its own viewport back'
  );
});

test('a page with no viewport tag is left without one afterwards', async () => {
  const { win, doc } = createPage('<video id="v"></video>');
  describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  assert.ok(doc.querySelector('meta[name="viewport"]'), 'one is added while fullscreen');

  win.__slateMedia.command('exit');
  assert.strictEqual(doc.querySelector('meta[name="viewport"]'), null);
});

// ------------------------------------------------------- embedded players

test('finds a video inside an embedded player and fullscreens both hops', async () => {
  const page = createPage('<h1>stream page</h1><div id="chrome">tip menu</div>');
  const frame = addFrame(page, '<video id="v"></video>', { width: 320, height: 180 });
  describeVideo(frame.doc.getElementById('v'), {
    width: 320, height: 180, paused: false, live: true, videoWidth: 1280, videoHeight: 720,
  });

  page.win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  // The iframe fills the screen in the top document...
  assert.strictEqual(styleOf(frame.iframe, 'position'), 'fixed');
  assert.strictEqual(styleOf(frame.iframe, 'width'), '100vw');
  assert.strictEqual(styleOf(frame.iframe, 'height'), '100vh');
  // ...and the video fills the iframe, which is now the whole screen.
  const v = frame.doc.getElementById('v');
  assert.strictEqual(styleOf(v, 'position'), 'fixed');
  assert.strictEqual(styleOf(v, 'object-fit'), 'contain');
});

test('an embedded stream beats a bigger one in the host page', async () => {
  const page = createPage('<video id="host"></video>');
  describeVideo(page.doc.getElementById('host'), { width: 900, height: 500, paused: true });
  const frame = addFrame(page, '<video id="v"></video>');
  describeVideo(frame.doc.getElementById('v'), { width: 320, height: 180, paused: false });

  page.win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  assert.strictEqual(styleOf(frame.iframe, 'position'), 'fixed');
  assert.strictEqual(styleOf(page.doc.getElementById('host'), 'position'), '');
});

test('exit unwinds both the frame and the video inside it', async () => {
  const page = createPage('<div id="wrap" style="overflow:hidden"></div>');
  const frame = addFrame(page, '<div style="transform:scale(1)"><video id="v"></video></div>');
  describeVideo(frame.doc.getElementById('v'), { width: 320, height: 180, paused: false });
  const frameStyleBefore = frame.iframe.getAttribute('style');

  page.win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  page.win.__slateMedia.command('exit');
  await delay(80);

  assert.strictEqual(frame.iframe.getAttribute('style'), frameStyleBefore);
  assert.strictEqual(frame.doc.getElementById('v').hasAttribute('style'), false);
  assert.strictEqual(page.doc.querySelector('style[data-slate-theater]'), null);
  assert.strictEqual(frame.doc.querySelector('style[data-slate-theater]'), null);
});

test('playback commands reach the video inside the embedded player', async () => {
  const page = createPage('<p>host</p>');
  const frame = addFrame(page, '<video id="v"></video>');
  const v = describeVideo(frame.doc.getElementById('v'), { width: 320, height: 180, paused: false });

  page.win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);

  page.win.__slateMedia.command('playPause');
  await delay(60);
  assert.strictEqual(v.paused, true, 'pause must cross the frame boundary');

  page.win.__slateMedia.command('mute');
  await delay(60);
  assert.strictEqual(v.muted, true);
});

test('the top frame reports media that lives in a child frame', async () => {
  const page = createPage('<p>host</p>');
  const frame = addFrame(page, '<video id="v"></video>');
  describeVideo(frame.doc.getElementById('v'), {
    width: 320, height: 180, paused: false, live: true, videoWidth: 1920, videoHeight: 1080,
  });

  page.win.__slateMedia.command('scan');
  await delay(ENTER_SETTLE_MS);

  const last = page.reports[page.reports.length - 1];
  assert.ok(last, 'the browser needs to know an embedded stream is playing');
  assert.strictEqual(last.hasVideo, true);
  assert.strictEqual(last.live, true);
  assert.strictEqual(last.w, 1920, 'aspect ratio must survive the trip up the frame tree');
});

test('routes through a player nested two frames deep', async () => {
  // Ad wrappers and CDN embeds routinely put the real player one frame further down.
  const page = createPage('<p>host</p>');
  const outer = addFrame(page, '<p>wrapper</p>');
  const inner = addFrame({ doc: outer.doc, win: outer.win, reports: [] }, '<video id="v"></video>');
  describeVideo(inner.doc.getElementById('v'), { width: 320, height: 180, paused: false, live: true });

  page.win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS + 200);

  assert.strictEqual(styleOf(outer.iframe, 'position'), 'fixed', 'the outer frame must expand');
  assert.strictEqual(styleOf(inner.iframe, 'position'), 'fixed', 'and so must the inner one');
  assert.strictEqual(styleOf(inner.doc.getElementById('v'), 'position'), 'fixed');

  page.win.__slateMedia.command('exit');
  await delay(120);
  assert.strictEqual(inner.doc.getElementById('v').hasAttribute('style'), false);
  assert.strictEqual(outer.iframe.hasAttribute('style'), false);
});

test('offers fullscreen for a loaded stream that has not been started yet', async () => {
  // Autoplay is off by default, so the button has to appear before the first tap on play.
  const { win, doc, reports } = createPage('<video id="v"></video>');
  describeVideo(doc.getElementById('v'), {
    width: 640, height: 360, paused: true, readyState: 4, videoWidth: 1280, videoHeight: 720,
  });

  win.__slateMedia.command('scan');
  await delay(ENTER_SETTLE_MS);

  const last = reports[reports.length - 1];
  assert.strictEqual(last.hasVideo, true);
  assert.strictEqual(last.playing, false);
});

test('a portrait stream is reported as portrait so the phone is not rotated', async () => {
  const { win, doc, reports } = createPage('<video id="v"></video>');
  describeVideo(doc.getElementById('v'), {
    width: 360, height: 640, paused: false, videoWidth: 720, videoHeight: 1280,
  });

  win.__slateMedia.command('scan');
  await delay(ENTER_SETTLE_MS);

  const last = reports[reports.length - 1];
  assert.strictEqual(last.w, 720);
  assert.strictEqual(last.h, 1280);
});

// ------------------------------------------------------- scrub previews

test('a stream assembled by Media Source reports that it cannot be previewed', async () => {
  // A blob: source belongs to one element and cannot be handed to a second one, so there is
  // nothing to seek and draw. Saying so once is what lets the browser fall back cleanly.
  const { win, doc, previews } = createPage('<video id="v"></video>');
  const v = describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });
  Object.defineProperty(v, 'currentSrc', { value: 'blob:https://example.test/abc', configurable: true });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  win.__slateMedia.command('previewOpen');
  await delay(120);

  assert.strictEqual(previews.length, 1);
  assert.strictEqual(previews[0].data, '', 'an empty payload means "cannot preview"');
});

test('a video with no source at all reports the same', async () => {
  const { win, doc, previews } = createPage('<video id="v"></video>');
  const v = describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });
  Object.defineProperty(v, 'currentSrc', { value: '', configurable: true });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  win.__slateMedia.command('previewOpen');
  await delay(120);

  assert.strictEqual(previews[previews.length - 1].data, '');
});

test('scrubbing a source that cannot be previewed never breaks playback', async () => {
  const { win, doc } = createPage('<video id="v"></video>');
  const v = describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });
  Object.defineProperty(v, 'currentSrc', { value: 'blob:https://example.test/abc', configurable: true });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  win.__slateMedia.command('previewOpen');
  win.__slateMedia.command('previewAt', '12.5');
  win.__slateMedia.command('previewAt', '30');
  win.__slateMedia.command('previewClose');
  await delay(120);

  assert.strictEqual(v.paused, false, 'the video that is playing must be untouched');
  assert.strictEqual(styleOf(doc.getElementById('v'), 'position'), 'fixed');
});

test('leaving fullscreen tears the preview down', async () => {
  const { win, doc } = createPage('<video id="v"></video>');
  const v = describeVideo(doc.getElementById('v'), { width: 640, height: 360, paused: false });
  Object.defineProperty(v, 'currentSrc', { value: 'https://cdn.example.test/clip.mp4', configurable: true });

  win.__slateMedia.command('enter', 'contain');
  await delay(ENTER_SETTLE_MS);
  win.__slateMedia.command('previewOpen');
  await delay(60);
  win.__slateMedia.command('exit');
  await delay(60);

  assert.strictEqual(win.__slateMedia.isActive(), false);
  assert.strictEqual(doc.getElementById('v').hasAttribute('style'), false);
});

// ------------------------------------------------------------------- runner

(async () => {
  let failed = 0;
  for (const { name, fn } of tests) {
    try {
      await fn();
      console.log(`  ok   ${name}`);
    } catch (err) {
      failed++;
      console.log(`  FAIL ${name}`);
      console.log(`       ${err.message.split('\n')[0]}`);
    }
  }
  console.log(`\n${tests.length - failed}/${tests.length} media agent tests passed`);
  process.exit(failed ? 1 : 0);
})();
