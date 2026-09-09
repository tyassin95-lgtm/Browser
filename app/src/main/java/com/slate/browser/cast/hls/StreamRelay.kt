package com.slate.browser.cast.hls

import android.util.Base64
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The phone, standing between a streaming site and a television that cannot talk to it.
 *
 * Nearly every video host serves HLS: a playlist of short pieces, fetched with the page's own
 * cookies and referrer, often from a server that refuses anything else. A DLNA television plays
 * *files* — hand it a playlist and it refuses, which is the message this exists to stop
 * producing. There is no version of that problem the television can solve.
 *
 * So the phone solves it. It is already the client the site trusts, so it fetches the pieces
 * itself, with the browser's own headers, and serves them to the television as one continuous
 * stream over the local network. Nothing is stored, nothing is transcoded and nothing is
 * re-uploaded anywhere: the pieces are passed through as they arrive, which is why the quality
 * is exactly what the site served and the phone's battery is not spent on a video encoder.
 *
 * The security shape matters as much as the plumbing. The server exists only while a cast
 * session does, answers only an unguessable path, refuses anything that is not on the local
 * network, and can be asked for exactly one thing: the stream the user chose to cast. It is not
 * a proxy for arbitrary addresses, and no part of a request decides what it fetches.
 */
class StreamRelay(
    /**
     * How the phone names itself to the receiver. Injectable only so the whole relay can be
     * exercised over a loopback socket in a test; in the browser it is always the Wi-Fi address,
     * because that is the one a television can reach.
     */
    private val hostAddress: () -> String? = { localAddress() },
) {

    /** What the phone will fetch, and as whom. Headers come from the tab doing the playing. */
    data class Source(
        val playlistUrl: String,
        val referer: String,
        val cookies: String,
        val userAgent: String,
    )

    /** What the television will be given. */
    data class Published(
        val url: String,
        val contentType: String,
        val durationMs: Long,
        val isLive: Boolean,
        /**
         * Whether the receiver can seek in this by itself.
         *
         * A file passed through byte for byte keeps its ranges, so the renderer seeks the way it
         * always does. A reassembled stream has no length and no ranges, and seeking in it means
         * starting it again from somewhere else.
         */
        val seekableByReceiver: Boolean,
    )

    /** What kind of thing is being relayed, which decides how a request is answered. */
    private enum class Mode { PIECES, FILE }

    private var mode = Mode.PIECES
    private var server: ServerSocket? = null
    private var token: String = ""
    private var source: Source? = null
    private var address: String = ""

    @Volatile private var streaming = false

    /**
     * Prepares a stream and returns the address to hand the receiver, or null when this is not
     * something the phone can reassemble.
     */
    @Synchronized
    fun publish(source: Source): Published? {
        val fetched = fetchText(source, source.playlistUrl)
        // Not a playlist: an ordinary file, which is relayed byte for byte. Worth doing because
        // a great many hosts serve a perfectly ordinary MP4 that they will hand to the page and
        // to nothing else — and the phone is the page.
        if (fetched == null || !fetched.startsWith("#EXTM3U")) {
            return publishFile(source)
        }

        val mediaUrl = if (HlsPlaylist.isMaster(fetched)) {
            HlsPlaylist.variants(fetched, source.playlistUrl).firstOrNull()?.url ?: return null
        } else {
            source.playlistUrl
        }
        val mediaText = if (mediaUrl == source.playlistUrl) fetched else fetchText(source, mediaUrl)
        val media = HlsPlaylist.media(mediaText ?: return null, mediaUrl)
        if (media.segments.isEmpty()) return null

        val address = listen() ?: return null
        this.source = source.copy(playlistUrl = mediaUrl)
        mode = Mode.PIECES

        // A fragmented-MP4 stream needs its initialisation section before anything else and is
        // then an MP4; a stream of transport-stream pieces is an MPEG-TS. Both are containers a
        // television plays from a USB stick, which is the bar being cleared here.
        return Published(
            url = "$address/stream/$token",
            contentType = if (media.initUrl != null) "video/mp4" else "video/mp2t",
            durationMs = if (media.isLive) 0 else media.durationMs,
            isLive = media.isLive,
            seekableByReceiver = false,
        )
    }

    /** An ordinary file, passed through with its ranges intact so the renderer can still seek. */
    private fun publishFile(source: Source): Published? {
        val head = open(source, source.playlistUrl) ?: return null
        val type = try {
            if (head.responseCode !in 200..299) return null
            head.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        } finally {
            head.disconnect()
        }
        if (!type.startsWith("video/") && !type.startsWith("audio/") &&
            !type.contains("octet-stream") && type.isNotEmpty()
        ) {
            return null
        }
        val address = listen() ?: return null
        this.source = source
        mode = Mode.FILE
        return Published(
            url = "$address/stream/$token",
            contentType = type.ifEmpty { "video/mp4" },
            durationMs = 0,
            isLive = false,
            seekableByReceiver = true,
        )
    }

    /** Opens the door, once per session, and names the address the receiver should knock on. */
    private fun listen(): String? {
        val host = hostAddress() ?: return null
        val socket = server ?: runCatching { ServerSocket(0) }.getOrNull() ?: return null
        if (server == null) {
            server = socket
            accept(socket)
        }
        token = newToken()
        address = "http://$host:${socket.localPort}"
        return address
    }

    /** The address to give the receiver for a restart at [seconds], which is how seeking works. */
    fun urlFor(seconds: Long): String =
        if (seconds <= 0) "$address/stream/$token" else "$address/stream/$token?at=$seconds"

    @Synchronized
    fun stop() {
        streaming = false
        runCatching { server?.close() }
        server = null
        source = null
        token = ""
    }

    // ---- Serving ------------------------------------------------------------

    private fun accept(socket: ServerSocket) {
        thread(isDaemon = true, name = "slate-relay") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(client) }; runCatching { client.close() } }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val head = readRequestHead(client) ?: return
        val request = head.first
        val output = client.getOutputStream()

        // Nothing outside the room. The receiver is a device on the same network, and a relay
        // that answers anything else is an open proxy for the browser's session.
        if (!isLocal(client.inetAddress)) {
            output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }

        val path = request.split(' ').getOrNull(1).orEmpty()
        val requested = path.substringBefore('?').removePrefix("/stream/")
        val current = token
        // Compared in constant time and against the live token only: the path names the one
        // stream the user chose, and cannot describe any other address.
        if (current.isEmpty() || !constantTimeEquals(requested, current)) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val startMs = path.substringAfter("?at=", "").takeWhile { it.isDigit() }.toLongOrNull()?.times(1000) ?: 0
        val headOnly = request.startsWith("HEAD ")
        val feed = source ?: return

        if (mode == Mode.FILE) {
            streaming = true
            runCatching { passThrough(feed, head.second["range"], headOnly, output) }
            output.flush()
            return
        }

        val playlistText = fetchText(feed, feed.playlistUrl) ?: return
        val media = HlsPlaylist.media(playlistText, feed.playlistUrl)
        val contentType = if (media.initUrl != null) "video/mp4" else "video/mp2t"

        // No length and no ranges: this is a live pipe the phone is filling as it goes, and
        // promising a size it does not know is how a renderer stalls waiting for bytes that
        // were never coming. Seeking is done by starting a new stream at an offset instead,
        // which every renderer supports because it is just another URL.
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Accept-Ranges: none\r\n" +
                    "transferMode.dlna.org: Streaming\r\n" +
                    "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;" +
                    "DLNA.ORG_FLAGS=8D500000000000000000000000000000\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        if (headOnly) return

        streaming = true
        runCatching { pump(feed, media, startMs, output) }
        output.flush()
    }

    /**
     * Feeds the pieces out in order, for as long as the television is listening.
     *
     * A live stream has no end: when the list runs out the playlist is fetched again and
     * anything new is appended, which is exactly what the phone's own player does. A recording
     * simply ends, and the closed connection is what tells the renderer the film is over.
     */
    private fun pump(feed: Source, first: HlsPlaylist.Media, startMs: Long, output: OutputStream) {
        var media = first
        first.initUrl?.let { init -> fetchSegment(feed, init)?.let { output.write(it) } }

        var elapsed = 0L
        var next = media.segments.first().sequence
        media.segments.forEach { segment ->
            if (elapsed + segment.durationMs > startMs) return@forEach
            elapsed += segment.durationMs
            next = segment.sequence + 1
        }
        // A live playlist is a sliding window, and its oldest piece is a minute behind what is
        // happening. Starting near the end is where a viewer expects a live stream to begin.
        if (media.isLive && startMs <= 0) {
            next = (media.segments.last().sequence - LIVE_EDGE_SEGMENTS).coerceAtLeast(next)
        }

        var idle = 0
        while (streaming) {
            val pending = media.segments.filter { it.sequence >= next }
            if (pending.isEmpty()) {
                if (!media.isLive) return
                // A live playlist grows; waiting a target duration for it to is what a player
                // does, and giving up after a minute of nothing is what stops a dead stream
                // holding a socket open for ever.
                if (++idle > MAX_IDLE_ROUNDS) return
                Thread.sleep(media.targetDurationMs.coerceIn(1_000, 10_000))
                media = HlsPlaylist.media(fetchText(feed, feed.playlistUrl) ?: return, feed.playlistUrl)
                continue
            }
            idle = 0
            pending.forEach { segment ->
                if (!streaming) return
                val bytes = fetchSegment(feed, segment.url) ?: return@forEach
                val plain = decryptIfNeeded(feed, segment, bytes) ?: return@forEach
                output.write(plain)
                output.flush()
                next = segment.sequence + 1
            }
            if (!media.isLive && media.segments.none { it.sequence >= next }) return
        }
    }

    // ---- Fetching, as the browser ------------------------------------------

    /**
     * Every fetch carries the tab's own referrer and cookies.
     *
     * This is the part that makes the relay work at all where a plain address does not: the
     * site is answering the client it already trusts, on the connection it already trusts,
     * rather than a television it has never seen. Nothing is shared outwards — the cookies stay
     * on the phone, and the receiver is given a local address with no credentials in it.
     */
    private fun open(feed: Source, url: String): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            if (feed.referer.isNotBlank()) setRequestProperty("Referer", feed.referer)
            if (feed.cookies.isNotBlank()) setRequestProperty("Cookie", feed.cookies)
            if (feed.userAgent.isNotBlank()) setRequestProperty("User-Agent", feed.userAgent)
            setRequestProperty("Accept", "*/*")
        }
    }.getOrNull()

    private fun fetchText(feed: Source, url: String): String? = runCatching {
        val connection = open(feed, url) ?: return null
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.readBounded(MAX_PLAYLIST_BYTES)?.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun fetchSegment(feed: Source, url: String): ByteArray? = runCatching {
        val connection = open(feed, url) ?: return null
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.readBounded(MAX_SEGMENT_BYTES)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * HLS's own AES-128, which is transport encryption rather than a licence: the key is served
     * to whoever may play the stream, and the phone may. Anything that needs a licence is
     * refused before casting is offered at all.
     */
    private fun decryptIfNeeded(feed: Source, segment: HlsPlaylist.Segment, bytes: ByteArray): ByteArray? {
        val keyUrl = segment.keyUrl ?: return bytes
        val key = keys.getOrPut(keyUrl) { fetchSegment(feed, keyUrl) ?: ByteArray(0) }
        if (key.size != 16) return null
        val iv = ivFor(segment)
        return runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(bytes)
            }
        }.getOrNull()
    }

    private val keys = mutableMapOf<String, ByteArray>()

    /** Either the playlist says, or it is the segment's own number, as the format specifies. */
    private fun ivFor(segment: HlsPlaylist.Segment): ByteArray {
        val stated = segment.iv?.removePrefix("0x")?.removePrefix("0X")
        if (stated != null && stated.length == 32) {
            return runCatching {
                ByteArray(16) { stated.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }.getOrDefault(sequenceIv(segment.sequence))
        }
        return sequenceIv(segment.sequence)
    }

    private fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also {
        for (byte in 0 until 8) it[15 - byte] = (sequence shr (8 * byte)).toByte()
    }

    // ---- Plumbing -----------------------------------------------------------

    /**
     * The request line and its headers, read defensively.
     *
     * Bounded in both directions — how long a line may be and how many there may be — because
     * this socket is open to the local network, and a device on the local network is not a
     * trusted peer just because it is in the same room.
     */
    private fun readRequestHead(client: Socket): Pair<String, Map<String, String>>? = runCatching {
        val stream = client.getInputStream()
        val lines = mutableListOf<String>()
        while (lines.size <= MAX_REQUEST_LINES) {
            val line = StringBuilder()
            while (line.length < MAX_REQUEST_CHARS) {
                val next = stream.read()
                if (next < 0) break
                if (next == '\n'.code) break
                if (next != '\r'.code) line.append(next.toChar())
            }
            if (line.isEmpty()) break
            lines += line.toString()
        }
        val request = lines.firstOrNull() ?: return null
        val headers = lines.drop(1).mapNotNull {
            val name = it.substringBefore(':', "").trim().lowercase()
            if (name.isEmpty()) null else name to it.substringAfter(':').trim()
        }.toMap()
        request to headers
    }.getOrNull()

    /**
     * An ordinary file, forwarded byte for byte with its ranges intact.
     *
     * The renderer keeps every ability it would have had fetching the file itself — seeking
     * included — and the only thing that changes is who asks the site for it. That is the whole
     * point: the site answers the phone, which it already trusts, and refuses the television,
     * which it has never seen.
     */
    private fun passThrough(feed: Source, range: String?, headOnly: Boolean, output: OutputStream) {
        val connection = open(feed, feed.playlistUrl) ?: return
        if (!range.isNullOrBlank()) connection.setRequestProperty("Range", range)
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val type = connection.contentType?.substringBefore(';')?.trim() ?: "video/mp4"
            val length = connection.getHeaderField("Content-Length")
            val contentRange = connection.getHeaderField("Content-Range")
            output.write(
                buildString {
                    append(if (status == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $type\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (length != null) append("Content-Length: $length\r\n")
                    if (contentRange != null) append("Content-Range: $contentRange\r\n")
                    append("transferMode.dlna.org: Streaming\r\n")
                    append(
                        "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;" +
                            "DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n",
                    )
                    append("Connection: close\r\n\r\n")
                }.toByteArray(),
            )
            if (headOnly) return
            val buffer = ByteArray(64 * 1024)
            val input = connection.inputStream
            while (streaming) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        /** The address the receiver can reach this phone at, which is the Wi-Fi one. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it.address.size == 4 && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        /** A receiver is on the same network. Anything else has no business here. */
        internal fun isLocal(address: InetAddress?): Boolean =
            address != null && (address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress)

        internal fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var difference = 0
            for (index in a.indices) difference = difference or (a[index].code xor b[index].code)
            return difference == 0
        }

        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
        private const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024
        private const val MAX_REQUEST_CHARS = 2048
        private const val MAX_REQUEST_LINES = 40
        private const val MAX_IDLE_ROUNDS = 10
        private const val LIVE_EDGE_SEGMENTS = 3
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}
