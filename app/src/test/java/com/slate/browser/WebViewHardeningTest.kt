package com.slate.browser

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.data.Settings
import com.slate.browser.web.WebViewConfigurator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings every tab is built with.
 *
 * These are the defaults an attacker inherits if they are wrong, and they are easy to
 * regress by accident — a single line in a configurator restores file access to every page in
 * the browser. Each one is asserted rather than assumed.
 */
@RunWith(RobolectricTestRunner::class)
class WebViewHardeningTest {

    private fun configured(settings: Settings = Settings()): WebView {
        val webView = WebView(ApplicationProvider.getApplicationContext())
        WebViewConfigurator.configure(webView, settings, desktopMode = false)
        return webView
    }

    @Test
    fun `web content cannot reach the device through the browser`() {
        val settings = configured().settings
        assertFalse("a page must not be able to load file: URLs", settings.allowFileAccess)
        assertFalse("a page must not be able to reach content providers", settings.allowContentAccess)
        @Suppress("DEPRECATION")
        assertFalse(settings.allowFileAccessFromFileURLs)
        @Suppress("DEPRECATION")
        assertFalse("a file URL must never get universal access", settings.allowUniversalAccessFromFileURLs)
    }

    @Test
    fun `a padlock means the whole page was delivered securely`() {
        assertEquals(
            "compatibility mode still lets a secure page pull images over plain http",
            WebSettings.MIXED_CONTENT_NEVER_ALLOW,
            configured().settings.mixedContentMode,
        )
    }

    @Test
    fun `a window still needs a gesture at the engine level`() {
        val settings = configured().settings
        assertTrue("popups are handled, not dropped", settings.supportMultipleWindows())
        assertFalse(
            "the engine's own gesture rule is the first of the layers",
            settings.javaScriptCanOpenWindowsAutomatically,
        )
    }

    @Test
    fun `third-party cookies are blocked out of the box`() {
        assertTrue(Settings().blockThirdPartyCookies)
    }

    @Test
    fun `the javascript preference is honoured in both directions`() {
        assertTrue(configured(Settings(javaScriptEnabled = true)).settings.javaScriptEnabled)
        assertFalse(configured(Settings(javaScriptEnabled = false)).settings.javaScriptEnabled)
    }

    @Test
    fun `autoplay stays gated on a gesture unless the user says otherwise`() {
        assertTrue(configured(Settings(allowAutoplay = false)).settings.mediaPlaybackRequiresUserGesture)
        assertFalse(configured(Settings(allowAutoplay = true)).settings.mediaPlaybackRequiresUserGesture)
    }

    @Test
    fun `the browser does not announce the user to every site it visits`() {
        // Do Not Track and Global Privacy Control are requests rather than enforcement, but a
        // browser that omits them is speaking for the user by silence.
        val headers = WebViewConfigurator.requestHeaders(Settings(doNotTrack = true))
        assertEquals("1", headers["DNT"])
        assertEquals("1", headers["Sec-GPC"])
        assertTrue(WebViewConfigurator.requestHeaders(Settings(doNotTrack = false)).isEmpty())
    }
}
