package com.slate.browser.tabs

import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    /**
     * Whether this tab is showing the browser's chrome.
     *
     * Per tab, and never inherited: a tab that scrolled its toolbar away must not hand that
     * state to a tab the user has just opened or switched to, which on a page with nothing to
     * scroll would leave them with no way to get it back.
     */
    var chromeVisible by mutableStateOf(true)
        internal set

    /**
     * Whether the certificate for what is on screen was ever accepted over an error.
     *
     * A padlock that survives a warning the user clicked through is a lie the browser tells on
     * the site's behalf, so this follows the document and is cleared by every navigation.
     */
    var certificateOverridden by mutableStateOf(false)
        internal set

    /**
     * How many modal dialogs this document has been allowed, and whether it has spent them.
     *
     * Both belong to the document rather than to the tab or the chrome client: a page that
     * exhausted its allowance must not be able to recover by asking again, and the next page
     * the user visits must not inherit the last one's bad behaviour.
     */
    var dialogsShown by mutableIntStateOf(0)
        internal set
    var dialogsSuppressed by mutableStateOf(false)
        internal set

    /** How many requests this page had refused, reset on every navigation. */
    var blockedCount by mutableIntStateOf(0)
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

    /** Handle for the desktop-viewport script, so it can be removed when the mode is turned off. */
    internal var desktopScript: androidx.webkit.ScriptHandler? = null

    /** Handle for the cosmetic filter script, so it can be lifted when blocking is turned off. */
    internal var cosmeticScript: androidx.webkit.ScriptHandler? = null

    /** Handle for the in-page popup guard, removed with it when blocking is turned off. */
    internal var guardScript: androidx.webkit.ScriptHandler? = null

    /** Deferred load for tabs restored from disk that have never been shown. */
    internal var pendingUrl: String? = null

    internal var lastAccess: Long = System.currentTimeMillis()

    val displayTitle: String get() = title.ifBlank { UrlUtils.fallbackTitle(url) }

    val host: String get() = UrlUtils.displayHost(url)

    val isBlank: Boolean get() = url.isBlank() && pendingUrl == null
}
