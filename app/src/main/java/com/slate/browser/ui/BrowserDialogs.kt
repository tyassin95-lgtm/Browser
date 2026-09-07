package com.slate.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    viewModel.pendingDownload?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDownload,
            title = { Text(if (request.disguised) "This file isn't what it looks like" else "Download this file?") },
            text = {
                Column {
                    Text(
                        if (request.disguised) {
                            "It is named to look like a document, but it will install or run " +
                                "code on your phone if you open it."
                        } else {
                            "Files like this run code on your phone when you open them. Only " +
                                "keep it if you trust where it came from."
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    // The real name and the real source, which is what the page cannot fake.
                    Text(request.fileName, style = MaterialTheme.typography.bodyMedium)
                    if (request.host.isNotBlank()) {
                        Text(
                            "from ${request.host}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            // Keeping the file is the second-listed, plainer action; discarding is the default.
            confirmButton = {
                TextButton(onClick = viewModel::dismissDownload) { Text("Cancel") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::confirmDownload) { Text("Download anyway") }
            },
        )
    }

    viewModel.externalLaunch?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissExternalLaunch,
            title = { Text("Leave the browser?") },
            text = {
                Column {
                    Text("This page wants to open another app.")
                    Text(
                        request.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmExternalLaunch) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissExternalLaunch) { Text("Stay here") }
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
