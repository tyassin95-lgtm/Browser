package com.slate.browser.web

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.slate.browser.tabs.Tab

/**
 * Hides the markup a blocked request leaves behind, and the advertising a network rule cannot
 * reach because the site serves it from its own origin.
 *
 * The selectors come from the same published lists as the network rules — the ones scoped to
 * the site being visited, plus the unscoped ones that name advertising unambiguously — so this
 * stays a second pass over community-maintained rules rather than a set of guesses of our own.
 * The stylesheet is installed at document start in every frame, before the page paints, so an
 * advert never appears and then vanishes.
 */
class CosmeticFilter(private val blocker: ContentBlocker) {

    fun apply(webView: WebView, tab: Tab, enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { tab.cosmeticScript?.remove() }
        }
        tab.cosmeticScript = null

        if (!enabled) {
            runCatching { webView.evaluateJavascript(REMOVE, null) }
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return

        val host = runCatching { Uri.parse(tab.url).host }.getOrNull().orEmpty()
        val selectors = blocker.cosmeticSelectors(host)
        if (selectors.isEmpty()) return

        val script = inject(selectors)
        // Both halves are needed and they cover different documents: the document-start hook
        // reaches every frame the page is about to create, but not the document already being
        // parsed, so that one is given the same stylesheet directly.
        tab.cosmeticScript = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }.getOrNull()
        runCatching { webView.evaluateJavascript(script, null) }
    }

    /**
     * The rules arrive as CSS text, so a selector the engine ships but this WebView cannot parse
     * would otherwise discard the whole stylesheet. They are inserted one rule at a time and the
     * failures are dropped individually.
     */
    private fun inject(selectors: List<String>): String {
        val json = selectors.joinToString(",", "[", "]") { "\"" + escape(it) + "\"" }
        return """
            (function () {
              if (window.__slateCosmetic) return;
              window.__slateCosmetic = true;
              var SELECTORS = $json;
              function inject() {
                try {
                  if (document.getElementById('slate-cosmetic')) return;
                  var head = document.head || document.documentElement;
                  if (!head) return;
                  var style = document.createElement('style');
                  style.id = 'slate-cosmetic';
                  head.appendChild(style);
                  var sheet = style.sheet;
                  if (!sheet) return;
                  for (var i = 0; i < SELECTORS.length; i++) {
                    try {
                      sheet.insertRule(SELECTORS[i] + '{display:none!important;}', sheet.cssRules.length);
                    } catch (e) { /* a selector this engine cannot parse is simply skipped */ }
                  }
                } catch (e) { /* a page we cannot tidy is still a page worth showing */ }
              }
              inject();
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', inject, { once: true });
              }
            })();
        """.trimIndent()
    }

    private fun escape(selector: String): String =
        selector.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        val REMOVE = """
            (function () {
              try {
                var style = document.getElementById('slate-cosmetic');
                if (style) style.remove();
                window.__slateCosmetic = false;
              } catch (e) { /* ignore */ }
            })();
        """.trimIndent()
    }
}
