package com.slate.browser

import com.slate.browser.cast.dlna.MediaTypes
import com.slate.browser.cast.dlna.UpnpParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calling a stream what the television calls it.
 *
 * "File format not supported" is almost never a set that cannot decode a video — it is a set
 * that was handed a media type missing from its list and refused before decoding anything. The
 * same bytes under a name it knows play perfectly. Every renderer publishes that list; these are
 * the tests for reading it and using it.
 */
class MediaTypesTest {

    /** What a Samsung set answers GetProtocolInfo with, trimmed to the shape that matters. */
    private val samsung = UpnpParsing.sinkTypes(
        "<u:GetProtocolInfoResponse><Source></Source><Sink>" +
            "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_EU_ISO," +
            "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD_1080i_AAC," +
            "http-get:*:video/x-matroska:*," +
            "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3" +
            "</Sink></u:GetProtocolInfoResponse>",
    )

    @Test
    fun `the list a renderer publishes is read as the types it accepts`() {
        assertEquals(
            setOf("video/mpeg", "video/mp4", "video/x-matroska", "audio/mpeg"),
            samsung,
        )
    }

    @Test
    fun `a transport stream is offered under the name this set knows`() {
        // The exact case in the screenshot: the browser was saying video/mp2t at a television
        // whose list says video/mpeg. Identical bytes, one refusal.
        assertEquals("video/mpeg", MediaTypes.nameFor("video/mp2t", samsung))
    }

    @Test
    fun `a type the set already lists is left alone`() {
        assertEquals("video/mp4", MediaTypes.nameFor("video/mp4", samsung))
        assertEquals("audio/mpeg", MediaTypes.nameFor("audio/mpeg", samsung))
    }

    @Test
    fun `a container with no name on the list is refused rather than guessed at`() {
        val fussy = setOf("video/mp4")
        assertNull("nothing here is a transport stream", MediaTypes.nameFor("video/mp2t", fussy))
        assertNull(MediaTypes.nameFor("video/webm", fussy))
    }

    @Test
    fun `a device that will not say what it plays is allowed to decide for itself`() {
        // Refusing on a silent device's behalf would be worse than letting it try, and plenty
        // of renderers answer this question with nothing at all.
        assertEquals("video/mp2t", MediaTypes.nameFor("video/mp2t", emptySet()))
    }

    @Test
    fun `a wildcard covers its family`() {
        assertEquals("video/mp2t", MediaTypes.nameFor("video/mp2t", setOf("video/*")))
        assertEquals("video/webm", MediaTypes.nameFor("video/webm", setOf("*/*")))
    }

    @Test
    fun `a refusal can name what the set does play`() {
        val described = MediaTypes.describe(samsung)
        assertTrue(described, described.contains("mpeg"))
        assertTrue(described, described.contains("mp4"))
        assertTrue("audio types are not the answer to a video question", !described.contains("audio"))
    }

    @Test
    fun `a device that answers nothing is not described as playing nothing`() {
        assertEquals("", MediaTypes.describe(emptySet()))
    }
}
