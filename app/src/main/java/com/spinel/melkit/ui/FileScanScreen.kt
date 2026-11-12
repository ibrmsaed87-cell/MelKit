package com.spinel.melkit.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import com.spinel.melkit.storage.ScanFinding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.spinel.melkit.R
import com.spinel.melkit.storage.FileScanState
import com.spinel.melkit.storage.FileScanViewModel
import com.spinel.melkit.storage.GameLibraryState
import com.spinel.melkit.storage.GameLibraryViewModel
import kotlinx.coroutines.flow.first

@Composable
fun FileScanScreen(state: FileScanState, model: FileScanViewModel, library: GameLibraryViewModel,
    connection: GameLibraryState, guide: () -> Unit) {
    val context = LocalContext.current
    val busy = state.busy || connection.busy
    val ready = connection.connected && !busy
    var actionFile by remember { mutableStateOf<ScanFinding?>(null) }
    var action by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    val scan = state.scan
    val vault = state.vault
    LaunchedEffect(connection.connected, state.tab) {
        if (connection.connected && state.tab == 1) {
            val available = library.state.first { !it.busy || !it.connected }
            if (available.connected) model.vaultPage(library)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = state.tab, containerColor = MelkitBackground, contentColor = MelkitMint) {
            listOf(R.string.scan_results_tab, R.string.scan_vault_tab).forEachIndexed { index, label ->
                Tab(selected = state.tab == index, enabled = !busy, onClick = { model.selectTab(index) },
                    text = { Text(stringResource(label)) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (state.tab == 0) R.string.scanner_description else R.string.scan_vault_description))
                    if (state.tab == 0) {
                        ActionButtons(listOf(
                            ActionButton(stringResource(R.string.scan_start), Icons.Outlined.Search,
                                enabled = ready, primary = true, onClick = { model.start(library) }),
                            ActionButton(stringResource(R.string.scan_isolate_selected, state.selection.size), Icons.Outlined.Shield,
                                enabled = ready && scan != null && state.selection.isNotEmpty(), onClick = model::requestQuarantine),
                        ))
                    } else if (connection.connected) {
                        FilledTonalButton(enabled = ready, onClick = { model.vaultPage(library) }) { Text(stringResource(R.string.library_refresh)) }
                    }
                    if (!connection.connected) {
                        Text(stringResource(R.string.saved_connect_help))
                        ActionButtons(listOf(
                            ActionButton(stringResource(R.string.library_connect), Icons.Outlined.Refresh,
                                enabled = !busy, primary = true, onClick = library::connect),
                            ActionButton(stringResource(R.string.shizuku_guide), Icons.Outlined.HelpOutline, onClick = guide),
                        ))
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    if (state.moved > 0) Text(stringResource(R.string.scan_moved, state.moved), color = MelkitMint)
                    state.actionDone?.let { Text(stringResource(R.string.scan_action_success, state.actionPath.orEmpty()), color = MelkitMint) }
                    if (state.restored) Text(stringResource(R.string.scan_restored), color = MelkitMint)
                    state.issue?.let { issue ->
                        Text(stringResource(when (issue) {
                            "expired" -> R.string.scan_expired
                            "changed" -> R.string.scan_changed
                            "exists" -> R.string.scan_exists
                            "missing" -> R.string.scan_missing
                            "denied" -> R.string.scan_denied
                            "space" -> R.string.vault_space
                            "limit" -> R.string.scan_limit
                            "recovery" -> R.string.profile_recovery_body
                            "connect" -> R.string.saved_connect_help
                            "busy" -> R.string.library_working
                            else -> R.string.scan_incomplete
                        }), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (state.tab == 0 && scan != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scan_summary, scan.files, scan.total))
                        if (scan.partial) Text(stringResource(R.string.scan_partial), color = MaterialTheme.colorScheme.error)
                        if (scan.total == 0 && !scan.partial) Text(stringResource(R.string.scan_clear))
                        if (state.selection.size == FileScanViewModel.PAGE_SIZE) Text(stringResource(R.string.scan_batch_limit))
                    }
                }
                items(scan.entries, key = { "finding:${it.index}" }) { entry ->
                    val selected = entry.index in state.selection
                    val canSelect = ready && (selected || state.selection.size < FileScanViewModel.PAGE_SIZE)
                    ScanFileCard(entry.path, if (entry.bytes >= 0) Formatter.formatFileSize(context, entry.bytes) else null) {
                        Text(stringResource(when (entry.issue) {
                            "empty" -> R.string.scan_empty_file
                            "duplicate" -> R.string.scan_duplicate
                            "invalid" -> R.string.scan_invalid
                            "unreadable" -> R.string.scan_unreadable
                            "changed" -> R.string.scan_changed
                            "limit" -> R.string.scan_limit
                            else -> R.string.scan_unsupported
                        }))
                        var menu by remember(entry.index) { mutableStateOf(false) }
                        Box {
                            IconButton(enabled = !busy, onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.file_actions))
                            }
                            FileActionsMenu(menu, { menu = false }) {
                                FileAction(R.string.file_details, Icons.Outlined.Info) { menu = false; actionFile = entry; action = "details" }
                                FileAction(R.string.scan_quarantine_one, Icons.Outlined.Shield, ready && entry.bytes >= 0) { menu = false; actionFile = entry; action = "file_quarantine" }
                                FileAction(R.string.bundle_rename, Icons.Outlined.Edit, ready && entry.bytes >= 0) {
                                    menu = false; actionFile = entry; action = "file_rename"
                                    val name = entry.path.substringAfterLast('/')
                                    newName = if (name.lastIndexOf('.') > 0) name.substringBeforeLast('.') else name
                                }
                                FileAction(R.string.scan_backup_one, Icons.Outlined.Save, ready && entry.bytes >= 0) { menu = false; model.fileAction(library, entry, "file_backup") }
                                FileAction(R.string.file_delete, Icons.Outlined.Delete, ready && entry.bytes >= 0) { menu = false; actionFile = entry; action = "file_delete" }
                            }
                        }
                        if (entry.reference.isNotEmpty()) {
                            Text(stringResource(R.string.scan_reference))
                            Text(entry.reference, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                        }
                        if (entry.retained) Text(stringResource(R.string.scan_retained))
                        if (entry.selectable) {
                            Row(Modifier.fillMaxWidth().toggleable(value = selected, enabled = canSelect,
                                role = Role.Checkbox, onValueChange = { model.select(entry) }),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(checked = selected, enabled = canSelect, onCheckedChange = null)
                                Text(stringResource(R.string.scan_select))
                            }
                        }
                    }
                }
                if (scan.offset > 0 || scan.next) item {
                    ScanPagination(scan.offset, scan.next, ready) { model.scanPage(library, it) }
                }
            }
            if (state.tab == 1 && vault != null) {
                if (vault.warnings > 0) item { Text(stringResource(R.string.vault_warning), color = MaterialTheme.colorScheme.error) }
                if (vault.total == 0 && vault.warnings == 0) item { Text(stringResource(R.string.scan_vault_empty)) }
                items(vault.entries, key = { "isolated:${it.id}" }) { entry ->
                    ScanFileCard(entry.path, Formatter.formatFileSize(context, entry.bytes)) {
                        if (entry.state == "recovery") Text(stringResource(R.string.scan_vault_recovery))
                        FilledTonalButton(enabled = ready, onClick = { model.requestRestore(entry) }) {
                            Text(stringResource(R.string.scan_restore))
                        }
                    }
                }
                if (vault.offset > 0 || vault.next) item {
                    ScanPagination(vault.offset, vault.next, ready) { model.vaultPage(library, it) }
                }
            }
        }
    }

    actionFile?.let { entry ->
        AlertDialog(onDismissRequest = { actionFile = null },
            title = { Text(stringResource(when (action) {
                "details" -> R.string.file_details
                "file_delete" -> R.string.file_delete
                "file_rename" -> R.string.bundle_rename
                else -> R.string.scan_quarantine_one
            })) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr))
                Text("Android/data/com.studio27.MelonPlayground/files/" + entry.path,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                if (entry.bytes >= 0) Text(Formatter.formatFileSize(context, entry.bytes))
                Text(stringResource(when (entry.issue) {
                    "empty" -> R.string.scan_empty_file
                    "duplicate" -> R.string.scan_duplicate
                    "invalid" -> R.string.scan_invalid
                    "unreadable" -> R.string.scan_unreadable
                    "changed" -> R.string.scan_changed
                    "limit" -> R.string.scan_limit
                    else -> R.string.scan_unsupported
                }))
                when (action) {
                    "file_delete" -> Text(stringResource(R.string.scan_delete_confirm))
                    "file_quarantine" -> Text(stringResource(R.string.scan_confirm_isolate))
                    "file_rename" -> {
                        OutlinedTextField(newName, { newName = it.take(120) }, singleLine = true,
                            label = { Text(stringResource(R.string.scan_name_stem)) })
                        Text(stringResource(R.string.scan_extension_kept))
                    }
                }
            } }, confirmButton = {
                TextButton(enabled = action == "details" || (ready && (action != "file_rename" ||
                    (newName.isNotBlank() && newName.none { it == '/' || it == '\\' || it.code < 32 }))), onClick = {
                    if (action != "details") model.fileAction(library, entry, action, newName)
                    actionFile = null
                }) { Text(stringResource(if (action == "details") android.R.string.ok else if (action == "file_delete") R.string.file_delete else R.string.vault_closed_continue)) }
            }, dismissButton = {
                if (action != "details") TextButton(onClick = { actionFile = null }) { Text(stringResource(R.string.saved_cancel)) }
            })
    }

    state.request?.let { request ->
        val file = request.file
        AlertDialog(onDismissRequest = model::dismiss,
            title = { Text(if (file == null) stringResource(R.string.scan_isolate_selected, request.indices.size)
                else stringResource(R.string.scan_restore)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (file != null) Text(file.path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                Text(stringResource(if (file == null) R.string.scan_confirm_isolate else R.string.scan_confirm_restore))
            } },
            confirmButton = { TextButton(enabled = ready, onClick = { model.confirm(library) }) {
                Text(stringResource(R.string.vault_closed_continue))
            } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.saved_cancel)) } })
    }
}

@Composable
private fun ScanFileCard(path: String, size: String?, body: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(path.substringAfterLast('/'), fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr))
            Text(path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
            if (size != null) Text(size)
            body()
        }
    }
}

@Composable
private fun ScanPagination(offset: Int, next: Boolean, enabled: Boolean, showPage: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(enabled = enabled && offset > 0, onClick = { showPage((offset - FileScanViewModel.PAGE_SIZE).coerceAtLeast(0)) }) {
            Text(stringResource(R.string.library_previous))
        }
        TextButton(enabled = enabled && next, onClick = { showPage(offset + FileScanViewModel.PAGE_SIZE) }) {
            Text(stringResource(R.string.library_next))
        }
    }
}
