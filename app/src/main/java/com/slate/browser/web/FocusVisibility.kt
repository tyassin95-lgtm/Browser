package com.slate.browser.web

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Installs the page's half of keeping the focused field visible when the keyboard is up.
 *
 * The browser gives the page the space the keyboard leaves; the page decides where inside
 * itself the field being typed into has to move to. That division is what makes this work for
 * a field in a scroller, in a fixed bar, or inside a cross-origin frame — which is why the
 * script goes into every frame at document start rather than being driven from here.
 *
 * Unconditional: this is not a feature to be switched off, and a page with no text input never
 * runs anything beyond registering two listeners.
 */
class FocusVisibility(context: Context) {

    private val script: String by lazy {
        runCatching {
            context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    fun install(webView: WebView) {
        if (script.isEmpty()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching { WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*")) }
    }

    private companion object {
        const val ASSET = "focus_visibility.js"
    }
}
