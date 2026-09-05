package com.slate.browser.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    /**
     * History is displayed collapsed by URL: one row per page, most recent visit first.
     * Grouping in SQL keeps the list cheap no matter how large the table grows.
     */
    @Query(
        """
        SELECT url, title, host, MAX(visitedAt) AS visitedAt, COUNT(*) AS visits
        FROM history
        GROUP BY url
        ORDER BY visitedAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun recent(limit: Int, offset: Int): Flow<List<HistoryGroup>>

    @Query(
        """
        SELECT url, title, host, MAX(visitedAt) AS visitedAt, COUNT(*) AS visits
        FROM history
        WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%'
        GROUP BY url
        ORDER BY visitedAt DESC
        LIMIT :limit
        """
    )
    fun search(query: String, limit: Int): Flow<List<HistoryGroup>>

    /** Omnibox suggestions: frequently and recently visited pages win. */
    @Query(
        """
        SELECT url, title, host, MAX(visitedAt) AS visitedAt, COUNT(*) AS visits
        FROM history
        WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%'
        GROUP BY url
        ORDER BY visits DESC, visitedAt DESC
        LIMIT :limit
        """
    )
    suspend fun suggest(query: String, limit: Int): List<HistoryGroup>

    @Query("DELETE FROM history WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM history WHERE visitedAt >= :since")
    suspend fun deleteSince(since: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    /** Trims the oldest rows so history cannot grow without bound. */
    @Query(
        """
        DELETE FROM history WHERE id IN (
            SELECT id FROM history ORDER BY visitedAt DESC LIMIT -1 OFFSET :keep
        )
        """
    )
    suspend fun trimTo(keep: Int)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY position ASC, createdAt DESC")
    fun all(): Flow<List<Bookmark>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%'
        ORDER BY position ASC, createdAt DESC
        """
    )
    fun search(query: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): Bookmark?

    @Query("SELECT url FROM bookmarks")
    fun allUrls(): Flow<List<String>>

    @Query("SELECT * FROM bookmarks WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun suggest(query: String, limit: Int): List<Bookmark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Update
    suspend fun update(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM bookmarks WHERE folderId IS :folderId")
    suspend fun nextPosition(folderId: Long?): Int

    @Query("UPDATE bookmarks SET folderId = :folderId, position = :position WHERE id = :id")
    suspend fun move(id: Long, folderId: Long?, position: Int)

    @Query("UPDATE bookmarks SET folderId = NULL WHERE folderId = :folderId")
    suspend fun detachFromFolder(folderId: Long)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM bookmark_folders ORDER BY name COLLATE NOCASE ASC")
    fun all(): Flow<List<BookmarkFolder>>

    @Insert
    suspend fun insert(folder: BookmarkFolder): Long

    @Update
    suspend fun update(folder: BookmarkFolder)

    @Query("DELETE FROM bookmark_folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}
