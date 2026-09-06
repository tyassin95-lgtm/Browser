package com.slate.browser.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slate.browser.web.LinkContext

/** Everything the browser can do with what was pressed. */
data class LinkContextActions(
    val onOpen: () -> Unit,
    val onOpenNewTab: () -> Unit,
    val onOpenBackgroundTab: () -> Unit,
    val onCopyLink: () -> Unit,
    val onShareLink: () -> Unit,
    val onOpenImage: () -> Unit,
    val onSaveImage: () -> Unit,
    val onCopyImageAddress: () -> Unit,
    val onShareImage: () -> Unit,
)

/**
 * The long-press sheet.
 *
 * Only what applies to the element is offered: a plain image has nothing to open in a new tab,
 * and a plain link has no image to save. An image inside a link gets both, separated, so it is
 * never ambiguous which of the two an action refers to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkContextSheet(
    context: LinkContext,
    actions: LinkContextActions,
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
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (context.hasLink) Icons.Rounded.Link else Icons.Rounded.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = context.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))

            if (context.hasLink) {
                SheetRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open") { onDismiss(); actions.onOpen() }
                SheetRow(Icons.Rounded.Add, "Open in new tab") { onDismiss(); actions.onOpenNewTab() }
                SheetRow(Icons.Rounded.Add, "Open in background tab") {
                    onDismiss(); actions.onOpenBackgroundTab()
                }
                SheetRow(Icons.Rounded.ContentCopy, "Copy link") { onDismiss(); actions.onCopyLink() }
                SheetRow(Icons.Rounded.Share, "Share link") { onDismiss(); actions.onShareLink() }
            }

            if (context.hasLink && context.hasImage) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(4.dp))
            }

            if (context.hasImage) {
                SheetRow(Icons.Rounded.Image, "Open image") { onDismiss(); actions.onOpenImage() }
                SheetRow(Icons.Rounded.Download, "Save image") { onDismiss(); actions.onSaveImage() }
                SheetRow(Icons.Rounded.ContentCopy, "Copy image address") {
                    onDismiss(); actions.onCopyImageAddress()
                }
                SheetRow(Icons.Rounded.Share, "Share image") { onDismiss(); actions.onShareImage() }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
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
