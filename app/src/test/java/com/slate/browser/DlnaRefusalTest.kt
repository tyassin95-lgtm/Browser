package com.slate.browser

import androidx.test.core.app.ApplicationProvider
import com.slate.browser.cast.CastSource
import com.slate.browser.cast.DlnaController
import com.slate.browser.cast.StreamFormat
import com.slate.browser.cast.dlna.SoapResult
import com.slate.browser.cast.dlna.UpnpFault
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a television's "no" is turned into.
 *
 * This is the sentence a user is left holding when casting does not work, so it has to say
 * something they can act on. "Samsung TV wouldn't accept this video" is true and useless: it
 * does not say that DLNA televisions are file players, that almost every video site serves an
 * adaptive stream instead, or that mirroring shows it anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DlnaRefusalTest {

    private val controller = DlnaController(ApplicationProvider.getApplicationContext())

    private fun source(format: StreamFormat) = CastSource(
        url = "http://cdn.test/x",
        format = format,
        contentType = format.contentType,
    )

    @Test
    fun `an adaptive stream refused by a file player explains itself and offers mirroring`() {
        val message = controller.refusalMessage(
            "Samsung TV",
            source(StreamFormat.HLS),
            SoapResult.Refused(UpnpFault(714, "Illegal MIME-type")),
        )
        assertTrue(message, message.contains("Samsung TV"))
        assertTrue(message, message.contains("files"))
        assertTrue(message, message.contains("mirroring", ignoreCase = true))
    }

    @Test
    fun `a file the television cannot decode is a different sentence`() {
        // Here the browser sent exactly what a DLNA renderer is built for, and it still said no,
        // so the honest answer is about the format rather than about the protocol.
        val message = controller.refusalMessage(
            "Samsung TV",
            source(StreamFormat.PROGRESSIVE),
            SoapResult.Refused(UpnpFault(714, "Illegal MIME-type")),
        )
        assertTrue(message, message.contains("format"))
        assertTrue(message, !message.contains("mirroring", ignoreCase = true))
    }

    @Test
    fun `an unusual error keeps its number, because nothing else identifies it`() {
        val message = controller.refusalMessage(
            "Samsung TV",
            source(StreamFormat.PROGRESSIVE),
            SoapResult.Refused(UpnpFault(718, "Invalid InstanceID")),
        )
        assertTrue(message, message.contains("718"))
    }

    @Test
    fun `a television that has stopped answering is not a television that refused`() {
        val message = controller.refusalMessage(
            "Samsung TV",
            source(StreamFormat.PROGRESSIVE),
            SoapResult.Unreachable,
        )
        assertTrue(message, message.contains("stopped answering"))
    }
}
