package com.slate.browser.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Favicons come from the pages the user actually visits — never from a third-party favicon
 * service — so browsing history is not leaked to fetch an icon. Icons are cached per host in
 * memory and on disk, and history/bookmark rows fall back to a letter tile when none is known.
 */
class FaviconStore(context: Context, private val scope: CoroutineScope) {

    private val dir = File(context.cacheDir, "favicons").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Hosts already known to have no cached icon, so list rows stop re-reading the disk. */
    private val misses = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Observable so Compose rows repaint the moment an icon becomes known. */
    val icons: SnapshotStateMap<String, Bitmap> = mutableStateMapOf()

    fun put(host: String, bitmap: Bitmap) {
        if (host.isBlank() || bitmap.width <= 1) return
        if (memory.get(host) != null && icons.containsKey(host)) return
        memory.put(host, bitmap)
        icons[host] = bitmap
        misses.remove(host)
        scope.launch(Dispatchers.IO) {
            runCatching {
                File(dir, host.hashCode().toString()).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    fun get(host: String): Bitmap? {
        if (host.isBlank()) return null
        icons[host]?.let { return it }
        memory.get(host)?.let { icons[host] = it; return it }
        // Called from list rows during composition, so a miss must be remembered rather than
        // sending every recomposition back to the filesystem.
        if (!misses.add(host)) return null
        scope.launch(Dispatchers.IO) {
            val file = File(dir, host.hashCode().toString())
            if (!file.exists()) return@launch
            val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (bitmap != null) {
                memory.put(host, bitmap)
                misses.remove(host)
                withContext(Dispatchers.Main) { icons[host] = bitmap }
            }
        }
        return null
    }

    fun clear() {
        memory.evictAll()
        icons.clear()
        misses.clear()
        scope.launch(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }
}
