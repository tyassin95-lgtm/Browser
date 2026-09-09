package com.slate.browser.cast.dlna

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI

/** A renderer on the network that can be told to play something. */
data class UpnpRenderer(
    /** The device's own permanent identifier, so it survives a changing address. */
    val udn: String,
    val name: String,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
    /**
     * Where to ask what this device can actually play.
     *
     * The single most useful thing a renderer will tell you, and the one this browser was not
     * asking: a list of exactly which media types it accepts. Guessing instead is what produces
     * "File format not supported" on a television that would have played the same video happily
     * under a name it recognised.
     */
    val connectionManagerUrl: String? = null,
)

/** A renderer's refusal, in the numbers the specification defines. */
data class UpnpFault(val code: Int, val description: String)

/**
 * The parts of UPnP AV this browser speaks.
 *
 * Kept apart from the sockets so the fiddly half — which is all of it — can be tested against
 * the replies real devices actually send, rather than the ones the specification implies. Every
 * parser here is defensive: these are strings from an unauthenticated device on the network,
 * and a television is not a trusted peer just because it is in the same room.
 */
object UpnpParsing {

    /** The description document's address, from an SSDP reply. Headers are case-insensitive. */
    fun locationOf(response: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    /**
     * Reads a device description into something castable, or null when the device turns out
     * not to render media — most of what answers an SSDP search does not.
     */
    fun renderer(xml: String, location: String): UpnpRenderer? = runCatching {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        var friendlyName: String? = null
        var udn: String? = null
        var avTransport: String? = null
        var renderingControl: String? = null
        var connectionManager: String? = null

        // Service entries are read as they close, because serviceType and controlURL are
        // siblings and either may come first.
        var serviceType: String? = null
        var controlUrl: String? = null
        var depth = 0
        var text: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("service", true)) {
                        serviceType = null
                        controlUrl = null
                    }
                    depth++
                    text = null
                }

                XmlPullParser.TEXT -> text = parser.text

