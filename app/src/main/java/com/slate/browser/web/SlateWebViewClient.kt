package com.slate.browser.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
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
    private val blocker: ContentBlocker,
    private val blockingEnabled: () -> Boolean,
    private val onRequestBlocked: () -> Unit,
    private val policy: NavigationPolicy,
    private val onNavigationBlocked: (String, String) -> Unit,
    private val onConfirmExternal: (String, String) -> Unit,
    private val onRendererGone: (Tab, Boolean) -> Unit,
) : WebViewClient() {

    /**
     * Runs on WebView's network threads, once per subresource. Everything it touches is built
     * to be cheap enough for that; see [ContentBlocker].
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!blockingEnabled()) return null
        // The page's own address decides what counts as third-party and which `$domain=` rules
        // apply, and it cannot be read from a network thread, so it is captured as it changes.
        if (!blocker.shouldBlock(request, documentUrl)) return null
        onRequestBlocked()
        return blocker.blockedResponse(request)
    }

    /**
     * Every navigation a page attempts goes through one policy, so there is a single place that
     * decides and a single place to look when something is wrong.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val decision = policy.decide(
            url = url,
            isMainFrame = request.isForMainFrame,
            hasGesture = request.hasGesture(),
            isRedirect = request.isRedirect,
            currentPageUrl = view.url,
            blockingEnabled = blockingEnabled(),
        )
        return when (decision) {
            is NavigationDecision.Allow -> false

            is NavigationDecision.Block -> {
                onRequestBlocked()
                onNavigationBlocked(url, decision.reason)
                true
            }

            is NavigationDecision.KeepInBrowser -> {
                // An app hand-off carrying an ordinary web address: keep the address, refuse
                // the hand-off. This is how a page moves the user into another browser.
                tab.webView?.loadUrl(decision.url)
                true
            }

            is NavigationDecision.ConfirmExternal -> {
                onConfirmExternal(decision.url, decision.label)
                true
            }
        }
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

    /**
     * The address of the document currently loaded in this tab, published for the network
     * threads. Third-party scoping and `$domain=` rules are decided against it, and
     * `WebView.getUrl()` may only be read on the UI thread.
     */
    @Volatile
    private var documentUrl: String? = null

    /**
     * An HTTP failure status seen on the main frame, held until the page has finished loading.
     *
     * A status is not by itself a reason to replace what the server sent. Rate limiters and
     * anti-bot challenges answer 503 with a real, working page that resolves itself in a few
     * seconds — covering that with an error panel is what turned a page that was loading fine
     * into one the user had to keep pressing Retry on, and each press started the challenge
     * again. So the status is only acted on if the document turns out to have nothing in it.
     */
    private var pendingHttpStatus: Int? = null

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        documentUrl = url
        pendingHttpStatus = null
        pageStarted(tab, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        pageFinished(tab, url, view.title.orEmpty())
        resolveHttpStatus(view)
    }

    /**
     * Decides whether a failure status left the user with anything to look at.
     *
     * The page itself is asked, because only the page knows: an empty document behind a 5xx is
     * a failure worth reporting, and an error page, a challenge or a maintenance notice is the
     * site talking to its visitor and belongs on screen untouched.
     */
    private fun resolveHttpStatus(view: WebView) {
        val status = pendingHttpStatus ?: return
        pendingHttpStatus = null
        if (!view.settings.javaScriptEnabled) {
            // No way to ask, so fall back to trusting the status.
            tab.errorMessage = describeStatus(status)
            return
        }
        view.evaluateJavascript(HAS_CONTENT) { answer ->
            if (answer == "false") tab.errorMessage = describeStatus(status)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Single-page apps navigate without a page load; keep the omnibox honest.
        documentUrl = url
        tab.url = url
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        // Sub-resource failures are the page's problem, not the browser's.
        if (!request.isForMainFrame) return
        val description = error.description?.toString().orEmpty()
        // A navigation this browser itself refused, or one the user replaced by starting
        // another, is reported here as a failed load. It is neither, and showing an error
        // panel for it would be the browser inventing a failure it caused.
        if (SELF_INFLICTED.any { description.contains(it, ignoreCase = true) }) return
        tab.errorMessage = describe(description)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (!request.isForMainFrame) return
        if (errorResponse.statusCode >= 500) pendingHttpStatus = errorResponse.statusCode
    }

    /**
     * The renderer died — usually because Android reclaimed its memory, occasionally because it
     * crashed. Without handling this the whole app is killed; with it, the tab is rebuilt and
     * the page comes back. This is the difference between "the browser closed itself" and a
     * page that reappears.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(tab, detail.didCrash())
        return true
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

    private fun describeStatus(status: Int): String = when (status) {
        503 -> "The site is temporarily unavailable."
        502, 504 -> "The site didn't respond in time."
        else -> "The site returned an error ($status)."
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

    private companion object {
        /** Failures this browser caused by refusing or replacing a navigation of its own. */
        val SELF_INFLICTED = listOf(
            "ERR_ABORTED",
            "ERR_BLOCKED_BY_CLIENT",
            "ERR_BLOCKED_BY_RESPONSE",
            "ERR_CACHE_MISS",
            "ERR_UNKNOWN_URL_SCHEME",
        )

        /**
         * Whether the server sent a document with anything in it.
         *
         * Markup counts before text does, and deliberately so: an anti-bot challenge parses to
         * a handful of empty elements and fills them in from script a moment later, so judging
         * it on its text would condemn exactly the page this check exists to protect — and
         * waiting for the text would mean inventing a delay to wait for. What is being asked
         * is whether the response had a body, which the parser has already settled by now.
         *
         * On any doubt it answers yes: leaving the site's own page up is always the safer
         * mistake.
         */
        const val HAS_CONTENT =
            "(function(){try{" +
                "if(!document.body)return false;" +
                "if(document.body.querySelectorAll('*').length>0)return true;" +
                "return (document.body.innerText||'').trim().length>0;" +
                "}catch(e){return true}})()"
    }
}
