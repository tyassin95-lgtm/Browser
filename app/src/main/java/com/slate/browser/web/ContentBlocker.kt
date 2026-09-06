package com.slate.browser.web

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network-level content blocking.
 *
 * Requests are stopped before they leave the device rather than hidden after they arrive, which
 * is the difference between saving the data, the battery and the tracking, and merely tidying
 * the page. Cosmetic tidying is a second pass for what a blocked request leaves behind, not the
 * mechanism.
 *
 * [shouldBlock] runs on WebView's network threads for every subresource on every page, so it is
 * built for that: the rule set is a hash set of registrable domains, and a lookup walks at most
 * a handful of labels of the request host. No regular expressions, no list scanning, no
 * allocation on the hot path beyond the substrings the walk needs.
 */
class ContentBlocker(context: Context) {

    private val appContext = context.applicationContext

    /** Loaded lazily so a launch that never browses never pays for it. */
    private val domains: Set<String> by lazy { loadRules() }

    private val blockedCount = AtomicInteger(0)

    val totalBlocked: Int get() = blockedCount.get()

    fun resetCount() = blockedCount.set(0)

    /**
     * Whether this request should be refused.
     *
     * The document itself is never blocked here: stopping a top-level navigation is a decision
     * about where the user is going, and belongs to the navigation policy where it can be
     * explained and overridden.
     */
    fun shouldBlock(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return false
        val host = request.url.host ?: return false
        return matches(host)
    }

    /** Whether a top-level navigation is heading somewhere that only serves advertising. */
    fun isBlockedDestination(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return matches(host)
    }

    fun matches(host: String): Boolean {
        if (domains.isEmpty()) return false
        val lower = host.lowercase()
        // Walk the labels right to left: "a.b.doubleclick.net" hits on "doubleclick.net".
        var index = 0
        while (true) {
            if (domains.contains(lower.substring(index))) return true
            val dot = lower.indexOf('.', index)
            if (dot < 0) return false
            index = dot + 1
            // A bare TLD can never be a rule, so stop before testing one.
            if (lower.indexOf('.', index) < 0) return false
        }
    }

    fun recordBlock() {
        blockedCount.incrementAndGet()
    }

    /**
     * What a blocked request receives. An empty 200 rather than an error keeps the page's own
     * error handling quiet, and the correct-ish content type stops an image slot from drawing a
     * broken-image glyph where the advert used to be.
     */
    fun blockedResponse(request: WebResourceRequest): WebResourceResponse {
        val accept = request.requestHeaders["Accept"].orEmpty()
        val mimeType = if (accept.contains("image/")) "image/gif" else "text/plain"
        val body = if (mimeType == "image/gif") TRANSPARENT_GIF else ByteArray(0)
        return WebResourceResponse(mimeType, "utf-8", ByteArrayInputStream(body)).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }

    private fun loadRules(): Set<String> = runCatching {
        appContext.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.lowercase() }
                .toHashSet()
        }
    }.getOrDefault(emptySet())

    private companion object {
        const val ASSET = "blocklist.txt"

        /** A 1x1 transparent GIF, so a blocked image occupies nothing rather than breaking. */
        val TRANSPARENT_GIF: ByteArray = intArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00,
            0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x21, 0xF9,
            0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        ).map { it.toByte() }.toByteArray(        )
    }
}
