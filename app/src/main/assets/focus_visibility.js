/*
 * Keeps the field being typed into visible when the keyboard takes part of the window.
 *
 * The browser gives the page the smaller viewport the keyboard leaves; this is the page's half
 * of that, and it lives here because only the page knows where its focused element actually is
 * — inside a scroller, a fixed bar, a frame, or an editor that moves its own caret.
 *
 * It listens to the viewport itself rather than to any signal from the browser side, so it
 * fires exactly when the space really changes, with no delay to guess at and no assumption
 * about how tall a keyboard is. `block: 'nearest'` means an element already in view is left
 * alone, so this neither fights the engine if it scrolls first nor moves the page when there
 * is nothing to fix.
 *
 * Injected at document start in every frame.
 */
(function () {
  if (window.__slateFocusVisibility) return;
  window.__slateFocusVisibility = true;

  function editable(el) {
    if (!el || el.nodeType !== 1 || el === document.body) return false;
    var name = el.tagName;
    if (name === 'INPUT') {
      // Buttons and checkboxes are focusable but nothing is typed into them.
      return !/^(button|submit|reset|checkbox|radio|file|image|range|color|hidden)$/i
        .test(el.type || 'text');
    }
    if (name === 'TEXTAREA' || name === 'SELECT') return true;
    return el.isContentEditable === true;
  }

  function reveal() {
    var el = document.activeElement;
    if (!editable(el)) return;
    var rect;
    try { rect = el.getBoundingClientRect(); } catch (e) { return; }
    if (!rect || (rect.width === 0 && rect.height === 0)) return;

    // What the user can actually see. The visual viewport is the honest answer where it
    // exists, because it already accounts for pinch-zoom and for an on-screen keyboard the
    // engine has told the page about.
    var view = window.visualViewport;
    var top = 0;
    var bottom = view ? view.height : (document.documentElement.clientHeight || window.innerHeight);
    if (rect.top >= top && rect.bottom <= bottom) return;

    try {
      el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    } catch (e) {
      try { el.scrollIntoView(false); } catch (err) { /* a page that refuses is left alone */ }
    }
  }

  // Resizing is what the keyboard does to the page, in both directions.
  try { window.addEventListener('resize', reveal, { passive: true }); } catch (e) { /* ignore */ }
  if (window.visualViewport) {
    try {
      window.visualViewport.addEventListener('resize', reveal, { passive: true });
    } catch (e) { /* ignore */ }
  }
  // And a field focused while the keyboard is already up gets the same treatment.
  try { window.addEventListener('focusin', reveal, { passive: true }); } catch (e) { /* ignore */ }
})();
