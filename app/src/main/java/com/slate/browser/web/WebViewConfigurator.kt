package com.slate.browser.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.slate.browser.data.Settings

/**
 * All WebView tuning lives here so every tab — including popups opened by a page — is
 * configured identically, and so a settings change can be replayed onto live tabs without
 * reloading them.
 */
object WebViewConfigurator {

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, settings: Settings, desktopMode: Boolean) {
        // Deliberately no layout params here. This runs again on every preference change,
        // including on tabs already attached to their host, and layout params belong to the
        // parent that adopted the view: handing a FrameLayout a bare ViewGroup.LayoutParams
        // makes it throw on its next layout pass.
        webView.apply {
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
            isScrollbarFadingEnabled = true
        }

        webView.settings.apply {
            javaScriptEnabled = settings.javaScriptEnabled
            domStorageEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            mediaPlaybackRequiresUserGesture = !settings.allowAutoplay

            // Modern sites assume a viewport-aware browser.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Popups are supported rather than silently dropped; the tab layer decides what to
            // do with each one. A window still needs a gesture behind it, and the engine's own
            // rule is the first of the layers that say so — the page-side guard and the
            // navigation policy are the others.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false

            // The browser renders the web. Nothing it loads has any business reaching the
            // device: `file:` would put this app's own storage inside a page's origin, and
            // `content:` would let one reach other apps' providers through the browser's
            // identity. Both stay off for every tab, including popups a page opens.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            // A padlock has to mean something. Compatibility mode still lets a secure page
            // pull images and media over plain http, which is exactly the content an attacker
            // on the path can replace, and the user is shown a lock throughout.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // Site-controlled text sizing only; the browser does not second-guess the page.
            textZoom = 100
            setGeolocationEnabled(true)
            setNeedInitialFocus(false)

            userAgentString = if (desktopMode) {
                UserAgent.desktop(webView.context)
            } else {
                UserAgent.mobile(webView.context)
            }
        }

        applyDarkening(webView)
        applySafeBrowsing(webView)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, !settings.blockThirdPartyCookies)
        }
        // A cookie scoped to `file:` is shared by every file the browser could ever open, so it
        // has no origin worth the name. File access is off anyway; this is the second lock on
        // the same door. Static by nature: it is a process-wide switch, not a per-view one.
        @Suppress("DEPRECATION")
        CookieManager.setAcceptFileSchemeCookies(false)
    }

    /**
     * Prefers the algorithmic-darkening API, which lets a site's own `prefers-color-scheme`
     * styling win and only inverts pages that have none.
     *
     * The feature check is necessary but not sufficient: some WebView providers advertise the
     * feature and still refuse the call. Dark pages are a nicety, so a provider that says no is
     * not a reason to fail a page load.
     */
    private fun applyDarkening(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true) }
    }

    /**
     * Google Safe Browsing, asked for explicitly rather than left to the provider's default.
     *
     * This is the one protection here that knows what a particular site has been caught doing,
     * and it costs nothing to ask for. A provider that cannot honour it is not a reason to
     * fail: every other layer still applies.
     */
    private fun applySafeBrowsing(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) return
        runCatching { WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true) }
    }

    /*
     * Not done here, and worth saying why: WebView adds `X-Requested-With: <package name>` to
     * the requests it makes, which hands every site a stable identifier of the browser build
     * the user is running. `WebSettingsCompat.setRequestedWithHeaderOriginAllowList` would
     * suppress it, but that API is still restricted to the androidx library group as of
     * webkit 1.12.1, and reaching into a restricted surface to gain a header is a worse trade
     * than the header. It is worth revisiting whenever it becomes public.
     */

    /** Applied on every navigation so the page cannot outlive a preference change. */
    fun requestHeaders(settings: Settings): Map<String, String> = buildMap {
        if (settings.doNotTrack) {
            put("DNT", "1")
            put("Sec-GPC", "1")
        }
    }
}
