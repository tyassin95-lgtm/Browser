package com.slate.browser.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Single entry point for everything the browser persists in SQLite. */
class BrowserRepository(private val db: AppDatabase) {

    constructor(context: Context) : this(AppDatabase.get(context))

    private val history = db.historyDao()
    private val bookmarks = db.bookmarkDao()
    private val folders = db.folderDao()

    // ---- History ------------------------------------------------------------

    fun recentHistory(limit: Int = 500, offset: Int = 0): Flow<List<HistoryGroup>> =
        history.recent(limit, offset)

    fun searchHistory(query: String, limit: Int = 500): Flow<List<HistoryGroup>> =
        history.search(query, limit)

    suspend fun recordVisit(url: String, title: String) {
        if (!isRecordable(url)) return
        history.insert(
            HistoryEntry(
                url = url,
                title = title.ifBlank { url },
                host = hostOf(url),
                visitedAt = System.currentTimeMillis(),
            )
        )
        history.trimTo(MAX_HISTORY_ROWS)
    }

    suspend fun removeHistory(url: String) = history.deleteByUrl(url)

    suspend fun clearHistorySince(since: Long) = history.deleteSince(since)

    suspend fun clearHistory() = history.clear()

    /**
     * Omnibox suggestions, drawn only from what the user has already visited or saved. No
     * query ever leaves the device to build this list.
     */
    suspend fun suggest(query: String, limit: Int = 8): List<Suggestion> {
        val saved = bookmarks.suggest(query, limit)
        val savedUrls = saved.map { it.url }.toSet()
        val visited = history.suggest(query, limit)
        return buildList {
            saved.forEach { add(Suggestion.Page(it.url, it.title, bookmarked = true)) }
            visited.filterNot { it.url in savedUrls }
                .forEach { add(Suggestion.Page(it.url, it.title, bookmarked = false)) }
        }.take(limit)
    }

    // ---- Bookmarks ----------------------------------------------------------

    fun allBookmarks(): Flow<List<Bookmark>> = bookmarks.all()

    fun searchBookmarks(query: String): Flow<List<Bookmark>> = bookmarks.search(query)

    fun bookmarkedUrls(): Flow<Set<String>> = bookmarks.allUrls().map { it.toSet() }

    fun allFolders(): Flow<List<BookmarkFolder>> = folders.all()

    suspend fun isBookmarked(url: String): Boolean = bookmarks.findByUrl(url) != null

    /** Returns true when the page ended up bookmarked, false when it was removed. */
    suspend fun toggleBookmark(url: String, title: String): Boolean {
        if (!isRecordable(url)) return false
        val existing = bookmarks.findByUrl(url)
        return if (existing != null) {
            bookmarks.deleteById(existing.id)
            false
        } else {
            bookmarks.insert(
                Bookmark(
                    url = url,
                    title = title.ifBlank { hostOf(url).ifBlank { url } },
                    host = hostOf(url),
                    createdAt = System.currentTimeMillis(),
                    position = bookmarks.nextPosition(null),
                )
            )
            true
        }
    }

    suspend fun updateBookmark(bookmark: Bookmark) = bookmarks.update(bookmark)

    suspend fun removeBookmark(id: Long) = bookmarks.deleteById(id)

    suspend fun moveBookmark(id: Long, folderId: Long?) {
        bookmarks.move(id, folderId, bookmarks.nextPosition(folderId))
    }

    suspend fun createFolder(name: String): Long =
        folders.insert(BookmarkFolder(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun renameFolder(folder: BookmarkFolder, name: String) =
        folders.update(folder.copy(name = name.trim()))

    /** Deleting a folder keeps its bookmarks, moving them back to the root. */
    suspend fun deleteFolder(id: Long) {
        bookmarks.detachFromFolder(id)
        folders.deleteById(id)
    }

    companion object {
        private const val MAX_HISTORY_ROWS = 20_000

        /** Only real web pages belong in history and bookmarks. */
        fun isRecordable(url: String): Boolean =
            (url.startsWith("http://") || url.startsWith("https://")) && url.length < 4096

        fun hostOf(url: String): String = runCatching {
            Uri.parse(url).host?.removePrefix("www.").orEmpty()
        }.getOrDefault("")
    }
}
