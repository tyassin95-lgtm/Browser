package com.slate.browser.cast

import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the media the same question a receiver would, before a receiver is asked to.
 *
 * A television fetching a video has none of this browser's cookies, none of its headers and
 * none of its origin. A stream the phone plays perfectly well because the user is signed in
 * answers a stranger with a redirect to a sign-in page, and the receiver's only way to report
 * that is a black screen and a status code nobody sees. So the browser asks first.
 *
 * The trap — and the bug this was rewritten to fix — is that a probe which is *less* capable
 * than the receiver condemns media the receiver could have played. The first version sent no
 * user agent at all, did not follow a redirect that changed protocol, and read every 403 as
 * "you need to sign in". Content delivery networks refuse an empty user agent as a matter of
 * course; that is hotlink protection, not authentication, and telling somebody to sign in to a
 * video that has no sign-in is worse than saying nothing. So the probe now looks like the thing
 * it is standing in for, follows redirects itself, and only says "sign in" when the server
 * actually asked for credentials.
 */
object CastPreflight {

    sealed interface Result {
        /** Fetchable by a stranger. [contentType] is what the server actually said it was. */
        data class Reachable(val contentType: String?) : Result

        /** The server asked for credentials: a 401, or a redirect into a sign-in page. */
        data object NeedsSignIn : Result

        /**
         * The server refused outright without asking for anything. This is what hotlink
         * protection looks like: the video plays in the page it belongs to and nowhere else.
         */
        data object Forbidden : Result

        /** The server answered, but with something that is not media — usually a web page. */
        data object NotMedia : Result

        /** Gone: the address does not lead anywhere any more. */
        data object Missing : Result

        /**
         * No usable answer — refused connection, timeout, a server error.
         *
         * Deliberately *not* a refusal to cast. This device and the receiver are on different
         * connections and often different routes, and a probe that times out here says nothing
         * certain about what a television would get. The caller lets the attempt proceed and
         * reports what the receiver itself says.
         */
        data class Inconclusive(val detail: String) : Result
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
        var address = url
        // Redirects are followed by hand because HttpURLConnection silently refuses to follow
        // one that changes protocol, and http-to-https is the most ordinary redirect there is:
        // left to itself it returns the 301 as though it were the answer.
        repeat(MAX_REDIRECTS) {
            val hop = request(address, rangedRequest, timeoutMs)
            val next = hop.location ?: return classify(hop, address)
            address = runCatching { URL(URL(address), next).toString() }.getOrNull() ?: return classify(hop, address)
            if (isSignInPage(address)) return Result.NeedsSignIn
        }
        return Result.Inconclusive("too many redirects")
    }

    private class Hop(
        val status: Int,
        val contentType: String?,
        val location: String?,
        val authenticate: String?,
        val failure: String?,
    )

    private fun request(url: String, rangedRequest: Boolean, timeoutMs: Int): Hop {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // One byte is enough to learn the status and the type, and asking for a range
                // also tells us the server supports seeking, which a receiver needs.
                if (rangedRequest) setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Accept", "*/*")
                // The receiver's view, not the browser's: no cookies and no referrer, because
                // borrowing the browser's session would prove nothing about the television.
                // A user agent, though, is not a credential — every real client sends one, and
                // sending none is what makes an ordinary CDN refuse a perfectly public file.
                setRequestProperty("User-Agent", RECEIVER_USER_AGENT)
                useCaches = false
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            val status = connection.responseCode
            Hop(
                status = status,
                contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(),
                location = if (status in 300..399) connection.getHeaderField("Location") else null,
                authenticate = connection.getHeaderField("WWW-Authenticate"),
                failure = null,
            )
        } catch (failure: Exception) {
            Hop(0, null, null, null, failure.javaClass.simpleName)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun classify(hop: Hop, finalUrl: String): Result = when {
        hop.failure != null -> Result.Inconclusive(hop.failure)
        hop.status == 401 || hop.status == 407 -> Result.NeedsSignIn
        // A refusal with no challenge attached is not an invitation to sign in. It is a server
        // saying this address is served to the page it belongs to and to nothing else.
        hop.status == 403 -> if (hop.authenticate != null) Result.NeedsSignIn else Result.Forbidden
        hop.status == 404 || hop.status == 410 -> Result.Missing
        // The server has the file and simply will not slice it. That is a statement about
        // ranges, not about whether a receiver could fetch the whole thing.
        hop.status == 416 -> Result.Reachable(hop.contentType)
        hop.status in 200..299 -> classifyType(hop.contentType, finalUrl)
        else -> Result.Inconclusive("the server returned ${hop.status}")
    }

    /**
     * A media request answered with a web page is either a sign-in wall or a mistake about what
     * the address was. Which one it is shows in the address, not in the type.
     */
    private fun classifyType(contentType: String?, finalUrl: String): Result = when {
        contentType == null -> Result.Reachable(null)
        contentType.startsWith("video/") || contentType.startsWith("audio/") ->
            Result.Reachable(contentType)
        contentType in ADAPTIVE_TYPES -> Result.Reachable(contentType)
        contentType.startsWith("text/html") || contentType.startsWith("application/xhtml") ->
            if (isSignInPage(finalUrl)) Result.NeedsSignIn else Result.NotMedia
        contentType.startsWith("text/") -> Result.NotMedia
        // Servers routinely hand out media as a generic binary type; that is not evidence
        // against it, and the receiver will work out what it has.
        else -> Result.Reachable(contentType)
    }

    /**
     * Whether an address is where a site sends somebody who is not signed in.
     *
     * Matched on whole path segments so that a film called `login-2` and a channel named
     * `signin` are not accused of being sign-in pages.
     */
    internal fun isSignInPage(url: String): Boolean {
        val path = runCatching { URL(url).path }.getOrNull()?.lowercase() ?: return false
        return path.split('/', '.', '-', '_').any { it in SIGN_IN_SEGMENTS }
    }

    private val SIGN_IN_SEGMENTS =
        setOf("login", "signin", "sign_in", "auth", "authorize", "oauth", "session", "account")

    private val ADAPTIVE_TYPES = setOf(
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "audio/x-mpegurl",
        "audio/mpegurl",
        "application/dash+xml",
        "application/octet-stream",
        "binary/octet-stream",
    )

    /**
     * What a Cast receiver looks like on the wire. Not a disguise — it is the client this probe
     * is standing in for, and a server's answer to it is the answer the receiver will get.
     */
    private const val RECEIVER_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36 CrKey/1.56.500000"

    private const val MAX_REDIRECTS = 5
    private const val DEFAULT_TIMEOUT_MS = 4_000
}
