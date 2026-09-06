package com.slate.browser.web

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** What the page is currently playing, as far as the browser can tell. */
data class MediaState(
    val hasVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val isLive: Boolean = false,
    val isMuted: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val volume: Float = 1f,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val seekableStartMs: Long = 0,
    val seekableEndMs: Long = 0,
) {
    /** Natural aspect ratio of the stream, or 0 when nothing has been decoded yet. */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f

    /**
     * Worth turning the phone for. Square-ish streams are left alone, and an aspect of zero —
     * a stream that has not decoded a frame yet — is not treated as a guess either way.
     */
    val prefersLandscape: Boolean get() = aspect >= 1.15f

    /** A recorded video with a known length: an ordinary scrub bar applies. */
    val isSeekable: Boolean get() = !isLive && durationMs > 0

    /**
     * A live stream that keeps a rewind buffer. Below this there is nothing useful to scrub
     * through, and offering a bar that snaps back to the edge would just look broken.
     */
    val hasLiveWindow: Boolean
        get() = isLive && (seekableEndMs - seekableStartMs) >= MIN_DVR_WINDOW_MS

    /** How far behind the live edge the viewer currently is. */
    val behindLiveMs: Long
        get() = if (isLive) (seekableEndMs - positionMs).coerceAtLeast(0) else 0

    val isAtLiveEdge: Boolean get() = !isLive || behindLiveMs <= LIVE_EDGE_TOLERANCE_MS

    companion object {
        val NONE = MediaState()
        private const val MIN_DVR_WINDOW_MS = 30_000L
        private const val LIVE_EDGE_TOLERANCE_MS = 5_000L
    }
}

/** A frame from the position a finger is hovering over while scrubbing. */
data class ScrubPreview(val frame: android.graphics.Bitmap, val positionMs: Long)

/** How the video is fitted to the screen when it does not match the display's shape. */
enum class MediaFit(val cssValue: String) {
    /** The whole frame, letterboxed. Never crops, never stretches. */
    CONTAIN("contain"),

    /** Fills the display edge to edge, trimming the overhang. */
    COVER("cover"),
    ;

    fun toggled(): MediaFit = if (this == CONTAIN) COVER else CONTAIN
}

/**
 * Drives the in-page media agent.
 *
 * The agent is injected at document start into *every* frame, which is what makes this work on
 * sites that put their player in a cross-origin iframe: the browser can reach the real video
 * element without needing the Fullscreen API, and therefore without needing the page or its
 * iframe to permit fullscreen at all.
 */
class MediaAgent(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** One thread: preview frames supersede each other, so they never need to run in parallel. */
    private val decoder = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val script: String by lazy {
        runCatching {
            appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    /** True when the agent reaches subframes; false when only the main frame can be served. */
    val supportsAllFrames: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun install(webView: WebView) {
        if (script.isEmpty()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            // Every frame, every origin: the video that needs rescuing is usually the one inside
            // a third-party player iframe, which nothing else can reach.
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }
    }

    /**
     * Fallback for WebView versions without document-start injection: the main frame still gets
     * the agent, so everything except videos inside cross-origin iframes keeps working.
     */
    fun injectIntoMainFrame(webView: WebView) {
        if (script.isEmpty() || supportsAllFrames) return
        webView.evaluateJavascript(script, null)
    }

    fun scan(webView: WebView) = command(webView, "scan")

    fun enterFullscreen(webView: WebView, fit: MediaFit) = command(webView, "enter", fit.cssValue)

    fun exitFullscreen(webView: WebView) = command(webView, "exit")

    fun setFit(webView: WebView, fit: MediaFit) = command(webView, "fill", fit.cssValue)

    fun togglePlayback(webView: WebView) = command(webView, "playPause")

    fun toggleMute(webView: WebView) = command(webView, "mute")

    fun seekTo(webView: WebView, positionMs: Long) =
        command(webView, "seek", (positionMs / 1000.0).toString())

    fun setVolume(webView: WebView, volume: Float) =
        command(webView, "volume", volume.coerceIn(0f, 1f).toString())

    fun openScrubPreview(webView: WebView) = command(webView, "previewOpen")

    fun previewAt(webView: WebView, positionMs: Long) =
        command(webView, "previewAt", (positionMs / 1000.0).toString())

    fun closeScrubPreview(webView: WebView) = command(webView, "previewClose")

    private fun command(webView: WebView, name: String, arg: String? = null) {
        val argument = if (arg == null) "null" else "'${arg.replace("'", "")}'"
        webView.evaluateJavascript(
            "window.__slateMedia && window.__slateMedia.command('$name', $argument);",
            null,
        )
    }

    /**
     * The page's end of the conversation. Pages are untrusted, so every field is re-derived
     * defensively and nothing here can do more than change what the media button shows.
     */
    inner class Bridge(
        private val onState: (MediaState) -> Unit,
        private val onEnterResult: (Boolean) -> Unit,
        private val onNavigationHint: (Boolean) -> Unit = {},
        private val onPreview: (ScrubPreview?) -> Unit = {},
    ) {
        /**
         * The page's verdict on whether the finger that just went down belongs to it. Arrives on
         * touch down, well before a drag can travel far enough to count as a swipe.
         */
        @JavascriptInterface
        fun navigationHint(suppress: Boolean) {
            main.post { onNavigationHint(suppress) }
        }

        @JavascriptInterface
        fun report(json: String) {
            val state = runCatching {
                val o = JSONObject(json)
                MediaState(
                    hasVideo = o.optBoolean("hasVideo"),
                    isPlaying = o.optBoolean("playing"),
                    isLive = o.optBoolean("live"),
                    isMuted = o.optBoolean("muted"),
                    width = o.optInt("w").coerceIn(0, 16384),
                    height = o.optInt("h").coerceIn(0, 16384),
                    volume = o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f),
                    positionMs = o.seconds("t"),
                    durationMs = o.seconds("d"),
                    seekableStartMs = o.seconds("ss"),
                    seekableEndMs = o.seconds("se"),
                )
            }.getOrNull() ?: return
            main.post { onState(state) }
        }

        @JavascriptInterface
        fun entered(success: Boolean) {
            main.post { onEnterResult(success) }
        }

        /**
         * A scrub preview frame, or an empty payload meaning the page cannot produce one.
         * Decoding happens off the main thread: this arrives several times a second while a
         * finger is moving, which is exactly when the main thread must not be doing work.
         */
        @JavascriptInterface
        fun preview(dataUrl: String, timeSeconds: Double) {
            if (dataUrl.isBlank()) {
                main.post { onPreview(null) }
                return
            }
            decoder.execute {
                val bitmap = runCatching {
                    val payload = dataUrl.substringAfter("base64,", "")
                    if (payload.isEmpty()) return@runCatching null
                    val bytes = Base64.decode(payload, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                val position = (timeSeconds * 1000).toLong().coerceAtLeast(0)
                main.post { onPreview(bitmap?.let { ScrubPreview(it, position) }) }
            }
        }
    }

    private companion object {
        const val ASSET = "media_agent.js"

        /** Times arrive as seconds from the page, and a page can send anything at all. */
        fun JSONObject.seconds(key: String): Long {
            val value = optDouble(key, 0.0)
            if (value.isNaN() || value.isInfinite() || value < 0) return 0
            return (value * 1000).toLong().coerceAtMost(MAX_DURATION_MS)
        }

        const val MAX_DURATION_MS = 24L * 60 * 60 * 1000
    }
}
