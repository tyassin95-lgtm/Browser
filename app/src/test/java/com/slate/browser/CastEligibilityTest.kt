package com.slate.browser

import com.slate.browser.cast.CastEligibility
import com.slate.browser.cast.CastVerdict
import com.slate.browser.cast.StreamFormat
import com.slate.browser.web.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What can be sent to a television, and what has to be explained instead.
 *
 * A Cast receiver fetches the media itself, over its own connection, with none of this
 * browser's cookies or origin. Most of these cases are that one fact having consequences: the
 * phone playing something is not evidence a receiver could.
 */
@RunWith(RobolectricTestRunner::class)
class CastEligibilityTest {

    private fun video(
        src: String = "https://cdn.example.com/movie.mp4",
        live: Boolean = false,
        mse: Boolean = false,
        drm: Boolean = false,
        audioOnly: Boolean = false,
    ) = MediaState(
        hasVideo = !audioOnly,
        audioOnly = audioOnly,
        isPlaying = true,
        width = if (audioOnly) 0 else 1280,
        height = if (audioOnly) 0 else 720,
        isLive = live,
        sourceUrl = src,
        isStreamedInPage = mse,
        isProtected = drm,
        pageTitle = "A page",
    )

    private fun evaluate(state: MediaState, observedManifest: String? = null) =
        CastEligibility.evaluate(state, "https://example.com/watch", observedManifest)

    @Test
    fun `an ordinary progressive video is castable`() {
        val verdict = evaluate(video()) as CastVerdict.Castable
        assertEquals("https://cdn.example.com/movie.mp4", verdict.url)
        assertEquals(StreamFormat.PROGRESSIVE, verdict.format)
        assertEquals("video/mp4", verdict.contentType)
        assertEquals(false, verdict.isLive)
    }

    @Test
    fun `adaptive streams are described to the receiver as what they are`() {
        val hls = evaluate(video(src = "https://cdn.example.com/live/index.m3u8", live = true)) as CastVerdict.Castable
        assertEquals(StreamFormat.HLS, hls.format)
        assertEquals("application/x-mpegurl", hls.contentType)
        assertTrue("a live stream must be sent as live", hls.isLive)

        val dash = evaluate(video(src = "https://cdn.example.com/v/manifest.mpd")) as CastVerdict.Castable
        assertEquals(StreamFormat.DASH, dash.format)
        assertEquals("application/dash+xml", dash.contentType)

        // A manifest behind a query full of tokens is still a manifest.
        val tokened = evaluate(video(src = "https://cdn.example.com/live/index.m3u8?token=abc&e=99")) as CastVerdict.Castable
        assertEquals(StreamFormat.HLS, tokened.format)
    }

    @Test
    fun `a Media Source player is cast by the manifest it is reading`() {
        /*
         * The case that matters, because it is nearly all of them. hls.js, dash.js and Shaka
         * all feed a MediaSource, so the element's source is a blob that exists nowhere else —
         * but the playlist the player is reading is an ordinary address, and a receiver opens
         * HLS and DASH natively. Refusing here, which is what the first version did, meant
         * refusing essentially the whole modern streaming web while being technically right.
         */
        val mse = video(src = "blob:https://example.com/9a7c-…", mse = true, live = true)

        val hls = evaluate(mse, observedManifest = "https://cdn.example.com/live/master.m3u8")
        assertTrue(hls.toString(), hls is CastVerdict.Castable)
        assertEquals("https://cdn.example.com/live/master.m3u8", (hls as CastVerdict.Castable).url)
        assertEquals(StreamFormat.HLS, hls.format)
        assertTrue(hls.isLive)

        val dash = evaluate(mse, observedManifest = "https://cdn.example.com/v/manifest.mpd")
        assertEquals(StreamFormat.DASH, (dash as CastVerdict.Castable).format)
    }

    @Test
    fun `a Media Source player with no manifest seen yet says what would help`() {
        val verdict = evaluate(video(src = "blob:https://example.com/9a7c-…", mse = true))
        assertTrue(verdict is CastVerdict.Refused)
        val reason = (verdict as CastVerdict.Refused).reason
        assertTrue(reason, reason.contains("inside the page"))
        assertTrue("the message must say what to try", reason.contains("Starting playback"))
    }

    @Test
    fun `the element's own address wins over anything seen on the network`() {
        // A plain file needs no guessing, and a manifest from an earlier video on the same page
        // must not override the video actually loaded.
        val verdict = evaluate(
            video(src = "https://cdn.example.com/movie.mp4"),
            observedManifest = "https://cdn.example.com/other/master.m3u8",
        )
        assertEquals("https://cdn.example.com/movie.mp4", (verdict as CastVerdict.Castable).url)
    }

    @Test
    fun `protection is refused whatever address was seen`() {
        // The content is decrypted by the phone as it plays; no URL changes that.
        val verdict = evaluate(
            video(src = "blob:x", mse = true, drm = true),
            observedManifest = "https://cdn.example.com/live/master.m3u8",
        )
        assertTrue(verdict is CastVerdict.Refused)
        assertTrue((verdict as CastVerdict.Refused).reason.contains("copy-protected"))
    }

    @Test
    fun `protected content is refused with the real reason`() {
        val verdict = evaluate(video(drm = true))
        assertTrue(verdict is CastVerdict.Refused)
        assertTrue((verdict as CastVerdict.Refused).reason.contains("copy-protected"))
    }

    @Test
    fun `an address only this phone can reach is refused`() {
        // A receiver is a different machine; loopback here says nothing about there.
        listOf(
            "http://localhost:8080/movie.mp4",
            "http://127.0.0.1/movie.mp4",
            "http://127.10.0.4:9000/movie.mp4",
        ).forEach { src ->
            val verdict = evaluate(video(src = src))
            assertTrue("$src must be refused", verdict is CastVerdict.Refused)
        }
    }

    @Test
    fun `a source the browser could not identify is refused rather than guessed at`() {
        assertTrue(evaluate(video(src = "")) is CastVerdict.Refused)
        assertTrue(evaluate(video(src = "data:video/mp4;base64,AAAA")) is CastVerdict.Refused)
        assertTrue(evaluate(video(src = "file:///sdcard/movie.mp4")) is CastVerdict.Refused)
    }

    @Test
    fun `nothing playing is not a refusal`() {
        // There is a difference between "this cannot be cast" and "there is nothing to cast",
        // and the user is told a different thing in each case.
        assertTrue(evaluate(MediaState.NONE) is CastVerdict.NothingPlaying)
    }

    @Test
    fun `audio is castable too, and described as audio`() {
        val verdict = evaluate(video(src = "https://cdn.example.com/show.mp3", audioOnly = true))
        assertTrue(verdict is CastVerdict.Castable)
        assertEquals("audio/mpeg", (verdict as CastVerdict.Castable).contentType)
    }

    @Test
    fun `a poster the page cannot vouch for is not passed on`() {
        val withBlobPoster = video().copy(posterUrl = "blob:https://example.com/x")
        assertEquals("", (evaluate(withBlobPoster) as CastVerdict.Castable).posterUrl)

        val withRealPoster = video().copy(posterUrl = "https://cdn.example.com/poster.jpg")
        assertEquals(
            "https://cdn.example.com/poster.jpg",
            (evaluate(withRealPoster) as CastVerdict.Castable).posterUrl,
        )
    }

    @Test
    fun `a page with no title still gives the receiver something to show`() {
        val untitled = video().copy(pageTitle = "")
        assertEquals("cdn.example.com", (evaluate(untitled) as CastVerdict.Castable).title)
    }
}
