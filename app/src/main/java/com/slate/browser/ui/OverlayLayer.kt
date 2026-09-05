package com.slate.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slate.browser.BrowserViewModel
import com.slate.browser.Overlay
import com.slate.browser.data.Bookmark
import com.slate.browser.ui.screens.BookmarksScreen
import com.slate.browser.ui.screens.ClearRange
import com.slate.browser.ui.screens.HistoryScreen
import com.slate.browser.ui.screens.SettingsScreen
import com.slate.browser.ui.screens.SettingToggle
import com.slate.browser.ui.screens.TabsScreen
import com.slate.browser.ui.theme.Motion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The full-screen panels. Only the visible one is composed, so their queries are not running
 * while the user is browsing.
 */
@Composable
fun OverlayLayer(viewModel: BrowserViewModel, landscape: Boolean, appVersion: String) {
    val overlay = viewModel.overlay

    AnimatedVisibility(
        visible = overlay == Overlay.TABS,
        enter = fadeIn(tween(Motion.MEDIUM)) + scaleIn(tween(Motion.MEDIUM), initialScale = 0.94f),
        exit = fadeOut(tween(Motion.FAST)) + scaleOut(tween(Motion.FAST), targetScale = 0.94f),
    ) {
        TabsScreen(
            tabs = viewModel.tabManager.tabs,
            activeId = viewModel.tabManager.activeTabId,
            landscape = landscape,
            onSelect = viewModel::selectTab,
            onClose = viewModel::closeTab,
            onCloseAll = viewModel::closeAllTabs,
            onNewTab = { viewModel.newTab() },
            onDismiss = viewModel::dismissOverlay,
        )
    }

    AnimatedVisibility(
        visible = overlay == Overlay.HISTORY,
        enter = slideInVertically(tween(Motion.MEDIUM)) { it / 6 } + fadeIn(tween(Motion.MEDIUM)),
        exit = slideOutVertically(tween(Motion.FAST)) { it / 6 } + fadeOut(tween(Motion.FAST)),
    ) {
        HistoryPanel(viewModel)
    }

    AnimatedVisibility(
        visible = overlay == Overlay.BOOKMARKS,
        enter = slideInVertically(tween(Motion.MEDIUM)) { it / 6 } + fadeIn(tween(Motion.MEDIUM)),
        exit = slideOutVertically(tween(Motion.FAST)) { it / 6 } + fadeOut(tween(Motion.FAST)),
    ) {
        BookmarksPanel(viewModel)
    }

    AnimatedVisibility(
        visible = overlay == Overlay.SETTINGS,
        enter = slideInVertically(tween(Motion.MEDIUM)) { it / 6 } + fadeIn(tween(Motion.MEDIUM)),
        exit = slideOutVertically(tween(Motion.FAST)) { it / 6 } + fadeOut(tween(Motion.FAST)),
    ) {
        SettingsPanel(viewModel, appVersion)
    }
}

@Composable
private fun HistoryPanel(viewModel: BrowserViewModel) {
    var query by remember { mutableStateOf("") }
    val entries by remember(query) {
        if (query.isBlank()) viewModel.repo.recentHistory() else viewModel.repo.searchHistory(query)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    HistoryScreen(
        entries = entries,
        query = query,
        iconFor = { host -> viewModel.faviconStore.get(host) },
        onQueryChange = { query = it },
        onOpen = { url -> viewModel.load(url); viewModel.dismissOverlay() },
        onRemove = viewModel::removeHistoryEntry,
        onClearRange = { range ->
            viewModel.clearHistory(
                when (range) {
                    ClearRange.LAST_HOUR -> System.currentTimeMillis() - 60 * 60 * 1000L
                    ClearRange.TODAY -> startOfToday()
                    ClearRange.ALL -> 0L
                }
            )
        },
        onDismiss = viewModel::dismissOverlay,
    )
}

@Composable
private fun BookmarksPanel(viewModel: BrowserViewModel) {
    var query by remember { mutableStateOf("") }
    var openFolderId by remember { mutableStateOf<Long?>(null) }
    val bookmarks by remember(query) {
        if (query.isBlank()) viewModel.repo.allBookmarks() else viewModel.repo.searchBookmarks(query)
    }.collectAsStateWithLifecycle(initialValue = emptyList<Bookmark>())
    val folders by viewModel.repo.allFolders().collectAsStateWithLifecycle(initialValue = emptyList())

    BookmarksScreen(
        bookmarks = bookmarks,
        folders = folders,
        openFolderId = openFolderId,
        query = query,
        iconFor = { host -> viewModel.faviconStore.get(host) },
        onQueryChange = { query = it },
        onOpenFolder = { openFolderId = it },
        onOpen = { url -> viewModel.load(url); viewModel.dismissOverlay() },
        onOpenInNewTab = { url -> viewModel.openInBackgroundTab(url) },
        onEdit = viewModel::editBookmark,
        onRemove = viewModel::removeBookmark,
        onCreateFolder = viewModel::createFolder,
        onRenameFolder = viewModel::renameFolder,
        onDeleteFolder = viewModel::deleteFolder,
        onDismiss = viewModel::dismissOverlay,
    )
}

@Composable
private fun SettingsPanel(viewModel: BrowserViewModel, appVersion: String) {
    val settings by viewModel.settings.collectAsState()
    SettingsScreen(
        settings = settings,
        appVersion = appVersion,
        onThemeChange = viewModel::setTheme,
        onEngineChange = viewModel::setSearchEngine,
        onHomePageChange = viewModel::setHomePage,
        onToggle = viewModel::setToggle,
        onClearBrowsingData = viewModel::clearBrowsingData,
        onDismiss = viewModel::dismissOverlay,
    )
}

private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
