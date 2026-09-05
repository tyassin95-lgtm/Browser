package com.slate.browser.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history",
    indices = [Index("visitedAt"), Index("url")],
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val host: String,
    @ColumnInfo(defaultValue = "0") val visitedAt: Long,
)

@Entity(tableName = "bookmark_folders")
data class BookmarkFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("url"), Index("folderId")],
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val host: String,
    /** null == the implicit root folder. */
    val folderId: Long? = null,
    val createdAt: Long,
    val position: Int = 0,
)

/** A history row collapsed to one entry per URL, carrying its visit count. */
data class HistoryGroup(
    val url: String,
    val title: String,
    val host: String,
    val visitedAt: Long,
    val visits: Int,
)
