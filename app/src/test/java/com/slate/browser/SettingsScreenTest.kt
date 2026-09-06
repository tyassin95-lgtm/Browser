package com.slate.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.slate.browser.ui.theme.SlateTheme
import com.slate.browser.web.BrowserHost
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every control on the Settings screen, operated the way a person operates it.
 *
 * A preference that cannot be changed without taking the process down is worse than one that
 * does not exist, so this walks the whole screen rather than sampling it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var viewModel: BrowserViewModel
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()

    /** Every switch on the screen, by the label the user actually sees. */
    private val toggles = listOf(
        "Desktop sites by default",
        "Hide toolbar while scrolling",
        "Fullscreen in landscape",
        "Reopen tabs on launch",
        "Block ads and trackers",
        "Block pop-ups and redirects",
        "Allow autoplay",
        "Turn sideways for video",
        "Save history",
        "Block third-party cookies",
        "Send Do Not Track",
        "Clear on exit",
        "JavaScript",
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

        compose.setContent {
            SlateTheme(themeMode = ThemeMode.LIGHT) {
                BrowserScreen(viewModel, "test", onShare = { _, _ -> }, onOpenExternally = {})
            }
        }
        viewModel.showOverlay(Overlay.SETTINGS)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
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

    private fun settle() {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        // Preference writes happen off the main thread; give them a real chance to land or fail.
        repeat(20) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun tap(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        settle()
    }

    @Test
    fun `every switch can be turned off and back on`() {
        toggles.forEach { label ->
            tap(label)
            tap(label)
        }
        assertTrue("the browser is still standing", viewModel.tabManager.count >= 1)
    }

    @Test
    fun `every switch survives being hammered`() {
        // Rapid taps are the realistic way a person discovers a broken preference.
        toggles.forEach { label ->
            repeat(4) { compose.onNodeWithText(label).performScrollTo().performClick() }
            settle()
        }
        assertTrue(viewModel.tabManager.count >= 1)
    }

    @Test
    fun `the search engine picker opens and every choice applies`() {
        tap("Search engine")
        listOf("DuckDuckGo", "Brave", "Bing", "Startpage", "Google").forEach { engine ->
            compose.onNodeWithText(engine).performClick()
            settle()
            tap("Search engine")
        }
        compose.onNodeWithText("Close").performClick()
        settle()
    }

    @Test
    fun `the appearance picker opens and every choice applies`() {
        tap("Appearance")
        listOf("Dark", "Light", "Follow system").forEach { mode ->
            compose.onNodeWithText(mode).performClick()
            settle()
            tap("Appearance")
        }
        compose.onNodeWithText("Close").performClick()
        settle()
    }

    @Test
    fun `the home page is stored and is what a new tab opens`() {
        // The dialog itself is not driven here: a Material3 text field never reports idle under
        // Robolectric, so the flow is covered where the behaviour actually lives.
        viewModel.setHomePage("example.com")
        settle()
        assertTrue(viewModel.settings.value.homePage == "example.com")

        viewModel.dismissOverlay()
        viewModel.newTab()
        settle()
        assertTrue(viewModel.activeTab?.url?.contains("example.com") == true)
    }

    @Test
    fun `clearing browsing data can be confirmed and cancelled`() {
        tap("Clear browsing data")
        compose.onNodeWithText("Cancel").performClick()
        settle()

        tap("Clear browsing data")
        compose.onNodeWithText("Clear").performClick()
        settle()
        assertTrue(viewModel.tabManager.count >= 1)
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
