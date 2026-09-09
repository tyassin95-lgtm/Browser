package com.slate.browser

import com.slate.browser.cast.CastPreflight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The check that stands in for the television.
 *
 * Run against a real socket speaking real HTTP rather than a mock, because what is being tested
 * is how real responses are read: a sign-in wall answers 200 with a web page, a hotlink guard
 * answers 403, a CDN answers 206 with a byte, and half the internet answers a redirect. Each
 * has to become a different sentence — and the ones that are not evidence of anything must not
 * become a refusal at all.
 */
class CastPreflightTest {

    private lateinit var server: ServerSocket
    private var port = 0

    /** Path to the raw response it should answer with. */
    private val routes: Map<String, String> by lazy {
        mapOf(
            "/movie.mp4" to
                "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
                "Content-Length: 1\r\nConnection: close\r\n\r\nA",
            // A stream behind a real credential check: the server asks for one.
            "/members-only.mp4" to
                "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Bearer\r\nContent-Length: 0\r\n" +
                "Connection: close\r\n\r\n",
            // The other shape of the same thing: a sign-in page delivered as a perfectly
            // successful response, which is what a receiver would silently render as nothing.
            "/watch/login" to
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: 27\r\n" +
                "Connection: close\r\n\r\n<html>Please sign in</html>",
            // A refusal with no challenge attached. This is hotlink protection, and it is not
            // an invitation to sign in to anything.
            "/hotlinked.mp4" to
                "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "/gone.mp4" to "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "/wobbly.mp4" to
                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "/playlist.m3u8" to
                "HTTP/1.1 200 OK\r\nContent-Type: application/x-mpegurl\r\nContent-Length: 8\r\n" +
                "Connection: close\r\n\r\n#EXTM3U\n",
            // Plenty of CDNs serve video as a generic binary type; that is not evidence against it.
            "/opaque.bin" to
                "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 1\r\n" +
                "Connection: close\r\n\r\nA",
            // A manifest served by something that refuses byte ranges.
            "/no-ranges.m3u8" to
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Type: application/x-mpegurl\r\n" +
                "Content-Length: 0\r\n\r\n",
            "/notes.txt" to
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1\r\n" +
                "Connection: close\r\n\r\nA",
            "/moved.mp4" to
                "HTTP/1.1 302 Found\r\nLocation: /movie.mp4\r\nContent-Length: 0\r\n" +
                "Connection: close\r\n\r\n",
            "/paywalled.mp4" to
                "HTTP/1.1 302 Found\r\nLocation: /watch/login\r\nContent-Length: 0\r\n" +
                "Connection: close\r\n\r\n",
        )
    }

    @Before
    fun setUp() {
        server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        socket.use {
                            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                            val request = reader.readLine().orEmpty()
                            val path = request.split(' ').getOrNull(1).orEmpty()
                            var userAgent = ""
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("User-Agent:", true)) {
                                    userAgent = line.substringAfter(':').trim()
                                }
                            }
                            // The one route that behaves like the CDNs this probe kept
                            // getting wrong: it serves anything that looks like a client and
                            // refuses anything that does not.
                            val response = if (path == "/needs-a-client.mp4") {
                                if (userAgent.isBlank()) {
                                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                } else {
                                    "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\n" +
                                        "Content-Length: 1\r\nConnection: close\r\n\r\nA"
                                }
                            } else {
                                routes[path]
                                    ?: "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            }
                            it.getOutputStream().apply {
                                write(response.toByteArray())
                                flush()
                            }
                        }
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    @Test
    fun `a real video answers as reachable, with its type`() {
        val result = CastPreflight.check(url("/movie.mp4"))
        assertTrue(result.toString(), result is CastPreflight.Result.Reachable)
        assertEquals("video/mp4", (result as CastPreflight.Result.Reachable).contentType)
    }

