package com.slate.browser.web

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.slate.browser.web.filter.CosmeticIndex
import com.slate.browser.web.filter.FilterEngine
import com.slate.browser.web.filter.ResourceType
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

/**
 * Content blocking, driven by the published filter lists.
 *
 * The rules are EasyList, EasyPrivacy and AdGuard's mobile list, carried in their own format
 * and matched by [FilterEngine]. That is the whole architecture: no curated set of domains to
 * fall behind, no per-site special cases, and updating the blocking is a matter of dropping in
 * newer copies of the lists (see `tools/build-filters.mjs`).
 *
 * Requests are stopped before they leave the device rather than hidden after they arrive, which
 * is the difference between saving the data, the battery and the tracking, and merely tidying
 * the page. The element-hiding rules are a second pass for the markup a blocked request leaves
 * behind, not the mechanism.
 */
class ContentBlocker(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var engine: FilterEngine? = null

    @Volatile
    private var cosmetic: CosmeticIndex? = null

    /** How many rules are live. Zero until the lists finish loading. */
    val ruleCount: Int get() = engine?.ruleCount ?: 0

    private val isReady: Boolean get() = engine != null

    /** Why the lists are not loaded, when they are not. Read by diagnostics and tests. */
    @Volatile
    var loadFailure: Throwable? = null
        private set

    private val loader: Thread =
        // Off the main thread, and started at construction so the lists are in place before the
        // first page has finished asking for anything.
        thread(name = "slate-filters", isDaemon = true, priority = Thread.MIN_PRIORITY) { load() }

    /** Blocks until the lists are in place. For tests and diagnostics, never for browsing. */
    fun awaitReady(timeoutMs: Long = 30_000): Boolean {
        runCatching { loader.join(timeoutMs) }
        return isReady
    }

    /**
     * Whether this request should be refused.
     *
     * The document itself is never blocked here: stopping a top-level navigation is a decision
     * about where the user is going, and belongs to the navigation policy where it can be
     * explained and overridden.
     */
    fun shouldBlock(request: WebResourceRequest, documentUrl: String?): Boolean {
        if (request.isForMainFrame) return false
        val active = engine ?: return false
        val url = request.url.toString()
        if (!url.startsWith("http")) return false
        val requestHost = request.url.host ?: return false
        val documentHost = hostOf(documentUrl) ?: requestHost
        return active.shouldBlock(
            url = url,
            type = resourceType(request),
            thirdParty = !sameSite(requestHost, documentHost),
            documentHost = documentHost,
        )
    }

    /**
     * Whether a window or navigation the page opened by itself is heading somewhere the lists
     * describe as advertising. Matched as a popup first, which is exactly what the lists'
     * `$popup` rules were written for, then as a document.
     */
    fun isBlockedDestination(url: String, documentUrl: String? = null): Boolean {
        val active = engine ?: return false
        if (!url.startsWith("http")) return false
        val host = hostOf(url) ?: return false
        val documentHost = hostOf(documentUrl) ?: host
        val thirdParty = !sameSite(host, documentHost)
        return active.shouldBlock(url, ResourceType.POPUP, thirdParty, documentHost) ||
            active.shouldBlock(url, ResourceType.DOCUMENT, thirdParty, documentHost)
    }

    /** The selectors to hide on a page, or an empty list before the lists are loaded. */
    fun cosmeticSelectors(host: String): List<String> =
        cosmetic?.selectorsFor(host).orEmpty()

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

    private fun load() {
        runCatching {
            engine = readAsset(NETWORK_ASSET, FilterEngine::compile)
            cosmetic = readAsset(COSMETIC_ASSET, CosmeticIndex::compile)
        }.onFailure { loadFailure = it }
    }

    /**
     * Streams the asset a line at a time. Reading it whole first would hold a three-megabyte
     * string alongside the index it is being turned into, for no gain.
     */
    private fun <T> readAsset(name: String, build: (Sequence<String>) -> T): T =
        appContext.assets.open(name).bufferedReader().use { reader ->
            build(reader.lineSequence())
        }

    /**
     * What kind of resource a request is for.
     *
     * WebView does not hand the navigation destination over directly, so this reads what the
     * page itself declared: `Sec-Fetch-Dest` when the WebView sends it, the `Accept` header the
     * loader sets per resource kind otherwise, and the address as a last resort. Getting this
     * right is what makes `$script`, `$image` and `$subdocument` rules mean anything.
     */
    private fun resourceType(request: WebResourceRequest): ResourceType {
        val headers = request.requestHeaders
        headers["Sec-Fetch-Dest"]?.lowercase()?.let { dest ->
            when (dest) {
                "script", "worker", "sharedworker", "serviceworker" -> return ResourceType.SCRIPT
                "image" -> return ResourceType.IMAGE
                "style" -> return ResourceType.STYLESHEET
                "iframe", "frame", "embed", "object", "document" -> return ResourceType.SUBDOCUMENT
                "font" -> return ResourceType.FONT
                "audio", "video", "track" -> return ResourceType.MEDIA
                "empty" -> return ResourceType.XHR
            }
        }
        val accept = headers["Accept"]?.lowercase().orEmpty()
        when {
            accept.startsWith("text/html") -> return ResourceType.SUBDOCUMENT
            accept.contains("image/") -> return ResourceType.IMAGE
            accept.contains("text/css") -> return ResourceType.STYLESHEET
            accept.contains("javascript") -> return ResourceType.SCRIPT
            accept.contains("font/") -> return ResourceType.FONT
            accept.contains("video/") || accept.contains("audio/") -> return ResourceType.MEDIA
        }
        val path = request.url.encodedPath?.lowercase().orEmpty()
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> ResourceType.SCRIPT
            path.endsWith(".css") -> ResourceType.STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") -> ResourceType.IMAGE
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") -> ResourceType.FONT
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") ||
                path.endsWith(".ts") || path.endsWith(".mpd") || path.endsWith(".mp3") ||
                path.endsWith(".m4s") -> ResourceType.MEDIA
            path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php") ->
                ResourceType.SUBDOCUMENT
            else -> ResourceType.XHR
        }
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
    }

    /** Good enough for "is this the same site": the last two labels of the host. */
    private fun sameSite(a: String, b: String): Boolean {
        if (a == b) return true
        return registrable(a) == registrable(b)
    }

    private fun registrable(host: String): String {
        val labels = host.split('.')
        return if (labels.size <= 2) host else labels.takeLast(2).joinToString(".")
    }

    private companion object {
        const val NETWORK_ASSET = "filters/network.txt"
        const val COSMETIC_ASSET = "filters/cosmetic.txt"

        /** A 1x1 transparent GIF, so a blocked image occupies nothing rather than breaking. */
        val TRANSPARENT_GIF: ByteArray = intArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00,
            0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x21, 0xF9,
            0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        ).map { it.toByte() }.toByteArray()
    }
}
