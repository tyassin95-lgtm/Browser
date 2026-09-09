package com.slate.browser.cast.hls

import java.net.URI

/**
 * Just enough of the HLS playlist format to relay a stream to a television.
 *
 * Every one of the video hosts people actually use serves HLS, and a DLNA television plays
 * files. The gap between those two facts is this file: an `.m3u8` is a plain text list of the
 * pieces a stream is made of, and once the pieces are known the phone can put them back together
 * into something a file player understands.
 *
 * Written as a parser over strings with no I/O, because a playlist comes off the network from
 * whoever is serving the video, and everything in it — durations, addresses, key locations — is
 * untrusted text that must not be able to do anything worse than produce a bad stream.
 */
object HlsPlaylist {

    /** One quality of a stream, from a master playlist. */
    data class Variant(
        val url: String,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val codecs: String,
    )

    /** One piece of a stream, from a media playlist. */
    data class Segment(
        val url: String,
        val durationMs: Long,
        val sequence: Long,
        /** AES-128 key location and initialisation vector, when the stream is encrypted. */
        val keyUrl: String? = null,
        val iv: String? = null,
    )

    data class Media(
        val segments: List<Segment>,
        /** The initialisation section a fragmented-MP4 stream needs before anything else. */
        val initUrl: String?,
        val isLive: Boolean,
        val targetDurationMs: Long,
    ) {
        val durationMs: Long get() = segments.sumOf { it.durationMs }
    }

    fun isMaster(text: String): Boolean =
        isPlaylist(text) && text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

    /**
     * Whether this is a playlist at all.
     *
     * The tag is mandatory and first, and demanding it is what stops an error page, a block
     * notice or a redirect body being read as a list of video pieces — every line of which
     * would then be fetched and fed to a television.
     */
    private fun isPlaylist(text: String): Boolean =
        text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")

    /**
     * The qualities a master playlist offers, best first.
     *
     * "Best" is not simply the highest bitrate. A television that cannot decode what it is given
     * shows nothing at all, and the sets this relay exists for are far more likely to manage
     * H.264 at 1080p than HEVC at 4K — so anything above that is taken only when there is
     * nothing else. Within what is playable, the highest bitrate wins, because the point of
     * casting is to use the big screen properly.
     */
    fun variants(text: String, baseUrl: String): List<Variant> {
        if (!isPlaylist(text)) return emptyList()
        val found = mutableListOf<Variant>()
        val lines = text.lines()
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val attributes = attributesOf(line.substringAfter(':', ""))
            val url = lines.drop(index + 1)
                .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                ?.trim()
                ?: return@forEachIndexed
            val resolution = attributes["RESOLUTION"].orEmpty().split('x')
            found += Variant(
                url = resolve(baseUrl, url),
                bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0,
                width = resolution.getOrNull(0)?.toIntOrNull() ?: 0,
                height = resolution.getOrNull(1)?.toIntOrNull() ?: 0,
                codecs = attributes["CODECS"].orEmpty(),
            )
        }
        return found.sortedWith(
            compareByDescending<Variant> { it.isWidelyPlayable }.thenByDescending { it.bandwidth },
        )
    }

    private val Variant.isWidelyPlayable: Boolean
        get() = (height == 0 || height <= 1080) && !codecs.contains("hvc1") && !codecs.contains("hev1")

    /** The pieces of one quality, in the order they are played. */
    fun media(text: String, baseUrl: String): Media {
        if (!isPlaylist(text)) return Media(emptyList(), null, false, DEFAULT_TARGET_MS)
        var duration = 0.0
        var sequence = 0L
        var target = 0L
        var initUrl: String? = null
        var keyUrl: String? = null
        var iv: String? = null
        var live = true
        val segments = mutableListOf<Segment>()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    target = ((line.substringAfter(':').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong()

                line.startsWith("#EXT-X-MAP:") -> {
                    val attributes = attributesOf(line.substringAfter(':'))
                    initUrl = attributes["URI"]?.let { resolve(baseUrl, it) }
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = attributesOf(line.substringAfter(':'))
                    // A stream the phone is already playing is one the phone already has the
                    // key for; relaying it changes nothing about who may watch it. Anything
                    // stronger than AES-128 is a licence rather than a key, and is refused
                    // where casting is decided rather than smuggled through here.
                    if (attributes["METHOD"] == "AES-128") {
                        keyUrl = attributes["URI"]?.let { resolve(baseUrl, it) }
                        iv = attributes["IV"]
                    } else {
                        keyUrl = null
                        iv = null
                    }
                }

                line == "#EXT-X-ENDLIST" -> live = false

                line.isNotBlank() && !line.startsWith("#") -> {
                    segments += Segment(
                        url = resolve(baseUrl, line),
                        durationMs = (duration * 1000).toLong().coerceAtLeast(0),
                        sequence = sequence + segments.size,
                        keyUrl = keyUrl,
                        iv = iv,
                    )
                    duration = 0.0
                }
            }
        }
        return Media(
            segments = segments,
            initUrl = initUrl,
            isLive = live,
            targetDurationMs = if (target > 0) target else DEFAULT_TARGET_MS,
        )
    }

    /**
     * `KEY=value` pairs, where a value may be quoted and may contain commas — which the
     * addresses in a playlist routinely do.
     */
    internal fun attributesOf(line: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        var index = 0
        while (index < line.length) {
            val equals = line.indexOf('=', index)
            if (equals < 0) break
            val key = line.substring(index, equals).trim().trimStart(',')
            var cursor = equals + 1
            val value: String
            if (cursor < line.length && line[cursor] == '"') {
                val close = line.indexOf('"', cursor + 1)
                if (close < 0) break
                value = line.substring(cursor + 1, close)
                cursor = close + 1
            } else {
                val comma = line.indexOf(',', cursor).let { if (it < 0) line.length else it }
                value = line.substring(cursor, comma).trim()
                cursor = comma
            }
            if (key.isNotEmpty()) attributes[key.uppercase()] = value
            index = cursor + 1
        }
        return attributes
    }

    /** Playlist addresses are usually relative, and a stream is worthless if they resolve wrong. */
    internal fun resolve(base: String, url: String): String =
        runCatching { URI(base).resolve(url).toString() }.getOrDefault(url)

    private const val DEFAULT_TARGET_MS = 6_000L
}
