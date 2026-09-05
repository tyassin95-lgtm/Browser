package com.slate.browser.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slate.browser.data.HistoryGroup
import com.slate.browser.ui.components.BarButton
import com.slate.browser.ui.components.EmptyState
import com.slate.browser.ui.components.RowSummary
import com.slate.browser.ui.components.SectionLabel
import com.slate.browser.ui.components.SiteIcon
import java.util.Calendar

@Composable
fun HistoryScreen(
    entries: List<HistoryGroup>,
    query: String,
    iconFor: (String) -> android.graphics.Bitmap?,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearRange: (ClearRange) -> Unit,
    onDismiss: () -> Unit,
) {
    var showClear by remember { mutableStateOf(false) }

    OverlayScaffold(
        title = "History",
        onDismiss = onDismiss,
        searchQuery = query,
        onSearchChange = onQueryChange,
        searchPlaceholder = "Search history",
        trailing = {
            BarButton(Icons.Rounded.DeleteSweep, "Clear history") { showClear = true }
        },
    ) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = if (query.isBlank()) "No history yet" else "Nothing found",
                    subtitle = if (query.isBlank()) {
                        "Pages you visit will appear here."
                    } else {
                        "No visited page matches \"$query\"."
                    },
                )
            }
            return@OverlayScaffold
        }

        // Grouped by day, so a long list stays scannable without a date on every row.
        val sections = remember(entries) { entries.groupBy { dayBucket(it.visitedAt) } }
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            sections.forEach { (label, rows) ->
                item(key = "header-$label") { SectionLabel(label) }
                items(rows, key = { it.url }) { entry ->
                    HistoryRow(
                        entry = entry,
                        icon = iconFor(entry.host),
                        onOpen = { onOpen(entry.url) },
                        onRemove = { onRemove(entry.url) },
                    )
                }
            }
        }
    }

    if (showClear) {
        ClearHistoryDialog(
            onDismiss = { showClear = false },
            onPick = { range ->
                showClear = false
                onClearRange(range)
            },
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryGroup,
    icon: android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIcon(entry.host, icon, size = 26.dp)
        Spacer(Modifier.width(16.dp))
        RowSummary(
            title = entry.title.ifBlank { entry.url },
            subtitle = if (entry.visits > 1) "${entry.host} · ${entry.visits} visits" else entry.host,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(40.dp)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove from history",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

enum class ClearRange(val label: String) {
    LAST_HOUR("Last hour"),
    TODAY("Today"),
    ALL("Everything"),
}

@Composable
private fun ClearHistoryDialog(onDismiss: () -> Unit, onPick: (ClearRange) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear browsing history") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ClearRange.entries.forEach { range ->
                    Text(
                        range.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(range) }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun dayBucket(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDelta = if (sameYear) now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) else 999
    return when {
        sameYear && dayDelta == 0 -> "Today"
        sameYear && dayDelta == 1 -> "Yesterday"
        sameYear && dayDelta < 7 -> "Earlier this week"
        sameYear && dayDelta < 30 -> "Earlier this month"
        else -> "Older"
    }
}
