package com.slate.browser.web

import android.content.Context
import android.webkit.WebSettings

/**
 * Desktop mode is only convincing when the user-agent, the viewport and the layout width all
 * agree. This object owns the UA half of that contract.
 *
 * Both strings are always derived from the platform default rather than from whatever the
 * WebView is currently set to, so switching back and forth is lossless.
 */
object UserAgent {

    /** A current desktop Chrome UA, kept in step with the WebView's own Chrome version. */
    fun desktop(context: Context): String {
        val chromeVersion = Regex("Chrome/([\\d.]+)")
            .find(defaultUa(context))?.groupValues?.get(1)
            ?: FALLBACK_CHROME
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeVersion Safari/537.36"
    }

    /**
     * The stock WebView UA advertises "wv", which some sites use to serve a degraded page or to
     * refuse sign-in. Dropping that token makes the browser look like ordinary mobile Chrome.
     */
    fun mobile(context: Context): String =
        defaultUa(context)
            .replace("; wv)", ")")
            .replace(" Version/4.0", "")

    private fun defaultUa(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(FALLBACK_MOBILE)

    private const val FALLBACK_CHROME = "131.0.0.0"
    private const val FALLBACK_MOBILE =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$FALLBACK_CHROME Mobile Safari/537.36"
}
