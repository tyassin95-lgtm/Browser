package com.slate.browser.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.slate.browser.tabs.Tab
import com.slate.browser.util.UrlUtils

/**
 * Navigation policy for a single tab.
 *
 * Everything that is not plain http(s) is handed to the rest of the device — dialer, mail,
 * store, banking apps — because a browser that swallows those links is broken in ways users
 * notice immediately.
 */
class SlateWebViewClient(
    private val tab: Tab,
    private val host: BrowserHost,
    private val pageStarted: (Tab, String) -> Unit,
    private val pageFinished: (Tab, String, String) -> Unit,
    private val onSslPrompt: (String, () -> Unit, () -> Unit) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (UrlUtils.isHttpLike(url)) return false
        return handleExternal(url)
    }

    private fun handleExternal(url: String): Boolean {
        if (url.startsWith("about:") || url.startsWith("javascript:")) return false
        if (host.openExternally(url)) return true

        // An `intent:` URL may nominate a web page to use when no app is installed.
        val fallback = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
        }.getOrNull()
        if (fallback != null && UrlUtils.isHttpLike(fallback)) {
            tab.webView?.loadUrl(fallback)
        } else {
            host.toast("No app can open this link")
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        pageStarted(tab, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        pageFinished(tab, url, view.title.orEmpty())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Single-page apps navigate without a page load; keep the omnibox honest.
        tab.url = url
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        // Sub-resource failures are the page's problem, not the browser's.
        if (!request.isForMainFrame) return
        tab.errorMessage = describe(error.description?.toString().orEmpty())
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (!request.isForMainFrame) return
        if (errorResponse.statusCode >= 500) {
            tab.errorMessage = "The site returned an error (${errorResponse.statusCode})."
        }
    }

    /**
     * Certificate problems are never resolved silently. The user is told which host failed and
     * why, and proceeding is an explicit, per-navigation choice.
     */
    @SuppressLint("WebViewClientOnReceivedSslError") // Never auto-proceeds: the user decides.
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val hostName = runCatching { Uri.parse(error.url).host }.getOrNull() ?: "this site"
        val reason = when (error.primaryError) {
            SslError.SSL_EXPIRED -> "its certificate has expired"
            SslError.SSL_IDMISMATCH -> "its certificate is for a different site"
            SslError.SSL_UNTRUSTED -> "its certificate is not trusted"
            SslError.SSL_DATE_INVALID -> "its certificate is not yet valid"
            SslError.SSL_NOTYETVALID -> "its certificate is not yet valid"
            else -> "its certificate could not be verified"
        }
        onSslPrompt("The connection to $hostName isn't private because $reason.", { handler.proceed() }, { handler.cancel() })
    }

    private fun describe(raw: String): String = when {
        raw.contains("ERR_INTERNET_DISCONNECTED", true) -> "You appear to be offline."
        raw.contains("ERR_NAME_NOT_RESOLVED", true) -> "That address couldn't be found."
        raw.contains("ERR_CONNECTION_REFUSED", true) -> "The site refused the connection."
        raw.contains("ERR_CONNECTION_TIMED_OUT", true) -> "The site took too long to respond."
        raw.contains("ERR_SSL", true) || raw.contains("ERR_CERT", true) ->
            "The secure connection couldn't be established."
        raw.isBlank() -> "This page couldn't be loaded."
        else -> raw.replaceFirstChar { it.uppercase() }
    }
}
