package com.slate.browser.cast

import android.net.Uri

/**
 * Recognises the streaming manifests a page fetches for itself.
 *
 * Almost no modern video site hands a `<video>` element a URL any more. hls.js, dash.js and
 * Shaka all feed a MediaSource, so the element's `currentSrc` is a `blob:` that means nothing
 * outside the document — which is true, and useless, because the manifest those players are
 * reading *is* an ordinary address, and a Cast receiver plays HLS and DASH natively.
 *
 * So the browser watches what the page asks the network for. It already sees every request, to
 * decide whether to block it; noticing that one of them is a playlist costs nothing more.
 */
object MediaManifests {

    /** Whether this request is a stream manifest worth remembering, and what kind. */
    fun formatOf(url: String): StreamFormat? {
        val path = pathOf(url) ?: return null
        val last = path.substringAfterLast('/')
        return when {
            // Within the last path segment rather than at the end of it: playlists are served
            // with a further extension often enough to matter (master.m3u8.txt through a CDN
            // that insists on one), and the token-laden query is already excluded.
            last.contains(".m3u8") || last.contains(".m3u") -> StreamFormat.HLS
            last.contains(".mpd") -> StreamFormat.DASH
            // Extensionless manifest endpoints, which several large sites use. The format is
            // named in the query rather than the path, and nothing but the query says so.
            last == "manifest" || last == "playlist" -> queryFormat(url)
            else -> null
        }
    }

    fun isManifest(url: String): Boolean = formatOf(url) != null

    /**
     * Whether this request is a plain media file — one a receiver that cannot play a manifest
     * could be handed directly.
     *
     * The hard part is that an adaptive stream's *segments* are media files too, and sending a
     * television four seconds of video would be worse than refusing. Two things separate them:
     * segments are named like segments, and they arrive after the manifest that lists them — so
     * the caller stops looking once a manifest has been seen, and the obvious segment shapes
     * are refused here.
     */
    fun isPlainMediaFile(url: String): Boolean {
        val path = pathOf(url) ?: return false
        val last = path.substringAfterLast('/')
        val extension = last.substringAfterLast('.', "")
        if (extension !in PLAYABLE_EXTENSIONS) return false
        return !isSegment(last.substringBeforeLast('.'))
    }

    /**
     * Segment naming, as packagers write it.
     *
     * A bare number is always a piece of something. A word plus a number — `video-3`, `seg_12`
     * — is too. A word on its own only counts when the word means nothing else: `init.mp4` is a
     * segment, but `video.mp4` and `audio.mp4` are perfectly ordinary names for a whole file
     * and are not refused on the strength of a noun.
     */
    private fun isSegment(stem: String): Boolean {
        if (stem.isEmpty()) return true
        if (stem.all { it.isDigit() }) return true
        val withoutIndex = stem.trimEnd { it.isDigit() }
        val numbered = withoutIndex.length != stem.length
        val word = withoutIndex.trimEnd('-', '_', '.')
            .substringAfterLast('-').substringAfterLast('_').substringAfterLast('.')
        if (word !in SEGMENT_WORDS) return false
        return numbered || word in ALWAYS_SEGMENT
    }

    private fun queryFormat(url: String): StreamFormat? {
        val query = runCatching { Uri.parse(url).query }.getOrNull()?.lowercase() ?: return null
        return when {
            query.contains("m3u8") || query.contains("hls") -> StreamFormat.HLS
            query.contains("mpd") || query.contains("dash") -> StreamFormat.DASH
            else -> null
        }
    }

    /**
     * The path, not the whole address: these URLs routinely carry a query string of tokens and
     * expiry stamps, and one of those containing ".mpd" proves nothing.
     */
    private fun pathOf(url: String): String? {
        if (url.length > MAX_URL_CHARS) return null
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return runCatching { Uri.parse(url).path }.getOrNull()?.lowercase()
    }

    /** Container formats a receiver stands a chance of playing from a single address. */
    private val PLAYABLE_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "webm", "ogv", "mkv",
        "mp3", "m4a", "aac", "oga", "ogg", "opus", "flac", "wav",
    )

    private val SEGMENT_WORDS =
        setOf("seg", "segment", "chunk", "frag", "fragment", "init", "media", "part", "video", "audio")

    /** The words that mean "a piece of a stream" even with no number after them. */
    private val ALWAYS_SEGMENT = setOf("seg", "segment", "chunk", "frag", "fragment", "init")

    private const val MAX_URL_CHARS = 4096
}
