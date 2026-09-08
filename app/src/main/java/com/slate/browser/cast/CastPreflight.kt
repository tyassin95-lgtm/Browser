package com.slate.browser.cast

import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the media the same question a receiver would, before a receiver is asked to.
 *
 * A television fetching a video has none of this browser's cookies, none of its headers and
 * none of its origin. A stream the phone plays perfectly well because the user is signed in
 * answers a stranger with a redirect to a sign-in page, or a 403, and the receiver's only way
 * to report that is a black screen and a status code nobody sees.
 *
 * So the browser asks first, anonymously and from this device: one ranged request, no cookies,
 * a short timeout, and a look at what comes back. It is not a guarantee — the receiver is on a
 * different connection and may still fail — but it turns the common failures into a sentence
 * before the session starts instead of a blank television afterwards.
 */
object CastPreflight {

    sealed interface Result {
        /** Fetchable anonymously. [contentType] is what the server actually said it was. */
        data class Reachable(val contentType: String?) : Result

        /** Reachable, but not by a stranger — the answer depends on being signed in. */
        data object NeedsSignIn : Result

        /** The server answered, but not with media a receiver could play. */
        data object NotMedia : Result

        /** No usable answer: gone, refused, or too slow to be worth waiting for. */
        data class Unreachable(val detail: String) : Result
    }

    /**
     * Runs the probe. Blocking, so it belongs on a background thread; the timeouts bound it.
     */
    fun check(
        url: String,
        /**
         * Whether to ask for a byte range. Worth doing for a single file, because a receiver
         * needs ranges to seek in one — and worth *not* doing for a manifest, which plenty of
         * servers answer with 416 and which would turn a perfectly castable stream into a
         * refusal.
         */
        rangedRequest: Boolean = true,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // One byte is enough to learn the status and the type, and asking for a range
                // also tells us the server supports seeking, which a receiver needs.
                if (rangedRequest) setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Accept", "*/*")
                // Deliberately no cookies and no referrer: this is the receiver's view, not the
                // browser's, and borrowing the browser's session would prove nothing.
                setRequestProperty("Cookie", "")
                useCaches = false
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            val status = connection.responseCode
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            when {
                status == 401 || status == 403 || status == 407 -> Result.NeedsSignIn
                // The server has the file and simply will not slice it. That is a statement
                // about ranges, not about whether a receiver could fetch the whole thing.
                status == 416 -> Result.Reachable(contentType)
                status in 200..299 -> classify(contentType)
                status in 500..599 -> Result.Unreachable("the server returned $status")
                else -> Result.Unreachable("the server returned $status")
            }
        } catch (failure: Exception) {
            Result.Unreachable(failure.javaClass.simpleName)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * A media request answered with a web page is the shape of a sign-in wall or a consent
     * interstitial: the server said yes, but what it is offering is not the video.
     */
    private fun classify(contentType: String?): Result = when {
        contentType == null -> Result.Reachable(null)
        contentType.startsWith("video/") || contentType.startsWith("audio/") ->
            Result.Reachable(contentType)
        contentType in ADAPTIVE_TYPES -> Result.Reachable(contentType)
        contentType.startsWith("text/html") || contentType.startsWith("application/xhtml") ->
            Result.NeedsSignIn
        contentType.startsWith("text/") -> Result.NotMedia
        // Servers routinely hand out media as a generic binary type; that is not evidence
        // against it, and the receiver will work out what it has.
        else -> Result.Reachable(contentType)
    }

    private val ADAPTIVE_TYPES = setOf(
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "audio/x-mpegurl",
        "audio/mpegurl",
        "application/dash+xml",
        "application/octet-stream",
        "binary/octet-stream",
    )

    private const val DEFAULT_TIMEOUT_MS = 4_000
}
