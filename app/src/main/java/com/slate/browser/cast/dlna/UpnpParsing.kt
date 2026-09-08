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
)

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
    fun didl(title: String, url: String, contentType: String): String {
        val objectClass = if (contentType.startsWith("audio/")) {
            "object.item.audioItem.musicTrack"
        } else {
            "object.item.videoItem"
        }
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            """<dc:title>${escape(title)}</dc:title>""" +
            """<upnp:class>$objectClass</upnp:class>""" +
            """<res protocolInfo="http-get:*:${escape(contentType)}:*">${escape(url)}</res>""" +
            """</item></DIDL-Lite>"""
    }

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
