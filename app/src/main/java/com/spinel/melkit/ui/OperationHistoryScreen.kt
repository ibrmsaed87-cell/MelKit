package com.spinel.melkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spinel.melkit.R
import com.spinel.melkit.storage.FileScanViewModel
import com.spinel.melkit.storage.GameLibraryState
import com.spinel.melkit.storage.GameLibraryViewModel
import com.spinel.melkit.storage.ModProfilesViewModel
import com.spinel.melkit.storage.ModVaultViewModel
import com.spinel.melkit.storage.OperationKind
import com.spinel.melkit.storage.ProfileRequest
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class RecoverySection(val label: Int) {
    GROUPS(R.string.profiles), MODS(R.string.vault_disabled_tab), QUARANTINE(R.string.scan_vault_tab),
}

private data class RecoveryPrompt(val title: Int, val message: Int, val path: String = "", val confirm: () -> Unit)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun OperationHistoryScreen(library: GameLibraryViewModel, connection: GameLibraryState, guide: () -> Unit) {
    val records by library.journal.records.collectAsState()
    val profiles: ModProfilesViewModel = viewModel(key = "history-profiles")
    val mods: ModVaultViewModel = viewModel(key = "history-mods")
    val quarantine: FileScanViewModel = viewModel(key = "history-quarantine")
    val groupState by profiles.state.collectAsState()
    val modState by mods.state.collectAsState()
    val scanState by quarantine.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var section by rememberSaveable { mutableStateOf(RecoverySection.GROUPS) }
    var prompt by remember { mutableStateOf<RecoveryPrompt?>(null) }
    val busy = connection.busy || groupState.busy || modState.busy || scanState.busy
    val ready = connection.connected && !busy
    val context = LocalContext.current
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.ENGLISH
    val dates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }

    fun refresh() = when (section) {
        RecoverySection.GROUPS -> profiles.refresh(library)
        RecoverySection.MODS -> if (modState.tab == 1) mods.refresh(library) else mods.selectTab(library, 1)
        RecoverySection.QUARANTINE -> quarantine.vaultPage(library)
    }
    LaunchedEffect(tab, section, connection.connected) {
        if (tab == 1 && connection.connected && library.state.first { !it.busy || !it.connected }.connected) refresh()
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MelkitBackground, contentColor = MelkitMint) {
            listOf(R.string.history_records, R.string.history_recovery).forEachIndexed { index, label ->
                Tab(selected = tab == index, enabled = !busy || index == 0, onClick = { tab = index },
                    text = { Text(stringResource(label)) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0) {
                if (records.isEmpty()) item { Text(stringResource(R.string.history_empty)) }
                items(records, key = { it.id }) { entry ->
                    HistoryCard(stringResource(historyLabel(entry.kind)), entry.subject) {
                        Text(dates.format(Date(entry.time)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.history_recovery_help))
                        if (connection.connected) {
                            ActionButtons(listOf(
                                ActionButton(stringResource(RecoverySection.GROUPS.label), Icons.Outlined.Layers,
                                    enabled = !busy, primary = section == RecoverySection.GROUPS,
                                    onClick = { section = RecoverySection.GROUPS }),
                                ActionButton(stringResource(RecoverySection.MODS.label), Icons.Outlined.Inventory2,
                                    enabled = !busy, primary = section == RecoverySection.MODS,
                                    onClick = { section = RecoverySection.MODS }),
                                ActionButton(stringResource(RecoverySection.QUARANTINE.label), Icons.Outlined.Shield,
                                    enabled = !busy, primary = section == RecoverySection.QUARANTINE,
                                    onClick = { section = RecoverySection.QUARANTINE }),
                            ))
                            TextButton(enabled = ready, onClick = { refresh() }) { Text(stringResource(R.string.library_refresh)) }
                        } else {
                            Text(stringResource(R.string.saved_connect_help))
                            FilledTonalButton(enabled = !busy, onClick = library::connect) {
                                Text(stringResource(R.string.library_connect))
                            }
                            TextButton(onClick = guide) { Text(stringResource(R.string.shizuku_guide)) }
                        }
                        if (busy) CircularProgressIndicator()
                        val issue = when (section) {
                            RecoverySection.GROUPS -> groupState.issue
                            RecoverySection.MODS -> modState.issue
                            RecoverySection.QUARANTINE -> scanState.issue
                        }
                        val result = when (section) {
                            RecoverySection.GROUPS -> groupState.result == "undo"
                            RecoverySection.MODS -> modState.result == "enabled"
                            RecoverySection.QUARANTINE -> scanState.restored
                        }
                        if (result) Text(stringResource(R.string.history_recovered), color = MelkitMint)
                        if (issue != null) Text(stringResource(when (issue) {
                            "exists" -> when (section) {
                                RecoverySection.GROUPS -> R.string.profile_conflict
                                RecoverySection.MODS -> R.string.vault_conflict
                                RecoverySection.QUARANTINE -> R.string.scan_exists
                            }
                            "changed" -> when (section) {
                                RecoverySection.GROUPS -> R.string.profile_changed
                                RecoverySection.MODS -> R.string.vault_changed
                                RecoverySection.QUARANTINE -> R.string.scan_changed
                            }
                            "space" -> R.string.vault_space
                            "recovery" -> R.string.profile_recovery_body
                            else -> R.string.history_recovery_failed
                        }), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (connection.connected) when (section) {
                    RecoverySection.GROUPS -> {
                        if (groupState.undo || groupState.recovery) item {
                            val title = if (groupState.recovery) R.string.profile_recover else R.string.profile_undo
                            val previous = groupState.previousName.ifEmpty { stringResource(R.string.history_previous_mod_state) }
                            val target = groupState.targetName.ifEmpty { stringResource(R.string.history_selected_group) }
                            val summary = stringResource(R.string.history_group_switch_summary, previous, target)
                            HistoryCard(stringResource(R.string.history_last_group_switch), "") {
                                Text(summary)
                                if (groupState.switchedAt > 0) Text(dates.format(Date(groupState.switchedAt)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FilledTonalButton(enabled = ready, onClick = {
                                    prompt = RecoveryPrompt(title, R.string.profile_undo_confirm, summary) {
                                        profiles.request(ProfileRequest("undo"))
                                        profiles.confirm(library)
                                    }
                                }) { Text(stringResource(title)) }
                            }
                        } else if (groupState.loaded && !busy) item { Text(stringResource(R.string.history_no_group_undo)) }
                    }
                    RecoverySection.MODS -> {
                        val entries = modState.entries.filter { it.id.isNotEmpty() }
                        if (modState.warnings > 0) item { Text(stringResource(R.string.vault_warning)) }
                        if (modState.loaded && !busy && entries.isEmpty() && modState.warnings == 0) item {
                            Text(stringResource(R.string.vault_disabled_empty))
                        }
                        items(entries, key = { it.id }) { entry ->
                            HistoryCard(entry.name, entry.path) {
                                if (entry.status == "recovery") Text(stringResource(R.string.vault_recovery))
                                FilledTonalButton(enabled = ready, onClick = {
                                    prompt = RecoveryPrompt(R.string.vault_enable, R.string.vault_confirm_enable, entry.path) {
                                        mods.request(entry)
                                        mods.confirm(library)
                                    }
                                }) { Text(stringResource(R.string.vault_enable)) }
                            }
                        }
                        if (modState.offset > 0 || modState.hasNext) item {
                            RecoveryPages(modState.offset, modState.hasNext, ready) { mods.refresh(library, it) }
                        }
                    }
                    RecoverySection.QUARANTINE -> scanState.vault?.let { page ->
                        if (page.warnings > 0) item { Text(stringResource(R.string.vault_warning)) }
                        if (page.total == 0 && !busy && page.warnings == 0) item { Text(stringResource(R.string.scan_vault_empty)) }
                        items(page.entries, key = { it.id }) { entry ->
                            HistoryCard(entry.path.substringAfterLast('/'), entry.path) {
                                if (entry.state == "recovery") Text(stringResource(R.string.scan_vault_recovery))
                                FilledTonalButton(enabled = ready, onClick = {
                                    prompt = RecoveryPrompt(R.string.scan_restore, R.string.scan_confirm_restore, entry.path) {
                                        quarantine.requestRestore(entry)
                                        quarantine.confirm(library)
                                    }
                                }) { Text(stringResource(R.string.scan_restore)) }
                            }
                        }
                        if (page.offset > 0 || page.next) item {
                            RecoveryPages(page.offset, page.next, ready) { quarantine.vaultPage(library, it) }
                        }
                        Unit
                    }
                }
            }
        }
    }
    prompt?.let { request ->
        AlertDialog(onDismissRequest = { prompt = null }, title = { Text(stringResource(request.title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (request.path.isNotEmpty()) HistoryPath(request.path)
                    Text(stringResource(request.message))
                }
            },
            confirmButton = {
                TextButton(enabled = ready, onClick = { prompt = null; request.confirm() }) {
                    Text(stringResource(R.string.vault_closed_continue))
                }
            }, dismissButton = { TextButton(onClick = { prompt = null }) { Text(stringResource(R.string.saved_cancel)) } })
    }
}

@Composable
private fun HistoryCard(title: String, subject: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (subject.isNotEmpty()) HistoryPath(subject)
            content()
        }
    }
}

@Composable
private fun HistoryPath(path: String) = Text(path,
    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr))

@Composable
private fun RecoveryPages(offset: Int, hasNext: Boolean, enabled: Boolean, select: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = enabled && offset > 0, onClick = { select((offset - 40).coerceAtLeast(0)) }) {
            Text(stringResource(R.string.library_previous))
        }
        TextButton(enabled = enabled && hasNext, onClick = { select(offset + 40) }) { Text(stringResource(R.string.library_next)) }
    }
}

private fun historyLabel(kind: OperationKind): Int = when (kind) {
    OperationKind.COPY -> R.string.history_copy
    OperationKind.RESTORE -> R.string.history_restore
    OperationKind.QUARANTINE -> R.string.history_quarantined
    OperationKind.RETURN_QUARANTINE -> R.string.history_returned
    OperationKind.DISABLE_MOD -> R.string.history_mod_disabled
    OperationKind.ENABLE_MOD -> R.string.history_mod_enabled
    OperationKind.APPLY_GROUP -> R.string.history_group_applied
    OperationKind.UNDO_GROUP -> R.string.history_group_undone
    OperationKind.EDIT_GROUP -> R.string.history_group_edited
    OperationKind.EDIT_CORE -> R.string.history_core_edited
    OperationKind.DELETE_GROUP -> R.string.history_group_deleted
    OperationKind.ORGANIZE -> R.string.history_organized
    OperationKind.EDIT_COVER -> R.string.history_cover_edited
}
