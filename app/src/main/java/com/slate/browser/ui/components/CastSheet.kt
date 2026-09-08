package com.slate.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slate.browser.cast.CastDevice

/**
 * Which nearby receiver to send to.
 *
 * Deliberately just a list. The device names come from the receivers themselves, so there is
 * nothing to add to them, and a browser that stops to explain casting every time is a browser
 * that has stopped being minimal.
 *
 * The exception is [note]: when this page's media cannot be sent to any receiver, the sheet
 * still opens, says why in one line, and offers mirroring — because mirroring is the answer to
 * that sentence, and a message that closes the only door to it is not a helpful message.
 * Devices are hidden in that case rather than listed and then refused on tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastPickerSheet(
    devices: List<CastDevice>,
    note: String,
    onPick: (String) -> Unit,
    onMirrorScreen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Nothing here can be sent, so a device to send it to is not a choice worth offering.
    val listed = if (note.isEmpty()) devices else emptyList()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                if (note.isEmpty()) "Cast to" else "Can't cast this page",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            if (note.isNotEmpty()) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            if (note.isEmpty() && listed.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "Looking for devices…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            listed.forEach { device ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(device.id) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (device.isSelected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                        contentDescription = null,
                        tint = if (device.isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            /*
             * The way out for receivers no app-level protocol reaches. A Fire TV Stick speaks
             * neither Google Cast nor DLNA — Amazon leaves both out — and mirroring is what it
             * does support. The browser cannot start mirroring itself, so this hands the user
             * to the system control that can, and says plainly that it is a different thing.
             */
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onMirrorScreen() }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.ScreenShare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(
                        "Mirror the screen instead",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "For Fire TV, Samsung and anything else that isn't listed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
