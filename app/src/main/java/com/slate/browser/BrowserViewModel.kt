package com.slate.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Message
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.slate.browser.data.Bookmark
import com.slate.browser.data.BookmarkFolder
import com.slate.browser.data.BrowserRepository
import com.slate.browser.data.SearchEngine
import com.slate.browser.data.Settings
import com.slate.browser.data.SettingsStore
import com.slate.browser.data.ThemeMode
import com.slate.browser.ui.screens.SettingToggle
import com.slate.browser.data.Suggestion
import com.slate.browser.tabs.Tab
import com.slate.browser.tabs.TabManager
import com.slate.browser.tabs.TabPersistence
import com.slate.browser.util.UrlUtils
import com.slate.browser.web.BrowserHost
import com.slate.browser.web.DownloadCoordinator
import com.slate.browser.web.FaviconStore
import com.slate.browser.web.JsDialogRequest
import com.slate.browser.web.SitePermissionRequest
import com.slate.browser.web.SlateWebChromeClient
import com.slate.browser.web.SlateWebViewClient
import com.slate.browser.web.WebViewConfigurator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The overlay currently covering the page, if any. Browsing is always the base layer. */
enum class Overlay { NONE, TABS, BOOKMARKS, HISTORY, SETTINGS }

data class SslPrompt(val message: String, val onProceed: () -> Unit, val onCancel: () -> Unit)

data class FindState(val active: Boolean = false, val query: String = "", val matches: Int = 0, val index: Int = 0)

/**
 * Collaborators are constructor arguments with production defaults, so the browser can be
 * driven against an in-memory database in tests without a dependency-injection framework.
 */
