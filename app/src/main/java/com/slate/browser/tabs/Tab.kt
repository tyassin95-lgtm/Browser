package com.slate.browser.tabs

import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.slate.browser.util.UrlUtils
import com.slate.browser.web.MediaState
import java.util.UUID

/**
 * One browsing context.
 *
 * A tab is not the same thing as a live [WebView]: only a handful of tabs keep a real WebView
 * around at any time (see [TabManager]). The rest hibernate, holding just enough state —
 * a serialised back/forward list, or failing that a URL — to come back indistinguishably.
 */
@Stable
class Tab(
    val id: String = UUID.randomUUID().toString(),
    initialUrl: String = "",
    initialTitle: String = "",
    desktopMode: Boolean = false,
) {
    var url by mutableStateOf(initialUrl)
        internal set
    var title by mutableStateOf(initialTitle)
        internal set
    var favicon by mutableStateOf<Bitmap?>(null)
        internal set
    var progress by mutableFloatStateOf(0f)
        internal set
    var isLoading by mutableStateOf(false)
        internal set
    var canGoBack by mutableStateOf(false)
        internal set
    var canGoForward by mutableStateOf(false)
        internal set
    var isDesktopMode by mutableStateOf(desktopMode)
        internal set
    var errorMessage by mutableStateOf<String?>(null)
        internal set
    /** What this tab is playing, reported by the in-page media agent. */
    var media by mutableStateOf(MediaState.NONE)
        internal set

    /** A page snapshot for the tab switcher, refreshed lazily when the tab is left. */
    var thumbnail by mutableStateOf<Bitmap?>(null)
        internal set

    /**
     * Non-null only while this tab is one of the live ones. Observable, so the composable
     * hosting the page follows the tab waking and hibernating without being told.
     */
    var webView by mutableStateOf<WebView?>(null)
        internal set

    /** WebView back/forward state for a hibernated tab. */
    internal var savedState: Bundle? = null

    /** Deferred load for tabs restored from disk that have never been shown. */
    internal var pendingUrl: String? = null

    internal var lastAccess: Long = System.currentTimeMillis()

    val displayTitle: String get() = title.ifBlank { UrlUtils.fallbackTitle(url) }

    val host: String get() = UrlUtils.displayHost(url)

    val isBlank: Boolean get() = url.isBlank() && pendingUrl == null
}
