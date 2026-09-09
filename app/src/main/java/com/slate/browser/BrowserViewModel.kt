package com.slate.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
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
import com.slate.browser.util.ChromeScrollPolicy
import com.slate.browser.util.UrlUtils
import com.slate.browser.cast.MediaReceivers
import com.slate.browser.cast.CastEligibility
import com.slate.browser.cast.CastPreflight
import com.slate.browser.cast.CastStage
import com.slate.browser.cast.CastState
import com.slate.browser.cast.CastVerdict
import com.slate.browser.cast.StreamFormat
import com.slate.browser.web.BrowserHost
import com.slate.browser.web.ContentBlocker
import com.slate.browser.web.CosmeticFilter
import com.slate.browser.web.FocusVisibility
import com.slate.browser.web.InPageGuard
import com.slate.browser.web.NavigationPolicy
import com.slate.browser.web.PopupGuard
import com.slate.browser.web.UrlSafety
import com.slate.browser.web.UserActivation
import com.slate.browser.web.DesktopMode
import com.slate.browser.web.DownloadCoordinator
import com.slate.browser.web.DownloadNaming
import com.slate.browser.web.FaviconStore
import com.slate.browser.web.MediaAgent
import com.slate.browser.web.MediaFit
import com.slate.browser.web.LinkContext
import com.slate.browser.web.LinkContextResolver
import com.slate.browser.web.MediaState
import com.slate.browser.web.NavigationDirection
import com.slate.browser.web.NavigationGestureListener
import com.slate.browser.web.SlateWebView
import kotlin.math.abs
import com.slate.browser.web.JsDialogRequest
import com.slate.browser.web.SitePermissionRequest
import com.slate.browser.web.SlateWebChromeClient
import com.slate.browser.web.SlateWebViewClient
import com.slate.browser.web.WebViewConfigurator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The overlay currently covering the page, if any. Browsing is always the base layer. */
enum class Overlay { NONE, TABS, BOOKMARKS, HISTORY, SETTINGS }

/** What the back gesture should unwind next. */
enum class BackAction {
    EXIT_ELEMENT_FULLSCREEN,
    EXIT_MEDIA_FULLSCREEN,
    BLUR_OMNIBOX,
    CLOSE_FIND,
    DISMISS_OVERLAY,
    EXIT_IMMERSIVE,
    GO_BACK,

