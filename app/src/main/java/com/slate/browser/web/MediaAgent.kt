package com.slate.browser.web

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    /** The page's own media element is an `<audio>`: real media, but nothing to display. */
    val audioOnly: Boolean = false,
    /**
     * The address the element settled on, as a receiver elsewhere would have to fetch it. A
     * `blob:` here means the stream is assembled inside the page and exists nowhere else.
     */
    val sourceUrl: String = "",
    /** The source is assembled by Media Source Extensions, or is a data URL. */
    val isStreamedInPage: Boolean = false,
    /** The element is decrypting as it plays: the content is protected. */
    val isProtected: Boolean = false,
    val posterUrl: String = "",
    val pageTitle: String = "",
) {
    /** Natural aspect ratio of the stream, or 0 when nothing has been decoded yet. */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f

    /**
     * Worth turning the phone for. Square-ish streams are left alone, and an aspect of zero —
     * a stream that has not decoded a frame yet — is not treated as a guess either way.
     */
    val prefersLandscape: Boolean get() = aspect >= 1.15f

    /** Something is playing that the browser can offer controls for. */
    val hasMedia: Boolean get() = hasVideo || audioOnly

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

    /** Explicit transitions, for the moments the browser knows which state it wants. */
    fun play(webView: WebView) = command(webView, "play")

    fun pause(webView: WebView) = command(webView, "pause")

    fun seekTo(webView: WebView, positionMs: Long) =
        command(webView, "seek", (positionMs / 1000.0).toString())

    fun setVolume(webView: WebView, volume: Float) =
        command(webView, "volume", volume.coerceIn(0f, 1f).toString())

    private fun command(webView: WebView, name: String, arg: String? = null) {
        val argument = if (arg == null) "null" else "'${arg.replace("'", "")}'"
        webView.evaluateJavascript(
            "window.__slateMedia && window.__slateMedia.command('$name', $argument);",
            null,
        )
    }

    /**
     * The page's end of the conversation.
     *
     * Pages are untrusted, so every field is re-derived defensively and clamped, and the
     * transport ([PageBridge]) has already established that the message came from the main
     * frame as well-formed JSON. Even so, nothing reachable from here does more than change
     * what the media button shows.
     */
    fun bridge(
        onState: (MediaState) -> Unit,
        onEnterResult: (Boolean) -> Unit,
        onNavigationHint: (Boolean) -> Unit,
    ): PageBridge = PageBridge.named(BRIDGE_NAME) { _, payload ->
        when (payload.optString("type")) {
            // The page's verdict on whether the finger that just went down belongs to it.
            "navigationHint" -> main.post { onNavigationHint(payload.optBoolean("suppress")) }

            "entered" -> main.post { onEnterResult(payload.optBoolean("ok")) }

            "report" -> {
                val state = runCatching {
                    val o = payload.optJSONObject("state") ?: return@runCatching null
                    MediaState(
                        hasVideo = o.optBoolean("hasVideo"),
                        isPlaying = o.optBoolean("playing"),
                        isLive = o.optBoolean("live"),
                        isMuted = o.optBoolean("muted"),
                        width = o.optInt("w").coerceIn(0, MAX_DIMENSION),
                        height = o.optInt("h").coerceIn(0, MAX_DIMENSION),
                        volume = o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f),
                        positionMs = o.seconds("t"),
                        durationMs = o.seconds("d"),
                        seekableStartMs = o.seconds("ss"),
                        seekableEndMs = o.seconds("se"),
                        audioOnly = o.optBoolean("audioOnly"),
                        sourceUrl = o.optString("src").take(MAX_URL_CHARS),
                        isStreamedInPage = o.optBoolean("mse"),
                        isProtected = o.optBoolean("drm"),
                        posterUrl = o.optString("poster").take(MAX_URL_CHARS),
                        pageTitle = o.optString("title").take(MAX_TITLE_CHARS),
                    )
                }.getOrNull() ?: return@named
                main.post { onState(state) }
            }
        }
    }

    private companion object {
        const val ASSET = "media_agent.js"

        /** The object name the injected agent posts to; must match the agent script. */
        const val BRIDGE_NAME = "SlateMedia"

        /** No display is this large; a page claiming otherwise is claiming it for a reason. */
        const val MAX_DIMENSION = 16384

        /** Page-supplied strings are bounded before they are held, like every other field. */
        const val MAX_URL_CHARS = 4096
        const val MAX_TITLE_CHARS = 300

        /** Times arrive as seconds from the page, and a page can send anything at all. */
        fun JSONObject.seconds(key: String): Long {
            val value = optDouble(key, 0.0)
            if (value.isNaN() || value.isInfinite() || value < 0) return 0
            return (value * 1000).toLong().coerceAtMost(MAX_DURATION_MS)
        }

        const val MAX_DURATION_MS = 24L * 60 * 60 * 1000
    }
}
