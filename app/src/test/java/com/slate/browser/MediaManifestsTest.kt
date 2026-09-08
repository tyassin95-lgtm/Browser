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
}
