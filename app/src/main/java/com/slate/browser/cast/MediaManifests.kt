package com.slate.browser.cast

import android.net.Uri

/**
 * Recognises the streaming manifests a page fetches for itself.
 *
 * Almost no modern video site hands a `<video>` element a URL any more. hls.js, dash.js and
 * Shaka all feed a MediaSource, so the element's `currentSrc` is a `blob:` that means nothing
 * outside the document — which is true, and useless, because the manifest those players are
 * reading *is* an ordinary address, and a Cast receiver plays HLS and DASH natively.
 *
 * So the browser watches what the page asks the network for. It already sees every request, to
 * decide whether to block it; noticing that one of them is a playlist costs nothing more.
 */
object MediaManifests {

    /** Whether this request is a stream manifest worth remembering, and what kind. */
    fun formatOf(url: String): StreamFormat? {
        if (url.length > MAX_URL_CHARS) return null
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        // The path, not the whole address: these URLs routinely carry a query string of tokens
        // and expiry stamps, and one of those containing ".mpd" proves nothing.
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase() ?: return null
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> StreamFormat.HLS
            path.endsWith(".mpd") -> StreamFormat.DASH
            else -> null
        }
    }

    fun isManifest(url: String): Boolean = formatOf(url) != null

    private const val MAX_URL_CHARS = 4096
}
