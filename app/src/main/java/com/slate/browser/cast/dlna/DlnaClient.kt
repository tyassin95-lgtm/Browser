package com.slate.browser.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * Discovery and control for UPnP AV renderers — the standard behind "DLNA".
 *
 * Written directly against the specification rather than pulled in as a library, because the
 * Android options are an archived project (Cling) and an OSGi container (jUPnP) that would
 * bring far more than a browser needs. The part actually used is small and stable: a multicast
 * search, a description document, and five SOAP actions that have not changed since 2008.
 *
 * Every response here comes from an unauthenticated device on the local network, so all of it
 * is bounded and parsed defensively.
 */
/** What came back from a renderer: the reply, the refusal, or nothing at all. */
sealed interface SoapResult {
    data class Ok(val body: String) : SoapResult
    data class Refused(val fault: UpnpFault) : SoapResult
    data object Unreachable : SoapResult
}

class DlnaClient(context: Context) {

    private val wifi = runCatching {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }.getOrNull()

    /**
     * Searches the local network for media renderers.
     *
     * Blocking, and bounded by [timeoutMs]: SSDP has no completion, only a window in which
     * devices answer. A multicast lock is held for the duration because Android drops
     * multicast to the application layer without one, which is the usual reason a search on a
     * working network finds nothing at all.
     */
    fun discover(timeoutMs: Int = DISCOVERY_WINDOW_MS): List<UpnpRenderer> {
        val lock = runCatching {
            wifi?.createMulticastLock("slate-dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val locations = LinkedHashSet<String>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = SOCKET_READ_MS
                val group = InetAddress.getByName(SSDP_ADDRESS)
                // Media renderers specifically, then everything: some devices only answer the
                // broad search, and a few only answer the specific one.
                listOf(RENDERER_TARGET, "ssdp:all").forEach { target ->
                    val message = searchMessage(target).toByteArray()
                    repeat(2) {
                        runCatching {
                            socket.send(DatagramPacket(message, message.size, group, SSDP_PORT))
                        }
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline && locations.size < MAX_DEVICES) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!received) continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    UpnpParsing.locationOf(text)?.let { locations.add(it) }
                }
            }
        }
        runCatching { lock?.release() }

        return locations.mapNotNull { location ->
            val xml = fetch(location) ?: return@mapNotNull null
            UpnpParsing.renderer(xml, location)
        }
    }

    private fun searchMessage(target: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $target\r\n\r\n"

    /** Fetches a description document, bounded in both time and size. */
    private fun fetch(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            useCaches = false
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.readBounded(MAX_DESCRIPTION_BYTES)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * Invokes one SOAP action. Returns the body on success, or null — a renderer that refuses
     * an action is reported to the caller rather than retried at it.
     */
    fun invoke(controlUrl: String, service: String, action: String, arguments: String): String? =
        (call(controlUrl, service, action, arguments) as? SoapResult.Ok)?.body

    /**
     * One SOAP action, with the renderer's answer kept intact.
     *
     * A refusal is not the same as silence, and the two are not the same as a fault the
     * specification has a number for. Everything above this needs to tell them apart to say
     * anything useful, so nothing is flattened into null here.
     */
    fun call(controlUrl: String, service: String, action: String, arguments: String): SoapResult =
        runCatching {
            val body =
                """<?xml version="1.0" encoding="utf-8"?>""" +
                    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
                    """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
                    """<u:$action xmlns:u="$service">$arguments</u:$action>""" +
                    """</s:Body></s:Envelope>"""
            val connection = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"$service#$action\"")
                setRequestProperty("Connection", "close")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val reply = stream?.readBounded(MAX_REPLY_BYTES).orEmpty()
                when {
                    status in 200..299 -> SoapResult.Ok(reply)
                    else -> SoapResult.Refused(
                        UpnpParsing.faultOf(reply) ?: UpnpFault(status, "HTTP $status"),
                    )
                }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(SoapResult.Unreachable)

    /** Reads at most [limit] bytes: a device on the network does not get to exhaust memory. */
    private fun java.io.InputStream.readBounded(limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val RENDERER_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val DISCOVERY_WINDOW_MS = 3_000
        private const val SOCKET_READ_MS = 700
        private const val HTTP_TIMEOUT_MS = 4_000
        private const val MAX_DEVICES = 24
        private const val MAX_DESCRIPTION_BYTES = 256 * 1024
        private const val MAX_REPLY_BYTES = 64 * 1024
    }
}
