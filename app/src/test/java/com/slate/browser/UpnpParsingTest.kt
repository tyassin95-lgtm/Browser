package com.slate.browser

import com.slate.browser.cast.dlna.UpnpParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading what a renderer on the network says about itself.
 *
 * Everything here arrives from an unauthenticated device in the same room, in whatever shape
 * its manufacturer felt like. The samples are the shapes real devices send — namespaced tags,
 * relative control URLs, services listed in either order — rather than the tidy ones the
 * specification implies.
 */
@RunWith(RobolectricTestRunner::class)
class UpnpParsingTest {

    @Test
    fun `the description address is taken from the reply, whatever case it is written in`() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n" +
            "Location: http://192.168.1.40:9197/dmr\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
        assertEquals("http://192.168.1.40:9197/dmr", UpnpParsing.locationOf(reply))
    }

    @Test
    fun `a reply pointing anywhere but the network is refused`() {
        assertNull(UpnpParsing.locationOf("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd\r\n"))
        assertNull(UpnpParsing.locationOf("HTTP/1.1 200 OK\r\nLOCATION: \r\n"))
        assertNull(UpnpParsing.locationOf("not an ssdp reply at all"))
    }

    @Test
    fun `a television's description yields a name and a control address`() {
        // The shape a Samsung set sends: relative control URLs, several services, the renderer
        // one not first.
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>[TV] Living Room</friendlyName>
                <UDN>uuid:0a1b2c3d-4e5f-6789-abcd-ef0123456789</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                    <controlURL>/upnp/control/ConnectionManager1</controlURL>
                  </service>
                  <service>
                    <controlURL>/upnp/control/AVTransport1</controlURL>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/upnp/control/RenderingControl1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val renderer = UpnpParsing.renderer(xml, "http://192.168.1.40:9197/dmr")
        assertTrue("a media renderer must be recognised", renderer != null)
        assertEquals("[TV] Living Room", renderer!!.name)
        assertEquals("uuid:0a1b2c3d-4e5f-6789-abcd-ef0123456789", renderer.udn)
        // Relative addresses resolved against where the description came from.
        assertEquals("http://192.168.1.40:9197/upnp/control/AVTransport1", renderer.avTransportUrl)
        assertEquals(
            "http://192.168.1.40:9197/upnp/control/RenderingControl1",
            renderer.renderingControlUrl,
        )
    }

    @Test
    fun `a device that cannot play anything is not offered as somewhere to cast`() {
        // Routers, printers and light bulbs all answer an SSDP search.
        val xml = """
            <root><device>
              <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
              <friendlyName>Home Router</friendlyName>
              <serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                <controlURL>/ctl/IPConn</controlURL>
              </service></serviceList>
            </device></root>
        """.trimIndent()
        assertNull(UpnpParsing.renderer(xml, "http://192.168.1.1:5000/desc.xml"))
    }

    @Test
    fun `an absolute control address is left alone, and rubbish does not throw`() {
        val xml = """
            <root><device><friendlyName>Speaker</friendlyName>
              <serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>http://192.168.1.55:1400/MediaRenderer/AVTransport/Control</controlURL>
              </service></serviceList>
            </device></root>
        """.trimIndent()
        assertEquals(
            "http://192.168.1.55:1400/MediaRenderer/AVTransport/Control",
            UpnpParsing.renderer(xml, "http://192.168.1.55:1400/xml/device_description.xml")?.avTransportUrl,
        )
        assertNull(UpnpParsing.renderer("<not xml", "http://192.168.1.1/d.xml"))
        assertNull(UpnpParsing.renderer("", "http://192.168.1.1/d.xml"))
    }

    @Test
    fun `positions are read out of a reply and written back in the same shape`() {
        val reply = """
            <s:Envelope><s:Body><u:GetPositionInfoResponse>
              <Track>1</Track>
              <TrackDuration>1:02:03</TrackDuration>
              <RelTime>0:00:42</RelTime>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>
        """.trimIndent()
        assertEquals(42_000L, UpnpParsing.parseClock(UpnpParsing.soapValue(reply, "RelTime")))
        assertEquals(3_723_000L, UpnpParsing.parseClock(UpnpParsing.soapValue(reply, "TrackDuration")))
        assertEquals("0:00:42", UpnpParsing.formatClock(42_000))
        assertEquals("1:02:03", UpnpParsing.formatClock(3_723_000))
    }

    @Test
    fun `the times renderers send when they do not know are read as zero`() {
        // All of these are what real devices send for a live stream.
        listOf("NOT_IMPLEMENTED", "", "   ", "0:00:00", "garbage", "9").forEach {
            assertEquals(it, 0L, UpnpParsing.parseClock(it))
        }
        assertEquals(0L, UpnpParsing.parseClock(null))
        // Fractions are tolerated; several devices append them.
        assertEquals(42_000L, UpnpParsing.parseClock("0:00:42.500"))
    }

    @Test
    fun `metadata quotes everything a title or address could contain`() {
        val didl = UpnpParsing.didl(
            title = "Cats & Dogs <the sequel>",
            url = "https://cdn.test/v.m3u8?a=1&b=2",
            contentType = "application/x-mpegurl",
        )
        assertTrue(didl, didl.contains("Cats &amp; Dogs &lt;the sequel&gt;"))
        assertTrue(didl, didl.contains("a=1&amp;b=2"))
        // The class tells the renderer what it is being handed.
        assertTrue(didl, didl.contains("object.item.videoItem"))
        assertTrue(
            UpnpParsing.didl("A show", "https://cdn.test/a.mp3", "audio/mpeg")
                .contains("object.item.audioItem.musicTrack"),
        )
    }
}
