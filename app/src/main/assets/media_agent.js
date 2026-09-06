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
  var Z_CANVAS = '2147483647';
  var Z_VIDEO_UNDER_CANVAS = '2147483646';
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
  var activeCanvas = null;
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
  var TIER_AUDIBLE = 7e8;
  var TIER_LIVE = 5e8;
  var TIER_READY = 1e6;

  /** A video the page has hidden is a decoy, however large its box claims to be. */
  function isPainted(el) {
    try {
      var style = getComputedStyle(el);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
      if (parseFloat(style.opacity || '1') < 0.05) return false;
      return true;
    } catch (e) { return true; }
  }

  function score(v) {
    try {
      var r = v.getBoundingClientRect();
      var w = Math.max(0, r.width), h = Math.max(0, r.height);
      // Tracking pixels and thumbnail tiles are never what fullscreen is for.
      if (w < 48 || h < 48) return 0;
      if (!isPainted(v)) return 0;

      var s = w * h + (v.videoWidth || 0) * (v.videoHeight || 0) / 40;
      if (!v.paused && !v.ended) s += TIER_PLAYING;
      // The element making the sound is the one being watched. Without this a large muted
      // teaser in the host page can outrank the stream itself, which is how a viewer ends up
      // looking at a black box while the audio carries on somewhere else.
      if (!v.paused && !v.muted && (v.volume === undefined || v.volume > 0)) s += TIER_AUDIBLE;
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

  function seekableWindow(v) {
    try {
      if (!v.seekable || v.seekable.length === 0) return null;
      return { start: v.seekable.start(0), end: v.seekable.end(v.seekable.length - 1) };
    } catch (e) { return null; }
  }

  function describe(v) {
    var duration = typeof v.duration === 'number' ? v.duration : NaN;
    var live = duration === Infinity || (isNaN(duration) && v.readyState > 0 && !v.paused);
    var window = seekableWindow(v);
    return {
      w: v.videoWidth || 0,
      h: v.videoHeight || 0,
      live: !!live,
      playing: !v.paused && !v.ended,
      muted: !!v.muted,
      volume: typeof v.volume === 'number' ? v.volume : 1,
      t: typeof v.currentTime === 'number' && isFinite(v.currentTime) ? v.currentTime : 0,
      d: isFinite(duration) ? duration : 0,
      ss: window && isFinite(window.start) ? window.start : 0,
      se: window && isFinite(window.end) ? window.end : 0
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
        'isolation': 'auto',
        'mix-blend-mode': 'normal',
        'transform-style': 'flat',
        'zoom': '1',
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

  /*
   * Everything that is not on the path to the video is hidden outright, rather than covered by
   * a black overlay of our own.
   *
   * An overlay looked simpler but is the wrong tool twice over. A hardware-decoded or WebRTC
   * video is composited on its own surface, and an opaque div can end up in front of it — which
   * shows as a black screen with the audio still playing. And an overlay only wins on z-index
   * within one stacking context, so any ancestor that quietly creates one puts the page's
   * furniture back on top. Hiding the siblings has neither failure mode: there is nothing left
   * to paint over the picture.
   */
  function isolateElement(el) {
    var node = el;
    var guard = 0;
    while (node && node !== document.documentElement && guard++ < 200) {
      var parent = node.parentElement;
      if (!parent) break;
      each(parent.children, function (child) {
        if (child === node) return;
        if (child.hasAttribute && child.hasAttribute('data-slate-keep')) return;
        var tag = child.tagName;
        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'LINK' || tag === 'META') return;
        stash(child);
        force(child, { 'display': 'none' });
      });
      // Letterbox bars should be black, not whatever the page painted behind the player.
      stash(parent);
      force(parent, { 'background': 'transparent' });
      node = parent;
    }
  }

  function addChrome() {
    overrideViewport();
    if (!styleTag) {
      styleTag = document.createElement('style');
      styleTag.setAttribute('data-slate-theater', '');
      styleTag.textContent =
        'html,body{overflow:hidden !important;margin:0 !important;padding:0 !important;' +
        'background:#000 !important;}';
      (document.head || document.documentElement).appendChild(styleTag);
    }
  }

  function applyFill() {
    if (activeVideo) force(activeVideo, { 'object-fit': fillMode, 'object-position': 'center' });
    if (activeCanvas) force(activeCanvas, { 'object-fit': fillMode, 'object-position': 'center' });
  }

  /** A canvas covering the same box as the video, i.e. the thing actually being drawn. */
  function companionCanvas(video) {
    try {
      var vr = video.getBoundingClientRect();
      var vArea = vr.width * vr.height;
      if (vArea <= 0) return null;
      var best = null, bestArea = 0;
      each(document.querySelectorAll('canvas'), function (c) {
        if (!isPainted(c)) return;
        var r = c.getBoundingClientRect();
        var overlap = Math.max(0, Math.min(vr.right, r.right) - Math.max(vr.left, r.left)) *
                      Math.max(0, Math.min(vr.bottom, r.bottom) - Math.max(vr.top, r.top));
        if (overlap < vArea * 0.6) return;
        var area = r.width * r.height;
        if (area > bestArea) { bestArea = area; best = c; }
      });
      return best;
    } catch (e) { return null; }
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
        } else if (activeCanvas && activeCanvas.isConnected) {
          force(activeCanvas, PINNED);
          force(activeCanvas, { 'z-index': Z_CANVAS });
          applyFill();
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
      routeChild.setAttribute('data-slate-keep', '');
      isolateElement(routeChild);
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
    activeCanvas = companionCanvas(activeVideo);

    activeVideo.setAttribute('data-slate-keep', '');
    if (activeCanvas) activeCanvas.setAttribute('data-slate-keep', '');

    isolateElement(activeVideo);
    if (activeCanvas) isolateElement(activeCanvas);

    savedControls = activeVideo.controls;
    try { activeVideo.controls = false; } catch (e) { /* ignore */ }
    pin(activeVideo);
    try { activeVideo.setAttribute('playsinline', ''); } catch (e) { /* ignore */ }

    if (activeCanvas) {
      // Some players decode into a canvas and keep the media element only for its audio and
      // timeline. Promoting both, canvas on top, shows the picture either way.
      pin(activeCanvas);
      force(activeCanvas, { 'z-index': Z_CANVAS });
    }
    applyFill();
    active = true;
    startWatchdog();
    reportProgress();
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
    each(document.querySelectorAll('[data-slate-keep]'), function (el) {
      try { el.removeAttribute('data-slate-keep'); } catch (e) { /* ignore */ }
    });
    activeCanvas = null;
    if (styleTag) { try { styleTag.remove(); } catch (e) { /* ignore */ } styleTag = null; }
    restoreViewport();
    previewClose();
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
      else if (type === 'seek') {
        var target = parseFloat(arg);
        if (isFinite(target)) {
          // Clamp into whatever the stream will actually accept, which for a live edge is the
          // seekable window rather than the duration.
          var win = seekableWindow(v);
          if (win) target = Math.max(win.start, Math.min(win.end, target));
          v.currentTime = target;
        }
      }
      else if (type === 'volume') {
        var level = parseFloat(arg);
        if (isFinite(level)) {
          v.volume = Math.max(0, Math.min(1, level));
          if (v.volume > 0) v.muted = false;
        }
      }
      else if (type === 'fill') { fillMode = arg; activeVideo = v; applyFill(); }
      else if (type === 'previewOpen') { activeVideo = activeVideo || v; previewOpen(); return; }
      else if (type === 'previewAt') { previewAt(arg); return; }
      else if (type === 'previewClose') { previewClose(); return; }
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
      volume: info && typeof info.volume === 'number' ? info.volume : 1,
      t: info ? info.t || 0 : 0,
      d: info ? info.d || 0 : 0,
      ss: info ? info.ss || 0 : 0,
      se: info ? info.se || 0 : 0,
      fullscreen: active
    };
    try {
      if (window.SlateMedia && window.SlateMedia.report) {
        window.SlateMedia.report(JSON.stringify(state));
      }
    } catch (e) { /* ignore */ }
  }

  /*
   * While fullscreen, the browser draws the transport controls, so it needs the position and
   * the seekable window continuously rather than only when something is probed. Updates ride
   * the same route back up as everything else, so a video inside an embedded player reports
   * just as readily as one in the top document.
   */
  var progressTimer = null;

  /*
   * Scrub previews.
   *
   * A second, detached video element is pointed at the same source and seeked to wherever the
   * finger is, then drawn into a small canvas. That only works when the source is a plain URL
   * the element can be given again and the server allows the pixels to be read back: a stream
   * assembled by Media Source has a blob: source no second element can open, and a cross-origin
   * file without CORS taints the canvas and makes toDataURL throw. Both are common, so the
   * failure is reported once and the browser falls back rather than retrying per frame.
   */
  var preview = null;

  function previewReport(dataUrl, time) {
    var message = { ns: NS, type: 'preview', data: dataUrl, t: time };
    if (TOP) publishPreview(message); else post(parent, message);
  }

  function publishPreview(message) {
    try {
      if (window.SlateMedia && window.SlateMedia.preview) {
        window.SlateMedia.preview(message.data || '', message.t || 0);
      }
    } catch (e) { /* ignore */ }
  }

  function previewOpen() {
    previewClose();
    var v = activeVideo;
    if (!v) { previewReport('', 0); return; }
    var src = v.currentSrc || v.src || '';
    // Nothing a second element could ever load.
    if (!src || src.lastIndexOf('blob:', 0) === 0 || src.lastIndexOf('data:', 0) === 0) {
      previewReport('', 0);
      return;
    }
    try {
      var el = document.createElement('video');
      el.muted = true;
      el.preload = 'auto';
      el.setAttribute('playsinline', '');
      el.crossOrigin = 'anonymous';
      var canvas = document.createElement('canvas');
      canvas.width = 192;
      canvas.height = 108;
      preview = { el: el, canvas: canvas, pending: null, busy: false, dead: false };
      el.addEventListener('seeked', previewDraw);
      el.addEventListener('error', previewFail);
      el.src = src;
    } catch (e) { previewFail(); }
  }

  function previewFail() {
    if (preview) preview.dead = true;
    previewReport('', 0);
  }

  function previewAt(seconds) {
    var t = parseFloat(seconds);
    if (!preview || preview.dead || !isFinite(t)) return;
    preview.pending = t;
    if (!preview.busy) previewPump();
  }

  function previewPump() {
    if (!preview || preview.dead || preview.pending === null) return;
    var t = preview.pending;
    preview.pending = null;
    preview.busy = true;
    try { preview.el.currentTime = t; } catch (e) { previewFail(); }
  }

  function previewDraw() {
    if (!preview || preview.dead) return;
    try {
      var el = preview.el;
      var canvas = preview.canvas;
      var ctx = canvas.getContext('2d');
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      // Letterboxed, never stretched: a squashed preview misreads the frame.
      var vw = el.videoWidth || 16, vh = el.videoHeight || 9;
      var scale = Math.min(canvas.width / vw, canvas.height / vh);
      var w = vw * scale, h = vh * scale;
      ctx.drawImage(el, (canvas.width - w) / 2, (canvas.height - h) / 2, w, h);
      previewReport(canvas.toDataURL('image/jpeg', 0.55), el.currentTime);
    } catch (e) {
      // A tainted canvas throws here, which is the common cross-origin case.
      previewFail();
      return;
    }
    preview.busy = false;
    if (preview.pending !== null) previewPump();
  }

  function previewClose() {
    if (!preview) return;
    try {
      preview.el.removeAttribute('src');
      preview.el.load();
    } catch (e) { /* ignore */ }
    preview = null;
  }

  function reportProgress() {
    if (progressTimer) return;
    progressTimer = setTimeout(function () {
      progressTimer = null;
      if (!active) return;
      var v = activeVideo;
      if (!v || !v.isConnected) return;
      var info = describe(v);
      if (TOP) publish(info);
      else post(parent, { ns: NS, type: 'progress', info: info });
    }, 250);
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
    if (d.type === 'preview') {
      if (TOP) publishPreview(d); else post(parent, d);
      return;
    }
    if (d.type === 'progress') {
      if (TOP) publish(d.info);
      else post(parent, { ns: NS, type: 'progress', info: d.info });
      return;
    }
    if (d.type === 'changed') { scheduleReport(); return; }
    if (d.type === 'touch') { reportTouch(d.suppress); }
  }, false);

  /*
   * The browser turns an unused horizontal swipe into back/forward, but only the page knows
   * whether the finger landed on something that pans by itself. Anything scrollable sideways, a
   * slider, an editable field or a live text selection means the swipe belongs to the page, so
   * that verdict is sent up on touch down and the native gesture stands down for that touch.
   */
  function panWidthExceeds(el) {
    try {
      var style = getComputedStyle(el);
      var overflowX = style.overflowX;
      var scrollable = overflowX === 'auto' || overflowX === 'scroll';
      if (scrollable && el.scrollWidth > el.clientWidth + 2) return true;
      // Carousels and maps commonly declare their intent instead of overflowing.
      var touchAction = style.touchAction;
      if (touchAction === 'none' || touchAction === 'pan-y' || touchAction === 'pinch-zoom') return true;
      return false;
    } catch (e) { return false; }
  }

  function ownsHorizontalTouch(target) {
    try {
      var selection = document.getSelection();
      if (selection && !selection.isCollapsed) return true;
    } catch (e) { /* ignore */ }

    var node = target;
    var guard = 0;
    while (node && node.nodeType === 1 && guard++ < 60) {
      var tag = node.tagName;
      if (tag === 'INPUT' && (node.type === 'range' || node.type === 'number')) return true;
      if (tag === 'TEXTAREA' || tag === 'SELECT' || tag === 'CANVAS' || tag === 'SVG') return true;
      if (node.isContentEditable) return true;
      if (node.getAttribute && node.getAttribute('draggable') === 'true') return true;
      if (panWidthExceeds(node)) return true;
      node = node.parentElement;
    }
    return false;
  }

  function reportTouch(suppress) {
    if (TOP) {
      try {
        if (window.SlateMedia && window.SlateMedia.navigationHint) {
          window.SlateMedia.navigationHint(!!suppress);
        }
      } catch (e) { /* ignore */ }
    } else {
      post(parent, { ns: NS, type: 'touch', suppress: !!suppress });
    }
  }

  document.addEventListener('touchstart', function (e) {
    if (!e.touches || e.touches.length !== 1) { reportTouch(true); return; }
    // Fullscreen media takes every touch itself, so navigation must not compete for it.
    reportTouch(active || ownsHorizontalTouch(e.target));
  }, true);

  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'emptied', 'resize']
    .forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (e.target && e.target.tagName === 'VIDEO') scheduleReport();
      }, true);
    });

  ['timeupdate', 'progress', 'volumechange', 'seeked', 'waiting']
    .forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (e.target === activeVideo) reportProgress();
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
