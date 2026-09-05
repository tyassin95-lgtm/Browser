package com.slate.browser.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.slate.browser.data.Bookmark
import com.slate.browser.data.BookmarkFolder
import com.slate.browser.ui.components.BarButton
import com.slate.browser.ui.components.EmptyState
import com.slate.browser.ui.components.RowSummary
import com.slate.browser.ui.components.SiteIcon

/**
 * Favourites, optionally filed into folders. Folders are flat by design: nested trees are a
 * desktop idea that nobody maintains on a phone.
 */
@Composable
fun BookmarksScreen(
    bookmarks: List<Bookmark>,
    folders: List<BookmarkFolder>,
    openFolderId: Long?,
    query: String,
    iconFor: (String) -> android.graphics.Bitmap?,
    onQueryChange: (String) -> Unit,
    onOpenFolder: (Long?) -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onEdit: (Bookmark, String, Long?) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (BookmarkFolder, String) -> Unit,
    onDeleteFolder: (BookmarkFolder) -> Unit,
    onDismiss: () -> Unit,
) {
    var newFolder by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var renaming by remember { mutableStateOf<BookmarkFolder?>(null) }

    val currentFolder = folders.firstOrNull { it.id == openFolderId }
    val searching = query.isNotBlank()
    val visible = remember(bookmarks, openFolderId, searching) {
        if (searching) bookmarks else bookmarks.filter { it.folderId == openFolderId }
    }

    OverlayScaffold(
        title = currentFolder?.name ?: "Favourites",
        onDismiss = { if (openFolderId != null) onOpenFolder(null) else onDismiss() },
        searchQuery = query,
        onSearchChange = onQueryChange,
        searchPlaceholder = "Search favourites",
        trailing = {
            if (openFolderId == null) {
                BarButton(Icons.Rounded.CreateNewFolder, "New folder") { newFolder = true }
            }
        },
    ) {
        if (visible.isEmpty() && (searching || folders.isEmpty() || openFolderId != null)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.StarBorder,
                    title = if (searching) "Nothing found" else "No favourites yet",
                    subtitle = if (searching) {
                        "No favourite matches \"$query\"."
                    } else {
                        "Save a page from the menu to find it here."
                    },
                )
            }
            return@OverlayScaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (openFolderId == null && !searching) {
                items(folders, key = { "folder-${it.id}" }) { folder ->
                    FolderRow(
                        folder = folder,
                        count = bookmarks.count { it.folderId == folder.id },
                        onOpen = { onOpenFolder(folder.id) },
                        onRename = { renaming = folder },
                        onDelete = { onDeleteFolder(folder) },
                    )
                }
            }
            items(visible, key = { it.id }) { bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    icon = iconFor(bookmark.host),
                    onOpen = { onOpen(bookmark.url) },
                    onOpenInNewTab = { onOpenInNewTab(bookmark.url) },
                    onEdit = { editing = bookmark },
                    onRemove = { onRemove(bookmark) },
                )
            }
        }
    }

    if (newFolder) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            initial = "",
            onDismiss = { newFolder = false },
            onConfirm = { name ->
                newFolder = false
                if (name.isNotBlank()) onCreateFolder(name)
            },
        )
    }

    renaming?.let { folder ->
        TextPromptDialog(
            title = "Rename folder",
            label = "Folder name",
            initial = folder.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                if (name.isNotBlank()) onRenameFolder(folder, name)
            },
        )
    }

    editing?.let { bookmark ->
        EditBookmarkDialog(
            bookmark = bookmark,
            folders = folders,
            onDismiss = { editing = null },
            onConfirm = { title, folderId ->
                editing = null
                onEdit(bookmark, title, folderId)
            },
        )
    }
}

@Composable
private fun FolderRow(
    folder: BookmarkFolder,
    count: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(16.dp))
        RowSummary(
            title = folder.name,
            subtitle = if (count == 1) "1 item" else "$count items",
            modifier = Modifier.weight(1f),
        )
        Box {
            BarButton(Icons.Rounded.MoreVert, "Folder options") { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete folder") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    icon: android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIcon(bookmark.host, icon, size = 26.dp)
        Spacer(Modifier.width(16.dp))
        RowSummary(bookmark.title, bookmark.url, Modifier.weight(1f))
        Box {
            BarButton(Icons.Rounded.MoreVert, "Favourite options") { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Open in new tab") },
                    onClick = { menuOpen = false; onOpenInNewTab() },
                )
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditBookmarkDialog(
    bookmark: Bookmark,
    folders: List<BookmarkFolder>,
    onDismiss: () -> Unit,
    onConfirm: (String, Long?) -> Unit,
) {
    var title by remember { mutableStateOf(bookmark.title) }
    var folderId by remember { mutableStateOf(bookmark.folderId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit favourite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Text("Folder", style = MaterialTheme.typography.labelLarge)
                FolderChoice("No folder", folderId == null) { folderId = null }
                folders.forEach { folder ->
                    FolderChoice(folder.name, folderId == folder.id) { folderId = folder.id }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, folderId) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
