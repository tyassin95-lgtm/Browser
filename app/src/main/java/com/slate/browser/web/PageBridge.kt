package com.slate.browser.web

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * The one way web content is allowed to talk to this application.
 *
 * `addJavascriptInterface` is the obvious way to do this and the wrong one for a browser. The
 * object it injects appears in *every* frame of the WebView, cross-origin iframes included, so
 * an advert nested three frames deep inside an unrelated site can call the same native methods
 * as the page the user is actually on, and the receiving code has no way to tell which of them
 * called. Nothing in the method signature carries the caller's identity.
 *
 * [WebViewCompat.addWebMessageListener] is the platform's answer: it delivers the source origin
 * and whether the sender is the main frame, checked here before a message is looked at. Every
 * message is also a bounded string of JSON — no method surface, no reflection, no object graph
 * — so the worst a hostile frame can do is be ignored.
 *
 * Where the feature is missing the bridge is simply not installed. A browser that cannot tell
 * who is calling should not be taking the call: the features that depend on it degrade, and
 * nothing is exposed in the meantime.
 */
class PageBridge private constructor(
    private val name: String,
    private val onMessage: (origin: String, payload: JSONObject) -> Unit,
) {

    fun install(webView: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        return runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                name,
                // Every origin may *reach* the listener; which of them are listened to is
                // decided per message, where the frame is known as well as the origin.
                setOf("*"),
                Listener(),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Every check a message must pass, in one place so it is the same on every path and can be
     * driven directly by a test. Returns whether the message was accepted.
     */
    internal fun accept(origin: String, raw: String?, isMainFrame: Boolean): Boolean {
        // Subframes are never the browser's correspondent. The media agent routes what it finds
        // up to the top document precisely so this can be true, and nothing else has any reason
        // to speak from inside an iframe.
        if (!isMainFrame) return false
        if (raw == null) return false
        // A page that sends more than this is not talking to the browser, it is trying to make
        // it do work. Generous for a state report, and cheap to enforce.
        if (raw.length > MAX_MESSAGE_CHARS) return false
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        runCatching { onMessage(origin, payload) }
        return true
    }

    private inner class Listener : WebViewCompat.WebMessageListener {
        override fun onPostMessage(
            view: WebView,
            message: WebMessageCompat,
            sourceOrigin: Uri,
            isMainFrame: Boolean,
            replyProxy: JavaScriptReplyProxy,
        ) {
            val raw = runCatching { message.data }.getOrNull()
            accept(sourceOrigin.toString(), raw, isMainFrame)
        }
    }

    companion object {
        internal const val MAX_MESSAGE_CHARS = 64 * 1024

        /**
         * Declares a bridge. Nothing is exposed until [install] is called on a WebView, and
         * [onMessage] only ever sees well-formed JSON from a main frame.
         */
        fun named(name: String, onMessage: (origin: String, payload: JSONObject) -> Unit) =
            PageBridge(name, onMessage)
    }
}
