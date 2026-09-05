package com.slate.browser.tabs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A tab as it survives across process death: enough to restore, cheap to write. */
data class PersistedTab(
    val id: String,
    val url: String,
    val title: String,
    val desktopMode: Boolean,
)

data class PersistedSession(val tabs: List<PersistedTab>, val activeId: String?)

/**
 * Session state is written as a small JSON file rather than into the database: it is rewritten
 * on every navigation, is worthless if stale, and must never block a page load.
 */
class TabPersistence(context: Context) {

    private val file = File(context.filesDir, "session.json")

    fun save(session: PersistedSession) {
        runCatching {
            val array = JSONArray()
            session.tabs.forEach { tab ->
                array.put(
                    JSONObject().apply {
                        put("id", tab.id)
                        put("url", tab.url)
                        put("title", tab.title)
                        put("desktop", tab.desktopMode)
                    }
                )
            }
            val root = JSONObject().apply {
                put("tabs", array)
                put("active", session.activeId ?: JSONObject.NULL)
            }
            file.writeText(root.toString())
        }
    }

    fun load(): PersistedSession? = runCatching {
        if (!file.exists()) return@runCatching null
        val root = JSONObject(file.readText())
        val array = root.optJSONArray("tabs") ?: return@runCatching null
        val tabs = (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val url = obj.optString("url")
            if (url.isBlank()) return@mapNotNull null
            PersistedTab(
                id = obj.optString("id"),
                url = url,
                title = obj.optString("title"),
                desktopMode = obj.optBoolean("desktop", false),
            )
        }
        if (tabs.isEmpty()) return@runCatching null
        PersistedSession(tabs, root.optString("active").takeIf { it.isNotBlank() })
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }
}
