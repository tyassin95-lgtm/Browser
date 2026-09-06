package com.slate.browser.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.slate.browser.data.SearchEngine
import com.slate.browser.data.Settings
import com.slate.browser.data.ThemeMode
import com.slate.browser.ui.components.SectionLabel

/** Every preference the browser has, in one scrollable list. No sub-pages, no search. */
@Composable
fun SettingsScreen(
    settings: Settings,
    appVersion: String,
    onThemeChange: (ThemeMode) -> Unit,
    onEngineChange: (SearchEngine) -> Unit,
    onHomePageChange: (String) -> Unit,
    onToggle: (SettingToggle, Boolean) -> Unit,
    onClearBrowsingData: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showEngine by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showHome by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    OverlayScaffold(title = "Settings", onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            SectionLabel("General")
            ChoiceRow("Search engine", settings.searchEngine.label) { showEngine = true }
            ChoiceRow("Appearance", settings.themeMode.label()) { showTheme = true }
            ChoiceRow(
                "Home page",
                settings.homePage.ifBlank { "New tab" },
            ) { showHome = true }

            SectionLabel("Browsing")
            ToggleRow(
                "Desktop sites by default",
                "New tabs request the desktop version of every site.",
                settings.desktopModeByDefault,
            ) { onToggle(SettingToggle.DESKTOP_DEFAULT, it) }
            ToggleRow(
                "Hide toolbar while scrolling",
                "The toolbar slides away as you read and returns when you scroll up.",
                settings.hideBarsOnScroll,
            ) { onToggle(SettingToggle.HIDE_ON_SCROLL, it) }
            ToggleRow(
                "Fullscreen in landscape",
                "Turn the phone sideways and the page takes the whole screen. Swipe down from the top to leave.",
                settings.autoImmersiveLandscape,
            ) { onToggle(SettingToggle.AUTO_IMMERSIVE, it) }
            ToggleRow(
                "Reopen tabs on launch",
                "Your open tabs come back exactly as you left them.",
                settings.restoreTabs,
            ) { onToggle(SettingToggle.RESTORE_TABS, it) }

            SectionLabel("Protection")
            ToggleRow(
                "Block ads and trackers",
                "Refuses advertising and tracking requests before they leave the device.",
                settings.blockAds,
            ) { onToggle(SettingToggle.BLOCK_ADS, it) }
            ToggleRow(
                "Block pop-ups and redirects",
                "Stops windows and redirects a page opens on its own. Anything you tap still opens.",
                settings.blockPopups,
            ) { onToggle(SettingToggle.BLOCK_POPUPS, it) }

            SectionLabel("Media")
            ToggleRow(
                "Allow autoplay",
                "Let pages start video and audio without a tap. Off saves data and battery.",
                settings.allowAutoplay,
            ) { onToggle(SettingToggle.AUTOPLAY, it) }

            ToggleRow(
                "Turn sideways for video",
                "Fullscreen video rotates the display to landscape when the stream is wider than it is tall.",
                settings.rotateForVideo,
            ) { onToggle(SettingToggle.ROTATE_FOR_VIDEO, it) }

            SectionLabel("Privacy")
            ToggleRow(
                "Save history",
                "Keep a record of the pages you visit on this device.",
                settings.saveHistory,
            ) { onToggle(SettingToggle.SAVE_HISTORY, it) }
            ToggleRow(
                "Block third-party cookies",
                "Stops sites embedded in other sites from setting cookies. May break some sign-ins.",
                settings.blockThirdPartyCookies,
            ) { onToggle(SettingToggle.BLOCK_3P_COOKIES, it) }
            ToggleRow(
                "Send Do Not Track",
                "Adds a request not to be tracked. Sites are free to ignore it.",
                settings.doNotTrack,
            ) { onToggle(SettingToggle.DNT, it) }
            ToggleRow(
                "Clear on exit",
                "Wipes cookies, cache and history when you close the browser.",
                settings.clearOnExit,
            ) { onToggle(SettingToggle.CLEAR_ON_EXIT, it) }
            ToggleRow(
                "JavaScript",
                "Turning this off breaks most modern sites. Only useful for reading plain pages.",
                settings.javaScriptEnabled,
            ) { onToggle(SettingToggle.JAVASCRIPT, it) }

            ChoiceRow("Clear browsing data", "Cookies, cache, history and site data") {
                confirmClear = true
            }

            SectionLabel("About")
            ChoiceRow("Version", appVersion) {}
        }
    }

    if (showEngine) {
        PickerDialog(
            title = "Search engine",
            options = SearchEngine.entries.map { it to it.label },
            selected = settings.searchEngine,
            onDismiss = { showEngine = false },
            onPick = { showEngine = false; onEngineChange(it) },
        )
    }
    if (showTheme) {
        PickerDialog(
            title = "Appearance",
            options = ThemeMode.entries.map { it to it.label() },
            selected = settings.themeMode,
            onDismiss = { showTheme = false },
            onPick = { showTheme = false; onThemeChange(it) },
        )
    }
    if (showHome) {
        var value by remember { mutableStateOf(settings.homePage) }
        AlertDialog(
            onDismissRequest = { showHome = false },
            title = { Text("Home page") },
            text = {
                Column {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Address") },
                        placeholder = { Text("Leave empty for a blank new tab") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHome = false; onHomePageChange(value) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showHome = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear browsing data?") },
            text = { Text("This removes history, cookies, cached files and site storage. Favourites are kept.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearBrowsingData() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

enum class SettingToggle {
    JAVASCRIPT, DESKTOP_DEFAULT, BLOCK_3P_COOKIES, DNT, AUTOPLAY, ROTATE_FOR_VIDEO,
    BLOCK_ADS, BLOCK_POPUPS,
    RESTORE_TABS, SAVE_HISTORY, AUTO_IMMERSIVE, HIDE_ON_SCROLL, CLEAR_ON_EXIT,
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.padding(top = 2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.padding(top = 2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> PickerDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onPick(value) })
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