class BrowserViewModel @JvmOverloads constructor(
    app: Application,
    private val repository: BrowserRepository = BrowserRepository(app),
    private val settingsStore: SettingsStore = SettingsStore(app),
    private val persistence: TabPersistence = TabPersistence(app),
) : AndroidViewModel(app) {

    val faviconStore = FaviconStore(app, viewModelScope)
    val snackbarHostState = SnackbarHostState()
    val repo: BrowserRepository get() = repository
    val store: SettingsStore get() = settingsStore

    val settings: StateFlow<Settings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private var host: BrowserHost? = null

    /**
     * WebViews must be built with an Activity context or their dialogs, IME and text-selection
     * menus misbehave. The reference is cleared in [detach] when the Activity goes away, and
     * [TabManager.bind] hibernates every live WebView if a different Activity ever attaches, so
     * no destroyed Activity is retained.
     */
    @SuppressLint("StaticFieldLeak")
    private var activityContext: Context? = null
    private var downloads: DownloadCoordinator? = null
    private var saveJob: Job? = null

    val tabManager = TabManager(
        createWebView = { tab -> buildWebView(tab) },
        onTabsChanged = { scheduleSessionSave() },
    )

    // ---- UI state -----------------------------------------------------------

    var overlay by mutableStateOf(Overlay.NONE)
        private set
    var isOmniboxFocused by mutableStateOf(false)
        private set
    var omniboxText by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
        private set
    var isImmersive by mutableStateOf(false)
        private set
    var chromeVisible by mutableStateOf(true)
        private set
    var fullscreenView by mutableStateOf<View?>(null)
        private set
    var jsDialog by mutableStateOf<JsDialogRequest?>(null)
        private set
    var sitePermission by mutableStateOf<SitePermissionRequest?>(null)
        private set
    var sslPrompt by mutableStateOf<SslPrompt?>(null)
        private set
    var find by mutableStateOf(FindState())
        private set
    var bookmarkedUrls by mutableStateOf<Set<String>>(emptySet())
        private set

    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    val activeTab: Tab? get() = tabManager.activeTab

    init {
        viewModelScope.launch {
            repository.bookmarkedUrls().collect { bookmarkedUrls = it }
        }
    }

    // ---- Lifecycle ----------------------------------------------------------

    fun attach(context: Context, browserHost: BrowserHost) {
        activityContext = context
        host = browserHost
        downloads = DownloadCoordinator(context.applicationContext, browserHost)
        tabManager.bind(context)
    }

    fun detach() {
        host = null
        activityContext = null
    }

    /** Called once, after [attach], to put the browser into a usable state. */
    fun bootstrap(initialUrl: String?) {
        if (tabManager.count > 0) {
            if (initialUrl != null) openInNewTab(initialUrl)
            return
        }
        val restored = if (settings.value.restoreTabs) persistence.load() else null
        if (restored != null) {
            tabManager.restore(restored)
        }
        if (initialUrl != null) {
            openInNewTab(initialUrl)
        } else if (tabManager.count == 0) {
            newTab(focusOmnibox = true)
        }
    }

    override fun onCleared() {
        persistSessionNow()
        tabManager.releaseAll()
        super.onCleared()
    }

    fun onTrimMemory() {
        tabManager.hibernateInactive()
    }

    // ---- Navigation ---------------------------------------------------------

    fun newTab(url: String? = null, focusOmnibox: Boolean = url == null) {
        captureThumbnail(activeTab)
        val home = url ?: settings.value.homePage.takeIf { it.isNotBlank() }
        tabManager.openTab(home, desktopMode = settings.value.desktopModeByDefault)
        overlay = Overlay.NONE
        if (focusOmnibox && home == null) focusOmnibox("") else blurOmnibox()
    }

    fun openInNewTab(url: String) {
        captureThumbnail(activeTab)
        tabManager.openTab(url, desktopMode = settings.value.desktopModeByDefault)
        overlay = Overlay.NONE
        blurOmnibox()
    }

    fun openInBackgroundTab(url: String) {
        tabManager.openTab(url, desktopMode = settings.value.desktopModeByDefault, select = false)
        snack("Opened in a new tab")
    }

    fun selectTab(id: String) {
        captureThumbnail(activeTab)
        tabManager.select(id)
        overlay = Overlay.NONE
        blurOmnibox()
        chromeVisible = true
    }

    fun closeTab(id: String) {
        val wasLast = tabManager.count == 1
        val hadContent = tabManager.tabs.firstOrNull { it.id == id }?.isBlank == false
        tabManager.close(id)
        if (wasLast) newTab(focusOmnibox = true)
        if (hadContent) snack("Tab closed", "Undo") { undoCloseTab() }
    }

    fun closeAllTabs() {
        val closed = tabManager.tabs.count { !it.isBlank }
        tabManager.closeAll()
        newTab(focusOmnibox = true)
        if (closed > 0) {
            snack(if (closed == 1) "1 tab closed" else "$closed tabs closed", "Undo") { undoCloseTab() }
        }
    }

    fun undoCloseTab() {
        tabManager.undoClose() ?: snack("Nothing to reopen")
    }

    fun load(input: String) {
        val tab = activeTab ?: tabManager.openTab()
        val url = UrlUtils.toLoadableUrl(input, settings.value.searchEngine)
        if (url.isBlank()) return
        tab.errorMessage = null
        // Show the destination straight away rather than leaving the previous page's address
        // in the omnibox until the network answers. onPageStarted corrects it on any redirect.
        tab.url = url
        tab.isLoading = true
        tab.progress = 0.02f
        blurOmnibox()
        chromeVisible = true
        val webView = tabManager.webViewFor(tab)
        val headers = WebViewConfigurator.requestHeaders(settings.value)
        if (headers.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, headers)
    }

    fun goBack(): Boolean {
        val webView = activeTab?.webView ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        chromeVisible = true
        return true
    }

    fun goForward() {
        activeTab?.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        val tab = activeTab ?: return
        tab.errorMessage = null
        tabManager.webViewFor(tab).reload()
    }

    fun stopLoading() {
        activeTab?.webView?.stopLoading()
    }

    fun goHome() {
        val home = settings.value.homePage
        if (home.isBlank()) focusOmnibox("") else load(home)
    }

    /** Desktop mode is per tab and survives navigation within that tab. */
    fun toggleDesktopMode() {
        val tab = activeTab ?: return
        tab.isDesktopMode = !tab.isDesktopMode
        val webView = tabManager.webViewFor(tab)
        WebViewConfigurator.configure(webView, settings.value, tab.isDesktopMode)
        webView.reload()
        snack(if (tab.isDesktopMode) "Desktop site" else "Mobile site")
        scheduleSessionSave()
    }

    // ---- Omnibox ------------------------------------------------------------

    fun focusOmnibox(text: String? = null) {
        omniboxText = text ?: activeTab?.url.orEmpty()
        isOmniboxFocused = true
        chromeVisible = true
        updateSuggestions(omniboxText)
    }

    fun blurOmnibox() {
        isOmniboxFocused = false
        suggestions = emptyList()
    }

    fun onOmniboxTextChanged(text: String) {
        omniboxText = text
        updateSuggestions(text)
    }

    private var suggestionJob: Job? = null

    private fun updateSuggestions(query: String) {
        suggestionJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // An empty omnibox offers the pages the user returns to most, not a blank sheet.
            suggestionJob = viewModelScope.launch {
                suggestions = repository.suggest("", limit = 6)
            }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(90) // Coalesce keystrokes; the database is fast but the UI is faster.
            val engine = settings.value.searchEngine
            val fromHistory = repository.suggest(trimmed)
            suggestions = buildList {
                add(Suggestion.Search(trimmed, engine))
                addAll(fromHistory)
            }.distinctBy { it.key }.take(8)
        }
    }

    // ---- Chrome, immersive mode, fullscreen media --------------------------

    fun onPageScrolled(delta: Int, scrollY: Int) {
        if (!settings.value.hideBarsOnScroll || isOmniboxFocused || isImmersive) return
        when {
            scrollY <= SCROLL_TOP_SLOP -> chromeVisible = true
            delta > SCROLL_HIDE_THRESHOLD -> chromeVisible = false
            delta < -SCROLL_SHOW_THRESHOLD -> chromeVisible = true
        }
    }

    fun enterImmersive() {
        isImmersive = true
        overlay = Overlay.NONE
        blurOmnibox()
    }

    fun exitImmersive() {
        isImmersive = false
        chromeVisible = true
    }

    fun toggleImmersive() {
        if (isImmersive) exitImmersive() else enterImmersive()
    }

    fun onEnterElementFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = view
        fullscreenCallback = callback
    }

    fun onExitElementFullscreen() {
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    /** Asks the page to put its primary video into fullscreen, from the browser's own UI. */
    fun requestMediaFullscreen() {
        val webView = activeTab?.webView ?: return
        webView.evaluateJavascript(REQUEST_VIDEO_FULLSCREEN) { result ->
            if (result == null || result == "false" || result == "\"false\"") {
                snack("No video found on this page")
            }
        }
    }

    // ---- Find in page -------------------------------------------------------

    fun openFind() {
        find = FindState(active = true)
        chromeVisible = true
    }

    fun closeFind() {
        activeTab?.webView?.clearMatches()
        find = FindState()
    }

    fun onFindQueryChanged(query: String) {
        find = find.copy(query = query, matches = 0, index = 0)
        val webView = activeTab?.webView ?: return
        if (query.isBlank()) webView.clearMatches() else webView.findAllAsync(query)
    }

    fun findNext(forward: Boolean) {
        activeTab?.webView?.findNext(forward)
    }

    // ---- Overlays and dialogs ----------------------------------------------

    fun showOverlay(target: Overlay) {
        captureThumbnail(activeTab)
        blurOmnibox()
        overlay = target
    }

    fun dismissOverlay() {
        overlay = Overlay.NONE
    }

    fun dismissJsDialog() {
        jsDialog = null
    }

    fun dismissSitePermission() {
        sitePermission = null
    }

    fun dismissSslPrompt() {
        sslPrompt = null
    }

    // ---- Bookmarks / history ------------------------------------------------

    fun toggleBookmark() {
        val tab = activeTab ?: return
        if (!BrowserRepository.isRecordable(tab.url)) {
            snack("This page can't be bookmarked")
            return
        }
        // The star flips at once; the database flow reconciles a moment later. Waiting on a
        // disk write to acknowledge a tap is the kind of lag that makes an app feel cheap.
        val url = tab.url
        val wasBookmarked = bookmarkedUrls.contains(url)
        bookmarkedUrls = if (wasBookmarked) bookmarkedUrls - url else bookmarkedUrls + url
        viewModelScope.launch {
            val added = repository.toggleBookmark(url, tab.displayTitle)
            bookmarkedUrls = if (added) bookmarkedUrls + url else bookmarkedUrls - url
            snack(if (added) "Added to favourites" else "Removed from favourites")
        }
    }

    fun isCurrentBookmarked(): Boolean = activeTab?.url?.let { bookmarkedUrls.contains(it) } == true

    fun removeHistoryEntry(url: String) {
        viewModelScope.launch { repository.removeHistory(url) }
    }

    /** [since] of 0 means everything; otherwise only visits at or after that instant. */
    fun clearHistory(since: Long) {
        viewModelScope.launch {
            if (since <= 0L) repository.clearHistory() else repository.clearHistorySince(since)
            snack("History cleared")
        }
    }

    fun editBookmark(bookmark: Bookmark, title: String, folderId: Long?) {
        viewModelScope.launch {
            repository.updateBookmark(bookmark.copy(title = title.trim().ifBlank { bookmark.title }))
            if (folderId != bookmark.folderId) repository.moveBookmark(bookmark.id, folderId)
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.removeBookmark(bookmark.id) }
    }

    fun createFolder(name: String) {
        viewModelScope.launch { repository.createFolder(name) }
    }

    fun renameFolder(folder: BookmarkFolder, name: String) {
        viewModelScope.launch { repository.renameFolder(folder, name) }
    }

    fun deleteFolder(folder: BookmarkFolder) {
        viewModelScope.launch { repository.deleteFolder(folder.id) }
    }

    // ---- Settings -----------------------------------------------------------

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setTheme(mode) }
    }

    fun setSearchEngine(engine: SearchEngine) {
        viewModelScope.launch { settingsStore.setSearchEngine(engine) }
    }

    fun setHomePage(url: String) {
        viewModelScope.launch { settingsStore.setHomePage(url) }
    }

    /**
     * Applies a preference and, where it changes how a page behaves, replays it onto the live
     * tabs immediately rather than waiting for the next navigation.
     */
    fun setToggle(toggle: SettingToggle, value: Boolean) {
        viewModelScope.launch {
            when (toggle) {
                SettingToggle.JAVASCRIPT -> settingsStore.setJavaScript(value)
                SettingToggle.DESKTOP_DEFAULT -> settingsStore.setDesktopDefault(value)
                SettingToggle.BLOCK_3P_COOKIES -> settingsStore.setBlockThirdPartyCookies(value)
                SettingToggle.DNT -> settingsStore.setDoNotTrack(value)
                SettingToggle.AUTOPLAY -> settingsStore.setAutoplay(value)
                SettingToggle.RESTORE_TABS -> {
                    settingsStore.setRestoreTabs(value)
                    if (!value) persistence.clear()
                }
                SettingToggle.SAVE_HISTORY -> settingsStore.setSaveHistory(value)
                SettingToggle.AUTO_IMMERSIVE -> settingsStore.setAutoImmersive(value)
                SettingToggle.HIDE_ON_SCROLL -> {
                    settingsStore.setHideBarsOnScroll(value)
                    if (!value) chromeVisible = true
                }
                SettingToggle.CLEAR_ON_EXIT -> settingsStore.setClearOnExit(value)
            }
            reapplySettingsToLiveTabs()
        }
    }

    private fun reapplySettingsToLiveTabs() {
        val current = settings.value
        tabManager.tabs.forEach { tab ->
            tab.webView?.let { WebViewConfigurator.configure(it, current, tab.isDesktopMode) }
        }
    }

    fun clearBrowsingData() {
        viewModelScope.launch {
            repository.clearHistory()
            faviconStore.clear()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            tabManager.tabs.forEach { tab ->
                tab.webView?.let { view ->
                    view.clearCache(true)
                    view.clearFormData()
                    view.clearHistory()
                }
            }
            snack("Browsing data cleared")
        }
    }

    /** Called from the activity when the process is going away for good. */
    fun onAppExit() {
        if (settings.value.clearOnExit) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            persistence.clear()
            viewModelScope.launch { repository.clearHistory() }
        } else {
            persistSessionNow()
        }
    }

    /** Moves [direction] places through the tab strip, wrapping at both ends. */
    fun stepTab(direction: Int) {
        val tabs = tabManager.tabs
        if (tabs.size < 2) return
        val current = tabs.indexOfFirst { it.id == tabManager.activeTabId }
        if (current < 0) return
        val next = ((current + direction) % tabs.size + tabs.size) % tabs.size
        selectTab(tabs[next].id)
    }

    fun snack(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        viewModelScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) action?.invoke()
        }
    }

    // ---- WebView construction ----------------------------------------------

    private fun buildWebView(tab: Tab): WebView {
        val context = activityContext ?: getApplication<Application>()
        val browserHost = host
        val webView = WebView(context)
        WebViewConfigurator.configure(webView, settings.value, tab.isDesktopMode)

        webView.webViewClient = SlateWebViewClient(
            tab = tab,
            host = requireHost(),
            pageStarted = { target, url ->
                target.url = url
                target.isLoading = true
                target.errorMessage = null
                target.progress = 0.02f
            },
            pageFinished = { target, url, title ->
                target.url = url
                target.title = title
                target.isLoading = false
                target.progress = 1f
                target.canGoBack = target.webView?.canGoBack() == true
                target.canGoForward = target.webView?.canGoForward() == true
                if (settings.value.saveHistory && target.errorMessage == null) {
                    viewModelScope.launch { repository.recordVisit(url, title) }
                }
                scheduleSessionSave()
            },
            onSslPrompt = { message, proceed, cancel ->
                sslPrompt = SslPrompt(message, proceed, cancel)
            },
        )

        webView.webChromeClient = SlateWebChromeClient(
            tab = tab,
            host = requireHost(),
            onProgress = { target, progress ->
                target.progress = progress / 100f
                target.isLoading = progress in 1..99
            },
            onTitle = { target, title -> target.title = title },
            onIcon = { target, icon ->
                target.favicon = icon
                faviconStore.put(UrlUtils.displayHost(target.url), icon)
            },
            onNewWindow = { message, isUserGesture -> openWindow(message, isUserGesture) },
            closeWindow = { target -> closeTab(target.id) },
            onJsDialog = { request -> jsDialog = request },
            onSitePermission = { request -> sitePermission = request },
        )

        webView.setDownloadListener { url, userAgent, disposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                downloads?.startBlobDownload(webView, url, mimeType)
                snack("Preparing download…")
            } else {
                downloads?.enqueue(url, userAgent, disposition, mimeType)
            }
        }
        downloads?.let { webView.addJavascriptInterface(it.JsBridge(), "SlateDownload") }

        webView.setFindListener { activeIndex, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                find = find.copy(matches = numberOfMatches, index = if (numberOfMatches == 0) 0 else activeIndex + 1)
            }
        }

        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            onPageScrolled(scrollY - oldScrollY, scrollY)
        }

        return webView
    }

    /**
     * A page asked to open a window. The transport WebView must be handed back synchronously,
     * so the tab is created immediately and only foregrounded when the user meant it.
     */
    private fun openWindow(resultMsg: Message, isUserGesture: Boolean): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val newTab = Tab(desktopMode = settings.value.desktopModeByDefault)
        val webView = buildWebView(newTab)
        newTab.webView = webView
        transport.webView = webView
        resultMsg.sendToTarget()

        tabManager.adoptTab(newTab, select = isUserGesture)
        if (!isUserGesture) {
            // Unrequested popups stay in the background and say so, rather than stealing focus.
            snack("Blocked pop-up opened in a background tab")
        }
        return true
    }

    private fun requireHost(): BrowserHost = host ?: NoOpHost

    // ---- Session persistence -----------------------------------------------

    private fun scheduleSessionSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            persistSessionNow()
        }
    }

    fun persistSessionNow() {
        if (!settings.value.restoreTabs) {
            persistence.clear()
            return
        }
        persistence.save(tabManager.snapshot())
    }

    // ---- Tab thumbnails -----------------------------------------------------

    /**
     * A downscaled snapshot for the tab switcher. Drawing the view is far cheaper than a
     * PixelCopy round-trip and does not need the window to be visible.
     */
    private fun captureThumbnail(tab: Tab?) {
        val webView = tab?.webView ?: return
        if (webView.width <= 0 || webView.height <= 0) return
        runCatching {
            val scale = THUMBNAIL_WIDTH.toFloat() / webView.width
            val bitmap = Bitmap.createBitmap(
                THUMBNAIL_WIDTH,
                (webView.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.RGB_565,
            )
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            webView.draw(canvas)
            tab.thumbnail = bitmap
        }
    }

    fun captureActiveThumbnail() = captureThumbnail(activeTab)

    private object NoOpHost : BrowserHost {
        override fun onEnterElementFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = Unit
        override fun onExitElementFullscreen() = Unit
        override fun requestSystemPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) = onResult(false)
        override fun openFileChooser(intent: android.content.Intent, callback: android.webkit.ValueCallback<Array<android.net.Uri>?>) = false
        override fun openExternally(url: String) = false
        override fun toast(message: String) = Unit
        override fun snack(message: String, actionLabel: String?, action: (() -> Unit)?) = Unit
    }

    private companion object {
        const val SCROLL_HIDE_THRESHOLD = 12
        const val SCROLL_SHOW_THRESHOLD = 8
        const val SCROLL_TOP_SLOP = 24
        const val THUMBNAIL_WIDTH = 360

        /**
         * Fullscreen must be triggered from inside the page: the Fullscreen API refuses calls
         * that are not user-activated, and only the page can nominate the right element.
         */
        const val REQUEST_VIDEO_FULLSCREEN = """
            (function() {
              var best = null, bestArea = 0;
              document.querySelectorAll('video').forEach(function(v) {
                var r = v.getBoundingClientRect();
                var area = r.width * r.height;
                if (area > bestArea) { bestArea = area; best = v; }
              });
              if (!best) return false;
              var fn = best.requestFullscreen || best.webkitRequestFullscreen ||
                       best.webkitEnterFullscreen;
              if (!fn) return false;
              try { fn.call(best); return true; } catch (e) { return false; }
            })();
        """
    }
}
