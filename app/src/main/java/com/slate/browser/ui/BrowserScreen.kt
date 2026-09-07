package com.slate.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slate.browser.BackAction
import com.slate.browser.BrowserViewModel
import com.slate.browser.NavGesture
import com.slate.browser.Overlay
import com.slate.browser.data.Suggestion
import com.slate.browser.web.MediaFit
import com.slate.browser.web.NavigationDirection
import com.slate.browser.ui.components.FindBar
import com.slate.browser.ui.components.FullscreenHost
import com.slate.browser.ui.components.LandscapeBar
import com.slate.browser.ui.components.LinkContextActions
import com.slate.browser.ui.components.LinkContextSheet
import com.slate.browser.ui.components.LoadingLine
import com.slate.browser.ui.components.MediaFullscreenOverlay
import com.slate.browser.ui.components.MediaOverlayMode
import com.slate.browser.ui.components.Omnibox
import com.slate.browser.ui.components.PortraitBar
import com.slate.browser.ui.components.SuggestionList
import com.slate.browser.ui.components.WebViewHost
import com.slate.browser.ui.theme.Motion

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

    val density = LocalDensity.current
    var chromeHeight by remember { mutableStateOf(0.dp) }

    // The browser holds still while the keyboard is up; see onKeyboardVisibilityChanged.
    val keyboardVisible = keyboardInsets().getBottom(density) > 0
    LaunchedEffect(keyboardVisible) { viewModel.onKeyboardVisibilityChanged(keyboardVisible) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        val omniboxSlot: @Composable (Modifier) -> Unit = { slotModifier ->
            Omnibox(
                url = tab?.url.orEmpty(),
                text = viewModel.omniboxText,
                focused = viewModel.isOmniboxFocused,
                isBookmarked = viewModel.isCurrentBookmarked(),
                certificateOverridden = tab?.certificateOverridden == true,
                compact = landscape,
                onTextChange = viewModel::onOmniboxTextChanged,
                onSubmit = viewModel::load,
                onRequestFocus = { viewModel.focusOmnibox() },
                onClear = { viewModel.onOmniboxTextChanged("") },
                modifier = slotModifier,
            )
        }

        /**
         * The page and the chrome are siblings in a column, never stacked.
         *
         * Laying the toolbar over the page is how content ends up unreachable on a site that
         * cannot scroll, so the page is given the space that is actually left instead. That
         * makes showing or hiding the toolbar a real resize, which is why it snaps rather than
         * slides: animating the height would relayout the page on every frame of the
         * animation, and a WebView reflow is far too expensive to do sixty times a second.
         *
         * Insets are split between the two so that together they cover every system edge and
         * neither sits under one. Which edge belongs to which depends on where the bar is, and
         * on whether it is currently shown at all — when it is hidden the page inherits its
         * edge as well. Nothing here assumes where the system bars are: a navigation bar on
         * the side in landscape is the case that puts the menu button out of reach, and it is
         * handled by the same expression as every other.
         */
        val pageSides = when {
            !chromeVisible -> WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical
            landscape -> WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            else -> WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        }
        val barSides = if (landscape) {
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        } else {
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        }

        val chrome: @Composable () -> Unit = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(CHROME_TAG)
                    .background(MaterialTheme.colorScheme.surface)
                    .onSizeChanged { size ->
                        chromeHeight = with(density) { size.height.toDp() }
                    }
                    .windowInsetsPadding(systemChromeInsets().only(barSides)),
            ) {
                if (landscape) {
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
                } else {
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

        val page: @Composable (Modifier) -> Unit = { pageModifier ->
            Box(
                pageModifier.then(
                    // Fullscreen browsing wants every pixel, cutout included — but a keyboard
                    // is not something to draw under, whatever mode the browser is in.
                    if (immersive) Modifier.windowInsetsPadding(keyboardInsets())
                    else Modifier.windowInsetsPadding(systemChromeInsets().only(pageSides)),
                ),
            ) {
                // Tagged inside the insets: this is the area the page actually gets.
                Box(Modifier.fillMaxSize().testTag(PAGE_TAG)) {
                    WebViewHost(
                        webView = webView,
                        backgroundColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxSize(),
                    )

                    tab?.errorMessage?.let { message ->
                        ErrorPanel(message = message, onRetry = viewModel::reload)
                    }
                }
            }
        }

        if (immersive) {
            page(Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize()) {
                if (landscape && chromeVisible) chrome()
                page(Modifier.fillMaxWidth().weight(1f))
                // Find sits in the column too, so it displaces the page rather than covering it.
                if (viewModel.find.active) {
                    FindBar(
                        state = viewModel.find,
                        onQueryChange = viewModel::onFindQueryChanged,
                        onNext = viewModel::findNext,
                        onClose = viewModel::closeFind,
                        modifier = Modifier
                            .then(
                                // The toolbar below already clears the navigation bar and the
                                // keyboard; without one there, this bar has to clear them itself.
                                if (!landscape && chromeVisible) {
                                    Modifier
                                } else {
                                    Modifier.windowInsetsPadding(
                                        systemChromeInsets().only(
                                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                                        ),
                                    )
                                },
                            ),
                    )
                }
                if (!landscape && chromeVisible) chrome()
            }
        }

        /**
         * What already occupies the bottom edge, so floating layers can sit clear of it without
         * counting the same inset twice: the measured toolbar height already includes whatever
         * inset padding the toolbar took.
         */
        val bottomOccupied = when {
            immersive -> keyboardInsets().asPaddingValues().calculateBottomPadding()
            !landscape && chromeVisible -> chromeHeight
            else -> systemChromeInsets().asPaddingValues().calculateBottomPadding()
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
                chromeHeight = chromeHeight,
                bottomOccupied = bottomOccupied,
                onPick = { suggestion ->
                    when (suggestion) {
                        is Suggestion.Search -> viewModel.load(suggestion.query)
                        is Suggestion.Page -> viewModel.load(suggestion.url)
                    }
                },
                onDismiss = viewModel::blurOmnibox,
            )
        }

        // ---- Horizontal navigation gesture ---------------------------------
        viewModel.navGesture?.let { gesture ->
            NavigationGestureAffordance(gesture, Modifier.fillMaxSize())
        }

        // ---- Immersive affordances -----------------------------------------
        if (immersive && !mediaFullscreen) {
            ImmersiveExitAffordance(onExit = viewModel::exitImmersive)
        }

        // ---- Browser-owned video fullscreen --------------------------------
        if (mediaFullscreen && viewModel.fullscreenView == null) {
            MediaFullscreenOverlay(
                mode = MediaOverlayMode.BROWSER,
                media = viewModel.media,
                isFilling = viewModel.mediaFit == MediaFit.COVER,
                showFitControl = true,
                onExit = viewModel::exitMediaFullscreen,
                onTogglePlay = viewModel::toggleMediaPlayback,
                onToggleMute = viewModel::toggleMediaMute,
                onToggleFit = viewModel::toggleMediaFit,
                onSeek = viewModel::seekMedia,
                onJumpToLive = viewModel::jumpToLiveEdge,
                onVolume = viewModel::setMediaVolume,
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
                media = viewModel.media,
                isFilling = false,
                showFitControl = false,
                onExit = viewModel::onExitElementFullscreen,
                onTogglePlay = viewModel::toggleMediaPlayback,
                onToggleMute = viewModel::toggleMediaMute,
                onToggleFit = {},
                onSeek = viewModel::seekMedia,
                onJumpToLive = viewModel::jumpToLiveEdge,
                onVolume = viewModel::setMediaVolume,
            )
        }

        // ---- Dialogs ---------------------------------------------------------
        BrowserDialogs(viewModel)

        SnackbarHost(
            hostState = viewModel.snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(systemChromeInsets().only(WindowInsetsSides.Horizontal))
                .padding(bottom = bottomOccupied + 12.dp, start = 12.dp, end = 12.dp),
        )
    }

    // ---- Long-press actions --------------------------------------------------
    viewModel.linkContext?.let { context ->
        val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        LinkContextSheet(
            context = context,
            sheetState = contextSheetState,
            onDismiss = viewModel::dismissLinkContext,
            actions = LinkContextActions(
                onOpen = { context.linkUrl?.let(viewModel::load) },
                onOpenInNewTab = { context.linkUrl?.let(viewModel::openInBackgroundTab) },
                onOpenInNewTabAndSwitch = { context.linkUrl?.let(viewModel::openInNewTabAndSwitch) },
                onCopyLink = { context.linkUrl?.let { viewModel.copyToClipboard(it, "Link") } },
                onShareLink = { context.linkUrl?.let { onShare(it, it) } },
                onOpenImage = { context.imageUrl?.let(viewModel::openInBackgroundTab) },
                onSaveImage = { context.imageUrl?.let(viewModel::saveImage) },
                onCopyImageAddress = {
                    context.imageUrl?.let { viewModel.copyToClipboard(it, "Image address") }
                },
                onShareImage = { context.imageUrl?.let { onShare(it, it) } },
            ),
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
    BackHandler(enabled = viewModel.pendingBackAction() != BackAction.LEAVE_BROWSER) {
        viewModel.handleBack()
    }
}

/**
 * The arrow that follows a back/forward swipe. It fills in as the drag approaches the commit
 * distance and settles when it is past it, so the gesture can be judged — and abandoned by
 * dragging back — without watching the page.
 */
@Composable
private fun NavigationGestureAffordance(gesture: NavGesture, modifier: Modifier = Modifier) {
    val back = gesture.direction == NavigationDirection.BACK
    val committed = gesture.progress >= 1f
    val scale by animateFloatAsState(
        targetValue = if (committed) 1f else 0.82f,
        animationSpec = tween(Motion.FAST),
        label = "navGestureScale",
    )

    Box(modifier) {
        Box(
            Modifier
                .align(if (back) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 12.dp)
                .graphicsLayer {
                    alpha = (0.35f + gesture.progress * 0.65f)
                    scaleX = scale
                    scaleY = scale
                    val slide = (1f - gesture.progress) * 28.dp.toPx()
                    translationX = if (back) -slide else slide
                }
                .size(44.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (back) {
                    Icons.AutoMirrored.Rounded.ArrowBack
                } else {
                    Icons.AutoMirrored.Rounded.ArrowForward
                },
                contentDescription = if (back) "Go back" else "Go forward",
                tint = if (committed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * What sits behind the omnibox while it is being edited.
 *
 * The dimming layer deliberately stops at the toolbar rather than covering the screen. It used
 * to be laid over everything, which put a tap-to-dismiss listener on top of the address bar
 * itself: the keyboard still worked, but every long-press, caret placement and selection drag
 * was swallowed and read as "dismiss", so text could be typed and never selected, copied or
 * pasted. The field has to be the topmost thing at its own coordinates for Android's own text
 * selection to work at all.
 */
@Composable
private fun OmniboxSheet(
    suggestions: List<Suggestion>,
    landscape: Boolean,
    chromeHeight: Dp,
    bottomOccupied: Dp,
    onPick: (Suggestion) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // The toolbar's own band is left out entirely, so nothing here is ever in front of
            // the text field, the tab button or the menu button.
            .padding(top = if (landscape) chromeHeight else 0.dp, bottom = bottomOccupied),
    ) {
        // Tapping or flicking anywhere outside the editor puts it away, the way dismissing a
        // keyboard-backed field is expected to work.
        Box(
            Modifier
                .fillMaxSize()
                .testTag(OMNIBOX_SCRIM_TAG)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .pointerInput(Unit) { detectVerticalDragGestures { _, _ -> onDismiss() } },
        )

        if (suggestions.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(if (landscape) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Only the horizontal insets: the band this sheet sits in already stops
                    // short of the toolbar, which carries the inset for the edge it is on.
                    .windowInsetsPadding(systemChromeInsets().only(WindowInsetsSides.Horizontal)),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SuggestionList(suggestions = suggestions, onPick = onPick)
                }
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

/**
 * Everything the system draws over: the status and navigation bars, plus the display cutout.
 *
 * The cutout matters because the window is laid out edge to edge through it, so a landscape
 * cutout would otherwise clip whichever control sits at that end of the toolbar.
 */
/** Test handles for the two regions whose relationship the layout is built around. */
const val PAGE_TAG = "slate:page"
const val CHROME_TAG = "slate:chrome"

/** The dimming layer behind the omnibox editor; it must never reach the toolbar. */
const val OMNIBOX_SCRIM_TAG = "slate:omnibox-scrim"

/**
 * Everything the browser's own layout has to keep clear of: the system bars, the display
 * cutout, and the keyboard.
 *
 * The keyboard belongs in the same expression as the rest, because the window does not resize
 * for it. This app draws edge to edge, which means the IME arrives as an inset and nothing
 * moves unless the layout moves it — so leaving the keyboard out of the model put the toolbar,
 * and with it the address bar being typed into, underneath the keyboard, and left the page its
 * full height with the bottom of it covered.
 *
 * [union] takes the larger of each side rather than adding them, so the bottom is the
 * navigation bar or the keyboard, never both counted at once, and never a fixed number.
 *
 * The *target* of the IME animation is used rather than its current position. The page is a
 * WebView, and following the animation would relayout and re-raster it on every frame of the
 * keyboard sliding in — the same cost that made scrolling tear, for an effect nobody sees
 * behind a moving keyboard. This settles the layout once, at the size the keyboard is going to
 * be, which is also what lets WebView scroll the focused field into view exactly once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun systemChromeInsets(): WindowInsets =
    WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.imeAnimationTarget)

/** The keyboard alone, for the layers that take no other inset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun keyboardInsets(): WindowInsets = WindowInsets.imeAnimationTarget
