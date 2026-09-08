package com.slate.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.data.AppDatabase
import com.slate.browser.data.BrowserRepository
import com.slate.browser.data.ThemeMode
import com.slate.browser.ui.BrowserScreen
import com.slate.browser.ui.PAGE_TAG
import com.slate.browser.ui.theme.SlateTheme
import com.slate.browser.web.BrowserHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * What scrolling is allowed to do to the layout.
 *
 * The toolbar is laid out beside the page, so moving it resizes the WebView. Doing that while
 * the page is still moving re-lays out and re-rasters under a live compositor, which is what
 * tears. These tests hold the line that a scroll — however fast, however jittery — resizes the
 * page at most once, and never before it has come to rest.
 */
@RunWith(RobolectricTestRunner::class)
class ScrollRenderingTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var viewModel: BrowserViewModel
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private val density by lazy {
        ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            // Same-thread executors: Room's own flow teardown then completes inline during
            // cancellation, instead of landing on a background thread after the database has
            // been closed and surfacing in whichever test happens to run next.
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .allowMainThreadQueries()
            .build()
        File(app.filesDir, "session.json").delete()
        val factory = viewModelFactory {
            initializer { BrowserViewModel(app, repository = BrowserRepository(db)) }
        }
        viewModel = ViewModelProvider(viewModelStore, factory)[BrowserViewModel::class.java]
        viewModel.attach(app, SilentHost)
        viewModel.bootstrapAndWait()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        repeat(5) { org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        runCatching { db.close() }
    }

    private fun render() {
        compose.setContent {
            SlateTheme(themeMode = ThemeMode.LIGHT) {
                BrowserScreen(viewModel, "test", onShare = { _, _ -> }, onOpenExternally = {})
            }
        }
        viewModel.blurOmnibox()
        compose.waitForIdle()
    }

    private fun dp(value: Float) = (value * density).toInt()

    /** Lets any pending settle timer run. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        compose.waitForIdle()
    }

    /** Counts how many times the toolbar's visibility flips across a scroll, i.e. how many
     *  times the WebView would be resized. */
    private fun countChromeChanges(scroll: () -> Unit): Int {
        var changes = 0
        var last = viewModel.chromeVisible
        val observe = {
            if (viewModel.chromeVisible != last) {
                changes++
                last = viewModel.chromeVisible
            }
        }
        observedDuringScroll = observe
        scroll()
        observedDuringScroll = null
        return changes
    }

    private var observedDuringScroll: (() -> Unit)? = null

    private fun feed(delta: Int, y: Int) {
        viewModel.onPageScrolled(delta, y)
        // The main looper runs between scroll frames on a device too.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        observedDuringScroll?.invoke()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `a fast fling in landscape never resizes the page mid-scroll`() {
        render()
        var y = 0
        val changes = countChromeChanges {
            // A hard flick: large frames decaying, the way a fling actually arrives.
            var step = dp(90f)
            repeat(30) {
                y += step
                feed(step, y)
                step = (step * 0.93f).toInt().coerceAtLeast(dp(2f))
            }
        }
        assertEquals("the toolbar must not move while the page is still moving", 0, changes)

        settle()
        assertFalse("and it should be gone once the page stops", viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `rapid repeated swipes in landscape resize the page at most once`() {
        render()
        var y = 0
        val changes = countChromeChanges {
            repeat(6) {
                // Down hard...
                repeat(8) { y += dp(40f); feed(dp(40f), y) }
                // ...then a short correction back up, as a thumb repositioning does.
                repeat(2) { y -= dp(12f); feed(-dp(12f), y) }
            }
        }
        assertEquals(0, changes)
        settle()
        assertFalse(viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `jitter around one position leaves the layout completely alone`() {
        render()
        var y = dp(500f)
        val changes = countChromeChanges {
            repeat(120) { step ->
                val delta = if (step % 2 == 0) dp(7f) else -dp(7f)
                y += delta
                feed(delta, y)
            }
        }
        settle()
        assertEquals("holding still must not resize anything", 0, changes)
        assertTrue(viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `the resize a hidden toolbar causes cannot start another one`() {
        render()
        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()
        assertFalse(viewModel.chromeVisible)

        // Hiding the toolbar grows the viewport, which reflows the page and reports a large
        // jump. Treating that as a scroll is what would close the loop and thrash the layout.
        feed(-dp(300f), y)
        feed(dp(300f), y)
        settle()
        assertFalse("a reflow must not read as a gesture", viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `hiding the toolbar in landscape moves the page origin, which is why it must be rare`() {
        render()
        val before = compose.onNodeWithTag(PAGE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("in landscape the toolbar is above the page", before.top > 0f)

        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()

        val after = compose.onNodeWithTag(PAGE_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("the page moves to the top of the window", 0f, after.top, 1f)
        assertTrue(after.height > before.height)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `portrait behaves the same way`() {
        render()
        var y = 0
        val changes = countChromeChanges {
            repeat(20) { y += dp(50f); feed(dp(50f), y) }
        }
        assertEquals(0, changes)
        settle()
        assertFalse(viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `returning to the top brings the toolbar back`() {
        render()
        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()
        assertFalse(viewModel.chromeVisible)

        feed(-dp(50f), 0)
        settle()
        assertTrue(viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `fullscreen browsing ignores scrolling entirely`() {
        render()
        viewModel.enterImmersive()
        compose.waitForIdle()

        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()
        assertTrue("immersive owns the chrome; scrolling must not touch it", viewModel.isImmersive)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `a pending toolbar move is dropped when navigation takes over`() {
        render()
        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        // Loading a page shows the chrome; the queued hide must not land afterwards.
        viewModel.load("https://example.com")
        settle()
        assertTrue(viewModel.chromeVisible)
    }

    // ---- The chrome can never be lost -----------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a new tab always arrives showing its chrome`() {
        render()
        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()
        assertFalse("scrolled away on this tab", viewModel.chromeVisible)

        // The new tab may well be a page with nothing to scroll, so inheriting a hidden
        // toolbar would leave no way at all to get it back.
        viewModel.newTab("https://other.test")
        settle()
        assertTrue(viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `switching tabs restores the chrome`() {
        render()
        val first = viewModel.activeTab!!
        viewModel.openInBackgroundTab("https://second.test")
        settle()

        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()
        assertFalse(viewModel.chromeVisible)

        val second = viewModel.tabManager.tabs.last()
        viewModel.selectTab(second.id)
        settle()
        assertTrue("arriving at a tab shows its controls", viewModel.chromeVisible)

        viewModel.selectTab(first.id)
        settle()
        assertTrue("and going back does too", viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `hiding the chrome on one tab does not hide it on another`() {
        render()
        val first = viewModel.activeTab!!
        viewModel.openInBackgroundTab("https://second.test")
        val second = viewModel.tabManager.tabs.last()
        settle()

        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()
        assertFalse(first.chromeVisible)
        assertTrue("the other tab was never scrolled", second.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `navigating brings the chrome back`() {
        render()
        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()
        assertFalse(viewModel.chromeVisible)

        viewModel.load("https://elsewhere.test")
        settle()
        assertTrue(viewModel.chromeVisible)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the omnibox and fullscreen both leave the chrome showing`() {
        render()
        var y = 0
        repeat(10) { y += dp(40f); feed(dp(40f), y) }
        settle()
        assertFalse(viewModel.chromeVisible)

        viewModel.focusOmnibox("")
        settle()
        assertTrue(viewModel.chromeVisible)
        viewModel.blurOmnibox()

        viewModel.enterImmersive()
        settle()
        assertFalse("fullscreen hides it deliberately", viewModel.chromeVisible)
        viewModel.exitImmersive()
        settle()
        assertTrue("and leaving fullscreen gives it back", viewModel.chromeVisible)
    }

    private object SilentHost : BrowserHost {
        override fun onEnterElementFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = Unit
        override fun onExitElementFullscreen() = Unit
        override fun requestSystemPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) = onResult(true)
        override fun openFileChooser(intent: Intent, callback: ValueCallback<Array<Uri>?>) = false
        override fun openExternally(url: String) = true
        override fun openCastSettings(): Boolean = false
    override fun toast(message: String) = Unit
        override fun snack(message: String, actionLabel: String?, action: (() -> Unit)?) = Unit
    }
}
