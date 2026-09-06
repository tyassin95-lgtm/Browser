package com.slate.browser.web

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Refuses a window the page opened on its own, while still finding out where it was going.
 *
 * Returning false from `onCreateWindow` cancels the window but tells us nothing about it, which
 * leaves the user with a notice they cannot act on. Instead the window is granted to a probe
 * WebView that is never attached to anything and never allowed to run script: WebView hands it
 * the pending navigation, the probe reports the URL and is destroyed before a byte is fetched.
 *
 * That turns "a pop-up was blocked" into "a pop-up to example.com was blocked, show it?", which
 * is the difference between a warning and a decision.
 */
class PopupGuard(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** The address of the most recently refused window, if it announced one. */
    var lastBlockedUrl: String? = null
        private set

    fun clear() {
        lastBlockedUrl = null
    }

    /**
     * Consumes [resultMsg] and refuses the window. [onResolved] is called with the destination
     * once the page reveals it, which may be a moment after the window is refused.
     */
    fun refuse(resultMsg: Message, onResolved: (String) -> Unit) {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return
        val probe = WebView(context)
        probe.settings.javaScriptEnabled = false
        probe.settings.loadsImagesAutomatically = false
        probe.settings.blockNetworkLoads = true

        var finished = false
        fun resolve(url: String?) {
            if (finished) return
            finished = true
            if (!url.isNullOrBlank() && url != "about:blank") {
                lastBlockedUrl = url
                onResolved(url)
            }
            // Never destroyed from inside its own callback: WebView is still on the stack.
            main.post { runCatching { probe.destroy() } }
        }

        probe.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                resolve(request.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                resolve(url)
            }
        }

        transport.webView = probe
        resultMsg.sendToTarget()

        // A page can ask for a window and then never navigate it. Clean up regardless.
        main.postDelayed({ resolve(null) }, PROBE_TIMEOUT_MS)
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 2_000L
    }
}
