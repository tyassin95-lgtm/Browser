/*
 * In-page defences against the tricks a filter list cannot see.
 *
 * Network rules stop known advertising from loading. They do nothing about a page that opens a
 * window from its own code, lays a transparent link over its own play button, or asks for
 * notification permission before the user has read a word — all of which are the site's own
 * first-party script, served from the site's own origin. Those are what this handles, and it
 * handles them by tying every one of them to a real user gesture rather than by recognising
 * particular sites.
 *
 * Injected at document start in every frame.
 */
(function () {
  if (window.__slatePopupGuard) return;
  window.__slatePopupGuard = true;

  var doc = document;

  /**
   * Whether the page is acting on something the user just did.
   *
   * `navigator.userActivation` is the platform's own answer to this question, so it is used
   * where it exists; the fallback is the last trusted pointer event this frame saw. Either way
   * a gesture is spent once, which is what stops one tap becoming six windows.
   */
  var lastGesture = 0;
  var spent = false;
  /**
   * Whether the gesture in hand has reached a click.
   *
   * This is the line between "open in a new window" and a pop-under. A window opened from
   * `pointerdown` is opened before the tap has finished, which is how the tap ends up spent on
   * the advert instead of on the play button underneath — and no ordinary control opens a
   * window that early. So a window is granted for a click, and not for the events leading up
   * to one.
   */
  var fromClick = false;

  function markGesture(e) {
    if (!e || !e.isTrusted) return;
    lastGesture = Date.now();
    spent = false;
    fromClick = e.type === 'click' || e.type === 'keydown';
  }

  ['pointerdown', 'mousedown', 'touchstart', 'keydown', 'click'].forEach(function (type) {
    try {
      window.addEventListener(type, markGesture, { capture: true, passive: true });
    } catch (e) { /* a frame that refuses listeners still gets the wrappers below */ }
  });

  function activationAvailable() {
    try {
      if (navigator.userActivation && typeof navigator.userActivation.isActive === 'boolean') {
        return navigator.userActivation.isActive;
      }
    } catch (e) { /* fall through to the observed gesture */ }
    // The transient-activation window the platform itself uses; not a delay of our choosing.
    return Date.now() - lastGesture < 5000;
  }

  function claimGesture() {
    if (spent) return false;
    if (!fromClick) return false;
    if (!activationAvailable()) return false;
    spent = true;
    return true;
  }

  // ---- Windows the page opens by itself -----------------------------------------------
  var nativeOpen = window.open;
  try {
    Object.defineProperty(window, 'open', {
      configurable: true,
      writable: true,
      value: function () {
        if (!claimGesture()) return null;
        try {
          return nativeOpen.apply(window, arguments);
        } catch (e) {
          return null;
        }
      },
    });
  } catch (e) { /* a frame that will not let the property be replaced keeps the browser-side guard */ }

  // A pop-under works by opening a window and then pulling the original back in front. With
  // one window per gesture the second half is pointless, and denying it removes the flicker.
  ['blur', 'focus'].forEach(function (name) {
    try {
      var original = window[name];
      Object.defineProperty(window, name, {
        configurable: true,
        writable: true,
        value: function () {
          if (name === 'focus' && window.top === window) return original.apply(window, arguments);
          return undefined;
        },
      });
    } catch (e) { /* ignore */ }
  });

  // ---- Permission prompts nobody asked for --------------------------------------------
  // A notification prompt on arrival is never the user's idea. Refusing it in the page means
  // no prompt is raised at all, and a site that asks again after a real interaction still can.
  try {
    if (window.Notification && Notification.requestPermission) {
      var nativeRequest = Notification.requestPermission.bind(Notification);
      Notification.requestPermission = function (callback) {
        if (activationAvailable()) return nativeRequest(callback);
        if (typeof callback === 'function') { try { callback('denied'); } catch (e) { /* ignore */ } }
        return Promise.resolve('denied');
      };
    }
  } catch (e) { /* ignore */ }

  // ---- Fullscreen taken without being asked -------------------------------------------
  // Sites use fullscreen to hide the browser's own chrome around an advert. The API is meant
  // to need activation; enforcing that is enough.
  try {
    var proto = Element.prototype;
    var nativeFullscreen = proto.requestFullscreen || proto.webkitRequestFullscreen;
    if (nativeFullscreen) {
      var guarded = function () {
        if (!activationAvailable()) return Promise.reject(new Error('blocked'));
        try {
          var result = nativeFullscreen.apply(this, arguments);
          return result && result.catch ? result : Promise.resolve();
        } catch (e) {
          return Promise.reject(e);
        }
      };
      if (proto.requestFullscreen) proto.requestFullscreen = guarded;
      if (proto.webkitRequestFullscreen) proto.webkitRequestFullscreen = guarded;
    }
  } catch (e) { /* ignore */ }

  // ---- Exit traps ----------------------------------------------------------------------
  // "Are you sure you want to leave?" is a dialogue the page uses to keep a user it is about
  // to redirect. Nothing legitimate on a page like this needs it.
  try {
    Object.defineProperty(window, 'onbeforeunload', {
      configurable: true,
      get: function () { return null; },
      set: function () { /* refused */ },
    });
  } catch (e) { /* ignore */ }

  // ---- Click hijacking -------------------------------------------------------------------
  /**
   * The transparent layer over the play button.
   *
   * The trick is always the same shape: an element that covers most of what the user is
   * looking at, carries no content of its own, and is either invisible or a bare click
   * catcher. A tap on it opens the advert instead of playing the video.
   *
   * The response is to identify the layer from what it is — not from where it came from — and
   * pass the tap to whatever it is covering, so the first tap does what the user meant.
   */
  function isOverlay(el) {
    if (!el || el === doc.body || el === doc.documentElement) return false;
    if (el.nodeType !== 1) return false;
    var rect;
    try { rect = el.getBoundingClientRect(); } catch (e) { return false; }
    var vw = window.innerWidth || 1;
    var vh = window.innerHeight || 1;
    // It has to be big: a small transparent element is a spacer, not a trap.
    if (rect.width < vw * 0.5 || rect.height < vh * 0.4) return false;

    var style;
    try { style = window.getComputedStyle(el); } catch (e) { return false; }
    if (!style || style.pointerEvents === 'none') return false;
    if (style.position !== 'fixed' && style.position !== 'absolute') return false;

    var invisible =
      parseFloat(style.opacity || '1') < 0.15 ||
      style.backgroundColor === 'transparent' ||
      style.backgroundColor === 'rgba(0, 0, 0, 0)';
    if (!invisible) return false;

    // And it has to be empty: an element with real content is real content.
    var text = (el.textContent || '').trim();
    if (text.length > 0) return false;
    if (el.querySelector('img, video, canvas, svg, input, button')) return false;
    return true;
  }

  function beneath(el, x, y) {
    var previous = el.style.getPropertyValue('display');
    var priority = el.style.getPropertyPriority('display');
    el.style.setProperty('display', 'none', 'important');
    var under = null;
    try { under = doc.elementFromPoint(x, y); } catch (e) { /* ignore */ }
    if (previous) el.style.setProperty('display', previous, priority);
    else el.style.removeProperty('display');
    return under;
  }

  /**
   * Only the click is intercepted, not the pointer events before it.
   *
   * A tap on a hijacked player still has to reach the page's own handlers — that is how the
   * video starts — so the earlier events are left alone and only the event that carries the
   * navigation is taken. The window the page tries to open off the same tap is handled by the
   * one-per-gesture rule above rather than by silencing the page.
   */
  function onClick(e) {
    if (!e.isTrusted) return;
    var target = e.target;
    var overlay = null;
    for (var node = target, depth = 0; node && depth < 4; node = node.parentElement, depth++) {
      if (isOverlay(node)) { overlay = node; break; }
    }
    if (!overlay) return;

    var under = beneath(overlay, e.clientX, e.clientY);
    // Nothing underneath means the layer is the page; leave it alone.
    if (!under || under === doc.body || under === doc.documentElement) return;

    e.stopPropagation();
    e.preventDefault();
    // The layer is gone for good: it exists only to intercept, and it will be rebuilt by the
    // page if the page genuinely needs one.
    overlay.style.setProperty('pointer-events', 'none', 'important');
    try { under.click(); } catch (err) { /* ignore */ }
  }

  try {
    window.addEventListener('click', onClick, true);
  } catch (e) { /* ignore */ }
})();