    /** Nothing left to unwind: back belongs to the system again. */
    LEAVE_BROWSER,
}

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
    private val mediaAgent = MediaAgent(app)
    private val desktopMode = DesktopMode()
    private val contentBlocker = ContentBlocker(app)
    private val popupGuard = PopupGuard(app)
    private val cosmeticFilter = CosmeticFilter(contentBlocker)

    private val inPageGuard = InPageGuard(app)

    private val focusVisibility = FocusVisibility(app)
    private val userActivation = UserActivation()
    private val navigationPolicy = NavigationPolicy(contentBlocker, userActivation)

    /** How far a swipe must travel to commit, in pixels, resolved once from the display. */
    private val gestureCommitDistancePx =
        96f * app.resources.displayMetrics.density
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
    private val bookmarkWrites = Mutex()
    private var chromeSettleJob: Job? = null
    private val chromeScrollPolicy =
        ChromeScrollPolicy(app.resources.displayMetrics.density)

    /** The database's view, and the taps that have not reached it yet. */
    private var storedBookmarks: Set<String> = emptySet()
    private val pendingBookmarks = mutableMapOf<String, Boolean>()

    private fun publishBookmarks() {
        bookmarkedUrls = pendingBookmarks.entries.fold(storedBookmarks) { acc, (url, wanted) ->
            if (wanted) acc + url else acc - url
        }
    }

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

    /** The browser's own fullscreen video presentation, independent of the page's player. */
    var isMediaFullscreen by mutableStateOf(false)
        private set
    var mediaFit by mutableStateOf(MediaFit.CONTAIN)
        private set

    /** A page asking to leave the browser, waiting on the user to say whether it may. */
    var externalLaunch by mutableStateOf<ExternalLaunch?>(null)
        private set

    /** What a long press landed on, while its sheet is open. */
    var linkContext by mutableStateOf<LinkContext?>(null)
        private set

    /** Live horizontal navigation drag, for the on-screen affordance. */
    var navGesture by mutableStateOf<NavGesture?>(null)
        private set

    /** True while a landscape stream should hold the display sideways. */
    var lockLandscapeForMedia by mutableStateOf(false)
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

    /**
     * Whether the chrome is showing for the tab on screen. Fullscreen overrides it, and a tab
     * with no tab at all shows it, so this can never resolve to "hidden with nothing to undo it".
     */
    val chromeVisible: Boolean
        get() = !isImmersive && (activeTab?.chromeVisible ?: true)

    init {
        viewModelScope.launch {
            repository.bookmarkedUrls().collect { stored ->
                storedBookmarks = stored
                // An optimistic value stands until the database actually agrees with it, so a
                // quick second tap is never undone by an emission still describing the first.
                pendingBookmarks.entries.removeAll { (url, wanted) -> stored.contains(url) == wanted }
                publishBookmarks()
            }
        }
    }

    // ---- Lifecycle ----------------------------------------------------------

    fun attach(context: Context, browserHost: BrowserHost) {
        activityContext = context
        host = browserHost
        downloads = DownloadCoordinator(context.applicationContext, browserHost)
        tabManager.bind(context)
        attachCast(context)
    }

    fun detach() {
        host = null
        activityContext = null
    }

    private var bootstrapped = false

    /**
     * Called once, after [attach], to put the browser into a usable state.
     *
     * The stored preferences are read before anything is decided. [settings] is a state flow
     * that starts on the defaults and only becomes the user's a moment later, so deciding from
     * it here would restore a session on every launch — including for a user who has turned
     * that off — which is why the whole thing is deferred until the real value arrives.
     */
    fun bootstrap(initialUrl: String?) {
        if (bootstrapped) {
            if (initialUrl != null) openInNewTabAndSwitch(initialUrl)
            return
        }
        bootstrapped = true
        viewModelScope.launch {
            val stored = runCatching { settingsStore.settings.first() }.getOrDefault(Settings())
            if (stored.restoreTabs) {
                // A session file that cannot be read is a session that is not restored, never a
                // launch that fails.
                runCatching { persistence.load() }.getOrNull()?.let { tabManager.restore(it) }
            } else {
                // Not merely ignored: a session left on disk would come back the moment the
                // setting was turned on again, which is not what "do not reopen tabs" means.
                persistence.clear()
            }
            when {
                initialUrl != null -> openInNewTabAndSwitch(initialUrl)
                // Launching is not a request to type. The browser comes up on the page, with
                // the keyboard down and nothing over it; the omnibox opens when it is tapped.
                tabManager.count == 0 -> newTab(focusOmnibox = false)
            }
        }
    }

    override fun onCleared() {
        cast?.release()
        persistSessionNow()
        tabManager.releaseAll()
        super.onCleared()
    }

    fun onTrimMemory() {
        tabManager.hibernateInactive()
    }

    // ---- Navigation ---------------------------------------------------------

    /** A new empty tab the user asked for, which is therefore the one they want to be in. */
    fun newTab(url: String? = null, focusOmnibox: Boolean = url == null) {
        val home = url ?: settings.value.homePage.takeIf { it.isNotBlank() }
        val tab = tabManager.createTab(home, desktopMode = settings.value.desktopModeByDefault)
        switchTo(tab.id)
        if (focusOmnibox && home == null) focusOmnibox("") else blurOmnibox()
    }

    /**
     * Opens a page in another tab without leaving the current one.
     *
     * This is the default for every "open in a new tab" in the browser, because that is what it
     * says: the tab is created, and where the user is looking does not change. Only [switchTo]
     * moves them, and only when something they did means to.
     */
    fun openInBackgroundTab(url: String) {
        // The identity is captured now: by the time the action is tapped, other tabs may exist.
        val created = tabManager.createTab(url, desktopMode = settings.value.desktopModeByDefault)
        snack("Opened in background", "Switch") {
            if (tabManager.tabs.any { it.id == created.id }) switchTo(created.id)
        }
    }

    /** Opens a page in a new tab and goes there. For links the browser itself is following. */
    fun openInNewTabAndSwitch(url: String) {
        val tab = tabManager.createTab(url, desktopMode = settings.value.desktopModeByDefault)
        switchTo(tab.id)
        blurOmnibox()
    }

    fun selectTab(id: String) = switchTo(id)

    /**
     * The single path by which the tab on screen changes. Everything that has to happen when the
     * user moves between tabs happens here and nowhere else, so no caller can move them and
     * forget half of it.
     */
    private fun switchTo(id: String) {
        exitMediaFullscreen()
        captureThumbnail(activeTab)
        tabManager.select(id)
        overlay = Overlay.NONE
        blurOmnibox()
        // A tab arrives showing its chrome, whatever the tab being left had done with its own.
        showChrome(tabManager.activeTab)
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

    /** The search URL for text that is not a link, using the engine the user chose. */
    fun searchUrlFor(text: String): String = settings.value.searchEngine.urlFor(text)

    fun load(input: String) {
        val tab = activeTab ?: tabManager.createTab().also { tabManager.select(it.id) }
        val url = UrlUtils.toLoadableUrl(input, settings.value.searchEngine)
        if (url.isBlank()) return
        exitMediaFullscreen()
        tab.media = MediaState.NONE
        tab.errorMessage = null
        // Show the destination straight away rather than leaving the previous page's address
        // in the omnibox until the network answers. onPageStarted corrects it on any redirect.
        tab.url = url
        tab.isLoading = true
        tab.progress = 0.02f
        blurOmnibox()
        showChrome()
        val webView = tabManager.webViewFor(tab)
        val headers = WebViewConfigurator.requestHeaders(settings.value)
        if (headers.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, headers)
    }

    /**
     * The layers unwind in the order the user perceives them, and page history is part of that
     * chain rather than an afterthought. Expressed as a value so the policy can be tested and
     * so no layer can be forgotten by an overlapping set of conditions.
     */
    fun pendingBackAction(): BackAction = when {
        fullscreenView != null -> BackAction.EXIT_ELEMENT_FULLSCREEN
        isMediaFullscreen -> BackAction.EXIT_MEDIA_FULLSCREEN
        isOmniboxFocused -> BackAction.BLUR_OMNIBOX
        find.active -> BackAction.CLOSE_FIND
        overlay != Overlay.NONE -> BackAction.DISMISS_OVERLAY
        isImmersive -> BackAction.EXIT_IMMERSIVE
        activeTab?.canGoBack == true -> BackAction.GO_BACK
        else -> BackAction.LEAVE_BROWSER
    }

    /** Performs [pendingBackAction]; false means the browser has nothing left to do. */
    fun handleBack(): Boolean {
        when (pendingBackAction()) {
            BackAction.EXIT_ELEMENT_FULLSCREEN -> onExitElementFullscreen()
            BackAction.EXIT_MEDIA_FULLSCREEN -> exitMediaFullscreen()
            BackAction.BLUR_OMNIBOX -> blurOmnibox()
            BackAction.CLOSE_FIND -> closeFind()
            BackAction.DISMISS_OVERLAY -> dismissOverlay()
            BackAction.EXIT_IMMERSIVE -> exitImmersive()
            BackAction.GO_BACK -> goBack()
            BackAction.LEAVE_BROWSER -> return false
        }
        return true
    }

    fun goBack(): Boolean {
        val webView = activeTab?.webView ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        showChrome()
        return true
    }

    fun goForward() {
        activeTab?.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    /**
     * Loads the page again.
     *
     * A failed navigation can leave the WebView with no history entry to reload — reloading
     * nothing is why Retry sometimes appeared to do nothing at all — so the address is loaded
     * outright whenever there is no document to refresh.
     */
    fun reload() {
        val tab = activeTab ?: return
        tab.errorMessage = null
        tab.isLoading = true
        val webView = tabManager.webViewFor(tab)
        val current = webView.url
        if (current.isNullOrBlank() || current == "about:blank") {
            if (tab.url.isNotBlank()) webView.loadUrl(tab.url) else tab.isLoading = false
        } else {
            webView.reload()
        }
    }

    /**
     * Rebuilds a tab whose renderer Android has taken away.
     *
     * This is not a page error and must not be reported as one: the document is gone through no
     * fault of the site, and the recovery is to build a new WebView and load the same address.
     * Reading the tab's WebView is what does that, so for the tab on screen it happens by
     * itself; the rest are left to be rebuilt when they are next looked at.
     */
    private fun recoverFromRendererLoss(tab: Tab, crashed: Boolean) {
        tabManager.discardDeadWebView(tab)
        tab.errorMessage = null
        if (tab.id == tabManager.activeTabId) {
            exitMediaFullscreen()
            tabManager.webViewFor(tab)
            if (crashed) snack("The page crashed and was reloaded")
        }
    }

    fun stopLoading() {
        activeTab?.webView?.stopLoading()
    }

    /** Desktop mode is per tab and survives navigation within that tab. */
    /**
     * Desktop mode is per tab and survives navigation within that tab.
     *
     * Changing the user-agent alone is not enough: a responsive site reads the page's own
     * viewport meta and would still lay out for a phone. [DesktopMode] widens the layout
     * viewport to match, which is what actually produces the desktop version of a site.
     */
    fun toggleDesktopMode() {
        val tab = activeTab ?: return
        val webView = runCatching { tabManager.webViewFor(tab) }.getOrNull() ?: return
        tab.isDesktopMode = !tab.isDesktopMode
        runCatching {
            WebViewConfigurator.configure(webView, settings.value, tab.isDesktopMode)
            desktopMode.apply(webView, tab, tab.isDesktopMode)
            webView.reload()
        }.onFailure { snack("Couldn't switch this site") }
        snack(if (tab.isDesktopMode) "Desktop site" else "Mobile site")
        scheduleSessionSave()
    }

    // ---- Omnibox ------------------------------------------------------------

    private var suggestionJob: Job? = null

    fun focusOmnibox(text: String? = null) {
        omniboxText = text ?: activeTab?.url.orEmpty()
        isOmniboxFocused = true
        showChrome()
        // Opening the address bar is not typing in it. Nothing is suggested until the text
        // actually changes, so focusing never puts browsing history on screen by itself.
        suggestionJob?.cancel()
        suggestions = emptyList()
    }

    fun blurOmnibox() {
        isOmniboxFocused = false
        suggestions = emptyList()
    }

    fun onOmniboxTextChanged(text: String) {
        omniboxText = text
        updateSuggestions(text)
    }

    private fun updateSuggestions(query: String) {
        suggestionJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // Nothing typed, nothing suggested: no list, and no query against history either,
            // so an empty omnibox costs neither a database read nor a surface to draw.
            suggestions = emptyList()
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

    /**
     * Moving the toolbar resizes the page, so it is never done while the page is moving.
     *
     * The policy decides what the toolbar should do; this applies that decision only once
     * scrolling has actually stopped. One resize at rest is invisible. The same resize during a
     * fling re-lays out and re-rasters the page under a moving compositor, which is precisely
     * what tears — and doing it repeatedly, as an unfiltered per-frame rule does, tears
     * continuously.
     */
    /**
     * Whether the keyboard is up.
     *
     * While it is, the toolbar stays put. Opening the keyboard over a page input makes the
     * WebView scroll the field into view, and that scroll is not the user asking for more
     * room — letting it collapse the toolbar would take the address bar away mid-edit and
     * resize the page a second time just as the field was being revealed.
     */
    var isKeyboardVisible by mutableStateOf(false)
        private set

    fun onKeyboardVisibilityChanged(visible: Boolean) {
        if (isKeyboardVisible == visible) return
        isKeyboardVisible = visible
        if (visible) {
            chromeSettleJob?.cancel()
            chromeSettleJob = null
            showChrome()
        }
    }

    /**
     * The measured height of the chrome, which is the exact distance a page moves when it
     * appears or disappears. Reported by the layout rather than assumed, because it is a toolbar
     * plus whatever the system bars take, and that differs on every phone.
     */
    var chromeHeightPx by mutableStateOf(0f)

    fun onPageScrolled(delta: Int, scrollY: Int, atBottom: Boolean = false) {
        if (!settings.value.hideBarsOnScroll || isOmniboxFocused || isImmersive) return
        if (isKeyboardVisible) return
        chromeScrollPolicy.onScroll(delta, scrollY, atBottom)

        if (chromeScrollPolicy.desired == chromeVisible) {
            chromeSettleJob?.cancel()
            chromeSettleJob = null
            return
        }
        // Restarted by every scroll event, so it only fires in the quiet after the last one.
        chromeSettleJob?.cancel()
        chromeSettleJob = viewModelScope.launch {
            delay(CHROME_SETTLE_MS)
            activeTab?.chromeVisible = chromeScrollPolicy.desired
            // The page is about to be resized by exactly the chrome's height, and will report
            // that as a scroll. Saying so here is what stops it being read as the user's.
            chromeScrollPolicy.onChromeChanged(chromeHeightPx)
        }
    }

    /**
     * Brings the chrome back and forgets any pending move.
     *
     * Called for every reason other than scrolling: navigation, tab creation, tab selection,
     * entering and leaving fullscreen, focusing the omnibox. The invariant this maintains is
     * that the only thing that can hide the chrome is the user scrolling a page that scrolls,
     * and anything else at all restores it.
     */
    private fun showChrome(tab: Tab? = activeTab) {
        chromeSettleJob?.cancel()
        chromeSettleJob = null
        chromeScrollPolicy.reset(visible = true)
        tab?.chromeVisible = true
    }

    fun enterImmersive() {
        isImmersive = true
        overlay = Overlay.NONE
        blurOmnibox()
    }

    fun exitImmersive() {
        if (isMediaFullscreen) {
            exitMediaFullscreen()
            return
        }
        isImmersive = false
        showChrome()
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

    // ---- Browser-owned media fullscreen -------------------------------------

    /**
     * What the transport is controlling.
     *
     * While a receiver has the media, its position, duration and playback state are the true
     * ones and the page's are stale — so they are merged over the page's report rather than
     * kept in a second place. Everything else, including whether the stream is live and what
     * shape it is, still comes from the page, because the page is what knows. This is what lets
     * one set of controls drive either end without knowing which end it is driving.
     */
    val media: MediaState
        get() {
            val page = activeTab?.media ?: MediaState.NONE
            val remote = castState
            if (!remote.isPlayingRemotely) return page
            return page.copy(
                isPlaying = remote.looksPlaying,
                positionMs = remote.positionMs,
                durationMs = if (remote.durationMs > 0) remote.durationMs else page.durationMs,
                volume = remote.volume,
                isMuted = remote.isMuted,
            )
        }

    /**
     * True when there is something worth offering the media control for.
     *
     * Audio counts. There is no picture to enlarge, but there is a transport worth having and
     * a stream worth casting, and a podcast page is exactly where sending it to a speaker is
     * the point.
     */
    val hasPlayableMedia: Boolean get() = media.hasMedia

    /**
     * Presents the page's main video full screen using the browser's own machinery rather than
     * the page's. Nothing here depends on the site exposing a fullscreen control, on its player
     * supporting one, or on an embedding iframe being marked `allowfullscreen`.
     */
    fun enterMediaFullscreen() {
        val tab = activeTab ?: return
        val webView = tab.webView ?: return
        if (!tab.media.hasMedia) {
            // While a receiver has the media there is still something to control, even on a
            // tab that is playing nothing itself — including the way to stop it.
            if (castState.isActive) {
                overlay = Overlay.NONE
                blurOmnibox()
                closeFind()
                isMediaFullscreen = true
                isImmersive = true
                return
            }
            mediaAgent.scan(webView)
            snack("Nothing is playing on this page")
            return
        }
        overlay = Overlay.NONE
        blurOmnibox()
        closeFind()
        isMediaFullscreen = true
        isImmersive = true
        lockLandscapeForMedia = shouldHoldLandscape(tab.media)
        // Asking for fullscreen *is* the user gesture. Without this the overlay's play button
        // would be silently refused on pages that have not been touched yet, because a script
        // driven call does not carry gesture provenance of its own.
        webView.settings.mediaPlaybackRequiresUserGesture = false
        mediaAgent.enterFullscreen(webView, mediaFit)
    }

    fun exitMediaFullscreen() {
        if (!isMediaFullscreen) return
        isMediaFullscreen = false
        isImmersive = false
        lockLandscapeForMedia = false
        showChrome()
        activeTab?.webView?.let { webView ->
            mediaAgent.exitFullscreen(webView)
            webView.settings.mediaPlaybackRequiresUserGesture = !settings.value.allowAutoplay
        }
    }

    fun toggleMediaFullscreen() {
        if (isMediaFullscreen) exitMediaFullscreen() else enterMediaFullscreen()
    }

    /** Switches between letterboxing the stream and filling the display with it. */
    fun toggleMediaFit() {
        mediaFit = mediaFit.toggled()
        activeTab?.webView?.let { mediaAgent.setFit(it, mediaFit) }
        snack(if (mediaFit == MediaFit.COVER) "Filling the screen" else "Fitting the whole frame")
    }

    fun toggleMediaPlayback() {
        if (castState.isPlayingRemotely) {
            // What the button is showing, so pressing it does what it says rather than what a
            // half-second-old status update thought.
            if (castState.looksPlaying) cast?.pause() else cast?.play()
            return
        }
        activeTab?.webView?.let { mediaAgent.togglePlayback(it) }
    }

    fun toggleMediaMute() {
        if (castState.isPlayingRemotely) {
            cast?.toggleMute()
            return
        }
        activeTab?.webView?.let { mediaAgent.toggleMute(it) }
    }

    fun seekMedia(positionMs: Long) {
        if (castState.isPlayingRemotely) {
            cast?.seekTo(positionMs)
            return
        }
        activeTab?.webView?.let { mediaAgent.seekTo(it, positionMs) }
    }

    /** Returns to the live edge of a stream the viewer has scrubbed back from. */
    fun jumpToLiveEdge() {
        val state = media
        if (!state.isLive) return
        if (castState.isPlayingRemotely) {
            cast?.seekToLiveEdge()
            return
        }
        activeTab?.webView?.let { mediaAgent.seekTo(it, state.seekableEndMs) }
    }

    fun setMediaVolume(volume: Float) {
        if (castState.isPlayingRemotely) {
            cast?.setVolume(volume)
            return
        }
        activeTab?.webView?.let { mediaAgent.setVolume(it, volume) }
    }

    // ---- Casting -----------------------------------------------------------

    private var cast: MediaReceivers? = null

    var castState by mutableStateOf(CastState())
        private set

    /** True while the device picker is on screen; discovery runs only for as long as it is. */
    var castPickerOpen by mutableStateOf(false)
        private set

    /** Worth showing the button at all: the framework is up and something is nearby. */
    val canOfferCast: Boolean get() = castState.canOffer

    val isCasting: Boolean get() = castState.isActive

    /**
     * Opens the device picker, having first established that there is anything worth sending.
     *
     * The refusal happens here rather than after a device has been chosen, because "this cannot
     * be cast" is a fact about the page, not about the television — making the user pick a
     * device to be told that would be a worse way of saying the same thing.
     */
    /**
     * What could be sent, taking both the element's own source and the manifest the page was
     * seen fetching into account.
     */
    private fun castVerdict(): CastVerdict = CastEligibility.evaluate(
        media = media,
        pageUrl = activeTab?.url.orEmpty(),
        observedManifest = activeTab?.observedManifest,
        observedMediaFile = activeTab?.observedMediaFile,
        observedReferer = activeTab?.observedManifestReferer,
    )

    /**
     * Why this page's media cannot be sent anywhere, or empty when it can.
     *
     * Shown inside the picker rather than as a message that dismisses itself, because the
     * answer to every one of these sentences is the mirroring row underneath it.
     */
    var castNote by mutableStateOf("")
        private set

    fun openCastPicker() {
        val controller = cast ?: return
        castNote = when (val verdict = castVerdict()) {
            is CastVerdict.NothingPlaying -> "Nothing is playing to cast."
            is CastVerdict.Refused -> verdict.reason
            is CastVerdict.Castable -> ""
        }
        castPickerOpen = true
        controller.startDiscovery(active = true)
    }

    fun closeCastPicker() {
        castPickerOpen = false
        // Back to the quiet watch: an active scan is expensive and nothing is looking at it.
        updateCastDiscovery()
    }

    /**
     * True between choosing a device and handing it the media.
     *
     * The load is triggered by an explicit intention rather than by inferring one from the
     * session's state: a connected session with nothing playing is also what the browser sees
     * after a video ends, and loading again there would restart something the viewer had
     * finished with.
     */
    private var castHandoffPending = false

    /**
     * Hands the user to Android's own screen-casting control.
     *
     * Mirroring is a different thing from casting a stream — the whole screen goes, and the
     * phone keeps decoding it — but it is the only thing some receivers accept, and saying so
     * is better than a picker that searches for ever.
     */
    fun mirrorScreen() {
        castPickerOpen = false
        updateCastDiscovery()
        if (host?.openCastSettings() != true) snack("This phone has no screen-casting setting")
    }

    fun connectCast(deviceId: String) {
        castPickerOpen = false
        castHandoffPending = true
        updateCastDiscovery()
        cast?.connect(deviceId)
    }

    /**
     * Sends what the page is playing to the receiver that has just connected.
     *
     * The reachability check happens here, between connecting and loading, and it is the whole
     * reason this is not a one-liner. The receiver fetches the stream itself, with none of this
     * browser's cookies — so a video the phone plays because the user is signed in can be a
     * sign-in page to the television. Asking first turns that into a sentence rather than a
     * black screen nobody can explain.
     */
    private fun sendToReceiver() {
        val controller = cast ?: return
        val verdict = castVerdict()
        castHandoffPending = false
        if (verdict !is CastVerdict.Castable) {
            val reason = (verdict as? CastVerdict.Refused)?.reason ?: "Nothing is playing to cast"
            controller.disconnect()
            snack(reason)
            return
        }
        val startAt = media.positionMs
        // The address that will actually be sent, which is not the same for both receivers: a
        // Cast receiver gets the manifest, a DLNA television gets a plain file.
        val source =
            if (controller.prefersPlainFile) verdict.forFilePlayer else verdict.forAdaptiveReceiver
        // When the phone is going to fetch the stream itself — as the page, with its cookies —
        // there is nothing to ask. The question the probe answers is whether a stranger could
        // fetch this, and no stranger is going to.
        val receiverFetchesItself = !controller.willRelay(verdict)
        viewModelScope.launch {
            val reachable = if (receiverFetchesItself) {
                withContext(Dispatchers.IO) {
                    CastPreflight.check(
                        source.url,
                        rangedRequest = source.format == StreamFormat.PROGRESSIVE,
                    )
                }
            } else {
                CastPreflight.Result.Reachable(null)
            }
            // A refusal here has to be worth the words. Only an answer that actually settles
            // the question stops the attempt: an inconclusive probe — a timeout, a server
            // error, a route that behaves differently from this device — is not evidence about
            // what a television on its own connection would get, and refusing on it is how a
            // browser ends up declining media that would have played perfectly well. When the
            // probe cannot tell, the receiver is asked and its own answer is reported.
            // Being unable to fetch something is only a refusal when nobody else can fetch it
            // either. Where the phone can stand in — it is the client the site is already
            // serving, signed in and referred by the right page — a receiver's inability to
            // fetch the address is a reason to relay rather than a reason to stop.
            val phoneCanStandIn = controller.canRelay
            val refusal = when (reachable) {
                is CastPreflight.Result.NeedsSignIn ->
                    if (phoneCanStandIn) null
                    else "This video needs you to be signed in, and the TV can't sign in for you."
                is CastPreflight.Result.Forbidden ->
                    if (phoneCanStandIn) null
                    else "This site only serves this video to the page it came from, so a TV " +
                        "can't fetch it. Screen mirroring will show it."
                is CastPreflight.Result.NotMedia ->
                    "That address answers with a web page rather than a video."
                is CastPreflight.Result.Missing ->
                    "That video's address has expired."
                is CastPreflight.Result.Inconclusive, is CastPreflight.Result.Reachable -> null
            }
            // The phone stops rendering. Not a pause that a player's own script can undo a
            // second later — the page is put into remote mode and held there for as long as
            // the receiver has the media.
            enterRemotePlayback()
            controller.load(
                verdict,
                startAt,
                receiverCanFetch = reachable is CastPreflight.Result.Reachable,
            )
        }
    }

    /** Ends the session and brings playback back to the phone where the receiver left it. */
    fun stopCasting() {
        castHandoffPending = false
        cast?.disconnect()
    }

    // ---- The handoff -------------------------------------------------------

    /**
     * Which end is rendering, and the only thing that decides it.
     *
     * `castState.isPlayingRemotely` is the source of truth; this is the browser's record of
     * having *acted* on it, so the two transitions each happen exactly once. Everything else —
     * the page being held paused, the controls being pointed at the receiver, the banner — is
     * downstream of these two calls rather than a second opinion about the same question.
     */
    private var playingRemotely = false

    /**
     * Which tab had its player handed over.
     *
     * Not "whichever tab is in front now": a viewer is free to open another tab while the
     * television plays, and releasing the wrong page would leave the cast tab silenced for the
     * rest of its life while the new one is pinned for no reason.
     */
    private var remoteTabId: String? = null

    /**
     * The page stops rendering.
     *
     * A single pause is not enough and never was: a player whose own script calls `play()` a
     * moment later leaves two copies of the video running in the same room, which is exactly
     * what the browser was doing. Remote mode pauses the element, mutes it, and holds it there
     * against the page's own attempts to start it again.
     */
    private fun enterRemotePlayback() {
        if (playingRemotely) return
        val tab = activeTab ?: return
        playingRemotely = true
        remoteTabId = tab.id
        tab.webView?.let { mediaAgent.setRemote(it, true) }
    }

    /**
     * The page takes playback back, at the position the receiver reached.
     *
     * A position of zero means the receiver finished the video rather than handing it over
     * mid-play, and finishing is not a reason to start it again on the phone — so the page is
     * released and left where it is.
     */
    private fun leaveRemotePlayback(positionMs: Long) {
        if (!playingRemotely) return
        playingRemotely = false
        val tab = tabManager.tabs.firstOrNull { it.id == remoteTabId }
        remoteTabId = null
        val webView = tab?.webView ?: return
        mediaAgent.setRemote(webView, false)
        if (positionMs <= 0) return
        if (!media.isLive) mediaAgent.seekTo(webView, positionMs)
        mediaAgent.play(webView)
    }



    /**
     * Everything that follows from the receiver changing its mind.
     *
     * The single place the browser reacts to a cast session, so that "what the receiver says"
     * and "what the browser does about it" cannot drift apart: the handoff between the two
     * players, the pending load, a failure's message, and whether discovery is still worth
     * running. Nothing else observes the session directly.
     */
    private fun onCastState(next: CastState) {
        val wasPlayingRemotely = castState.isPlayingRemotely
        castState = next
        // The one place the phone and the receiver swap roles. Driven by what the receiver
        // reports rather than by what the browser asked for, so a session that ends on the
        // television — stopped with its own remote, switched off, timed out — gives playback
        // back here just as a deliberate disconnection does.
        if (!wasPlayingRemotely && next.isPlayingRemotely) enterRemotePlayback()
        // A session that is reconnecting has not given anything back yet, so the page stays
        // pinned through the gap rather than playing for the two seconds it takes to return.
        if (wasPlayingRemotely && !next.isPlayingRemotely && !next.isReconnecting) {
            leaveRemotePlayback(next.positionMs)
        }
        if (castHandoffPending && next.stage == CastStage.CONNECTED) {
            castHandoffPending = false
            sendToReceiver()
        }
        if (next.stage == CastStage.FAILED) {
            castHandoffPending = false
            if (next.message.isNotBlank()) snack(next.message)
        }
        if (next.stage == CastStage.IDLE) castHandoffPending = false
        updateCastDiscovery()
    }

    /**
     * Drives the cast state directly, for tests that need a receiver without one being nearby.
     * The path is the same one the controller's callback uses — deliberately, because what is
     * worth testing is everything that path does, not the assignment at the start of it.
     */
    internal fun applyCastStateForTest(state: CastState) = onCastState(state)

    private fun attachCast(context: Context) {
        if (cast != null) return
        val controller = MediaReceivers(context.applicationContext)
        controller.onStateChanged = { next -> onCastState(next) }
        controller.initialise()
        cast = controller
        updateCastDiscovery()
    }

    /**
     * Only a stream we know to be wider than it is tall earns the rotation. A live stream often
     * reports no dimensions until its first frame decodes, and turning the phone on a guess —
     * then turning it back a moment later — is worse than waiting for that frame.
     */
    internal fun shouldHoldLandscape(state: MediaState): Boolean =
        settings.value.rotateForVideo && state.prefersLandscape

    internal fun onMediaState(tab: Tab, state: MediaState) {
        tab.media = state
        // A stream whose dimensions only arrive after playback starts still gets the rotation.
        if (isMediaFullscreen && tab.id == tabManager.activeTabId) {
            lockLandscapeForMedia = shouldHoldLandscape(state)
        }
        if (tab.id == tabManager.activeTabId) updateCastDiscovery()
    }

    /**
     * Keeps discovery running exactly as long as it is worth running.
     *
     * A quiet watch while there is something castable on the page, so the button can appear
     * honestly rather than promising to go and look; nothing at all otherwise. The picker
     * turns it up to an active scan for as long as it is on screen.
     */
    private fun updateCastDiscovery() {
        val controller = cast ?: return
        // A framework that was not ready the first time — Play Services updating, or disabled
        // and since re-enabled — gets another chance whenever there is a reason to care. The
        // call is a no-op once it has succeeded.
        controller.initialise()
        when {
            castPickerOpen -> controller.startDiscovery(active = true)
            media.hasMedia || castState.isActive -> controller.startDiscovery(active = false)
            else -> controller.stopDiscovery()
        }
    }

    // ---- Find in page -------------------------------------------------------

    fun openFind() {
        find = FindState(active = true)
        showChrome()
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
        // The star flips at once; waiting on a disk write to acknowledge a tap is the kind of
        // lag that makes an app feel cheap.
        val url = tab.url
        val wanted = !bookmarkedUrls.contains(url)
        pendingBookmarks[url] = wanted
        publishBookmarks()

        viewModelScope.launch {
            // Serialised, so two quick taps reach the database in the order they were made.
            val added = bookmarkWrites.withLock {
                runCatching { repository.toggleBookmark(url, tab.displayTitle) }.getOrNull()
            }
            if (added == null) {
                pendingBookmarks.remove(url)
                publishBookmarks()
                snack("Couldn't save that favourite")
                return@launch
            }
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

    /**
     * Every preference change goes through here.
     *
     * Storage can fail — a full disk, a corrupted preferences file, a WebView that rejects a
     * setting — and none of those are worth losing the user's tabs over. A failed change is
     * reported and the browser carries on with the value it already had.
     */
    private fun editSettings(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { snack("Couldn't save that setting") }
        }
    }

    fun setTheme(mode: ThemeMode) = editSettings { settingsStore.setTheme(mode) }

    fun setSearchEngine(engine: SearchEngine) = editSettings { settingsStore.setSearchEngine(engine) }

    fun setHomePage(url: String) = editSettings { settingsStore.setHomePage(url) }

    /**
     * Applies a preference and, where it changes how a page behaves, replays it onto the live
     * tabs immediately rather than waiting for the next navigation.
     */
    fun setToggle(toggle: SettingToggle, value: Boolean) {
        editSettings {
            when (toggle) {
                SettingToggle.JAVASCRIPT -> settingsStore.setJavaScript(value)
                SettingToggle.DESKTOP_DEFAULT -> settingsStore.setDesktopDefault(value)
                SettingToggle.BLOCK_3P_COOKIES -> settingsStore.setBlockThirdPartyCookies(value)
                SettingToggle.DNT -> settingsStore.setDoNotTrack(value)
                SettingToggle.AUTOPLAY -> settingsStore.setAutoplay(value)
                SettingToggle.BLOCK_ADS -> settingsStore.setBlockAds(value)
                SettingToggle.BLOCK_POPUPS -> settingsStore.setBlockPopups(value)
                SettingToggle.ROTATE_FOR_VIDEO -> {
                    settingsStore.setRotateForVideo(value)
                    if (!value) lockLandscapeForMedia = false
                }
                SettingToggle.RESTORE_TABS -> {
                    settingsStore.setRestoreTabs(value)
                    if (!value) persistence.clear()
                }
                SettingToggle.SAVE_HISTORY -> settingsStore.setSaveHistory(value)
                SettingToggle.AUTO_IMMERSIVE -> settingsStore.setAutoImmersive(value)
                SettingToggle.HIDE_ON_SCROLL -> {
                    settingsStore.setHideBarsOnScroll(value)
                    if (!value) showChrome()
                }
                SettingToggle.CLEAR_ON_EXIT -> settingsStore.setClearOnExit(value)
            }
            reapplySettingsToLiveTabs()
        }
    }

    /** One tab refusing a setting must not stop the others from receiving it. */
    private fun reapplySettingsToLiveTabs() {
        val current = settings.value
        tabManager.tabs.forEach { tab ->
            val webView = tab.webView ?: return@forEach
            runCatching { WebViewConfigurator.configure(webView, current, tab.isDesktopMode) }
            runCatching { cosmeticFilter.apply(webView, tab, current.blockAds) }
            runCatching { inPageGuard.apply(webView, tab, current.blockAds) }
        }
    }

    fun clearBrowsingData() {
        editSettings {
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
        val webView = SlateWebView(context)
        WebViewConfigurator.configure(webView, settings.value, tab.isDesktopMode)

        webView.webViewClient = SlateWebViewClient(
            tab = tab,
            host = requireHost(),
            pageStarted = { target, url ->
                target.url = url
                target.isLoading = true
                target.errorMessage = null
                target.progress = 0.02f
                // A new document has no media, and no blocked requests, until it says so.
                target.media = MediaState.NONE
                target.blockedCount = 0
                // A new document is a new certificate decision; an exception never carries over.
                target.certificateOverridden = false
                target.dialogsSuppressed = false
                target.dialogsShown = 0
                popupGuard.clear()
                // Element-hiding rules are chosen by the site being visited, so they follow the
                // navigation: applied to the document now loading, and installed at document
                // start for the frames it is about to create.
                target.webView?.let { view ->
                    cosmeticFilter.apply(view, target, settings.value.blockAds)
                }
                // A new document has scrolled nowhere, so its chrome starts where it should.
                showChrome(target)
                if (target.id == tabManager.activeTabId) exitMediaFullscreen()
            },
            pageFinished = { target, url, title ->
                target.url = url
                target.title = title
                target.isLoading = false
                target.progress = 1f
                target.canGoBack = target.webView?.canGoBack() == true
                target.canGoForward = target.webView?.canGoForward() == true
                target.webView?.let { webView ->
                    mediaAgent.injectIntoMainFrame(webView)
                    desktopMode.reassert(webView, target.isDesktopMode)
                    // A new document is a new agent with no memory of the handoff. Without
                    // this, navigating while a receiver is playing starts the phone's copy
                    // again and the room hears both.
                    if (playingRemotely && target.id == remoteTabId) {
                        mediaAgent.setRemote(webView, true)
                    }
                }
                if (settings.value.saveHistory && target.errorMessage == null) {
                    viewModelScope.launch { repository.recordVisit(url, title) }
                }
                scheduleSessionSave()
            },
            onSslPrompt = { message, proceed, cancel ->
                sslPrompt = SslPrompt(message, proceed, cancel)
            },
            blocker = contentBlocker,
            blockingEnabled = { settings.value.blockAds },
            onRequestBlocked = { tab.blockedCount++ },
            policy = navigationPolicy,
            onNavigationBlocked = { url, reason -> onNavigationBlocked(url, reason) },
            onConfirmExternal = { url, label -> externalLaunch = ExternalLaunch(url, label) },
            onRendererGone = { target, crashed -> recoverFromRendererLoss(target, crashed) },
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
            requestDownload(webView, url, userAgent, disposition, mimeType)
        }
        downloads?.bridge()?.install(webView)

        webView.navigationListener = navigationListenerFor(tab)
        webView.userActivation = userActivation

        // Long press offers actions for links and images, and declines everything else so text
        // selection, form fields and the platform's own text menu behave exactly as usual.
        webView.setOnLongClickListener {
            val target = tab.webView ?: return@setOnLongClickListener false
            LinkContextResolver.resolve(target) { resolved ->
                if (resolved != null && resolved.isActionable) linkContext = resolved
            }
            LinkContextResolver.isActionable(target)
        }

        mediaAgent.install(webView)
        focusVisibility.install(webView)
        desktopMode.apply(webView, tab, tab.isDesktopMode)
        cosmeticFilter.apply(webView, tab, settings.value.blockAds)
        inPageGuard.apply(webView, tab, settings.value.blockAds)
        mediaAgent.bridge(
            onState = { state -> onMediaState(tab, state) },
            onNavigationHint = { suppress -> onNavigationHint(tab, suppress) },
            onEnterResult = { success ->
                // Audio declines fullscreen by design — there is nothing to put on the screen —
                // and the controls stay up over a plain backdrop so it can still be driven and
                // cast. Only a video that could not be reached is a failure worth reporting.
                if (!success && isMediaFullscreen && !media.audioOnly) {
                    exitMediaFullscreen()
                    snack("Couldn't make this video fullscreen")
                }
            },
        ).install(webView)

        webView.setFindListener { activeIndex, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                find = find.copy(matches = numberOfMatches, index = if (numberOfMatches == 0) 0 else activeIndex + 1)
            }
        }

        webView.setOnScrollChangeListener { view, _, scrollY, _, oldScrollY ->
            // Whether the page has anything left below. The engine's own answer, so it accounts
            // for zoom, dynamic content and pages shorter than the window.
            onPageScrolled(scrollY - oldScrollY, scrollY, atBottom = !view.canScrollVertically(1))
        }

        return webView
    }

    /**
     * Turns an unused horizontal swipe into history navigation, with the drag distance driving
     * the on-screen arrow so the gesture can be abandoned by dragging back.
     */
    private fun navigationListenerFor(tab: Tab) = object : NavigationGestureListener {
        override fun canNavigate(direction: NavigationDirection): Boolean {
            val webView = tab.webView ?: return false
            return if (direction == NavigationDirection.BACK) webView.canGoBack() else webView.canGoForward()
        }

        override fun onGestureStart(direction: NavigationDirection) {
            navGesture = NavGesture(direction, 0f)
        }

        override fun onGestureProgress(distancePx: Float) {
            navGesture = navGesture?.copy(progress = progressOf(distancePx))
        }

        override fun onGestureFinish(distancePx: Float) {
            val committed = progressOf(distancePx) >= 1f
            val direction = navGesture?.direction
            navGesture = null
            if (!committed) return
            if (direction == NavigationDirection.BACK) goBack() else goForward()
        }

        override fun onGestureCancel() {
            navGesture = null
        }

        private fun progressOf(distancePx: Float) =
            (abs(distancePx) / gestureCommitDistancePx).coerceIn(0f, 1f)
    }

    /**
     * A page agent found something under the finger that pans horizontally by itself, so the
     * navigation gesture must stand down for this touch.
     */
    /**
     * A navigation the browser refused. Reported once per page rather than per attempt: a
     * redirect loop can try dozens of times, and a notice per attempt is its own kind of abuse.
     */
    private fun onNavigationBlocked(url: String, reason: String) {
        activeTab?.blockedCount = (activeTab?.blockedCount ?: 0) + 1
        if (reason == "redirect") {
            val host = UrlUtils.displayHost(url)
            snack("Blocked a redirect to $host", "Allow") {
                userActivation.recordTouch()
                load(url)
            }
        }
    }

    /**
     * A download the page has started.
     *
     * Nothing is fetched until the browser knows what it would be saving. A file that runs when
     * it is opened — an installable package, a script — is worth a sentence and a decision,
     * named by what it actually is rather than by what the page called it, because a download
     * the user did not understand is the last step of most attacks that get this far. Ordinary
     * files are not interrupted: a browser that asks about every photo teaches people to say
     * yes without reading.
     */
    private fun requestDownload(
        webView: WebView,
        url: String,
        userAgent: String?,
        disposition: String?,
        mimeType: String?,
    ) {
        val coordinator = downloads ?: return
        val isBlob = url.startsWith("blob:")
        if (!isBlob && !UrlSafety.isWeb(url) && !url.startsWith("data:")) {
            snack("That download isn't from a web address")
            return
        }
        val resolved = DownloadNaming.resolve(url, disposition, mimeType)
        val start = {
            if (isBlob) {
                coordinator.startBlobDownload(webView, url, mimeType)
                snack("Preparing download…")
            } else {
                coordinator.enqueue(url, userAgent, disposition, mimeType)
            }
        }
        if (resolved.isExecutable || resolved.isDisguised) {
            pendingDownload = RiskyDownload(
                fileName = resolved.fileName,
                host = UrlUtils.displayHost(webView.url.orEmpty()),
                disguised = resolved.isDisguised,
                onConfirm = start,
            )
        } else {
            start()
        }
    }

    /** A download waiting on the user to say whether they meant it. */
    var pendingDownload by mutableStateOf<RiskyDownload?>(null)
        private set

    fun confirmDownload() {
        val request = pendingDownload ?: return
        pendingDownload = null
        request.onConfirm()
    }

    fun dismissDownload() {
        pendingDownload = null
    }

    fun confirmExternalLaunch() {
        val request = externalLaunch ?: return
        externalLaunch = null
        if (host?.openExternally(request.url) != true) snack("No app can open this link")
    }

    fun dismissExternalLaunch() {
        externalLaunch = null
    }

    fun dismissLinkContext() {
        linkContext = null
    }

    fun copyToClipboard(text: String, label: String) {
        val clipboard = activityContext?.getSystemService(ClipboardManager::class.java)
            ?: return snack("Couldn't copy that")
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 and later shows its own copy confirmation; a second one would be noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snack("Copied")
    }

    /** Saves an image the user pressed and held, through the same path as any other download. */
    fun saveImage(url: String) {
        val webView = activeTab?.webView
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            downloads?.enqueue(url, webView?.settings?.userAgentString, null, null)
            return
        }
        downloads?.enqueue(url, webView?.settings?.userAgentString, null, null)
    }

    private fun onNavigationHint(tab: Tab, suppress: Boolean) {
        (tab.webView as? SlateWebView)?.suppressNavigationGesture = suppress
    }

    /**
     * A page asked to open a window. The transport WebView must be handed back synchronously,
     * so the tab is created immediately and only foregrounded when the user meant it.
     */
    /**
     * A page asked to open a window.
     *
     * A window the user asked for opens and takes focus. A window the page opened on its own is
     * refused outright rather than parked in the background: pop-unders and redirect chains
     * depend on the window existing at all, and one the user never sees is one they cannot
     * close. The snackbar keeps the decision reversible, so a site that genuinely needs a
     * popup — a payment flow, an OAuth window — is one tap away.
     */
    private fun openWindow(resultMsg: Message, isUserGesture: Boolean): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        // A gesture flag is not evidence of a distinct gesture: one tap can be replayed into a
        // dozen window.open calls that all report one. Only the first thing to claim the touch
        // gets a window.
        val permitted = isUserGesture && navigationPolicy.allowWindow(null, settings.value.blockAds)
        if (!permitted && settings.value.blockPopups) {
            activeTab?.let { it.blockedCount++ }
            popupGuard.refuse(resultMsg) { blockedUrl ->
                snack("Pop-up blocked: ${UrlUtils.displayHost(blockedUrl)}", "Show") {
                    openInNewTabAndSwitch(blockedUrl)
                }
            }
            return true
        }

        // Even a window the user did ask for opens behind the page they are on. A tap on a
        // play button that also opens a tab should not take them away from the video.
        val newTab = Tab(desktopMode = settings.value.desktopModeByDefault)
        val webView = buildWebView(newTab)
        newTab.webView = webView
        transport.webView = webView
        resultMsg.sendToTarget()
        tabManager.adoptTab(newTab)
        snack("Opened in background", "Switch") { switchTo(newTab.id) }
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
        override fun openCastSettings(): Boolean = false
        override fun toast(message: String) = Unit
        override fun snack(message: String, actionLabel: String?, action: (() -> Unit)?) = Unit
    }

    private companion object {
        /** Long enough that a fling's trailing frames do not count as "stopped". */
        const val CHROME_SETTLE_MS = 160L
        const val THUMBNAIL_WIDTH = 360
    }
}

/** An in-flight horizontal navigation drag. */
data class NavGesture(val direction: NavigationDirection, val progress: Float)

/** A page asking to hand the user to another app, pending their decision. */
data class ExternalLaunch(val url: String, val label: String)

/** A download that runs code when opened, held until the user confirms it. */
data class RiskyDownload(
    val fileName: String,
    val host: String,
    val disguised: Boolean,
    val onConfirm: () -> Unit,
)
