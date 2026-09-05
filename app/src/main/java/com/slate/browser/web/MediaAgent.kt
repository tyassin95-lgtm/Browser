package com.slate.browser.web

import android.content.Context
import android.os.Handler
import android.os.Looper
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
) {
    /** Natural aspect ratio of the stream, or 0 when nothing has been decoded yet. */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f

    /**
     * Worth turning the phone for. Square-ish streams are left alone, and an aspect of zero —
     * a stream that has not decoded a frame yet — is not treated as a guess either way.
     */
    val prefersLandscape: Boolean get() = aspect >= 1.15f

    companion object {
        val NONE = MediaState()
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
    ) {
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
                )
            }.getOrNull() ?: return
            main.post { onState(state) }
        }

        @JavascriptInterface
        fun entered(success: Boolean) {
            main.post { onEnterResult(success) }
        }
    }

    private companion object {
        const val ASSET = "media_agent.js"
    }
}
