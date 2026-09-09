package com.slate.browser

import com.slate.browser.cast.hls.StreamRelay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The phone standing in for a television that cannot fetch a stream itself.
 *
 * Run against real sockets on both sides — a real origin serving a real playlist, and the relay
 * answering a real HTTP request — because everything that makes this work or fail is on the
 * wire: whether the page's headers are carried, whether the pieces come out in order and
 * unbroken, whether an offset lands where it should, and whether anything else can ask for it.
 */
@RunWith(RobolectricTestRunner::class)
class StreamRelayTest {

    private lateinit var origin: ServerSocket
    private var port = 0
    private lateinit var relay: StreamRelay

    /** Every request the origin saw, so the headers the page needs can be checked. */
    private val seen = mutableListOf<String>()

    private val key = ByteArray(16) { (it + 1).toByte() }

    private fun segment(marker: Byte) = ByteArray(188) { marker }

    private fun encrypted(plain: ByteArray, sequence: Long): ByteArray {
        val iv = ByteArray(16).also {
            for (byte in 0 until 8) it[15 - byte] = (sequence shr (8 * byte)).toByte()
        }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }
    }

    private fun body(path: String): Pair<String, ByteArray>? = when (path) {
        "/hls/master.m3u8" -> "application/vnd.apple.mpegurl" to (
            "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS=\"avc1.4d401e\"\n" +
                "360/index.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028\"\n" +
                "1080/index.m3u8\n"
            ).toByteArray()

        "/hls/1080/index.m3u8" -> "application/vnd.apple.mpegurl" to (
            "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:6.0,\nseg-0.ts\n#EXTINF:6.0,\nseg-1.ts\n#EXTINF:6.0,\nseg-2.ts\n" +
                "#EXT-X-ENDLIST\n"
            ).toByteArray()

        "/hls/1080/seg-0.ts" -> "video/mp2t" to segment(1)
        "/hls/1080/seg-1.ts" -> "video/mp2t" to segment(2)
        "/hls/1080/seg-2.ts" -> "video/mp2t" to segment(3)

        "/locked/index.m3u8" -> "application/vnd.apple.mpegurl" to (
            "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n" +
                "#EXTINF:6.0,\nseg-0.ts\n#EXT-X-ENDLIST\n"
            ).toByteArray()

        "/locked/key.bin" -> "application/octet-stream" to key
        "/locked/seg-0.ts" -> "video/mp2t" to encrypted(segment(9), 0)

        else -> null
    }

    @Before
    fun setUp() {
        origin = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        port = origin.localPort
        thread(isDaemon = true) {
            while (!origin.isClosed) {
                val client = runCatching { origin.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        client.use {
                            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                            val request = StringBuilder()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                request.append(line).append('\n')
                            }
                            val text = request.toString()
                            synchronized(seen) { seen += text }
                            val path = text.lineSequence().first().split(' ').getOrNull(1).orEmpty()
                            val found = body(path)
                            val out = it.getOutputStream()
                            if (found == null) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            } else {
                                out.write(
                                    (
                                        "HTTP/1.1 200 OK\r\nContent-Type: ${found.first}\r\n" +
                                            "Content-Length: ${found.second.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(),
                                )
                                out.write(found.second)
                            }
                            out.flush()
                        }
                    }
                }
            }
        }
        relay = StreamRelay(hostAddress = { "127.0.0.1" })
    }

    @After
    fun tearDown() {
        relay.stop()
        runCatching { origin.close() }
    }

    private fun source(path: String) = StreamRelay.Source(
        playlistUrl = "http://127.0.0.1:$port$path",
        referer = "https://someplayer.test/watch/9",
        cookies = "session=abc",
        userAgent = "Vox/1.0",
    )

    /** Reads a relayed stream the way a television does: one GET, then bytes until it ends. */
    private fun fetch(url: String): ByteArray {
        val address = java.net.URL(url)
        Socket(address.host, address.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(
                ("GET ${address.file} HTTP/1.1\r\nHost: ${address.host}\r\n\r\n").toByteArray(),
            )
            val input = socket.getInputStream()
            val all = input.readBytes()
            val split = indexOfHeaderEnd(all)
            return all.copyOfRange(split, all.size)
        }
    }

    private fun headersOf(url: String): String {
        val address = java.net.URL(url)
        Socket(address.host, address.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(
                ("HEAD ${address.file} HTTP/1.1\r\nHost: ${address.host}\r\n\r\n").toByteArray(),
            )
            return String(socket.getInputStream().readBytes())
        }
    }

    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 3) {
            if (bytes[index] == 13.toByte() && bytes[index + 1] == 10.toByte() &&
                bytes[index + 2] == 13.toByte() && bytes[index + 3] == 10.toByte()
            ) {
                return index + 4
            }
        }
        return 0
    }

    @Test
    fun `a playlist becomes one continuous stream a file player can read`() {
        val published = relay.publish(source("/hls/master.m3u8"))
        assertNotNull("the phone must be able to publish this", published)
        assertEquals("video/mp2t", published!!.contentType)
        assertEquals(18_000L, published.durationMs)

        val streamed = fetch(published.url)
        // The pieces, in order, unaltered: nothing is transcoded, so the quality is the site's.
        assertEquals(188 * 3, streamed.size)
        assertEquals(1.toByte(), streamed[0])
        assertEquals(2.toByte(), streamed[188])
        assertEquals(3.toByte(), streamed[376])
    }

    @Test
    fun `the best quality the television can decode is the one chosen`() {
        relay.publish(source("/hls/master.m3u8"))
        val asked = synchronized(seen) { seen.joinToString("\n") }
        assertTrue("the 1080p variant is the one fetched", asked.contains("/hls/1080/index.m3u8"))
    }

    @Test
    fun `every fetch is made as the page, which is why hotlinked streams work at all`() {
        val published = relay.publish(source("/hls/master.m3u8"))!!
        fetch(published.url)
        val requests = synchronized(seen) { seen.toList() }
        val segments = requests.filter { it.contains("seg-") }
        assertTrue("segments must be fetched", segments.isNotEmpty())
        segments.forEach {
            assertTrue("a segment fetch must carry the referrer:\n$it", it.contains("Referer: https://someplayer.test/watch/9"))
            assertTrue("and the cookies:\n$it", it.contains("Cookie: session=abc"))
            assertTrue("and the browser's user agent:\n$it", it.contains("User-Agent: Vox/1.0"))
        }
    }

    @Test
    fun `an offset starts the stream at the piece the viewer asked for`() {
        // This is how seeking works on a renderer that cannot seek in a stream it is being fed:
        // the same stream, started somewhere else.
        val published = relay.publish(source("/hls/master.m3u8"))!!
        val streamed = fetch(relay.urlFor(12))
        assertEquals("two pieces are behind 12 seconds", 188, streamed.size)
        assertEquals(3.toByte(), streamed[0])
        assertTrue(published.url.isNotEmpty())
    }

    @Test
    fun `an encrypted stream is delivered in the clear the television can read`() {
        val published = relay.publish(source("/locked/index.m3u8"))
        assertNotNull(published)
        val streamed = fetch(published!!.url)
        assertEquals(188, streamed.size)
        assertEquals(9.toByte(), streamed[0])
    }

    @Test
    fun `nothing but the stream that was published can be asked for`() {
        val published = relay.publish(source("/hls/master.m3u8"))!!
        val base = published.url.substringBeforeLast('/')
        // A guessed path is not a stream, and no part of a request names an address to fetch.
        assertEquals(0, fetch("$base/somebodyelsestoken").size)
        assertTrue(headersOf("$base/somebodyelsestoken").startsWith("HTTP/1.1 404"))
    }

    @Test
    fun `the stream stops existing when the session does`() {
        val published = relay.publish(source("/hls/master.m3u8"))!!
        relay.stop()
        // The socket is gone, so this cannot even be connected to.
        val refused = runCatching { fetch(published.url) }.isFailure
        assertTrue("no listening socket outlives the cast session", refused)
    }

    @Test
    fun `something that is neither a playlist nor media publishes nothing`() {
        // An address that leads nowhere is not a stream, and neither is an error page. Relaying
        // one would put a television in front of a spinner with no explanation.
        assertNull(relay.publish(source("/nothing-here")))
    }

    @Test
    fun `only the local network may be answered`() {
        assertTrue(StreamRelay.isLocal(InetAddress.getByName("192.168.1.40")))
        assertTrue(StreamRelay.isLocal(InetAddress.getByName("10.0.0.5")))
        assertTrue(!StreamRelay.isLocal(InetAddress.getByName("203.0.113.9")))
    }

    @Test
    fun `a file the site will not hand a television is forwarded byte for byte`() {
        // Half the video hosts people use serve a perfectly ordinary MP4 and refuse anything
        // that is not the page. The phone is the page, so it fetches and forwards — and because
        // the bytes are untouched, the renderer keeps its own seeking.
        val published = relay.publish(source("/hls/1080/seg-0.ts"))
        assertNotNull(published)
        assertTrue("the renderer can still seek in a file", published!!.seekableByReceiver)
        assertEquals("video/mp2t", published.contentType)
        assertEquals(188, fetch(published.url).size)
    }

    @Test
    fun `a range asked for by the television is asked of the site`() {
        val published = relay.publish(source("/hls/1080/seg-0.ts"))!!
        val address = java.net.URL(published.url)
        Socket(address.host, address.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(
                (
                    "GET ${address.file} HTTP/1.1\r\nHost: ${address.host}\r\n" +
                        "Range: bytes=10-20\r\n\r\n"
                    ).toByteArray(),
            )
            String(socket.getInputStream().readBytes())
        }
        val forwarded = synchronized(seen) { seen.last { it.contains("seg-0.ts") } }
        assertTrue("the range must reach the origin:\n$forwarded", forwarded.contains("Range: bytes=10-20"))
    }
}
