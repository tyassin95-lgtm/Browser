package com.slate.browser.web

import android.annotation.SuppressLint
import android.view.ViewGroup
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
        webView.apply {
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
            isScrollbarFadingEnabled = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
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
            // do with each one.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            allowFileAccess = false
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, !settings.blockThirdPartyCookies)
        }
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

    /** Applied on every navigation so the page cannot outlive a preference change. */
    fun requestHeaders(settings: Settings): Map<String, String> = buildMap {
        if (settings.doNotTrack) {
            put("DNT", "1")
            put("Sec-GPC", "1")
        }
    }
}
