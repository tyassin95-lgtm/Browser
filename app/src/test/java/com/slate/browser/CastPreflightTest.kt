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
 * Run against a real socket speaking real HTTP rather than a mock, because what is being
 * tested is how real responses are read: a sign-in wall answers 200 with a web page, a token
 * check answers 403, and a CDN answers 206 with a byte. Each has to become a different
 * sentence.
 */
class CastPreflightTest {

    private lateinit var server: ServerSocket
    private var port = 0

    /** Path to the raw response it should answer with. */
    private val routes = mapOf(
        "/movie.mp4" to
            "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
            "Content-Length: 1\r\nConnection: close\r\n\r\nA",
        // A stream that only plays because the browser is signed in.
        "/members-only.mp4" to "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        // The other shape of the same thing: a sign-in page delivered as a perfectly
        // successful response, which is what a receiver would silently render as nothing.
        "/redirects-to-login.mp4" to
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: 24\r\n" +
            "Connection: close\r\n\r\n<html>Please sign in</html>",
        "/gone.mp4" to "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
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
            "Content-Length: 0\r\nConnection: close\r\n\r\n",
        "/notes.txt" to
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1\r\n" +
            "Connection: close\r\n\r\nA",
    )

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
                            val response = routes[path]
                                ?: "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
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
    fun `a stream that needs a session is recognised, however it says so`() {
        assertTrue(CastPreflight.check(url("/members-only.mp4")) is CastPreflight.Result.NeedsSignIn)
        // The important one: a success carrying a sign-in page. Nothing about the status code
        // says anything is wrong, and a receiver would have shown a blank screen.
        assertTrue(
            CastPreflight.check(url("/redirects-to-login.mp4")) is CastPreflight.Result.NeedsSignIn,
        )
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
    fun `text that is not media is refused`() {
        assertTrue(CastPreflight.check(url("/notes.txt")) is CastPreflight.Result.NotMedia)
    }

    @Test
    fun `refusing a byte range is not refusing the media`() {
        // Plenty of manifest servers answer 416. Treating that as unreachable would refuse a
        // stream a receiver could play perfectly well.
        assertTrue(CastPreflight.check(url("/no-ranges.m3u8")) is CastPreflight.Result.Reachable)
        assertTrue(
            CastPreflight.check(url("/playlist.m3u8"), rangedRequest = false) is
                CastPreflight.Result.Reachable,
        )
    }

    @Test
    fun `a missing file is unreachable`() {
        assertTrue(CastPreflight.check(url("/gone.mp4")) is CastPreflight.Result.Unreachable)
    }

    @Test
    fun `a server that is not there fails quickly rather than hanging`() {
        val started = System.currentTimeMillis()
        // Port 1 is reserved and nothing listens on it.
        val result = CastPreflight.check("http://127.0.0.1:1/movie.mp4", timeoutMs = 1_000)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result is CastPreflight.Result.Unreachable)
        assertTrue("took ${elapsed}ms; a cast attempt must not hang on this", elapsed < 5_000)
    }

    @Test
    fun `a malformed address is an answer, not a crash`() {
        assertTrue(CastPreflight.check("not a url") is CastPreflight.Result.Unreachable)
        assertTrue(CastPreflight.check("") is CastPreflight.Result.Unreachable)
    }
}
