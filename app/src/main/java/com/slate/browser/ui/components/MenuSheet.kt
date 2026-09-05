package com.slate.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Everything the toolbar has no room for, one tap from the page. */
data class MenuActions(
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val isLoading: Boolean,
    val isBookmarked: Boolean,
    val isDesktopMode: Boolean,
    val showImmersive: Boolean,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onReload: () -> Unit,
    val onStop: () -> Unit,
    val onBookmark: () -> Unit,
    val onShare: () -> Unit,
    val onNewTab: () -> Unit,
    val onFind: () -> Unit,
    val onToggleDesktop: () -> Unit,
    val onImmersive: () -> Unit,
    val onMediaFullscreen: () -> Unit,
    val onBookmarks: () -> Unit,
    val onHistory: () -> Unit,
    val onSettings: () -> Unit,
    val onHome: () -> Unit,
    val onOpenExternally: () -> Unit,
)

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun MenuSheet(
    actions: MenuActions,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { Box(Modifier.height(0.dp)) },
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            // Quick actions live on one row: the things reached most often, reached fastest.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", actions.canGoBack) {
                    onDismiss(); actions.onBack()
                }
                QuickAction(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", actions.canGoForward) {
                    onDismiss(); actions.onForward()
                }
                QuickAction(
                    if (actions.isLoading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                    if (actions.isLoading) "Stop" else "Reload",
                    true,
                ) {
                    onDismiss(); if (actions.isLoading) actions.onStop() else actions.onReload()
                }
                QuickAction(
                    if (actions.isBookmarked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (actions.isBookmarked) "Saved" else "Save",
                    true,
                    tinted = actions.isBookmarked,
                ) {
                    onDismiss(); actions.onBookmark()
                }
                QuickAction(Icons.Rounded.Share, "Share", true) {
                    onDismiss(); actions.onShare()
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))

            MenuRow(Icons.Rounded.Add, "New tab") { onDismiss(); actions.onNewTab() }
            MenuRow(Icons.Rounded.Search, "Find in page") { onDismiss(); actions.onFind() }
            MenuRow(
                if (actions.isDesktopMode) Icons.Rounded.PhoneAndroid else Icons.Rounded.DesktopWindows,
                if (actions.isDesktopMode) "Mobile site" else "Desktop site",
            ) { onDismiss(); actions.onToggleDesktop() }
            MenuRow(Icons.Rounded.PlayCircle, "Watch video fullscreen") {
                onDismiss(); actions.onMediaFullscreen()
            }
            if (actions.showImmersive) {
                MenuRow(Icons.Rounded.Fullscreen, "Fullscreen browsing") {
                    onDismiss(); actions.onImmersive()
                }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))

            MenuRow(Icons.Rounded.Star, "Favourites") { onDismiss(); actions.onBookmarks() }
            MenuRow(Icons.Rounded.History, "History") { onDismiss(); actions.onHistory() }
            MenuRow(Icons.Rounded.Home, "Home") { onDismiss(); actions.onHome() }
            MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open in another app") {
                onDismiss(); actions.onOpenExternally()
            }
            MenuRow(Icons.Rounded.Settings, "Settings") { onDismiss(); actions.onSettings() }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    tinted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (tinted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp).alpha(if (enabled) 1f else 0.32f),
        )
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
