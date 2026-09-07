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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.slate.browser.ui.CHROME_TAG
import com.slate.browser.ui.PAGE_TAG
import com.slate.browser.ui.theme.SlateTheme
import com.slate.browser.web.BrowserHost
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * How the page and the browser's own chrome share the window.
 *
 * The two rules that matter are that they never overlap — a toolbar drawn over the page makes
 * content unreachable on a site that cannot scroll — and that between them they clear every
 * system inset, including a navigation bar that moves to the side in landscape, which is what
 * puts the menu button out of reach.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutInsetsTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var viewModel: BrowserViewModel
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()

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
        // Clearing the store cancels the ViewModel's scope; the database is only closed once
        // that cancellation has actually run, or a query still unwinding reports a closed
        // connection into whichever test happens to start next.
        viewModelStore.clear()
        repeat(5) { org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        runCatching { db.close() }
    }

    private var hostView: View? = null

    private fun render() {
        compose.setContent {
            hostView = LocalView.current
            SlateTheme(themeMode = ThemeMode.LIGHT) {
                BrowserScreen(viewModel, "test", onShare = { _, _ -> }, onOpenExternally = {})
            }
        }
        viewModel.blurOmnibox()
        compose.waitForIdle()
    }

    /** Puts real system bars around the window, the way a device does. */
    private fun applyInsets(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
        compose.runOnUiThread {
            val root = requireNotNull(hostView)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(left, top, right, bottom),
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        compose.waitForIdle()
    }

    private fun pageBounds() = compose.onNodeWithTag(PAGE_TAG).fetchSemanticsNode().boundsInRoot
    private fun chromeBounds() = compose.onNodeWithTag(CHROME_TAG).fetchSemanticsNode().boundsInRoot

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `in portrait the page ends where the toolbar begins`() {
        render()
        val page = pageBounds()
        val chrome = chromeBounds()

        assertTrue("the toolbar sits below the page", chrome.top >= page.bottom - 1f)
        assertTrue("the page must actually have room", page.height > 0f)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun `in landscape the page starts where the toolbar ends`() {
        render()
        val page = pageBounds()
        val chrome = chromeBounds()

        assertTrue("the toolbar sits above the page", chrome.bottom <= page.top + 1f)
        assertTrue(page.height > 0f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the page keeps clear of the status bar and the toolbar keeps clear of the navigation bar`() {
        render()
        val statusBar = 48
        val navBar = 60
        applyInsets(top = statusBar, bottom = navBar)

        val page = pageBounds()
        val chrome = chromeBounds()
        assertTrue("page must start below the status bar", page.top >= statusBar - 1f)
        assertTrue("page must still end at the toolbar", chrome.top >= page.bottom - 1f)

        // The toolbar's surface reaches the window edge, but its controls do not: they are
        // pushed up out of the navigation bar.
        val menu = compose.onNodeWithContentDescription("Menu").fetchSemanticsNode().boundsInRoot
        assertTrue("the menu button must clear the navigation bar", menu.bottom <= chrome.bottom - navBar + 1f)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun `a navigation bar on the side never covers the menu button`() {
        render()
        // Landscape on a gesture-navigation phone: the bar moves to one side, and the window
        // still extends underneath it.
        val sideBar = 88
        applyInsets(top = 28, right = sideBar)
        compose.waitForIdle()

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithContentDescription("Menu").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the menu button ends up under a side navigation bar unless the toolbar takes " +
                "horizontal insets as well as vertical ones",
            menu.right <= root.right - sideBar + 1f,
        )
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `hiding the toolbar gives its space, and its inset, to the page`() {
        render()
        applyInsets(top = 48, bottom = 60)
        val withChrome = pageBounds()

        // Scrolling decides the toolbar should go; the move itself lands once the page has
        // stopped, so the test has to let that settle just as a real scroll would.
        var y = 0
        repeat(10) {
            y += 60
            viewModel.onPageScrolled(delta = 60, scrollY = y)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(400))
        compose.waitForIdle()
        val withoutChrome = pageBounds()

        assertTrue("the page grows when the toolbar goes away", withoutChrome.height > withChrome.height)
        // It grows into the toolbar's space but not into the navigation bar's.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the page must not slide under the navigation bar once the toolbar is gone",
            withoutChrome.bottom <= root.bottom - 60 + 1f,
        )
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun `fullscreen browsing takes the whole window, insets included`() {
        render()
        applyInsets(top = 28, right = 88)
        viewModel.enterImmersive()
        compose.waitForIdle()

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val page = pageBounds()
        assertTrue("immersive means every pixel", page.width >= root.width - 1f)
        assertTrue(page.height >= root.height - 1f)
        compose.onNodeWithTag(CHROME_TAG).assertDoesNotExist()
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
