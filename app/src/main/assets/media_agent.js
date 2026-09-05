/*
 * Slate media agent.
 *
 * Runs at document start in EVERY frame, including cross-origin ones. Its job is to let the
 * browser present any video full screen without asking the page's permission, because the
 * pages that need it most are exactly the ones that will not cooperate: players with no
 * fullscreen button, videos inside iframes that lack `allowfullscreen`, and mobile layouts that
 * crop the stream inside a fixed-size box.
 *
 * Rather than calling the Fullscreen API, the agent pins the video element itself to the
 * viewport and neutralises whatever the page had done to constrain it. The browser hides its
 * own chrome and the system bars around that, so the result is a real fullscreen view that
 * works even where the site offers none.
 *
 * Frames talk to each other with postMessage; only the top frame talks to the browser.
 */
(function () {
  'use strict';
  if (window.__slateMedia) return;

  var NS = 'slate.media.v1';
  var TOP = window.top === window;
  var Z_VIDEO = '2147483647';
  var Z_BACKDROP = '2147483646';
  /*
   * A probe walks down the frame tree and the answers walk back up, so every level must reply
   * before the level above stops listening. Each hop therefore gets a smaller budget than its
   * parent: enough for players nested behind an ad wrapper or a CDN embed, without making the
   * common single-frame page wait.
   */
  var PROBE_BUDGET_MS = 300;
  var PROBE_HOP_COST_MS = 90;
  var PROBE_MIN_BUDGET_MS = 60;

  var active = false;
  var activeVideo = null;
  var routeChild = null;      // the <iframe> on the way to the winning video, if any
  var winnerChild = null;     // set by the most recent probe
  var fillMode = 'contain';
  var stashed = [];           // [{ el, style }] to restore on exit
  var savedControls = null;
  var backdrop = null;
  var styleTag = null;
  var watchdog = null;
  var savedViewport = null;
  var probes = {};
  var reportTimer = null;

  // ---------------------------------------------------------------- helpers

  function each(list, fn) { Array.prototype.forEach.call(list || [], fn); }
  function frames() { return document.querySelectorAll('iframe,frame'); }

  function post(win, msg) {
    try { if (win) win.postMessage(msg, '*'); } catch (e) { /* cross-origin, nothing to do */ }
  }

  function toChildren(msg) {
    each(frames(), function (f) { post(f.contentWindow, msg); });
  }

  function childFor(win) {
    var hit = null;
    each(frames(), function (f) {
      try { if (f.contentWindow === win) hit = f; } catch (e) { /* ignore */ }
    });
    return hit;
  }

  /*
   * Ranking is by tier first and size second, never size alone. A page can easily carry a large
   * paused hero video or preview element next to the small stream the viewer is actually
   * watching, and area on its own picks the wrong one. Something that is playing always beats
   * something that is not, and a live stream beats a recorded one; size only breaks ties within
   * a tier. The offsets are far larger than any plausible pixel area, so the ordering holds.
   */
  var TIER_PLAYING = 1e9;
  var TIER_LIVE = 5e8;
  var TIER_READY = 1e6;

  function score(v) {
    try {
      var r = v.getBoundingClientRect();
      var w = Math.max(0, r.width), h = Math.max(0, r.height);
      // Tracking pixels and thumbnail tiles are never what fullscreen is for.
      if (w < 48 || h < 48) return 0;

      var s = w * h + (v.videoWidth || 0) * (v.videoHeight || 0) / 40;
      if (!v.paused && !v.ended) s += TIER_PLAYING;
      if (v.duration === Infinity) s += TIER_LIVE;
      if (v.readyState > 0) s += TIER_READY;
      return s;
    } catch (e) { return 0; }
  }

  function bestLocal() {
    var best = null, bestScore = 0;
    each(document.querySelectorAll('video'), function (v) {
      var s = score(v);
      if (s > bestScore) { bestScore = s; best = v; }
    });
    return best ? { el: best, score: bestScore } : null;
  }

  function describe(v) {
    return {
      w: v.videoWidth || 0,
      h: v.videoHeight || 0,
      live: v.duration === Infinity,
      playing: !v.paused && !v.ended,
      muted: !!v.muted
    };
  }

  // ------------------------------------------------------------- maximising

  function stash(el) {
    stashed.push({ el: el, style: el.getAttribute('style') });
  }

  function force(el, rules) {
    for (var name in rules) {
      try { el.style.setProperty(name, rules[name], 'important'); } catch (e) { /* ignore */ }
    }
  }

  /**
   * Any ancestor carrying transform, filter, contain, backdrop-filter or will-change becomes
   * the containing block for `position: fixed`, which is what traps a "fullscreen" video inside
   * a small box. Overflow and clipping do the same to anything that escapes. Undoing those on
   * the whole ancestor chain is what makes pinning the video to the viewport actually work.
   */
  function neutraliseAncestors(el) {
    var node = el.parentElement;
    var guard = 0;
    while (node && node !== document.documentElement && guard++ < 200) {
      stash(node);
      force(node, {
        'transform': 'none',
        'filter': 'none',
        'backdrop-filter': 'none',
        'perspective': 'none',
        'contain': 'none',
        'content-visibility': 'visible',
        'clip-path': 'none',
        'mask': 'none',
        'will-change': 'auto',
        'overflow': 'visible',
        'opacity': '1',
        'z-index': 'auto',
        'display': node.style.display === 'none' ? 'block' : (getComputedStyle(node).display === 'none' ? 'block' : '')
      });
      node = node.parentElement;
    }
  }

  var PINNED = {
    'position': 'fixed',
    'top': '0',
    'left': '0',
    'right': '0',
    'bottom': '0',
    'width': '100vw',
    'height': '100vh',
    'max-width': 'none',
    'max-height': 'none',
    'min-width': '0',
    'min-height': '0',
    'margin': '0',
    'padding': '0',
    'border': '0',
    'border-radius': '0',
    'transform': 'none',
    'clip-path': 'none',
    'float': 'none',
    'background': '#000',
    'opacity': '1',
    'visibility': 'visible',
    'display': 'block',
    'z-index': Z_VIDEO
  };

  function pin(el) {
    neutraliseAncestors(el);
    stash(el);
    force(el, PINNED);
  }

  /*
   * A page left zoomed in, or one rendered at desktop width, lays out `position: fixed` against
   * the layout viewport rather than what is actually on screen — so the pinned video would be
   * cropped to whatever part of the page happens to be visible. Pinning the viewport to the
   * device for the duration puts 100vw/100vh back in step with the display, and viewport-fit
   * lets the picture run into the cutout.
   */
  function overrideViewport() {
    if (savedViewport) return;
    var meta = document.querySelector('meta[name="viewport"]');
    if (meta) {
      savedViewport = { el: meta, content: meta.getAttribute('content'), added: false };
    } else {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'viewport');
      (document.head || document.documentElement).appendChild(meta);
      savedViewport = { el: meta, content: null, added: true };
    }
    meta.setAttribute(
      'content',
      'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover'
    );
  }

  function restoreViewport() {
    if (!savedViewport) return;
    try {
      if (savedViewport.added) savedViewport.el.remove();
      else if (savedViewport.content === null) savedViewport.el.removeAttribute('content');
      else savedViewport.el.setAttribute('content', savedViewport.content);
    } catch (e) { /* ignore */ }
    savedViewport = null;
  }

  function addChrome() {
    overrideViewport();
    if (!backdrop) {
      backdrop = document.createElement('div');
      backdrop.setAttribute('data-slate-backdrop', '');
      force(backdrop, {
        'position': 'fixed', 'top': '0', 'left': '0',
        'width': '100vw', 'height': '100vh',
        'margin': '0', 'background': '#000', 'z-index': Z_BACKDROP
      });
      (document.body || document.documentElement).appendChild(backdrop);
    }
    if (!styleTag) {
      styleTag = document.createElement('style');
      styleTag.setAttribute('data-slate-theater', '');
      styleTag.textContent =
        'html,body{overflow:hidden !important;margin:0 !important;background:#000 !important;}' +
        // Nothing the page anchors to the viewport may sit over the stream.
        'body>*:not([data-slate-backdrop]){pointer-events:none !important;}';
      (document.head || document.documentElement).appendChild(styleTag);
    }
  }

  function applyFill() {
    if (activeVideo) force(activeVideo, { 'object-fit': fillMode, 'object-position': 'center' });
  }

  /**
   * Players such as video.js and JW rewrite inline dimensions whenever they think the layout
   * changed, which would quietly shrink the video back into its box. Re-asserting a few times a
   * second costs nothing and only runs while fullscreen is actually on screen.
   */
  function startWatchdog() {
    stopWatchdog();
    watchdog = setInterval(function () {
      if (!active) return stopWatchdog();
      try {
        if (activeVideo && activeVideo.isConnected) {
          force(activeVideo, PINNED);
          applyFill();
          if (activeVideo.controls) activeVideo.controls = false;
        } else if (routeChild && routeChild.isConnected) {
          force(routeChild, PINNED);
        } else if (activeVideo) {
          // The player replaced its media element; adopt the new one rather than dropping out.
          var next = bestLocal();
          if (next) { activeVideo = next.el; pin(activeVideo); applyFill(); }
        }
      } catch (e) { /* ignore */ }
    }, 400);
  }

  function stopWatchdog() {
    if (watchdog) { clearInterval(watchdog); watchdog = null; }
  }

  // ------------------------------------------------------------ enter/exit

  function enter(fill) {
    fillMode = fill || 'contain';
    addChrome();

    if (winnerChild && winnerChild.isConnected) {
      // The video lives further down; make the path to it fill the screen and pass it on.
      routeChild = winnerChild;
      pin(routeChild);
      force(routeChild, { 'background': '#000' });
      post(routeChild.contentWindow, { ns: NS, type: 'enter', fill: fillMode });
      active = true;
      startWatchdog();
      return true;
    }

    var local = bestLocal();
    if (!local) { cleanup(); return false; }

    activeVideo = local.el;
    savedControls = activeVideo.controls;
    try { activeVideo.controls = false; } catch (e) { /* ignore */ }
    pin(activeVideo);
    applyFill();
    try { activeVideo.setAttribute('playsinline', ''); } catch (e) { /* ignore */ }
    active = true;
    startWatchdog();
    return true;
  }

  function cleanup() {
    stopWatchdog();
    for (var i = stashed.length - 1; i >= 0; i--) {
      var s = stashed[i];
      try {
        if (s.style === null) s.el.removeAttribute('style');
        else s.el.setAttribute('style', s.style);
      } catch (e) { /* ignore */ }
    }
    stashed = [];
    if (activeVideo && savedControls !== null) {
      try { activeVideo.controls = savedControls; } catch (e) { /* ignore */ }
    }
    savedControls = null;
    if (backdrop) { try { backdrop.remove(); } catch (e) { /* ignore */ } backdrop = null; }
    if (styleTag) { try { styleTag.remove(); } catch (e) { /* ignore */ } styleTag = null; }
    restoreViewport();
    active = false;
    activeVideo = null;
    routeChild = null;
  }

  function exit() {
    toChildren({ ns: NS, type: 'exit' });
    cleanup();
  }

  // ------------------------------------------------------------- commands

  /** Commands act on whichever frame owns the video, so they follow the same route as `enter`. */
  function apply(type, arg) {
    if (routeChild && routeChild.isConnected) {
      post(routeChild.contentWindow, { ns: NS, type: 'cmd', cmd: type, arg: arg });
      return;
    }
    var v = activeVideo || (bestLocal() || {}).el;
    if (!v) return;
    try {
      if (type === 'playPause') { if (v.paused) v.play(); else v.pause(); }
      else if (type === 'mute') { v.muted = !v.muted; }
      else if (type === 'fill') { fillMode = arg; activeVideo = v; applyFill(); }
    } catch (e) { /* ignore */ }
    scheduleReport();
  }

  // ---------------------------------------------------------------- probing

  function probe(id, replyTo, budget, onSettled) {
    var local = bestLocal();
    var state = {
      best: local ? { score: local.score, info: describe(local.el), child: null } : null,
      replyTo: replyTo,
      done: false,
      onSettled: onSettled
    };
    probes[id] = state;

    var childBudget = Math.max(PROBE_MIN_BUDGET_MS, budget - PROBE_HOP_COST_MS);
    var kids = frames();
    if (!kids.length) {
      settle(id);
      return;
    }
    toChildren({ ns: NS, type: 'probe', id: id, budget: childBudget });
    setTimeout(function () { settle(id); }, budget);
  }

  function settle(id) {
    var state = probes[id];
    if (!state || state.done) return;
    state.done = true;
    delete probes[id];

    winnerChild = state.best ? state.best.child : null;
    var payload = state.best ? state.best.info : null;
    var best = state.best ? state.best.score : 0;

    if (state.replyTo) {
      post(state.replyTo, { ns: NS, type: 'probeResult', id: id, score: best, info: payload });
    }
    if (TOP) publish(payload);
    if (state.onSettled) {
      try { state.onSettled(); } catch (e) { /* ignore */ }
    }
  }

  function publish(info) {
    if (!TOP) return;
    var state = {
      hasVideo: !!info,
      playing: !!(info && info.playing),
      live: !!(info && info.live),
      muted: !!(info && info.muted),
      w: info ? info.w : 0,
      h: info ? info.h : 0,
      fullscreen: active
    };
    try {
      if (window.SlateMedia && window.SlateMedia.report) {
        window.SlateMedia.report(JSON.stringify(state));
      }
    } catch (e) { /* ignore */ }
  }

  var probeSeq = 0;
  function scan() { probe(NS + ':' + (++probeSeq), null, PROBE_BUDGET_MS); }

  function scheduleReport() {
    if (reportTimer) return;
    reportTimer = setTimeout(function () {
      reportTimer = null;
      if (TOP) scan();
      else post(parent, { ns: NS, type: 'changed' });
    }, 250);
  }

  // ---------------------------------------------------------------- wiring

  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || d.ns !== NS) return;

    var fromParent = e.source === window.parent && !TOP;
    var fromChild = childFor(e.source);

    if (fromParent) {
      if (d.type === 'probe') { probe(d.id, e.source, d.budget || PROBE_MIN_BUDGET_MS); return; }
      if (d.type === 'enter') { enter(d.fill); return; }
      if (d.type === 'exit') { exit(); return; }
      if (d.type === 'cmd') { apply(d.cmd, d.arg); return; }
      return;
    }

    if (!fromChild) return;   // ignore anything that is not our own parent or child

    if (d.type === 'probeResult') {
      var state = probes[d.id];
      if (!state || state.done) return;
      if (d.score > 0 && (!state.best || d.score > state.best.score)) {
        state.best = { score: d.score, info: d.info, child: fromChild };
      }
      return;
    }
    if (d.type === 'changed') { scheduleReport(); }
  }, false);

  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'emptied', 'resize']
    .forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (e.target && e.target.tagName === 'VIDEO') scheduleReport();
      }, true);
    });

  window.__slateMedia = {
    command: function (name, arg) {
      try {
        if (name === 'scan') { scan(); return true; }
        if (name === 'exit') { exit(); publish(null); scheduleReport(); return true; }
        if (name === 'enter') {
          // Entering waits on the probe itself rather than a fixed delay, so a deeply nested
          // player is never missed because a timer fired first.
          probe(NS + ':enter:' + (++probeSeq), null, PROBE_BUDGET_MS, function () {
            var ok = enter(arg);
            try {
              if (window.SlateMedia && window.SlateMedia.entered) window.SlateMedia.entered(!!ok);
            } catch (e) { /* ignore */ }
          });
          return true;
        }
        apply(name, arg);
        return true;
      } catch (e) { return false; }
    },
    isActive: function () { return active; }
  };

  if (TOP) {
    if (document.readyState === 'complete' || document.readyState === 'interactive') scheduleReport();
    else document.addEventListener('DOMContentLoaded', scheduleReport, { once: true });
  }
})();
