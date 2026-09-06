package com.slate.browser.web

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.slate.browser.tabs.Tab

/**
 * The in-page half of the anti-popup defences.
 *
 * [NavigationPolicy] and [PopupGuard] decide what the browser will carry out; this decides what
 * the page is able to ask for in the first place, which is the only place some of these tricks
 * can be caught: a transparent layer over a play button, a window opened six times from one
 * tap, a notification prompt on arrival and a page that takes fullscreen for an advert are all
 * the site's own first-party script and never touch the network.
 *
 * The script is installed at document start in every frame, including cross-origin ones, so a
 * page cannot get its first tap in before the guard exists.
 */
class InPageGuard(context: Context) {

    private val script: String by lazy {
        runCatching {
            context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    fun apply(webView: WebView, tab: Tab, enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { tab.guardScript?.remove() }
        }
        tab.guardScript = null
        if (!enabled || script.isEmpty()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        tab.guardScript = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }.getOrNull()
    }

    private companion object {
        const val ASSET = "popup_guard.js"
    }
}
