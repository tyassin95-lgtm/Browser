package com.slate.browser

import com.slate.browser.cast.MediaManifests
import com.slate.browser.cast.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which of a page's requests is the stream it is playing.
 *
 * This runs on WebView's network threads for every request a page makes, so it has to be cheap
 * and it has to be sure: a false positive here is the wrong thing appearing on somebody's
 * television.
 */
@RunWith(RobolectricTestRunner::class)
class MediaManifestsTest {

    @Test
    fun `playlists are recognised, with their format`() {
        assertEquals(StreamFormat.HLS, MediaManifests.formatOf("https://cdn.test/live/master.m3u8"))
        assertEquals(StreamFormat.HLS, MediaManifests.formatOf("https://cdn.test/x.m3u"))
        assertEquals(StreamFormat.DASH, MediaManifests.formatOf("https://cdn.test/v/manifest.mpd"))
    }

    @Test
    fun `a query full of tokens does not hide the playlist, or invent one`() {
        // Signed URLs are the norm for streaming, and the token is what will let the receiver
        // fetch it at all.
        assertEquals(
            StreamFormat.HLS,
            MediaManifests.formatOf("https://cdn.test/live/master.m3u8?token=abc&expires=99"),
        )
        // And a token that merely contains ".mpd" is not a manifest.
        assertNull(MediaManifests.formatOf("https://cdn.test/api/play?file=video.mpd.enc"))
        assertNull(MediaManifests.formatOf("https://cdn.test/track?ref=x.m3u8"))
    }

    @Test
    fun `segments and everything else are not manifests`() {
        // A live stream requests these hundreds of times; casting one would send two seconds
        // of video and stop.
        listOf(
            "https://cdn.test/live/seg-00042.ts",
            "https://cdn.test/live/chunk-1.m4s",
            "https://cdn.test/movie.mp4",
            "https://cdn.test/player.js",
            "https://cdn.test/style.css",
            "https://cdn.test/poster.jpg",
            "https://cdn.test/",
        ).forEach { assertFalse(it, MediaManifests.isManifest(it)) }
    }

    @Test
    fun `only the web is watched`() {
        assertFalse(MediaManifests.isManifest("blob:https://cdn.test/9a7c"))
        assertFalse(MediaManifests.isManifest("data:application/x-mpegurl,#EXTM3U"))
        assertFalse(MediaManifests.isManifest("file:///sdcard/master.m3u8"))
        assertFalse(MediaManifests.isManifest(""))
    }

    @Test
    fun `an absurd address is refused rather than parsed`() {
        assertTrue(MediaManifests.formatOf("https://cdn.test/" + "a".repeat(9000) + ".m3u8") == null)
    }

    @Test
    fun `an extensionless manifest endpoint is recognised from its query`() {
        // Several large sites serve the playlist from a path with no extension at all, and say
        // which format it is only in the query.
        assertEquals(
            StreamFormat.HLS,
            MediaManifests.formatOf("https://v.test/api/manifest?format=m3u8&id=9"),
        )
        assertEquals(
            StreamFormat.DASH,
            MediaManifests.formatOf("https://v.test/video/playlist?type=dash"),
        )
        // But an endpoint that says nothing about a stream is not one.
        assertNull(MediaManifests.formatOf("https://v.test/api/manifest?id=9"))
    }

    @Test
    fun `a whole media file is offered to the receivers that only play files`() {
        listOf(
            "https://cdn.test/videos/big-buck-bunny.mp4",
            "https://cdn.test/clip.webm",
            "https://cdn.test/talk.m4a?token=abc",
        ).forEach {
            assertTrue(it, MediaManifests.isPlainMediaFile(it))
        }
    }

    @Test
    fun `a segment of a stream is never mistaken for the whole video`() {
        // Four seconds of video on a television is worse than an honest refusal.
        listOf(
            "https://cdn.test/live/seg-0001.mp4",
            "https://cdn.test/live/chunk.mp4",
            "https://cdn.test/live/init.mp4",
            "https://cdn.test/live/video_12.mp4",
            "https://cdn.test/live/0004.mp4",
            "https://cdn.test/live/fragment-9.m4a",
        ).forEach {
            assertFalse(it, MediaManifests.isPlainMediaFile(it))
        }
    }

    @Test
    fun `an ordinary noun in a filename is not evidence of a segment`() {
        // "video.mp4" is what half the internet calls a whole film.
        assertTrue(MediaManifests.isPlainMediaFile("https://cdn.test/video.mp4"))
        assertTrue(MediaManifests.isPlainMediaFile("https://cdn.test/audio.mp3"))
        assertTrue(MediaManifests.isPlainMediaFile("https://cdn.test/part-two-trailer.mp4"))
    }

    @Test
    fun `things that are not media files are not offered as one`() {
        listOf(
            "https://cdn.test/app.js",
            "https://cdn.test/poster.jpg",
            "https://cdn.test/live/seg.ts",
            "blob:https://cdn.test/1234",
        ).forEach {
            assertFalse(it, MediaManifests.isPlainMediaFile(it))
        }
    }
}
