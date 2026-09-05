package com.slate.browser.tabs

import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.slate.browser.web.MediaState

/**
 * Owns the set of open tabs and, crucially, decides which of them get a live [WebView].
 *
 * A WebView costs several megabytes of native memory and keeps a renderer alive, so a browser
 * that keeps one per tab falls over at a dozen tabs on an ordinary phone. Instead at most
 * [MAX_LIVE_WEBVIEWS] are resident; the least recently used tab beyond that is *hibernated* —
 * its navigation history is serialised and the view destroyed. Waking it restores the same
 * back/forward stack and scroll position, so the difference is invisible apart from a brief
 * re-render.
 */
class TabManager(
    private val createWebView: (Tab) -> WebView,
    private val onTabsChanged: () -> Unit,
) {

    val tabs: SnapshotStateList<Tab> = mutableStateListOf()

    var activeTabId by mutableStateOf<String?>(null)
        private set

    /** Supports a single level of "undo close", the only kind anyone actually uses. */
    private val recentlyClosed = ArrayDeque<PersistedTab>()

    /** Set while the manager is bound to a particular Activity's context. */
    private var boundContext: Context? = null

    val activeTab: Tab? get() = tabs.firstOrNull { it.id == activeTabId }

    val count: Int get() = tabs.size

    val canUndoClose: Boolean get() = recentlyClosed.isNotEmpty()

    /**
     * Binds to the context that new WebViews will be created with. If the Activity was
     * recreated (process death, an uncovered configuration change) every live WebView is
     * hibernated first: a WebView holding a destroyed Activity leaks it and cannot show
     * dialogs or IME correctly.
     */
    fun bind(context: Context) {
        if (boundContext === context) return
        if (boundContext != null) tabs.forEach { hibernate(it) }
        boundContext = context
    }

    fun openTab(url: String? = null, desktopMode: Boolean = false, select: Boolean = true): Tab {
        val tab = Tab(initialUrl = url.orEmpty(), desktopMode = desktopMode)
        if (url != null) tab.pendingUrl = url
        tabs.add(tab)
        if (select) select(tab.id)
        onTabsChanged()
        return tab
    }

    /** Used for popups: the tab is created around a WebView the page already owns. */
    fun adoptTab(tab: Tab, select: Boolean) {
        tabs.add(tab)
        if (select) select(tab.id)
        onTabsChanged()
    }

    fun select(id: String) {
        if (activeTabId == id) return
        activeTabId = id
        tabs.firstOrNull { it.id == id }?.lastAccess = System.currentTimeMillis()
        enforceLiveBudget()
        onTabsChanged()
    }

    fun close(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = tabs[index]

        if (tab.url.isNotBlank()) {
            recentlyClosed.addLast(PersistedTab(tab.id, tab.url, tab.displayTitle, tab.isDesktopMode))
            while (recentlyClosed.size > UNDO_DEPTH) recentlyClosed.removeFirst()
        }

        destroy(tab)
        tabs.removeAt(index)

        if (activeTabId == id) {
            // Focus follows the neighbour to the left, matching how tab strips behave.
            activeTabId = tabs.getOrNull(index - 1)?.id ?: tabs.getOrNull(index)?.id
        }
        onTabsChanged()
    }

    fun closeAll() {
        tabs.forEach { tab ->
            if (tab.url.isNotBlank()) {
                recentlyClosed.addLast(PersistedTab(tab.id, tab.url, tab.displayTitle, tab.isDesktopMode))
            }
            destroy(tab)
        }
        while (recentlyClosed.size > UNDO_DEPTH) recentlyClosed.removeFirst()
        tabs.clear()
        activeTabId = null
        onTabsChanged()
    }

    fun undoClose(): Tab? {
        val restored = recentlyClosed.removeLastOrNull() ?: return null
        return openTab(restored.url, restored.desktopMode)
    }

    /**
     * Returns the live WebView for a tab, waking it if necessary. Callers must be on the main
     * thread; this is the only place a WebView is ever constructed.
     */
    fun webViewFor(tab: Tab): WebView {
        tab.lastAccess = System.currentTimeMillis()
        tab.webView?.let { return it }

        val webView = createWebView(tab)
        tab.webView = webView

        val state = tab.savedState
        val pending = tab.pendingUrl
        when {
            state != null -> {
                webView.restoreState(state)
                tab.savedState = null
                // restoreState does not always re-issue the load for a page that never
                // finished; a tab with no history left is simply reloaded.
                if (webView.copyBackForwardList().size == 0 && tab.url.isNotBlank()) {
                    webView.loadUrl(tab.url)
                }
            }
            pending != null -> {
                tab.pendingUrl = null
                webView.loadUrl(pending)
            }
        }
        enforceLiveBudget()
        return webView
    }

    /** Frees the renderer for tabs the user is not looking at. */
    private fun enforceLiveBudget() {
        val live = tabs.filter { it.webView != null }
        if (live.size <= MAX_LIVE_WEBVIEWS) return
        live.asSequence()
            .filter { it.id != activeTabId }
            .sortedBy { it.lastAccess }
            .take(live.size - MAX_LIVE_WEBVIEWS)
            .toList()
            .forEach { hibernate(it) }
    }

    /** Serialises a tab's navigation history and releases its WebView. */
    fun hibernate(tab: Tab) {
        val webView = tab.webView ?: return
        val bundle = Bundle()
        if (webView.saveState(bundle) != null) {
            tab.savedState = bundle
        } else if (tab.url.isNotBlank()) {
            tab.pendingUrl = tab.url
        }
        tab.webView = null
        // The document is gone, so whatever it was playing is gone with it.
        tab.media = MediaState.NONE
        teardown(webView)
    }

    /** Trims memory without losing anything: every inactive tab goes to sleep. */
    fun hibernateInactive() {
        tabs.filter { it.id != activeTabId }.forEach { hibernate(it) }
    }

    private fun destroy(tab: Tab) {
        val webView = tab.webView ?: return
        tab.webView = null
        tab.savedState = null
        tab.media = MediaState.NONE
        teardown(webView)
    }

    private fun teardown(webView: WebView) {
        webView.stopLoading()
        webView.onPause()
        webView.webChromeClient = null
        webView.setOnScrollChangeListener(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }

    fun releaseAll() {
        tabs.forEach { destroy(it) }
        boundContext = null
    }

    fun snapshot(): PersistedSession = PersistedSession(
        tabs = tabs.filter { it.url.isNotBlank() || it.pendingUrl != null }.map {
            PersistedTab(
                id = it.id,
                url = it.pendingUrl ?: it.url,
                title = it.displayTitle,
                desktopMode = it.isDesktopMode,
            )
        },
        activeId = activeTabId,
    )

    /**
     * Restores a saved session without loading anything: tabs come back as titles and URLs and
     * only fetch the network when the user actually opens them.
     */
    fun restore(session: PersistedSession) {
        tabs.clear()
        session.tabs.forEach { persisted ->
            val tab = Tab(
                id = persisted.id.ifBlank { java.util.UUID.randomUUID().toString() },
                initialUrl = persisted.url,
                initialTitle = persisted.title,
                desktopMode = persisted.desktopMode,
            )
            tab.pendingUrl = persisted.url
            tabs.add(tab)
        }
        activeTabId = session.activeId?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.lastOrNull()?.id
        onTabsChanged()
    }

    private companion object {
        const val MAX_LIVE_WEBVIEWS = 4
        const val UNDO_DEPTH = 10
    }
}
