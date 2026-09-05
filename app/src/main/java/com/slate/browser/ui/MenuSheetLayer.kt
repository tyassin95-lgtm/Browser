package com.slate.browser.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import com.slate.browser.BrowserViewModel
import com.slate.browser.Overlay
import com.slate.browser.ui.components.MenuActions
import com.slate.browser.ui.components.MenuSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheetLayer(
    open: Boolean,
    sheetState: SheetState,
    viewModel: BrowserViewModel,
    landscape: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    if (!open) return
    val tab = viewModel.activeTab
    MenuSheet(
        actions = MenuActions(
            canGoBack = tab?.canGoBack == true,
            canGoForward = tab?.canGoForward == true,
            isLoading = tab?.isLoading == true,
            isBookmarked = viewModel.isCurrentBookmarked(),
            isDesktopMode = tab?.isDesktopMode == true,
            showImmersive = landscape,
            onBack = { viewModel.goBack() },
            onForward = viewModel::goForward,
            onReload = viewModel::reload,
            onStop = viewModel::stopLoading,
            onBookmark = viewModel::toggleBookmark,
            onShare = onShare,
            onNewTab = { viewModel.newTab() },
            onFind = viewModel::openFind,
            onToggleDesktop = viewModel::toggleDesktopMode,
            onImmersive = viewModel::enterImmersive,
            onMediaFullscreen = viewModel::enterMediaFullscreen,
            onBookmarks = { viewModel.showOverlay(Overlay.BOOKMARKS) },
            onHistory = { viewModel.showOverlay(Overlay.HISTORY) },
            onSettings = { viewModel.showOverlay(Overlay.SETTINGS) },
            onOpenExternally = onOpenExternally,
        ),
        sheetState = sheetState,
        onDismiss = onDismiss,
    )
}
