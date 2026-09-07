'use strict';
/**
 * A minimal DOM harness for the media agent.
 *
 * jsdom has no layout engine, so element geometry and media element state are supplied
 * explicitly. That is a fair trade: the parts of the agent worth testing are its element
 * selection, the CSS it forces, and whether it puts the page back exactly as it found it.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const AGENT = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'media_agent.js'),
  'utf8'
);

function createPage(html, options = {}) {
  const dom = new JSDOM(html, {
    runScripts: 'outside-only',
    pretendToBeVisual: true,
    url: options.url || 'https://example.test/',
  });
  const win = dom.window;

  // Geometry: elements report whatever a test pinned on them, zero otherwise.
  win.Element.prototype.getBoundingClientRect = function () {
    const r = this.__rect || { width: 0, height: 0 };
    return { width: r.width, height: r.height, top: 0, left: 0, right: r.width, bottom: r.height,
             x: 0, y: 0, toJSON() { return this; } };
  };

  // jsdom implements no media pipeline, so these would otherwise fill the output with
  // "not implemented" notices from calls that are perfectly ordinary on a device.
  win.HTMLMediaElement.prototype.load = function () {};
  win.HTMLMediaElement.prototype.play = function () { return Promise.resolve(); };
  win.HTMLMediaElement.prototype.pause = function () {};

  const reports = [];
  const entered = [];
  win.SlateMedia = {
    report: (json) => reports.push(JSON.parse(json)),
    entered: (ok) => entered.push(ok),
  };

  win.eval(AGENT);
  return { dom, win, doc: win.document, reports, entered };
}

/** Gives a <video> the geometry and playback state jsdom will not produce on its own. */
function describeVideo(v, spec) {
  v.__rect = { width: spec.width || 0, height: spec.height || 0 };
  define(v, 'videoWidth', spec.videoWidth || 0);
  define(v, 'videoHeight', spec.videoHeight || 0);
  define(v, 'paused', spec.paused !== undefined ? spec.paused : true);
  define(v, 'ended', false);
  define(v, 'readyState', spec.readyState !== undefined ? spec.readyState : 4);
  define(v, 'duration', spec.live ? Infinity : (spec.duration || 120));
  v.play = function () { define(v, 'paused', false); return Promise.resolve(); };
  v.pause = function () { define(v, 'paused', true); };
  return v;
}

function define(obj, name, value) {
  Object.defineProperty(obj, name, { value, configurable: true, writable: true });
}

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Adds a child frame and installs the agent inside it, standing in for the embedded player
 * that livestream sites almost always use.
 *
 * jsdom leaves MessageEvent.source null, and the agent authenticates every frame message by
 * comparing source against window.parent or an iframe's contentWindow. So the link is modelled
 * the way browsers actually behave: each side sees the other through a stable WindowProxy whose
 * postMessage stamps the matching source. That identity is what real cross-origin routing rests
 * on too, since it is the one thing a page cannot forge.
 */
function addFrame(page, html, rect) {
  const iframe = page.doc.createElement('iframe');
  iframe.__rect = rect || { width: 320, height: 180 };
  page.doc.body.appendChild(iframe);

  const childWin = iframe.contentWindow;
  const childDoc = childWin.document;
  childDoc.body.innerHTML = html;

  childWin.Element.prototype.getBoundingClientRect = function () {
    const r = this.__rect || { width: 0, height: 0 };
    return { width: r.width, height: r.height, top: 0, left: 0, right: r.width, bottom: r.height,
             x: 0, y: 0, toJSON() { return this; } };
  };

  // What the parent holds as iframe.contentWindow, and what the child holds as window.parent.
  const childProxy = { postMessage: (data) => dispatch(childWin, data, parentProxy) };
  const parentProxy = { postMessage: (data) => dispatch(page.win, data, childProxy) };

  Object.defineProperty(iframe, 'contentWindow', { value: childProxy, configurable: true });
  Object.defineProperty(childWin, 'parent', { value: parentProxy, configurable: true });

  childWin.eval(AGENT);

  return { iframe, win: childWin, doc: childDoc };
}

function dispatch(targetWin, data, source) {
  setTimeout(() => {
    const event = new targetWin.MessageEvent('message', { data });
    Object.defineProperty(event, 'source', { value: source, configurable: true });
    targetWin.dispatchEvent(event);
  }, 0);
}

module.exports = { createPage, describeVideo, define, delay, addFrame };
