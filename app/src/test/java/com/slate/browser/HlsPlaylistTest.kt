package com.slate.browser

import com.slate.browser.cast.hls.HlsPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the playlists real video hosts serve.
 *
 * Every one of these shapes comes from a stream in the wild: a master listing four qualities, a
 * recording that ends, a live window that slides, relative addresses, a key, and attribute
 * values with commas inside the quotes. Getting any of them wrong produces a television playing
 * the wrong thing, or nothing.
 */
class HlsPlaylistTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
        360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160,CODECS="hvc1.2.4.L150,mp4a.40.2"
        2160/index.m3u8
    """.trimIndent()

    @Test
    fun `a master playlist is recognised and its qualities read`() {
        assertTrue(HlsPlaylist.isMaster(master))
        val variants = HlsPlaylist.variants(master, "https://cdn.test/hls/master.m3u8")
        assertEquals(3, variants.size)
        // Best playable first: 1080p H.264 beats 4K HEVC, because a television that cannot
        // decode what it is given shows nothing at all.
        assertEquals("https://cdn.test/hls/1080/index.m3u8", variants.first().url)
        assertEquals(1080, variants.first().height)
    }

    @Test
    fun `a codec list with commas in it does not split the attributes`() {
        val attributes = HlsPlaylist.attributesOf(
            """BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",FRAME-RATE=25""",
        )
        assertEquals("3000000", attributes["BANDWIDTH"])
        assertEquals("avc1.640028,mp4a.40.2", attributes["CODECS"])
        assertEquals("25", attributes["FRAME-RATE"])
    }

    @Test
    fun `a recording is read as a finite list of pieces`() {
        val media = HlsPlaylist.media(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6.0,
            seg-0.ts
            #EXTINF:6.0,
            seg-1.ts
            #EXTINF:4.5,
            seg-2.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.test/hls/1080/index.m3u8",
        )
        assertFalse("an ENDLIST means the film ends", media.isLive)
        assertEquals(3, media.segments.size)
        assertEquals("https://cdn.test/hls/1080/seg-1.ts", media.segments[1].url)
        assertEquals(16_500L, media.durationMs)
        assertEquals(2L, media.segments[2].sequence)
    }

    @Test
    fun `a live window has no end and starts where it says it does`() {
        val media = HlsPlaylist.media(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:1200
            #EXTINF:4.0,
            /live/1200.ts
            #EXTINF:4.0,
            /live/1201.ts
            """.trimIndent(),
            "https://cdn.test/hls/live.m3u8",
        )
        assertTrue(media.isLive)
        assertEquals(1200L, media.segments.first().sequence)
        // A root-relative address resolves against the host, not the playlist's folder.
        assertEquals("https://cdn.test/live/1200.ts", media.segments.first().url)
        assertEquals(4_000L, media.targetDurationMs)
    }

    @Test
    fun `an encrypted stream carries its key, and a licensed one does not`() {
        val encrypted = HlsPlaylist.media(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x0123456789ABCDEF0123456789ABCDEF
            #EXTINF:6.0,
            seg-0.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.test/hls/index.m3u8",
        )
        assertEquals("https://cdn.test/hls/key.bin", encrypted.segments.first().keyUrl)

        // SAMPLE-AES with a licence server is DRM, and nothing here pretends otherwise.
        val licensed = HlsPlaylist.media(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://licence",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:6.0,
            seg-0.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.test/hls/index.m3u8",
        )
        assertNull(licensed.segments.first().keyUrl)
    }

    @Test
    fun `a fragmented stream keeps its initialisation section`() {
        val media = HlsPlaylist.media(
            """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg-0.m4s
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.test/hls/index.m3u8",
        )
        assertEquals("https://cdn.test/hls/init.mp4", media.initUrl)
    }

    @Test
    fun `rubbish is a playlist with nothing in it rather than a crash`() {
        val media = HlsPlaylist.media("not a playlist at all", "https://cdn.test/x.m3u8")
        assertTrue(media.segments.isEmpty())
        assertTrue(HlsPlaylist.variants("", "https://cdn.test/x.m3u8").isEmpty())
    }
}
