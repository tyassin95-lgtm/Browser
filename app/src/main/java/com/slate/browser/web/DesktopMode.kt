package com.slate.browser.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.slate.browser.tabs.Tab

/**
 * Makes "desktop site" mean what the user expects.
 *
 * Swapping the user-agent is only half of it. A responsive site lays out from its own
 * `<meta name="viewport">`, so with `width=device-width` it still renders the phone layout no
 * matter what the UA claims. Widening the layout viewport is what actually produces the desktop
 * version, and it is the half that browsers which "only change the UA" get wrong.
 */
class DesktopMode {

    /**
     * Applied at document start where the WebView supports it, so the page never lays out at
     * phone width first and reflows. Older WebViews fall back to [reassert] after load.
     */
    fun apply(webView: WebView, tab: Tab, enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { tab.desktopScript?.remove() }
        }
        tab.desktopScript = null

        if (!enabled) {
            runCatching { webView.evaluateJavascript(RESTORE, null) }
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        tab.desktopScript = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, FORCE_WIDE, setOf("*"))
        }.getOrNull()
    }

    /** Re-applies after a load for WebViews without document-start injection. */
    fun reassert(webView: WebView, enabled: Boolean) {
        if (!enabled) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching { webView.evaluateJavascript(FORCE_WIDE, null) }
    }

    private companion object {
        const val DESKTOP_WIDTH = 1024

        /**
         * Pins the layout viewport wide and keeps it there: single-page apps rewrite the
         * viewport tag on navigation, and a site that does so would otherwise snap back to its
         * mobile layout without a reload.
         */
        val FORCE_WIDE = """
            (function () {
              if (window.__slateDesktop) { window.__slateDesktop.apply(); return; }
              var CONTENT = 'width=$DESKTOP_WIDTH, initial-scale=1';
              function apply() {
                try {
                  var head = document.head || document.documentElement;
                  if (!head) return;
                  var tags = document.querySelectorAll('meta[name="viewport"]');
                  for (var i = 0; i < tags.length; i++) {
                    if (tags[i].getAttribute('data-slate-desktop') === null) tags[i].remove();
                  }
                  var meta = document.querySelector('meta[name="viewport"][data-slate-desktop]');
                  if (!meta) {
                    meta = document.createElement('meta');
                    meta.setAttribute('name', 'viewport');
                    meta.setAttribute('data-slate-desktop', '');
                    head.appendChild(meta);
                  }
                  if (meta.getAttribute('content') !== CONTENT) meta.setAttribute('content', CONTENT);
                } catch (e) { /* a page we cannot widen is still a page worth showing */ }
              }
              window.__slateDesktop = { apply: apply };
              apply();
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', apply, { once: true });
              }
              // Frameworks re-render the head; keep the wide viewport asserted when they do.
              try {
                new MutationObserver(apply).observe(document.documentElement, {
                  childList: true, subtree: true, attributeFilter: ['content']
                });
              } catch (e) { /* ignore */ }
            })();
        """.trimIndent()

        val RESTORE = """
            (function () {
              try {
                var tags = document.querySelectorAll('meta[name="viewport"][data-slate-desktop]');
                for (var i = 0; i < tags.length; i++) tags[i].remove();
                window.__slateDesktop = null;
              } catch (e) { /* ignore */ }
            })();
        """.trimIndent()
    }
}
