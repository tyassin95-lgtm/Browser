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
    fun `opening a tab selects it and notifies`() {
        val tab = manager.openTab("https://example.com")
        assertEquals(1, manager.count)
        assertEquals(tab.id, manager.activeTabId)
        assertTrue(changes > 0)
    }

    @Test
    fun `at most four webviews stay live`() {
        repeat(8) { index -> manager.openTab("https://example.com/$index") }
        manager.tabs.forEach { manager.webViewFor(it) }

        val live = manager.tabs.count { it.webView != null }
        assertTrue("expected at most 4 live webviews but found $live", live <= 4)
        assertEquals("the active tab must always be live", true, manager.activeTab?.webView != null)
    }

    @Test
    fun `a hibernated tab keeps its url and comes back`() {
        val first = manager.openTab("https://first.test")
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
        val a = manager.openTab("https://a.test")
        val b = manager.openTab("https://b.test")
        val c = manager.openTab("https://c.test")
        manager.select(b.id)

        manager.close(b.id)
        assertEquals(a.id, manager.activeTabId)
        assertEquals(2, manager.count)
        assertTrue(manager.tabs.none { it.id == b.id })
        assertNotNull(c)
    }

    @Test
    fun `a closed tab can be reopened`() {
        val tab = manager.openTab("https://example.com")
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
        manager.openTab("https://a.test")
        manager.openTab("https://b.test")
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
        manager.openTab("https://a.test")
        manager.openTab("https://b.test")
        val snapshot = manager.snapshot()

        val other = TabManager(createWebView = { WebView(context) }, onTabsChanged = {})
        other.bind(context)
        other.restore(snapshot)

        assertEquals(manager.count, other.count)
        assertEquals(manager.activeTabId, other.activeTabId)
    }

    @Test
    fun `hibernating the inactive tabs spares the active one`() {
        val a = manager.openTab("https://a.test")
        val b = manager.openTab("https://b.test")
        manager.webViewFor(a)
        manager.webViewFor(b)
        manager.select(b.id)

        manager.hibernateInactive()
        assertNull(a.webView)
        assertNotNull(b.webView)
    }

    @Test
    fun `rebinding to a different context releases the old webviews`() {
        val tab = manager.openTab("https://a.test")
        val original = manager.webViewFor(tab)
        assertSame(original, tab.webView)

        // A recreated Activity is a different Context object; nothing may keep the old view alive.
        manager.bind(android.content.ContextWrapper(context))
        assertNull(tab.webView)
    }

    @Test
    fun `an adopted popup joins the strip`() {
        val popup = Tab(initialUrl = "https://popup.test")
        manager.adoptTab(popup, select = false)
        assertEquals(1, manager.count)
        assertNull(manager.activeTabId)
    }
}
