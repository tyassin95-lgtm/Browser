package com.slate.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.data.AppDatabase
import com.slate.browser.data.BrowserRepository
import com.slate.browser.data.ThemeMode
import com.slate.browser.tabs.Tab
import com.slate.browser.ui.BrowserScreen
import com.slate.browser.ui.theme.SlateTheme
import com.slate.browser.web.BrowserHost
import com.slate.browser.web.MediaAgent
import com.slate.browser.web.MediaFit
import com.slate.browser.web.MediaState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The browser half of fullscreen video: when the control is offered, what it does to the
 * window, and every way back out. The in-page half is covered by tools/test-media-agent.js,
 * which exercises the injected script against a real DOM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class MediaFullscreenTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var viewModel: BrowserViewModel
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()

    private val widescreenLive = MediaState(
        hasVideo = true, isPlaying = true, isLive = true, width = 1920, height = 1080,
    )
    private val portraitClip = MediaState(
        hasVideo = true, isPlaying = true, width = 720, height = 1280,
    )

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        File(app.filesDir, "session.json").delete()
        val factory = viewModelFactory {
            initializer { BrowserViewModel(app, repository = BrowserRepository(db)) }
        }
        viewModel = ViewModelProvider(viewModelStore, factory)[BrowserViewModel::class.java]
        viewModel.attach(app, SilentHost)
        viewModel.bootstrap(null)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        db.close()
    }

    private fun render() {
        compose.setContent {
            SlateTheme(themeMode = ThemeMode.LIGHT) {
                BrowserScreen(viewModel, "test", onShare = { _, _ -> }, onOpenExternally = {})
            }
        }
    }

    private fun playing(state: MediaState = widescreenLive): Tab {
        val tab = viewModel.activeTab!!
        viewModel.load("https://stream.test/room")
        compose.waitForIdle()
        tab.media = state
        compose.waitForIdle()
        return tab
    }

    // ---- What the page reports ---------------------------------------------

    @Test
    fun `a widescreen stream is worth turning the phone for, a portrait one is not`() {
        assertTrue(widescreenLive.prefersLandscape)
        assertFalse(portraitClip.prefersLandscape)
        // A square-ish stream is left alone rather than forced sideways.
        assertFalse(MediaState(hasVideo = true, width = 1000, height = 1000).prefersLandscape)
    }

    @Test
    fun `the bridge survives whatever the page sends it`() {
        var received: MediaState? = null
        val bridge = MediaAgent(ApplicationProvider.getApplicationContext())
            .Bridge(onState = { received = it }, onEnterResult = {})

        bridge.report("not json at all")
        assertEquals("malformed input is ignored, not crashed on", null, received)

        bridge.report("""{"hasVideo":true,"playing":true,"live":true,"w":1280,"h":720}""")
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1280, received?.width)
        assertEquals(720, received?.height)
        assertTrue(received?.isLive == true)

        // A hostile page cannot make the browser believe in an absurd resolution.
        bridge.report("""{"hasVideo":true,"w":999999999,"h":-5}""")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(16384, received?.width)
        assertEquals(0, received?.height)
    }

    // ---- Offering the control ----------------------------------------------

    @Test
    fun `the fullscreen control appears only once there is a video`() {
        render()
        viewModel.blurOmnibox()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Watch fullscreen").assertDoesNotExist()

        playing()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Watch fullscreen").assertIsDisplayed()
    }

    @Test
    fun `asking for fullscreen with nothing playing says so instead of blanking the screen`() {
        render()
        viewModel.enterMediaFullscreen()
        compose.waitForIdle()
        assertFalse(viewModel.isMediaFullscreen)
    }

    // ---- Entering ------------------------------------------------------------

    @Test
    fun `entering hides the browser chrome and holds the phone in landscape`() {
        render()
        playing()
        compose.onNodeWithContentDescription("Watch fullscreen").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        assertTrue(viewModel.isMediaFullscreen)
        assertTrue("the window must go edge to edge", viewModel.isImmersive)
        assertTrue("a 16:9 stream belongs sideways", viewModel.lockLandscapeForMedia)
        compose.onNodeWithContentDescription("1 open tabs").assertDoesNotExist()
        compose.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed()
    }

    @Test
    fun `a portrait stream is not forced into landscape`() {
        render()
        playing(portraitClip)
        viewModel.enterMediaFullscreen()
        compose.waitForIdle()

        assertTrue(viewModel.isMediaFullscreen)
        assertFalse("rotating a 9:16 stream would only shrink it", viewModel.lockLandscapeForMedia)
    }

    @Test
    fun `the rotation policy turns the phone only for a stream known to be wide`() {
        // Enabled by default, so a 16:9 stream rotates and a 9:16 one does not.
        assertTrue(viewModel.shouldHoldLandscape(widescreenLive))
        assertFalse(viewModel.shouldHoldLandscape(portraitClip))
        // Dimensions not yet decoded: wait for the frame rather than guess and rotate twice.
        assertFalse(viewModel.shouldHoldLandscape(MediaState(hasVideo = true, isLive = true)))
        assertFalse(viewModel.shouldHoldLandscape(MediaState.NONE))
    }

    @Test
    fun `dimensions arriving late still rotate the phone`() {
        render()
        // Live streams routinely report 0x0 until the first frame is decoded.
        playing(MediaState(hasVideo = true, isPlaying = true, isLive = true))
        viewModel.enterMediaFullscreen()
        compose.waitForIdle()
        assertFalse(viewModel.lockLandscapeForMedia)

        viewModel.onMediaState(viewModel.activeTab!!, widescreenLive)
        compose.waitForIdle()
        assertTrue("the rotation arrives with the first decoded frame", viewModel.lockLandscapeForMedia)
    }

    // ---- Fit -----------------------------------------------------------------

    @Test
    fun `fit defaults to showing the whole frame and can be toggled to fill`() {
        render()
        playing()
        viewModel.enterMediaFullscreen()
        compose.waitForIdle()
        assertEquals("never crop the stream by default", MediaFit.CONTAIN, viewModel.mediaFit)

        viewModel.toggleMediaFit()
        assertEquals(MediaFit.COVER, viewModel.mediaFit)
        viewModel.toggleMediaFit()
        assertEquals(MediaFit.CONTAIN, viewModel.mediaFit)
    }

    // ---- Every way back out --------------------------------------------------

    @Test
    fun `the close button leaves fullscreen and restores the chrome`() {
        render()
        playing()
        viewModel.enterMediaFullscreen()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Exit fullscreen").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        assertFalse(viewModel.isMediaFullscreen)
        assertFalse(viewModel.isImmersive)
        assertFalse(viewModel.lockLandscapeForMedia)
        compose.onNodeWithContentDescription("1 open tabs").assertIsDisplayed()
    }

    @Test
    fun `exiting immersive by any route also leaves media fullscreen`() {
        render()
        playing()
        viewModel.enterMediaFullscreen()
        compose.waitForIdle()

        viewModel.exitImmersive()
        compose.waitForIdle()
        assertFalse(viewModel.isMediaFullscreen)
        assertFalse(viewModel.isImmersive)
    }

    @Test
    fun `navigating away cannot strand the viewer in a black screen`() {
        render()
        val tab = playing()
        viewModel.enterMediaFullscreen()
        compose.waitForIdle()
        assertTrue(viewModel.isMediaFullscreen)

        // A stream page that redirects, or a link followed inside the player.
        viewModel.load("https://stream.test/elsewhere")
        compose.waitForIdle()
        assertFalse(viewModel.isMediaFullscreen)
        assertFalse(tab.media.hasVideo)
    }

    @Test
    fun `switching tabs leaves fullscreen behind`() {
        render()
        playing()
        viewModel.enterMediaFullscreen()
        compose.waitForIdle()

        viewModel.newTab("https://other.test")
        compose.waitForIdle()
        assertFalse(viewModel.isMediaFullscreen)
        assertFalse(viewModel.lockLandscapeForMedia)
    }

    @Test
    fun `the browser's own controls drive the video the page would not let us reach`() {
        render()
        playing()
        viewModel.enterMediaFullscreen()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        // Every control the viewer needs is the browser's, because in this mode the page's own
        // controls are deliberately unreachable.
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volume").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fill the screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed()
    }

    @Test
    fun `a recorded video gets a scrub bar and its position`() {
        render()
        playing(
            MediaState(
                hasVideo = true, isPlaying = true, width = 1920, height = 1080,
                positionMs = 65_000, durationMs = 300_000,
            )
        )
        viewModel.enterMediaFullscreen()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Seek").assertIsDisplayed()
        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithText("5:00").assertIsDisplayed()
    }

    @Test
    fun `a live stream with no rewind buffer gets no scrub bar`() {
        render()
        playing(widescreenLive)
        viewModel.enterMediaFullscreen()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        // A bar that snaps straight back to the edge is worse than no bar at all.
        compose.onNodeWithContentDescription("Seek").assertDoesNotExist()
        compose.onNodeWithContentDescription("Live").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun `a live stream with a rewind buffer can be scrubbed and returned to the edge`() {
        render()
        playing(
            MediaState(
                hasVideo = true, isPlaying = true, isLive = true, width = 1920, height = 1080,
                positionMs = 300_000, seekableStartMs = 60_000, seekableEndMs = 360_000,
            )
        )
        viewModel.enterMediaFullscreen()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Seek").assertIsDisplayed()
        // A minute behind the edge, so the badge offers the way back rather than saying LIVE.
        compose.onNodeWithContentDescription("Go live").assertIsDisplayed()
    }

    @Test
    fun `live state is derived from the stream, not guessed`() {
        val noBuffer = MediaState(hasVideo = true, isLive = true, seekableStartMs = 0, seekableEndMs = 4_000)
        assertFalse("four seconds is not a rewind buffer", noBuffer.hasLiveWindow)
        assertFalse(noBuffer.isSeekable)

        val dvr = MediaState(hasVideo = true, isLive = true, seekableStartMs = 0, seekableEndMs = 120_000)
        assertTrue(dvr.hasLiveWindow)

        val vod = MediaState(hasVideo = true, durationMs = 90_000, positionMs = 10_000)
        assertTrue(vod.isSeekable)
        assertTrue("a recording is always at its own edge", vod.isAtLiveEdge)

        val behind = MediaState(hasVideo = true, isLive = true, positionMs = 100_000, seekableEndMs = 160_000)
        assertEquals(60_000L, behind.behindLiveMs)
        assertFalse(behind.isAtLiveEdge)
    }

    @Test
    fun `entering fullscreen counts as the gesture that permits playback`() {
        render()
        val tab = playing()
        // Autoplay is off by default, which would otherwise refuse the overlay's play button.
        assertTrue(tab.webView!!.settings.mediaPlaybackRequiresUserGesture)

        viewModel.enterMediaFullscreen()
        compose.waitForIdle()
        assertFalse(tab.webView!!.settings.mediaPlaybackRequiresUserGesture)

        viewModel.exitMediaFullscreen()
        compose.waitForIdle()
        assertTrue("the page's own autoplay stays blocked afterwards",
            tab.webView!!.settings.mediaPlaybackRequiresUserGesture)
    }

    private object SilentHost : BrowserHost {
        override fun onEnterElementFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = Unit
        override fun onExitElementFullscreen() = Unit
        override fun requestSystemPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) = onResult(true)
        override fun openFileChooser(intent: Intent, callback: ValueCallback<Array<Uri>?>) = false
        override fun openExternally(url: String) = true
        override fun toast(message: String) = Unit
        override fun snack(message: String, actionLabel: String?, action: (() -> Unit)?) = Unit
    }
}
