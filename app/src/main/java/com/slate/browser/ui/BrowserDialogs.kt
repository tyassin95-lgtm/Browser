package com.slate.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import com.slate.browser.BrowserViewModel
import com.slate.browser.web.JsDialogRequest

/**
 * Dialogs a page or the network can raise. They are rendered by the app rather than by the
 * platform so they inherit the browser's own type, spacing and colour, and so the origin that
 * raised them is always named.
 */
@Composable
fun BrowserDialogs(viewModel: BrowserViewModel) {

    viewModel.jsDialog?.let { request ->
        val dismiss = viewModel::dismissJsDialog
        when (request) {
            is JsDialogRequest.Alert -> AlertDialog(
                onDismissRequest = { request.result.cancel(); dismiss() },
                title = { Text(request.origin) },
                text = { Text(request.message) },
                confirmButton = {
                    TextButton(onClick = { request.result.confirm(); dismiss() }) { Text("OK") }
                },
            )

            is JsDialogRequest.Confirm -> AlertDialog(
                onDismissRequest = { request.result.cancel(); dismiss() },
                title = { Text(request.origin) },
                text = { Text(request.message) },
                confirmButton = {
                    TextButton(onClick = { request.result.confirm(); dismiss() }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { request.result.cancel(); dismiss() }) { Text("Cancel") }
                },
            )

            is JsDialogRequest.Prompt -> {
                var value by remember(request) { mutableStateOf(request.defaultValue) }
                AlertDialog(
                    onDismissRequest = { request.result.cancel(); dismiss() },
                    title = { Text(request.origin) },
                    text = {
                        Column {
                            Text(request.message)
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { request.result.confirm(value); dismiss() }) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { request.result.cancel(); dismiss() }) { Text("Cancel") }
                    },
                )
            }
        }
    }

    viewModel.sitePermission?.let { request ->
        val dismiss = viewModel::dismissSitePermission
        AlertDialog(
            onDismissRequest = { request.onDecision(false); dismiss() },
            title = { Text(request.origin) },
            text = { Text("Allow this site to use ${request.labels.humanJoin()}?") },
            confirmButton = {
                TextButton(onClick = { request.onDecision(true); dismiss() }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { request.onDecision(false); dismiss() }) { Text("Block") }
            },
        )
    }

    viewModel.sslPrompt?.let { prompt ->
        val dismiss = viewModel::dismissSslPrompt
        AlertDialog(
            onDismissRequest = { prompt.onCancel(); dismiss() },
            title = { Text("Connection is not private") },
            text = {
                Column {
                    Text(prompt.message)
                    Text(
                        "Anything you send could be read or changed by someone else.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { prompt.onCancel(); dismiss() }) { Text("Go back") }
            },
            dismissButton = {
                TextButton(onClick = { prompt.onProceed(); dismiss() }) {
                    Text("Continue anyway", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

private fun List<String>.humanJoin(): String = when (size) {
    0 -> "this device"
    1 -> first()
    2 -> "${first()} and ${last()}"
    else -> dropLast(1).joinToString(", ") + " and " + last()
}
