'use strict';
/**
 * A real-browser harness for the media agent.
 *
 * jsdom can say whether a property was assigned. It cannot say whether the video moved, which
 * is the only thing that matters here — so this drives the actual agent inside real Chromium,
 * against real video served over real HTTP, and reads the decoded frame back to find out where
 * playback actually is.
 *
 * Two origins are served so cross-origin iframes and CORS behave as they do in the wild:
 * localhost and 127.0.0.1 are different origins to a browser.
 */
const fs = require('fs');
const http = require('http');
const path = require('path');
const { chromium } = require('playwright');

const AGENT = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'media_agent.js'),
  'utf8'
);
const FIXTURES = path.join(__dirname, 'fixtures');
const CHROME = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';

/** Serves the fixtures, optionally with CORS, and whatever pages a test defines. */
function startServer({ cors }) {
  const pages = new Map();
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    const send = (type, body) => {
      const headers = { 'Content-Type': type };
      if (cors) headers['Access-Control-Allow-Origin'] = '*';
      res.writeHead(200, headers);
      res.end(body);
    };
    if (pages.has(url.pathname)) return send('text/html', pages.get(url.pathname));
    const file = path.join(FIXTURES, path.basename(url.pathname));
    if (fs.existsSync(file)) {
      const body = fs.readFileSync(file);
      const type = file.endsWith('.mp4') ? 'video/mp4' : 'video/webm';
      const headers = { 'Content-Type': type, 'Accept-Ranges': 'bytes' };
      if (cors) headers['Access-Control-Allow-Origin'] = '*';
      // Range support, without which a browser will not seek a progressive file.
      const range = req.headers.range;
      if (range) {
        const [s, e] = range.replace('bytes=', '').split('-');
        const start = Number(s);
        const end = e ? Number(e) : body.length - 1;
        res.writeHead(206, {
          ...headers,
          'Content-Range': `bytes ${start}-${end}/${body.length}`,
          'Content-Length': end - start + 1,
        });
        return res.end(body.slice(start, end + 1));
      }
      res.writeHead(200, { ...headers, 'Content-Length': body.length });
      return res.end(body);
    }
    res.writeHead(404).end('no');
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      resolve({ server, port: server.address().port, pages });
    });
  });
}

/**
 * Boots a browser with the agent installed the way Android installs it: at document start, in
 * every frame, with the bridge exposed as a global object on the main frame only.
 */
async function launch() {
  const withCors = await startServer({ cors: true });
  const withoutCors = await startServer({ cors: false });

  const browser = await chromium.launch({
    executablePath: CHROME,
    args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio'],
  });
  const context = await browser.newContext();

  const events = { reports: [], entered: [], hints: [] };
  // The bridge Android injects with addJavascriptInterface, main frame only.
  await context.exposeBinding('__slateBridge', (source, name, a) => {
    if (name === 'report') events.reports.push(JSON.parse(a));
    if (name === 'entered') events.entered.push(a);
    if (name === 'navigationHint') events.hints.push(a);
  });
  await context.addInitScript(() => {
    if (window.top === window) {
      window.SlateMedia = {
        report: (j) => window.__slateBridge('report', j),
        entered: (ok) => window.__slateBridge('entered', ok),
        navigationHint: (s) => window.__slateBridge('navigationHint', s),
      };
    }
  });
  // Exactly what WebViewCompat.addDocumentStartJavaScript does: every frame, document start.
  await context.addInitScript({ content: AGENT });

  const page = await context.newPage();
  page.on('pageerror', (e) => events.reports.push({ pageerror: String(e) }));

  return {
    page,
    events,
    sameOrigin: withCors,
    crossOrigin: withoutCors,
    urlSame: (p) => `http://127.0.0.1:${withCors.port}${p}`,
    urlCross: (p) => `http://localhost:${withoutCors.port}${p}`,
    /** Mirrors MediaAgent.command: evaluateJavascript, which only ever runs in the main frame. */
    command: (name, arg) =>
      page.evaluate(
        ([n, a]) => window.__slateMedia && window.__slateMedia.command(n, a),
        [name, arg === undefined ? null : String(arg)]
      ),
    close: async () => {
      await browser.close();
      withCors.server.close();
      withoutCors.server.close();
    },
  };
}

/**
 * Reads the colour actually being displayed and converts it back to a timestamp. The fixture
 * paints one flat colour per second, so this reports where the video visibly is — which is the
 * only evidence that a seek did anything.
 */
const VISIBLE_SECOND = `(() => {
  const v = document.querySelector('video');
  if (!v) return -1;
  const c = document.createElement('canvas');
  c.width = 8; c.height = 8;
  const ctx = c.getContext('2d');
  ctx.drawImage(v, 0, 0, 8, 8);
  const [r] = ctx.getImageData(4, 4, 1, 1).data;
  return Math.round(r / 8);
})()`;

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = { launch, delay, VISIBLE_SECOND };
