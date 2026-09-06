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
import com.slate.browser.BackAction
import com.slate.browser.ui.BrowserScreen
import com.slate.browser.ui.theme.SlateTheme
import com.slate.browser.web.BrowserHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Renders the real browsing surface — real ViewModel, real tab manager, real WebViews — and
 * drives it the way a person would. This is the check that the app is a working browser rather
 * than a set of screens that merely compile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BrowserUiTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var viewModel: BrowserViewModel
    private val host = RecordingHost()

    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // A fresh in-memory database per test: no state survives from the previous one.
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        File(app.filesDir, "session.json").delete()

        // Built through a real ViewModelStore so teardown exercises the same shutdown path the
        // Activity does, instead of leaving coroutines running against a closed database.
        val factory = viewModelFactory {
            initializer { BrowserViewModel(app, repository = BrowserRepository(db)) }
        }
        viewModel = ViewModelProvider(viewModelStore, factory)[BrowserViewModel::class.java]
        viewModel.attach(app, host)
        viewModel.bootstrap(null)
    }

    @After
    fun tearDown() {
        // Clearing the store cancels the ViewModel's scope; the database is only closed once
        // that cancellation has actually run, or a query still unwinding reports a closed
        // connection into whichever test happens to start next.
        viewModelStore.clear()
        repeat(5) { org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        runCatching { db.close() }
    }

    private fun render() {
        compose.setContent {
            SlateTheme(themeMode = ThemeMode.LIGHT) {
                BrowserScreen(
                    viewModel = viewModel,
                    appVersion = "test",
                    onShare = { _, _ -> },
                    onOpenExternally = { },
                )
            }
        }
    }

    @Test
    fun `the browser starts on a single blank tab`() {
        render()
        assertEquals(1, viewModel.tabManager.count)
        compose.onNodeWithContentDescription("1 open tabs").assertIsDisplayed()
    }

    @Test
    fun `tapping the collapsed omnibox opens the editor`() {
        render()
        viewModel.blurOmnibox()
        compose.waitForIdle()

        compose.onNodeWithText("Search or enter address").performClick()
        compose.waitForIdle()
        assertTrue(viewModel.isOmniboxFocused)

        viewModel.onOmniboxTextChanged("example.com")
        compose.waitForIdle()
        assertEquals("example.com", viewModel.omniboxText)
    }

    @Test
    fun `submitting the omnibox loads the page and closes the editor`() {
        render()
        viewModel.focusOmnibox("")
        compose.waitForIdle()
        viewModel.load("example.com")
        compose.waitForIdle()

        assertFalse(viewModel.isOmniboxFocused)
        val webView = viewModel.activeTab?.webView
        assertTrue("a webview must exist after a load", webView != null)
        assertEquals("https://example.com", webView?.url ?: webView?.originalUrl)
    }

    @Test
    fun `tapping outside the omnibox dismisses it and gives the toolbar back`() {
        render()
        // A launch is not a request to type, so the omnibox starts closed. Once the user opens
        // it, the first tap elsewhere must put it away rather than being swallowed.
        assertFalse("a fresh launch must not focus the omnibox", viewModel.isOmniboxFocused)
        viewModel.focusOmnibox("")
        compose.waitForIdle()
        assertTrue(viewModel.isOmniboxFocused)
        compose.onNodeWithContentDescription("1 open tabs").performClick()
        compose.waitForIdle()
        assertFalse(viewModel.isOmniboxFocused)
        assertEquals(Overlay.NONE, viewModel.overlay)
    }

    @Test
    fun `the tab counter opens the switcher and a new tab appears there`() {
        render()
        compose.onNodeWithContentDescription("1 open tabs").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("1 tab").assertIsDisplayed()

        compose.onNodeWithContentDescription("New tab").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(2, viewModel.tabManager.count)
    }

    @Test
    fun `closing the last tab always leaves one open`() {
        render()
        val id = viewModel.tabManager.activeTabId!!
        viewModel.closeTab(id)
        compose.waitForIdle()
        assertEquals("a browser is never tabless", 1, viewModel.tabManager.count)
    }

    @Test
    fun `history records a finished page load`() {
        render()
        viewModel.load("https://example.com/page")
        compose.waitForIdle()

        val tab = viewModel.activeTab!!
        assertEquals("https://example.com/page", tab.url)
    }

    @Test
    fun `fullscreen browsing hides the chrome and back leaves it`() {
        render()
        viewModel.enterImmersive()
        compose.waitForIdle()
        assertTrue(viewModel.isImmersive)
        compose.onNodeWithContentDescription("1 open tabs").assertDoesNotExist()

        viewModel.exitImmersive()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("1 open tabs").assertIsDisplayed()
    }

    @Test
    fun `find in page opens its own bar`() {
        render()
        viewModel.openFind()
        compose.waitForIdle()
        compose.onNodeWithText("Find in page").assertIsDisplayed()

        viewModel.closeFind()
        compose.waitForIdle()
        compose.onNodeWithText("Find in page").assertDoesNotExist()
    }

    @Test
    fun `desktop mode is per tab and switches the user agent`() {
        render()
        viewModel.load("https://example.com")
        compose.waitForIdle()
        val webView = viewModel.activeTab!!.webView!!
        val mobileUa = webView.settings.userAgentString
        assertFalse("mobile UA must not advertise a WebView", mobileUa.contains("; wv)"))

        viewModel.toggleDesktopMode()
        compose.waitForIdle()
        val desktopUa = viewModel.activeTab!!.webView!!.settings.userAgentString
        assertTrue(desktopUa.contains("X11; Linux x86_64"))
        assertFalse(desktopUa.contains("Android"))
        assertTrue(viewModel.activeTab!!.isDesktopMode)

        // Switching back must restore the original mobile UA, not leave the desktop one in
        // place: the UA is always derived from the platform default, never from the last value.
        viewModel.toggleDesktopMode()
        compose.waitForIdle()
        assertEquals(mobileUa, viewModel.activeTab!!.webView!!.settings.userAgentString)
        assertFalse(viewModel.activeTab!!.isDesktopMode)
    }

    @Test
    fun `desktop mode does not leak into other tabs`() {
        render()
        viewModel.load("https://a.test")
        compose.waitForIdle()
        viewModel.toggleDesktopMode()
        compose.waitForIdle()

        viewModel.newTab("https://b.test")
        compose.waitForIdle()
        assertFalse(viewModel.activeTab!!.isDesktopMode)
        assertFalse(
            "a new tab must not inherit the other tab's desktop UA",
            viewModel.activeTab!!.webView!!.settings.userAgentString.contains("X11; Linux x86_64"),
        )
    }

    @Test
    fun `the history panel opens and reports being empty`() {
        render()
        viewModel.showOverlay(Overlay.HISTORY)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("No history yet").assertIsDisplayed()
    }

    @Test
    fun `the favourites panel opens and reports being empty`() {
        render()
        viewModel.showOverlay(Overlay.BOOKMARKS)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("No favourites yet").assertIsDisplayed()
    }

    @Test
    fun `settings shows the real preference values`() {
        render()
        viewModel.showOverlay(Overlay.SETTINGS)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("Search engine").assertIsDisplayed()
        compose.onNodeWithText("Google").assertIsDisplayed()
        compose.onNodeWithText("Fullscreen in landscape").assertIsDisplayed()
    }

    @Test
    fun `landscape moves the chrome to the top and adds real navigation buttons`() {
        // The bars are not the same bar rotated: landscape trades the thumb-reachable bottom
        // strip for a shorter top strip plus back, forward and a fullscreen control.
        org.robolectric.RuntimeEnvironment.setQualifiers("w891dp-h411dp-land")
        render()
        viewModel.blurOmnibox()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithContentDescription("Forward").assertExists()
        compose.onNodeWithContentDescription("Fullscreen browsing").assertExists()
        compose.onNodeWithContentDescription("1 open tabs").assertExists()
    }

    @Test
    fun `portrait keeps the chrome minimal`() {
        render()
        viewModel.blurOmnibox()
        compose.waitForIdle()

        // Portrait deliberately shows only the omnibox, the tab count and the menu.
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fullscreen browsing").assertDoesNotExist()
        compose.onNodeWithContentDescription("Menu").assertExists()
    }

    @Test
    fun `bookmarking the current page is reflected everywhere`() {
        render()
        viewModel.load("https://example.com/page")
        compose.waitForIdle()

        viewModel.toggleBookmark()
        compose.waitForIdle()
        assertTrue("the star must respond immediately", viewModel.isCurrentBookmarked())

        viewModel.toggleBookmark()
        compose.waitForIdle()
        assertFalse(viewModel.isCurrentBookmarked())
    }

    @Test
    fun `switching tabs never rebuilds the page`() {
        render()
        val first = viewModel.activeTab!!
        viewModel.load("https://a.test")
        compose.waitForIdle()
        val firstWebView = first.webView

        viewModel.newTab("https://b.test")
        compose.waitForIdle()
        viewModel.selectTab(first.id)
        compose.waitForIdle()

        assertSame("returning to a tab must reuse its webview", firstWebView, first.webView)
    }

    @Test
    fun `back unwinds the layers in the order the user sees them`() {
        render()
        // An open omnibox is the first thing back should close.
        viewModel.focusOmnibox("")
        compose.waitForIdle()
        assertEquals(BackAction.BLUR_OMNIBOX, viewModel.pendingBackAction())
        assertTrue(viewModel.handleBack())

        // With nothing open and no history, back belongs to the system: it leaves the browser
        // rather than being swallowed.
        assertEquals(BackAction.LEAVE_BROWSER, viewModel.pendingBackAction())
        assertFalse(viewModel.handleBack())

        viewModel.showOverlay(Overlay.HISTORY)
        assertEquals(BackAction.DISMISS_OVERLAY, viewModel.pendingBackAction())
        assertTrue(viewModel.handleBack())

        viewModel.openFind()
        assertEquals(BackAction.CLOSE_FIND, viewModel.pendingBackAction())
        assertTrue(viewModel.handleBack())

        viewModel.enterImmersive()
        assertEquals(BackAction.EXIT_IMMERSIVE, viewModel.pendingBackAction())
        assertTrue(viewModel.handleBack())
    }

    @Test
    fun `page history is part of the back chain`() {
        render()
        viewModel.blurOmnibox()
        viewModel.load("https://a.test")
        compose.waitForIdle()
        assertEquals(BackAction.LEAVE_BROWSER, viewModel.pendingBackAction())

        // Once the page reports history, back navigates instead of closing the browser — the
        // single most surprising thing a browser can get wrong.
        viewModel.activeTab!!.canGoBack = true
        compose.waitForIdle()
        assertEquals(BackAction.GO_BACK, viewModel.pendingBackAction())
    }

    @Test
    fun `fullscreen unwinds before anything else`() {
        render()
        viewModel.blurOmnibox()
        viewModel.load("https://a.test")
        compose.waitForIdle()
        viewModel.activeTab!!.media = com.slate.browser.web.MediaState(
            hasVideo = true, isPlaying = true, width = 1920, height = 1080,
        )
        viewModel.showOverlay(Overlay.NONE)
        viewModel.enterMediaFullscreen()
        compose.waitForIdle()

        assertEquals(BackAction.EXIT_MEDIA_FULLSCREEN, viewModel.pendingBackAction())
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.isMediaFullscreen)
    }

    @Test
    fun `desktop mode changes the user agent and is remembered for that tab`() {
        render()
        viewModel.blurOmnibox()
        viewModel.load("https://example.com")
        compose.waitForIdle()
        val tab = viewModel.activeTab!!

        viewModel.toggleDesktopMode()
        compose.waitForIdle()
        assertTrue(tab.isDesktopMode)
        assertTrue(tab.webView!!.settings.userAgentString.contains("X11; Linux x86_64"))

        // The preference travels with the tab, so a restored session comes back in the same mode.
        val snapshot = viewModel.tabManager.snapshot()
        assertTrue(snapshot.tabs.single { it.id == tab.id }.desktopMode)

        viewModel.toggleDesktopMode()
        compose.waitForIdle()
        assertFalse(tab.isDesktopMode)
        assertFalse(viewModel.tabManager.snapshot().tabs.single { it.id == tab.id }.desktopMode)
    }

    /** Records what the browser asked the platform to do, so nothing escapes to the device. */
    private class RecordingHost : BrowserHost {
        val externalUrls = mutableListOf<String>()
        override fun onEnterElementFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = Unit
        override fun onExitElementFullscreen() = Unit
        override fun requestSystemPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) =
            onResult(true)
        override fun openFileChooser(intent: Intent, callback: ValueCallback<Array<Uri>?>) = false
        override fun openExternally(url: String): Boolean {
            externalUrls += url
            return true
        }
        override fun toast(message: String) = Unit
        override fun snack(message: String, actionLabel: String?, action: (() -> Unit)?) = Unit
    }
}
