package com.slate.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slate.browser.data.Suggestion
import com.slate.browser.util.UrlUtils

/**
 * The single control that is both address bar and search box. Collapsed it shows only the host,
 * because the rest of a URL is noise on a phone; focused it becomes a full editor with the
 * whole URL selected, ready to be replaced.
 */
@Composable
fun Omnibox(
    url: String,
    text: String,
    focused: Boolean,
    isBookmarked: Boolean,
    compact: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onRequestFocus: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Box(
        modifier = modifier
            .height(if (compact) 40.dp else 44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (focused) Modifier else Modifier.clickable(onClick = onRequestFocus)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    focused -> Icons.Rounded.Search
                    UrlUtils.isSecure(url) -> Icons.Rounded.Lock
                    else -> Icons.Rounded.Public
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))

            if (focused) {
                // The whole URL starts selected so the first keystroke replaces it, while the
                // caret afterwards stays exactly where the user put it. Saved rather than
                // merely remembered, so a rotation mid-edit keeps the caret and the selection
                // instead of dropping the user back at the end of the line.
                var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                    mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
                }
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
                LaunchedEffect(text) {
                    if (text != field.text) field = TextFieldValue(text, TextRange(text.length))
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        onTextChange(it.text)
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onSubmit(field.text) }),
                    decorationBox = { inner ->
                        if (field.text.isEmpty()) {
                            Text(
                                "Search or enter address",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                if (field.text.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(onClick = onClear),
                    )
                }
            } else {
                Text(
                    text = url.takeIf { it.isNotBlank() }?.let { UrlUtils.displayHost(it) }
                        ?: "Search or enter address",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (url.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isBookmarked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The suggestion list shown while the omnibox has focus. */
@Composable
fun SuggestionList(
    suggestions: List<Suggestion>,
    onPick: (Suggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        suggestions.forEach { suggestion ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(suggestion) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (suggestion) {
                    is Suggestion.Search -> Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    is Suggestion.Page -> Icon(
                        if (suggestion.bookmarked) Icons.Rounded.Star else Icons.Rounded.Public,
                        contentDescription = null,
                        tint = if (suggestion.bookmarked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                RowSummary(suggestion.primary, suggestion.secondary, Modifier.weight(1f))
            }
        }
    }
}
