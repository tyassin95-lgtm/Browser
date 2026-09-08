package com.slate.browser.cast

import android.net.Uri
import com.slate.browser.web.DownloadNaming
import com.slate.browser.web.MediaState
import com.slate.browser.web.UrlSafety

/** What kind of stream a receiver would be asked to play, which decides how it is described to it. */
enum class StreamFormat(val contentType: String) {
    HLS("application/x-mpegurl"),
    DASH("application/dash+xml"),
    PROGRESSIVE("video/mp4"),
}

/**
 * Whether what the page is playing can be sent to a receiver, and if not, why not in words a
 * person can act on.
 *
 * This is the honest core of casting. A Cast receiver fetches the media itself, over its own
 * network connection, with none of this browser's cookies, headers or origin — so "the phone is
 * playing it" is not evidence that a television could. Everything that follows from that is
 * decided here, in one place, with no Android dependencies, so it can be reasoned about and
 * tested directly.
 */
sealed interface CastVerdict {

    /** The receiver can be asked for this. */
    data class Castable(
        val url: String,
        val format: StreamFormat,
        val contentType: String,
        val isLive: Boolean,
        val title: String,
        val posterUrl: String,
    ) : CastVerdict

    /** It cannot be sent, and this is what to tell the user. */
    data class Refused(val reason: String) : CastVerdict

    /** There is nothing playing to send. */
    data object NothingPlaying : CastVerdict
}

object CastEligibility {

    /**
     * @param observedManifest the first stream manifest the page fetched, if any. This is what
     *   makes the feature useful rather than merely correct: almost every video site now feeds
     *   a MediaSource, so the element's own source is a `blob:` that exists nowhere outside the
     *   document — while the playlist the page is reading is an ordinary address a receiver
     *   opens natively.
     */
    fun evaluate(
        media: MediaState,
        pageUrl: String,
        observedManifest: String? = null,
    ): CastVerdict {
        if (!media.hasMedia) return CastVerdict.NothingPlaying

        // Protection is about the content, not about where it lives, so no address helps.
        if (media.isProtected) {
            return CastVerdict.Refused(
                "This video is copy-protected, so it can only play on this phone.",
            )
        }

        val elementSource = media.sourceUrl.trim()
        val usableElementSource = elementSource.takeIf { !media.isStreamedInPage && it.isNotEmpty() }
        val url = usableElementSource ?: observedManifest?.trim().orEmpty()

        if (url.isEmpty()) {
            return CastVerdict.Refused(
                if (media.isStreamedInPage) {
                    "This site builds the video inside the page, so there is no address a TV " +
                        "can open. Starting playback first sometimes gives the browser one."
                } else {
                    "The browser can't tell where this video is coming from."
                },
            )
        }
        if (!UrlSafety.isWeb(url)) {
            return CastVerdict.Refused("This video isn't at an address a TV can open.")
        }

        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()
        if (host.isNullOrBlank()) {
            return CastVerdict.Refused("This video isn't at an address a TV can open.")
        }
        if (isPhoneOnly(host)) {
            return CastVerdict.Refused("This video is only reachable from this phone.")
        }

        val format = formatOf(url)
        return CastVerdict.Castable(
            url = url,
            format = format,
            contentType = contentTypeFor(url, format, media.audioOnly),
            isLive = media.isLive,
            title = media.pageTitle.ifBlank { host },
            posterUrl = media.posterUrl.takeIf { UrlSafety.isWeb(it) }.orEmpty(),
        )
    }

    /**
     * An address only this device can resolve. A receiver is a separate machine on the network,
     * so loopback and `.local`-style names that resolve here say nothing about there.
     */
    private fun isPhoneOnly(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host.startsWith("127.")

    /**
     * Adaptive formats are recognised by their manifest, which is the one part of the address
     * that is reliable — a query string full of tokens is not.
     */
    fun formatOf(url: String): StreamFormat {
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> StreamFormat.HLS
            path.endsWith(".mpd") -> StreamFormat.DASH
            else -> StreamFormat.PROGRESSIVE
        }
    }

    /**
     * What to tell the receiver it is fetching. For a progressive file the extension is the
     * best evidence, and the browser already has a table for turning one into a media type —
     * the same one downloads use, so the two cannot drift apart.
     */
    private fun contentTypeFor(url: String, format: StreamFormat, audioOnly: Boolean): String {
        if (format != StreamFormat.PROGRESSIVE) return format.contentType
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
        val guessed = DownloadNaming.mimeForFileName(path)
        return when {
            guessed.startsWith("video/") || guessed.startsWith("audio/") -> guessed
            audioOnly -> "audio/mpeg"
            else -> StreamFormat.PROGRESSIVE.contentType
        }
    }
}
