package com.slate.browser

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slate.browser.data.AppDatabase
import com.slate.browser.data.BrowserRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * History and favourites are the two things a user would be upset to lose, so their storage is
 * exercised against a real (in-memory) SQLite database rather than a mock.
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BrowserRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BrowserRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `visits are recorded and collapsed by url`() = runTest {
        repo.recordVisit("https://example.com/a", "Example A")
        repo.recordVisit("https://example.com/a", "Example A")
        repo.recordVisit("https://example.com/b", "Example B")

        val history = repo.recentHistory().first()
        assertEquals(2, history.size)
        val a = history.first { it.url == "https://example.com/a" }
        assertEquals(2, a.visits)
        assertEquals("example.com", a.host)
    }

    @Test
    fun `non-web urls are never recorded`() = runTest {
        repo.recordVisit("about:blank", "Blank")
        repo.recordVisit("data:text/html,hi", "Data")
        assertTrue(repo.recentHistory().first().isEmpty())
    }

    @Test
    fun `history search matches title and url`() = runTest {
        repo.recordVisit("https://kotlinlang.org/docs", "Kotlin docs")
        repo.recordVisit("https://example.com", "Example")

        assertEquals(1, repo.searchHistory("kotlin").first().size)
        assertEquals(1, repo.searchHistory("docs").first().size)
        assertEquals(0, repo.searchHistory("nothing here").first().size)
    }

    @Test
    fun `removing one page leaves the rest`() = runTest {
        repo.recordVisit("https://a.test", "A")
        repo.recordVisit("https://b.test", "B")
        repo.removeHistory("https://a.test")

        val remaining = repo.recentHistory().first()
        assertEquals(1, remaining.size)
        assertEquals("https://b.test", remaining.single().url)
    }

    @Test
    fun `clearing since a cutoff keeps older visits`() = runTest {
        repo.recordVisit("https://old.test", "Old")
        val cutoff = System.currentTimeMillis() + 1
        Thread.sleep(5)
        repo.recordVisit("https://new.test", "New")

        repo.clearHistorySince(cutoff)
        val remaining = repo.recentHistory().first()
        assertEquals(1, remaining.size)
        assertEquals("https://old.test", remaining.single().url)
    }

    @Test
    fun `bookmarking toggles both ways`() = runTest {
        assertTrue(repo.toggleBookmark("https://example.com", "Example"))
        assertTrue(repo.isBookmarked("https://example.com"))
        assertFalse(repo.toggleBookmark("https://example.com", "Example"))
        assertFalse(repo.isBookmarked("https://example.com"))
    }

    @Test
    fun `a bookmark with no title falls back to its host`() = runTest {
        repo.toggleBookmark("https://www.example.com/page", "")
        val bookmark = repo.allBookmarks().first().single()
        assertEquals("example.com", bookmark.title)
    }

    @Test
    fun `deleting a folder keeps its bookmarks at the root`() = runTest {
        val folderId = repo.createFolder("Reading")
        repo.toggleBookmark("https://example.com", "Example")
        val bookmark = repo.allBookmarks().first().single()
        repo.moveBookmark(bookmark.id, folderId)
        assertEquals(folderId, repo.allBookmarks().first().single().folderId)

        repo.deleteFolder(folderId)
        val survivor = repo.allBookmarks().first().single()
        assertNull(survivor.folderId)
        assertTrue(repo.allFolders().first().isEmpty())
    }

    @Test
    fun `suggestions prefer bookmarks and never duplicate a url`() = runTest {
        repo.toggleBookmark("https://example.com/docs", "Docs")
        repo.recordVisit("https://example.com/docs", "Docs")
        repo.recordVisit("https://example.com/other", "Other")

        val suggestions = repo.suggest("example")
        assertEquals(2, suggestions.size)
        assertEquals(1, suggestions.count { it.key == "https://example.com/docs" })
    }
}
