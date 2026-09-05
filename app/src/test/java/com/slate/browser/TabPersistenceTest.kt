package com.slate.browser

import androidx.test.core.app.ApplicationProvider
import com.slate.browser.tabs.PersistedSession
import com.slate.browser.tabs.PersistedTab
import com.slate.browser.tabs.TabPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Tabs must survive the process being killed, which is the normal way a phone reclaims memory. */
@RunWith(RobolectricTestRunner::class)
class TabPersistenceTest {

    private lateinit var persistence: TabPersistence
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        persistence = TabPersistence(context)
        file = File(context.filesDir, "session.json")
        file.delete()
    }

    @Test
    fun `a saved session round-trips`() {
        val session = PersistedSession(
            tabs = listOf(
                PersistedTab("a", "https://example.com", "Example", desktopMode = false),
                PersistedTab("b", "https://news.example/x", "News", desktopMode = true),
            ),
            activeId = "b",
        )
        persistence.save(session)

        val restored = persistence.load()
        assertEquals(2, restored?.tabs?.size)
        assertEquals("b", restored?.activeId)
        assertEquals("https://news.example/x", restored?.tabs?.get(1)?.url)
        assertEquals(true, restored?.tabs?.get(1)?.desktopMode)
    }

    @Test
    fun `no session file means no restore`() {
        assertNull(persistence.load())
    }

    @Test
    fun `a corrupt session file is ignored rather than crashing`() {
        file.writeText("{ this is not json")
        assertNull(persistence.load())
    }

    @Test
    fun `tabs without a url are dropped`() {
        persistence.save(
            PersistedSession(listOf(PersistedTab("a", "", "", false)), activeId = "a"),
        )
        assertNull(persistence.load())
    }

    @Test
    fun `clear removes the session`() {
        persistence.save(PersistedSession(listOf(PersistedTab("a", "https://a.test", "A", false)), "a"))
        persistence.clear()
        assertNull(persistence.load())
    }
}
