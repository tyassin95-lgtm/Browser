package com.slate.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.slate.browser.BrowserViewModel
import com.slate.browser.Overlay
import com.slate.browser.data.Suggestion
import com.slate.browser.web.MediaFit
import com.slate.browser.ui.components.FindBar
import com.slate.browser.ui.components.FullscreenHost
import com.slate.browser.ui.components.LandscapeBar
import com.slate.browser.ui.components.LoadingLine
import com.slate.browser.ui.components.MediaFullscreenOverlay
import com.slate.browser.ui.components.MediaOverlayMode
import com.slate.browser.ui.components.MenuActions
import com.slate.browser.ui.components.MenuSheet
import com.slate.browser.ui.components.Omnibox
import com.slate.browser.ui.components.PortraitBar
import com.slate.browser.ui.components.SuggestionList
import com.slate.browser.ui.components.WebViewHost
import com.slate.browser.ui.theme.Motion
import kotlin.math.roundToInt

/**
 * The browsing surface: page, chrome, and every transient layer that can appear above them.
 *
 * Chrome overlays the page rather than displacing it, so showing and hiding the toolbar never
 * reflows the WebView — the single most expensive thing a browser UI can do while scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    appVersion: String,
    onShare: (String, String) -> Unit,
    onOpenExternally: (String) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val tab = viewModel.activeTab
    // Reading tab.webView subscribes to it, so hibernating or waking a tab re-hosts the page.
    val webView = tab?.let { it.webView ?: viewModel.tabManager.webViewFor(it) }
    val immersive = viewModel.isImmersive
    val mediaFullscreen = viewModel.isMediaFullscreen
    val chromeVisible = viewModel.chromeVisible && !immersive
    val settings by viewModel.settings.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    // Turning the phone sideways is itself the gesture for fullscreen browsing, when the user
    // has asked for that. Turning it back leaves.
    LaunchedEffect(landscape, settings.autoImmersiveLandscape) {
        if (!settings.autoImmersiveLandscape) return@LaunchedEffect
        if (landscape) viewModel.enterImmersive() else viewModel.exitImmersive()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Measured rather than assumed: the bar also carries whatever the system insets add.
    var barHeightPx by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ---- The page ------------------------------------------------------
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (immersive) Modifier
                    else Modifier.windowInsetsPadding(WindowInsets.systemBars),
                ),
        ) {
            WebViewHost(webView, Modifier.fillMaxSize())

            tab?.errorMessage?.let { message ->
                ErrorPanel(message = message, onRetry = viewModel::reload)
            }
        }

        // ---- Chrome --------------------------------------------------------
        if (!immersive) {
            val barOffset by animateFloatAsState(
                targetValue = if (chromeVisible) 0f else 1f,
                animationSpec = tween(Motion.MEDIUM),
                label = "chromeOffset",
            )

            val omniboxSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                Omnibox(
                    url = tab?.url.orEmpty(),
                    text = viewModel.omniboxText,
                    focused = viewModel.isOmniboxFocused,
                    isBookmarked = viewModel.isCurrentBookmarked(),
                    compact = landscape,
                    onTextChange = viewModel::onOmniboxTextChanged,
                    onSubmit = viewModel::load,
                    onRequestFocus = { viewModel.focusOmnibox() },
                    onClear = { viewModel.onOmniboxTextChanged("") },
                    modifier = slotModifier,
                )
            }

            if (landscape) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .onSizeChanged { barHeightPx = it.height.toFloat() }
                        .offset { IntOffset(0, (-barOffset * barHeightPx).roundToInt()) }
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    LandscapeBar(
                        urlSlot = omniboxSlot,
                        tabCount = viewModel.tabManager.count,
                        canGoBack = tab?.canGoBack == true,
                        canGoForward = tab?.canGoForward == true,
                        onBack = { viewModel.goBack() },
                        onForward = viewModel::goForward,
                        onImmersive = viewModel::enterImmersive,
                        showMedia = viewModel.hasPlayableMedia,
                        onMedia = viewModel::enterMediaFullscreen,
                        onTabs = { viewModel.showOverlay(Overlay.TABS) },
                        onMenu = { menuOpen = true },
                    )
                    LoadingLine(tab?.progress ?: 0f, tab?.isLoading == true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { barHeightPx = it.height.toFloat() }
                        .offset { IntOffset(0, (barOffset * barHeightPx).roundToInt()) }
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    LoadingLine(tab?.progress ?: 0f, tab?.isLoading == true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PortraitBar(
                        urlSlot = omniboxSlot,
                        tabCount = viewModel.tabManager.count,
                        showMedia = viewModel.hasPlayableMedia,
                        onMedia = viewModel::enterMediaFullscreen,
                        onTabs = { viewModel.showOverlay(Overlay.TABS) },
                        onMenu = { menuOpen = true },
                        onSwipeTab = { direction -> viewModel.stepTab(direction) },
                    )
                }
            }
        }

        // ---- Omnibox editing layer -----------------------------------------
        AnimatedVisibility(
            visible = viewModel.isOmniboxFocused,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.FAST)),
        ) {
            OmniboxSheet(
                suggestions = viewModel.suggestions,
                landscape = landscape,
                onPick = { suggestion ->
                    when (suggestion) {
                        is Suggestion.Search -> viewModel.load(suggestion.query)
                        is Suggestion.Page -> viewModel.load(suggestion.url)
                    }
                },
                onDismiss = viewModel::blurOmnibox,
            )
        }

        // ---- Find in page ---------------------------------------------------
        AnimatedVisibility(
            visible = viewModel.find.active,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            FindBar(
                state = viewModel.find,
                onQueryChange = viewModel::onFindQueryChanged,
                onNext = viewModel::findNext,
                onClose = viewModel::closeFind,
                modifier = Modifier.navigationBarsPadding().imePadding(),
            )
        }

        // ---- Immersive affordances -----------------------------------------
        if (immersive && !mediaFullscreen) {
            ImmersiveExitAffordance(onExit = viewModel::exitImmersive)
        }

        // ---- Browser-owned video fullscreen --------------------------------
        if (mediaFullscreen && viewModel.fullscreenView == null) {
            MediaFullscreenOverlay(
                mode = MediaOverlayMode.BROWSER,
                isPlaying = viewModel.media.isPlaying,
                isMuted = viewModel.media.isMuted,
                isLive = viewModel.media.isLive,
                isFilling = viewModel.mediaFit == MediaFit.COVER,
                showFitControl = true,
                onExit = viewModel::exitMediaFullscreen,
                onTogglePlay = viewModel::toggleMediaPlayback,
                onToggleMute = viewModel::toggleMediaMute,
                onToggleFit = viewModel::toggleMediaFit,
            )
        }

        // ---- Overlays --------------------------------------------------------
        OverlayLayer(viewModel = viewModel, landscape = landscape, appVersion = appVersion)

        // ---- A page element asked for the whole screen ------------------------
        viewModel.fullscreenView?.let { view ->
            FullscreenHost(view, Modifier.fillMaxSize())
            // A page that opened its own fullscreen still gets the browser's exit gestures, so
            // recovery never depends on the site drawing a working close button.
            MediaFullscreenOverlay(
                mode = MediaOverlayMode.PAGE,
                isPlaying = viewModel.media.isPlaying,
                isMuted = viewModel.media.isMuted,
                isLive = viewModel.media.isLive,
                isFilling = false,
                showFitControl = false,
                onExit = viewModel::onExitElementFullscreen,
                onTogglePlay = viewModel::toggleMediaPlayback,
                onToggleMute = viewModel::toggleMediaMute,
                onToggleFit = {},
            )
        }

        // ---- Dialogs ---------------------------------------------------------
        BrowserDialogs(viewModel)

        SnackbarHost(
            hostState = viewModel.snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (landscape) 16.dp else 68.dp, start = 12.dp, end = 12.dp),
        )
    }

    MenuSheetLayer(
        open = menuOpen,
        sheetState = sheetState,
        viewModel = viewModel,
        landscape = landscape,
        onDismiss = { menuOpen = false },
        onShare = { onShare(tab?.url.orEmpty(), tab?.displayTitle.orEmpty()) },
        onOpenExternally = { onOpenExternally(tab?.url.orEmpty()) },
    )

    // ---- Back handling ----------------------------------------------------
    // Each layer consumes back in the order the user perceives them.
    BackHandler(enabled = viewModel.fullscreenView != null) { viewModel.onExitElementFullscreen() }
    BackHandler(enabled = viewModel.fullscreenView == null && viewModel.isMediaFullscreen) {
        viewModel.exitMediaFullscreen()
    }
    BackHandler(
        enabled = viewModel.fullscreenView == null && !viewModel.isMediaFullscreen &&
            viewModel.isOmniboxFocused,
    ) {
        viewModel.blurOmnibox()
    }
    BackHandler(
        enabled = viewModel.fullscreenView == null && !viewModel.isMediaFullscreen &&
            !viewModel.isOmniboxFocused && viewModel.find.active,
    ) {
        viewModel.closeFind()
    }
    BackHandler(
        enabled = viewModel.fullscreenView == null && !viewModel.isMediaFullscreen &&
            !viewModel.isOmniboxFocused && !viewModel.find.active && viewModel.overlay != Overlay.NONE,
    ) { viewModel.dismissOverlay() }
    BackHandler(
        enabled = viewModel.fullscreenView == null && !viewModel.isMediaFullscreen &&
            !viewModel.isOmniboxFocused && !viewModel.find.active &&
            viewModel.overlay == Overlay.NONE && viewModel.isImmersive,
    ) { viewModel.exitImmersive() }
}

@Composable
private fun OmniboxSheet(
    suggestions: List<Suggestion>,
    landscape: Boolean,
    onPick: (Suggestion) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            // Tapping or flicking anywhere outside the editor puts it away, the way dismissing
            // a keyboard-backed field is expected to work.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .pointerInput(Unit) { detectVerticalDragGestures { _, _ -> onDismiss() } },
    ) {
        Surface(
            modifier = Modifier
                .align(if (landscape) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = if (landscape) 0.dp else BOTTOM_BAR_HEIGHT, top = if (landscape) TOP_BAR_HEIGHT else 0.dp)
                .imePadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SuggestionList(suggestions = suggestions, onPick = onPick)
            }
        }
    }
}

/**
 * In fullscreen browsing the only chrome left is a gesture: drag down from the very top edge to
 * come back. A hint appears the first few seconds so the gesture is never a secret.
 */
@Composable
private fun ImmersiveExitAffordance(onExit: () -> Unit) {
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2600)
        showHint = false
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                val threshold = EXIT_DRAG_DISTANCE.toPx()
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (travelled > threshold) onExit() },
                ) { _, dragAmount -> travelled += dragAmount }
            },
    )

    AnimatedVisibility(
        visible = showHint,
        enter = fadeIn(tween(Motion.SLOW)),
        exit = fadeOut(tween(Motion.SLOW)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "Swipe down from the top to exit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(36.dp),
        ) {
            Text("Can't reach this page", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                "Try again",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** A deliberate pull, not a stray touch near the top edge. */
private val EXIT_DRAG_DISTANCE = 44.dp

/** Chrome heights, shared by the bars themselves and by anything that must sit clear of them. */
val BOTTOM_BAR_HEIGHT = 56.dp
val TOP_BAR_HEIGHT = 48.dp
