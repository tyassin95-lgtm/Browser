package com.slate.browser

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.tabs.PersistedSession
import com.slate.browser.tabs.PersistedTab
import com.slate.browser.tabs.Tab
import com.slate.browser.tabs.TabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tab layer is where a browser either stays responsive or runs the phone out of memory, so
 * its budget and hibernation rules are pinned down here.
 */
@RunWith(RobolectricTestRunner::class)
class TabManagerTest {

    private lateinit var context: Context
    private var changes = 0
    private lateinit var manager: TabManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        changes = 0
        manager = TabManager(
            createWebView = { WebView(context) },
            onTabsChanged = { changes++ },
        )
        manager.bind(context)
    }

    @Test
    fun `creating a tab never changes which tab is on screen`() {
        val first = manager.createTab("https://first.test")
        manager.select(first.id)

        // The whole point of the split: creation is not selection, so a background tab cannot
        // move the user however the caller happens to be written.
        val second = manager.createTab("https://second.test")
        val third = manager.createTab("https://third.test")

        assertEquals(3, manager.count)
        assertEquals("still looking at the first tab", first.id, manager.activeTabId)
        assertNotNull(second)
        assertNotNull(third)
        assertTrue(changes > 0)
    }

    @Test
    fun `selecting is the only thing that moves the user`() {
        val first = manager.createTab("https://first.test")
        manager.select(first.id)
        val second = manager.createTab("https://second.test")
        assertEquals(first.id, manager.activeTabId)

        manager.select(second.id)
        assertEquals(second.id, manager.activeTabId)
    }

    @Test
    fun `a background tab does not disturb the live webview of the tab on screen`() {
        val first = manager.createTab("https://first.test")
        manager.select(first.id)
        val live = manager.webViewFor(first)

        repeat(3) { manager.createTab("https://background.test/" + it) }

        assertSame("the page on screen must be left exactly as it was", live, first.webView)
        assertEquals(first.id, manager.activeTabId)
    }

    @Test
    fun `at most four webviews stay live`() {
        repeat(8) { index -> manager.select(manager.createTab("https://example.com/$index").id) }
        manager.tabs.forEach { manager.webViewFor(it) }

        val live = manager.tabs.count { it.webView != null }
        assertTrue("expected at most 4 live webviews but found $live", live <= 4)
        assertEquals("the active tab must always be live", true, manager.activeTab?.webView != null)
    }

    @Test
    fun `a hibernated tab keeps its url and comes back`() {
        val first = manager.createTab("https://first.test")
        manager.select(first.id)
        manager.webViewFor(first)
        assertNotNull(first.webView)

        manager.hibernate(first)
        assertNull(first.webView)

        val revived = manager.webViewFor(first)
        assertNotNull(revived)
        assertEquals("https://first.test", first.pendingUrl ?: first.url)
    }

    @Test
    fun `closing the selected tab focuses its left neighbour`() {
        val a = manager.createTab("https://a.test")
        val b = manager.createTab("https://b.test")
        val c = manager.createTab("https://c.test")
        manager.select(c.id)
        manager.select(b.id)

        manager.close(b.id)
        assertEquals(a.id, manager.activeTabId)
        assertEquals(2, manager.count)
        assertTrue(manager.tabs.none { it.id == b.id })
        assertNotNull(c)
    }

    @Test
    fun `a closed tab can be reopened`() {
        val tab = manager.createTab("https://example.com")
        manager.select(tab.id)
        tab.url = "https://example.com"
        manager.close(tab.id)
        assertTrue(manager.canUndoClose)

        val restored = manager.undoClose()
        assertNotNull(restored)
        assertEquals("https://example.com", restored?.pendingUrl)
        assertNotSame(tab.id, restored?.id)
    }

    @Test
    fun `closing every tab leaves nothing selected`() {
        manager.createTab("https://a.test")
        manager.createTab("https://b.test")
        manager.closeAll()
        assertEquals(0, manager.count)
        assertNull(manager.activeTabId)
    }

    @Test
    fun `a restored session loads nothing until a tab is opened`() {
        manager.restore(
            PersistedSession(
                tabs = listOf(
                    PersistedTab("a", "https://a.test", "A", false),
                    PersistedTab("b", "https://b.test", "B", true),
                ),
                activeId = "a",
            )
        )

        assertEquals(2, manager.count)
        assertEquals("a", manager.activeTabId)
        assertTrue("restore must not build webviews", manager.tabs.all { it.webView == null })
        assertEquals(true, manager.tabs[1].isDesktopMode)
        assertEquals("B", manager.tabs[1].title)
    }

    @Test
    fun `a snapshot round-trips through restore`() {
        manager.select(manager.createTab("https://a.test").id)
        manager.select(manager.createTab("https://b.test").id)
        val snapshot = manager.snapshot()

        val other = TabManager(createWebView = { WebView(context) }, onTabsChanged = {})
        other.bind(context)
        other.restore(snapshot)

        assertEquals(manager.count, other.count)
        assertEquals(manager.activeTabId, other.activeTabId)
    }

    @Test
    fun `hibernating the inactive tabs spares the active one`() {
        val a = manager.createTab("https://a.test")
        val b = manager.createTab("https://b.test")
        manager.select(a.id)
        manager.webViewFor(a)
        manager.webViewFor(b)
        manager.select(b.id)

        manager.hibernateInactive()
        assertNull(a.webView)
        assertNotNull(b.webView)
    }

    @Test
    fun `rebinding to a different context releases the old webviews`() {
        val tab = manager.createTab("https://a.test")
        manager.select(tab.id)
        val original = manager.webViewFor(tab)
        assertSame(original, tab.webView)

        // A recreated Activity is a different Context object; nothing may keep the old view alive.
        manager.bind(android.content.ContextWrapper(context))
        assertNull(tab.webView)
    }

    @Test
    fun `an adopted popup joins the strip without taking over`() {
        val existing = manager.createTab("https://page.test")
        manager.select(existing.id)

        val popup = Tab(initialUrl = "https://popup.test")
        manager.adoptTab(popup)

        assertEquals(2, manager.count)
        assertEquals("a window a page opened must not steal the screen", existing.id, manager.activeTabId)
    }
}
