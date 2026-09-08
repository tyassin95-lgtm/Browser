package com.slate.browser

import androidx.test.core.app.ApplicationProvider
import com.slate.browser.cast.dlna.DlnaClient
import com.slate.browser.cast.dlna.SoapResult
import com.slate.browser.cast.dlna.UpnpParsing
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
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The half of DLNA that talks to a renderer, against a real socket.
 *
 * A television is unforgiving about the envelope: the SOAPAction header has to be quoted and
 * carry the service type, the action has to be namespaced, and a device that dislikes any of it
 * answers 500 and plays nothing. Checking the bytes on the wire is the only way to know.
 */
@RunWith(RobolectricTestRunner::class)
class DlnaClientTest {

    private lateinit var server: ServerSocket
    private var port = 0

    /** What the last request looked like on the wire. */
    @Volatile private var lastRequest: String = ""

    /** What to answer with. */
    @Volatile private var response: String =
        "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 62\r\nConnection: close\r\n\r\n" +
            "<s:Envelope><s:Body><u:PlayResponse/></s:Body></s:Envelope>"

    @Before
    fun setUp() {
        server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                        val builder = StringBuilder()
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            builder.append(line).append('\n')
                            if (line.startsWith("Content-Length:", true)) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        if (contentLength > 0) {
                            val body = CharArray(contentLength)
                            reader.read(body, 0, contentLength)
                            builder.append(body)
                        }
                        lastRequest = builder.toString()
                        it.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun client() = DlnaClient(ApplicationProvider.getApplicationContext())

    private fun controlUrl() = "http://127.0.0.1:$port/upnp/control/AVTransport1"

    @Test
    fun `an action reaches the renderer in the shape it expects`() {
        val reply = client().invoke(
            controlUrl(),
            DlnaClient.AV_TRANSPORT,
            "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>",
        )
        assertNotNull("a 200 must come back as a body", reply)

        val request = lastRequest
        assertTrue(request, request.startsWith("POST /upnp/control/AVTransport1"))
        // Quoted, and carrying the service type: an unquoted header is the classic reason a
        // television answers 500 to a perfectly good action.
        assertTrue(
            request,
            request.contains("SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#Play\""),
        )
        assertTrue(request, request.contains("Content-Type: text/xml"))
        assertTrue(request, request.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(request, request.contains("<InstanceID>0</InstanceID><Speed>1</Speed>"))
        assertTrue(request, request.contains("s:Envelope"))
    }

    @Test
    fun `handing over a stream carries escaped metadata alongside the address`() {
        val url = "https://cdn.test/live/master.m3u8?a=1&b=2"
        client().invoke(
            controlUrl(),
            DlnaClient.AV_TRANSPORT,
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${UpnpParsing.escape(url)}</CurrentURI>" +
                "<CurrentURIMetaData>" +
                UpnpParsing.escape(UpnpParsing.didl("A show", url, "application/x-mpegurl")) +
                "</CurrentURIMetaData>",
        )
        val request = lastRequest
        // The ampersand in the address must survive as an entity, or the envelope is malformed
        // and the renderer rejects the whole thing.
        assertTrue(request, request.contains("master.m3u8?a=1&amp;b=2"))
        assertTrue(request, request.contains("&lt;DIDL-Lite"))
        assertTrue(request, request.contains("SetAVTransportURI"))
    }

    @Test
    fun `a renderer that refuses an action is reported, not retried at`() {
        response = "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/xml\r\n" +
            "Content-Length: 9\r\nConnection: close\r\n\r\n<fault/>"
        assertNull(client().invoke(controlUrl(), DlnaClient.AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID>"))
    }

    @Test
    fun `a device that is not there fails quickly rather than hanging`() {
        val started = System.currentTimeMillis()
        val reply = client().invoke(
            "http://127.0.0.1:1/control",
            DlnaClient.AV_TRANSPORT,
            "Play",
            "<InstanceID>0</InstanceID>",
        )
        assertNull(reply)
        assertTrue("took too long", System.currentTimeMillis() - started < 8_000)
    }

    @Test
    fun `a position reply is read back into milliseconds`() {
        response = "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nConnection: close\r\n\r\n" +
            "<s:Envelope><s:Body><u:GetPositionInfoResponse>" +
            "<TrackDuration>0:45:00</TrackDuration><RelTime>0:01:30</RelTime>" +
            "</u:GetPositionInfoResponse></s:Body></s:Envelope>"
        val reply = client().invoke(
            controlUrl(),
            DlnaClient.AV_TRANSPORT,
            "GetPositionInfo",
            "<InstanceID>0</InstanceID>",
        )
        assertEquals(90_000L, UpnpParsing.parseClock(UpnpParsing.soapValue(reply.orEmpty(), "RelTime")))
        assertEquals(2_700_000L, UpnpParsing.parseClock(UpnpParsing.soapValue(reply.orEmpty(), "TrackDuration")))
    }

    @Test
    fun `a refusal keeps the reason the renderer gave`() {
        // 714 is "Illegal MIME-type": the television is there, on the network, and cannot play
        // this. Flattening that into null loses the only diagnosis anyone gets.
        val fault =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                """<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">""" +
                """<errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription>""" +
                """</UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        response = "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/xml\r\n" +
            "Content-Length: ${fault.length}\r\nConnection: close\r\n\r\n" + fault

        val result = client().call(
            controlUrl(),
            DlnaClient.AV_TRANSPORT,
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>",
        )
        assertTrue(result.toString(), result is SoapResult.Refused)
        assertEquals(714, (result as SoapResult.Refused).fault.code)
    }

    @Test
    fun `a device that is not there is unreachable, which is a different thing`() {
        val result = client().call(
            "http://127.0.0.1:1/control",
            DlnaClient.AV_TRANSPORT,
            "Play",
            "<InstanceID>0</InstanceID>",
        )
        assertTrue(result.toString(), result is SoapResult.Unreachable)
    }
}