                XmlPullParser.END_TAG -> {
                    depth--
                    val value = text?.trim().orEmpty()
                    when {
                        // The first friendlyName is the device's; embedded services have their
                        // own further down.
                        parser.name.equals("friendlyName", true) && friendlyName == null ->
                            friendlyName = value
                        parser.name.equals("UDN", true) && udn == null -> udn = value
                        parser.name.equals("serviceType", true) -> serviceType = value
                        parser.name.equals("controlURL", true) -> controlUrl = value
                        parser.name.equals("service", true) -> {
                            val type = serviceType.orEmpty()
                            val url = controlUrl?.takeIf { it.isNotBlank() }
                            if (url != null) {
                                when {
                                    type.contains("AVTransport", true) ->
                                        avTransport = resolve(location, url)
                                    type.contains("RenderingControl", true) ->
                                        renderingControl = resolve(location, url)
                                    type.contains("ConnectionManager", true) ->
                                        connectionManager = resolve(location, url)
                                }
                            }
                        }
                    }
                    text = null
                }
            }
        }

        val control = avTransport ?: return@runCatching null
        UpnpRenderer(
            udn = udn?.takeIf { it.isNotBlank() } ?: location,
            name = friendlyName?.takeIf { it.isNotBlank() } ?: hostOf(location),
            avTransportUrl = control,
            renderingControlUrl = renderingControl,
            connectionManagerUrl = connectionManager,
        )
    }.getOrNull()

    /** Control URLs are usually relative to the description's address, and sometimes are not. */
    internal fun resolve(base: String, url: String): String =
        runCatching { URI(base).resolve(url).toString() }.getOrDefault(url)

    private fun hostOf(location: String): String =
        runCatching { URI(location).host }.getOrNull().orEmpty().ifBlank { "Media renderer" }

    /** Pulls one element's text out of a SOAP reply. */
    fun soapValue(xml: String, tag: String): String? {
        val open = Regex("<(?:[a-zA-Z0-9]+:)?$tag[^>]*>", RegexOption.IGNORE_CASE).find(xml) ?: return null
        val start = open.range.last + 1
        val close = Regex("</(?:[a-zA-Z0-9]+:)?$tag>", RegexOption.IGNORE_CASE).find(xml, start) ?: return null
        return unescape(xml.substring(start, close.range.first))
    }

    /**
     * The UPnP error inside a SOAP fault, if the reply is one.
     *
     * A renderer that refuses says why in a number defined by the specification, and that
     * number is the difference between "the television is not on the network" and "this
     * television cannot play this kind of stream". Throwing it away and saying "it wouldn't
     * accept this video" wastes the only diagnosis anyone gets.
     */
    fun faultOf(xml: String): UpnpFault? {
        val code = soapValue(xml, "errorCode")?.trim()?.toIntOrNull() ?: return null
        return UpnpFault(code, soapValue(xml, "errorDescription")?.trim().orEmpty())
    }

    /**
     * The media types a renderer says it accepts, from its `GetProtocolInfo` reply.
     *
     * The reply is a comma-separated list of `protocolInfo` strings — `http-get:*:video/mp4:*`
     * and so on — and the third field of each is the type. Everything else in the entry is the
     * device's own profile vocabulary, which varies by manufacturer and says nothing a browser
     * can use; the type is the part that decides whether a stream is even offered.
     */
    fun sinkTypes(xml: String): Set<String> {
        val sink = soapValue(xml, "Sink").orEmpty()
        return sink.split(',')
            .mapNotNull { entry ->
                val fields = entry.trim().split(':')
                fields.getOrNull(2)?.trim()?.lowercase()?.takeIf { it.contains('/') }
            }
            .toSet()
    }

    /** `H:MM:SS` or `HH:MM:SS.mmm`, as position and duration both arrive. */
    fun parseClock(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text.startsWith("NOT_IMPLEMENTED")) return 0
        val parts = text.split(':')
        if (parts.size < 2 || parts.size > 3) return 0
        return runCatching {
            val seconds = parts.last().substringBefore('.').toLong()
            val minutes = parts[parts.size - 2].toLong()
            val hours = if (parts.size == 3) parts[0].toLong() else 0
            ((hours * 3600 + minutes * 60 + seconds) * 1000).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    fun formatClock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    /**
     * The metadata a renderer is given alongside the address.
     *
     * Many devices refuse a URL with no DIDL-Lite at all, and many more decide how to treat the
     * stream from the protocolInfo rather than from the address — so this is not decoration.
     */
    fun didl(title: String, url: String, contentType: String, isLive: Boolean = false): String {
        val objectClass = if (contentType.startsWith("audio/")) {
            "object.item.audioItem.musicTrack"
        } else {
            "object.item.videoItem"
        }
        // The DLNA flags matter more than they look. A renderer reads them to decide whether it
        // may seek (OP=01 for a file it can range-request, 00 for a live stream), and whether
        // the source is streaming or a background transfer. Several televisions refuse outright
        // rather than guess when the fourth field is a bare "*".
        val operations = if (isLive) "00" else "01"
        val flags = if (isLive) LIVE_FLAGS else SEEKABLE_FLAGS
        val protocolInfo =
            "http-get:*:$contentType:DLNA.ORG_OP=$operations;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$flags"
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            """<dc:title>${escape(title)}</dc:title>""" +
            """<upnp:class>$objectClass</upnp:class>""" +
            """<res protocolInfo="${escape(protocolInfo)}">${escape(url)}</res>""" +
            """</item></DIDL-Lite>"""
    }

    /** Streaming, seekable by byte range, background-transfer allowed. */
    private const val SEEKABLE_FLAGS = "01700000000000000000000000000000"

    /** Streaming, no seeking: what a live stream honestly is. */
    private const val LIVE_FLAGS = "01500000000000000000000000000000"

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
