package com.slate.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.tabs.Tab
import com.slate.browser.web.BrowserHost
import com.slate.browser.web.ContentBlocker
import com.slate.browser.web.NavigationPolicy
import com.slate.browser.web.SlateWebViewClient
import com.slate.browser.web.UserActivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How a page that plays through MediaSource becomes castable.
 *
 * The element's own source is a `blob:` and always will be, so the only address worth having
 * is the playlist the player fetches — and the browser is already watching every request in
 * order to block adverts. This is that observation, tested where it actually runs.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestObservationTest {

    private lateinit var tab: Tab
    private lateinit var client: SlateWebViewClient
    private lateinit var blocker: ContentBlocker
    private var blockingOn = true

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        blocker = ContentBlocker(app)
        assertTrue("the filter lists must load", blocker.awaitReady())
        tab = Tab(id = "t1")
        client = SlateWebViewClient(
            tab = tab,
            host = SilentHost,
            pageStarted = { _, _ -> },
            pageFinished = { _, _, _ -> },
            onSslPrompt = { _, _, _ -> },
            blocker = blocker,
            blockingEnabled = { blockingOn },
            onRequestBlocked = {},
            policy = NavigationPolicy(blocker, UserActivation()),
            onNavigationBlocked = { _, _ -> },
            onConfirmExternal = { _, _ -> },
            onRendererGone = { _, _ -> },
        )
    }

    private fun request(url: String, headers: MutableMap<String, String> = mutableMapOf()) =
        object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = headers
    }

    private fun fetch(url: String, headers: Map<String, String> = emptyMap()) =
        client.shouldInterceptRequest(FakeWebView.of(), request(url, headers.toMutableMap()))

    @Test
    fun `the playlist a player fetches is remembered`() {
        fetch("https://cdn.example.com/player.js")
        fetch("https://cdn.example.com/live/master.m3u8?token=abc")
        assertEquals("https://cdn.example.com/live/master.m3u8?token=abc", tab.observedManifest)
    }

    @Test
    fun `the first playlist wins, not the last`() {
        // A live player re-requests its variant playlist for ever. Keeping the newest would
        // pin the receiver to one bitrate; the master is the one that carries them all.
        fetch("https://cdn.example.com/live/master.m3u8")
        repeat(5) { fetch("https://cdn.example.com/live/720p/index.m3u8") }
        assertEquals("https://cdn.example.com/live/master.m3u8", tab.observedManifest)
    }

    @Test
    fun `segments and ordinary resources are not mistaken for the stream`() {
        listOf(
            "https://cdn.example.com/live/seg-0001.ts",
            "https://cdn.example.com/live/chunk.m4s",
            "https://cdn.example.com/app.js",
            "https://cdn.example.com/poster.jpg",
        ).forEach { fetch(it) }
        assertNull(tab.observedManifest)
    }

    @Test
    fun `an advert's playlist is never what gets sent to the television`() {
        // Recorded after the blocking decision, so a pre-roll — often the very first manifest
        // a page asks for — cannot become the thing on somebody's TV.
        val adManifest = "https://securepubads.g.doubleclick.net/preroll/master.m3u8"
        val blocked = fetch(adManifest)
        assertTrue("the advert must be blocked for this test to mean anything", blocked != null)
        assertNull(tab.observedManifest)

        fetch("https://cdn.example.com/live/master.m3u8")
        assertEquals("https://cdn.example.com/live/master.m3u8", tab.observedManifest)
    }

    @Test
    fun `observation still works with ad blocking turned off`() {
        // The early return for disabled blocking used to skip everything below it.
        blockingOn = false
        fetch("https://cdn.example.com/live/master.m3u8")
        assertEquals("https://cdn.example.com/live/master.m3u8", tab.observedManifest)
    }

    @Test
    fun `a new document is a new stream`() {
        fetch("https://cdn.example.com/live/master.m3u8")
        client.onPageStarted(FakeWebView.of(), "https://example.com/another", null)
        assertNull("the last page's playlist is not this page's", tab.observedManifest)
    }

    @Test
    fun `a plain file is kept for the receivers that cannot play a playlist`() {
        fetch("https://cdn.example.com/player.js")
        fetch("https://cdn.example.com/videos/talk.mp4")
        assertEquals("https://cdn.example.com/videos/talk.mp4", tab.observedMediaFile)
    }

    @Test
    fun `once a playlist is seen, the files after it are its segments`() {
        // Everything a page fetches after a manifest belongs to that manifest. Offering one of
        // those to a television would put four seconds of video on it.
        fetch("https://cdn.example.com/live/master.m3u8")
        fetch("https://cdn.example.com/live/hd/piece.mp4")
        assertNull(tab.observedMediaFile)
    }

    @Test
    fun `a new document forgets the last page's file too`() {
        fetch("https://cdn.example.com/videos/talk.mp4")
        client.onPageStarted(FakeWebView.of(), "https://example.com/another", null)
        assertNull(tab.observedMediaFile)
    }

    @Test
    fun `the referrer the browser used is remembered with the playlist`() {
        // These streams are served only to the player that embeds them, and that player is
        // almost never the page in the address bar. Guessing it would fail on every host that
        // matters; the browser's own request already says.
        fetch(
            "https://cdn.example.com/live/master.m3u8",
            headers = mapOf("Referer" to "https://embed.example.net/player/9"),
        )
        assertEquals("https://embed.example.net/player/9", tab.observedManifestReferer)
    }

    @Test
    fun `a new page forgets the last one's referrer too`() {
        fetch(
            "https://cdn.example.com/live/master.m3u8",
            headers = mapOf("Referer" to "https://embed.example.net/player/9"),
        )
        client.onPageStarted(FakeWebView.of(), "https://example.com/another", null)
        assertNull(tab.observedManifestReferer)
    }

    private object SilentHost : BrowserHost {
        override fun onEnterElementFullscreen(view: android.view.View, callback: android.webkit.WebChromeClient.CustomViewCallback) = Unit
        override fun onExitElementFullscreen() = Unit
        override fun requestSystemPermissions(permissions: Array<String>, callback: (Boolean) -> Unit) = callback(false)
        override fun openFileChooser(intent: android.content.Intent, callback: android.webkit.ValueCallback<Array<Uri>?>): Boolean = false
        override fun openExternally(url: String): Boolean = false
        override fun openCastSettings(): Boolean = false
    override fun toast(message: String) = Unit
        override fun snack(message: String, actionLabel: String?, action: (() -> Unit)?) = Unit
    }

    private object FakeWebView {
        fun of(): android.webkit.WebView =
            android.webkit.WebView(ApplicationProvider.getApplicationContext())
    }
}
