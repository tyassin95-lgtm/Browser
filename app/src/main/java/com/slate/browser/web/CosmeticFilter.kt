package com.slate.browser.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.slate.browser.tabs.Tab

/**
 * Collapses the slots a blocked request leaves behind.
 *
 * This is the second pass, not the mechanism: the request is already refused by
 * [ContentBlocker], and what remains is an empty frame or a reserved gap where the advert was
 * going to be. The rules are deliberately few and all anchored to markup that only ad tooling
 * produces, because a cosmetic rule that guesses is how a blocker starts eating real content.
 */
class CosmeticFilter {

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
        tab.cosmeticScript = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, INJECT, setOf("*"))
        }.getOrNull()
    }

    private companion object {
        val RULES = listOf(
            "ins.adsbygoogle",
            "iframe[src*='doubleclick.net']",
            "iframe[src*='googlesyndication.com']",
            "iframe[src*='googleadservices.com']",
            "iframe[src*='adnxs.com']",
            "iframe[id^='google_ads_iframe']",
            "div[id^='google_ads_']",
            "div[id^='div-gpt-ad']",
            "div[id^='taboola-']",
            "div[class^='trc_related_container']",
            "#adsbygoogle",
        ).joinToString(",")

        val INJECT = """
            (function () {
              if (window.__slateCosmetic) return;
              window.__slateCosmetic = true;
              function inject() {
                try {
                  if (document.getElementById('slate-cosmetic')) return;
                  var head = document.head || document.documentElement;
                  if (!head) return;
                  var style = document.createElement('style');
                  style.id = 'slate-cosmetic';
                  style.textContent = "$RULES{display:none !important;}";
                  head.appendChild(style);
                } catch (e) { /* a page we cannot tidy is still a page worth showing */ }
              }
              inject();
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', inject, { once: true });
              }
            })();
        """.trimIndent()

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