    @Test
    fun `a server that refuses a client with no name is not a sign-in wall`() {
        // The bug this test exists for: the probe sent no user agent, ordinary CDNs refused it
        // on that basis alone, and every one of those refusals was reported to the user as
        // "this video needs you to be signed in". The probe now looks like a client.
        val result = CastPreflight.check(url("/needs-a-client.mp4"))
        assertTrue(result.toString(), result is CastPreflight.Result.Reachable)
    }

    @Test
    fun `a challenge is a sign-in, and a bare refusal is not`() {
        assertTrue(CastPreflight.check(url("/members-only.mp4")) is CastPreflight.Result.NeedsSignIn)
        // 403 with nothing asked for is hotlink protection: the video is served to the page it
        // belongs to and to nobody else. Telling somebody to sign in to it is a lie.
        assertTrue(CastPreflight.check(url("/hotlinked.mp4")) is CastPreflight.Result.Forbidden)
    }

    @Test
    fun `a redirect into a sign-in page is a sign-in`() {
        assertTrue(CastPreflight.check(url("/paywalled.mp4")) is CastPreflight.Result.NeedsSignIn)
    }

    @Test
    fun `an ordinary redirect is followed rather than reported as a failure`() {
        // Redirects are how media is served. Reading the 302 itself as the answer refused a
        // great deal of perfectly castable video.
        val result = CastPreflight.check(url("/moved.mp4"))
        assertTrue(result.toString(), result is CastPreflight.Result.Reachable)
    }

    @Test
    fun `a manifest is reachable`() {
        assertTrue(CastPreflight.check(url("/playlist.m3u8")) is CastPreflight.Result.Reachable)
    }

    @Test
    fun `a generic binary type is not held against the media`() {
        assertTrue(CastPreflight.check(url("/opaque.bin")) is CastPreflight.Result.Reachable)
    }

    @Test
    fun `a web page where a video should be is not media`() {
        assertTrue(CastPreflight.check(url("/notes.txt")) is CastPreflight.Result.NotMedia)
    }

    @Test
    fun `refusing a byte range is not refusing the media`() {
        // Plenty of manifest servers answer 416. Treating that as a failure would refuse a
        // stream a receiver could play perfectly well.
        assertTrue(CastPreflight.check(url("/no-ranges.m3u8")) is CastPreflight.Result.Reachable)
        assertTrue(
            CastPreflight.check(url("/playlist.m3u8"), rangedRequest = false) is
                CastPreflight.Result.Reachable,
        )
    }

    @Test
    fun `an address that leads nowhere is missing`() {
        assertTrue(CastPreflight.check(url("/gone.mp4")) is CastPreflight.Result.Missing)
    }

    @Test
    fun `a server having a bad moment settles nothing, and must not refuse the cast`() {
        // This device and the television are on different connections. A 503 here is not
        // evidence about what the receiver would get, so the attempt goes ahead and the
        // receiver's own answer is what gets reported.
        assertTrue(CastPreflight.check(url("/wobbly.mp4")) is CastPreflight.Result.Inconclusive)
    }

    @Test
    fun `a server that is not there fails quickly rather than hanging`() {
        val started = System.currentTimeMillis()
        // Port 1 is reserved and nothing listens on it.
        val result = CastPreflight.check("http://127.0.0.1:1/movie.mp4", timeoutMs = 1_000)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result is CastPreflight.Result.Inconclusive)
        assertTrue("took ${elapsed}ms; a cast attempt must not hang on this", elapsed < 5_000)
    }

    @Test
    fun `a malformed address is an answer, not a crash`() {
        assertTrue(CastPreflight.check("not a url") is CastPreflight.Result.Inconclusive)
        assertTrue(CastPreflight.check("") is CastPreflight.Result.Inconclusive)
    }

    @Test
    fun `a film called login is not a sign-in page`() {
        // Matched on whole segments, so a perfectly ordinary filename is not condemned by the
        // letters in it.
        assertTrue(CastPreflight.isSignInPage("https://cdn.test/account/login"))
        assertTrue(!CastPreflight.isSignInPage("https://cdn.test/videos/logins-of-2024.mp4"))
        assertTrue(!CastPreflight.isSignInPage("https://cdn.test/movie.mp4"))
    }
}
