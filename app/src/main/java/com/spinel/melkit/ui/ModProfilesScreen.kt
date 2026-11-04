package com.spinel.melkit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.spinel.melkit.R
import com.spinel.melkit.storage.*
import kotlinx.coroutines.flow.first

@Composable
fun ModProfilesScreen(state: ModProfilesState, model: ModProfilesViewModel, library: GameLibraryViewModel,
    connection: GameLibraryState, guide: () -> Unit) {
    val busy = state.busy || connection.busy
    LaunchedEffect(connection.connected) {
        if (connection.connected) {
            val ready = library.state.first { !it.busy || !it.connected }
            if (ready.connected) model.refresh(library)
        }
    }
    BackHandler(enabled = state.editor != null) { if (!busy) model.closeEditor() }
    val editor = state.editor
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if (editor?.id == "core") R.string.profile_core else R.string.profiles),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(if (editor?.id == "core") R.string.profile_core_body else R.string.profile_body))
                if (!connection.connected) Text(stringResource(R.string.saved_connect_help))
                if (editor == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = !busy, onClick = {
                            if (connection.connected) model.refresh(library) else library.connect()
                        }) { Text(stringResource(if (connection.connected) R.string.library_refresh else R.string.library_connect)) }
                        if (!connection.connected) TextButton(onClick = guide) { Text(stringResource(R.string.shizuku_guide)) }
                    }
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.profile_working))
                }
            }
        }
        state.issue?.let { issue -> item {
            Text(stringResource(when (issue) {
                "recovery" -> R.string.profile_recovery_body
                "rolled_back" -> R.string.profile_rolled_back
                "missing_mod" -> R.string.profile_missing
                "changed" -> R.string.profile_changed
                "exists" -> R.string.profile_conflict
                "limit" -> R.string.profile_limit
                "connect" -> R.string.saved_connect_help
                "busy" -> R.string.library_working
                else -> R.string.profile_error
            }), color = MaterialTheme.colorScheme.error)
        } }
        if (state.warnings > 0) item { Text(stringResource(R.string.profile_vault_warning), color = MaterialTheme.colorScheme.error) }
        if (editor != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (editor.id != "core") OutlinedTextField(value = editor.name, onValueChange = model::name,
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                        label = { Text(stringResource(R.string.profile_name)) })
                    OutlinedTextField(value = editor.query, onValueChange = model::query,
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                        label = { Text(stringResource(R.string.profile_search)) })
                    Text(stringResource(R.string.profile_selected_count, editor.selected.size))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = !busy && connection.connected && (editor.id == "core" || editor.name.isNotBlank()),
                            onClick = { model.save(library) }) { Text(stringResource(R.string.profile_save)) }
                        TextButton(enabled = !busy, onClick = model::closeEditor) { Text(stringResource(R.string.saved_cancel)) }
                    }
                }
            }
            val choices = editor.choices.filter { it.name.contains(editor.query, ignoreCase = true) || it.path.contains(editor.query, ignoreCase = true) }
            if (choices.isEmpty()) item { Text(stringResource(R.string.profile_no_choices)) }
            items(choices, key = { it.path }) { choice ->
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
                    Row(Modifier.fillMaxWidth().toggleable(value = choice.path in editor.selected, enabled = !busy,
                        role = Role.Checkbox, onValueChange = { model.select(choice.path) }).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(checked = choice.path in editor.selected, enabled = !busy, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(choice.name, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr))
                            Text(choice.path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                            if (!choice.available) Text(stringResource(R.string.profile_unavailable), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else {
            if (state.recovery) item { Text(stringResource(R.string.profile_recovery_body), color = MaterialTheme.colorScheme.error) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = connection.connected && !busy && !state.recovery,
                        onClick = { model.edit(library) }) { Text(stringResource(R.string.profile_new)) }
                    TextButton(enabled = connection.connected && !busy && !state.recovery,
                        onClick = { model.edit(library, "core") }) { Text(stringResource(R.string.profile_core_count, state.coreCount)) }
                    if (state.undo) FilledTonalButton(enabled = connection.connected && !busy,
                        onClick = { model.request(ProfileRequest("undo")) }) {
                        Text(stringResource(if (state.recovery) R.string.profile_recover else R.string.profile_undo))
                    }
                }
            }
            state.result?.let { result -> item {
                Text(stringResource(when (result) {
                    "apply" -> R.string.profile_applied
                    "undo" -> R.string.profile_undone
                    "delete" -> R.string.profile_deleted
                    else -> R.string.profile_saved
                }), color = MelkitMint)
            } }
            if (state.loaded && state.items.isEmpty()) item { Text(stringResource(R.string.profile_empty)) }
            items(state.items, key = { it.id }) { profile ->
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.profile_member_count, profile.count))
                        if (profile.active) Text(stringResource(R.string.profile_active), color = MelkitMint, fontWeight = FontWeight.Bold)
                        FilledTonalButton(enabled = connection.connected && !busy && !state.recovery && !profile.active,
                            onClick = { model.request(ProfileRequest("apply", profile.id, profile.name)) }) {
                            Text(stringResource(R.string.profile_apply))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = connection.connected && !busy && !state.recovery,
                                onClick = { model.edit(library, profile.id) }) { Text(stringResource(R.string.profile_edit)) }
                            TextButton(enabled = connection.connected && !busy && !state.recovery,
                                onClick = { model.request(ProfileRequest("delete", profile.id, profile.name)) }) { Text(stringResource(R.string.profile_delete)) }
                        }
                    }
                }
            }
        }
    }
    state.request?.let { request ->
        AlertDialog(onDismissRequest = model::dismissRequest,
            title = { Text(stringResource(when (request.action) {
                "delete" -> R.string.profile_delete
                "undo" -> R.string.profile_undo
                else -> R.string.profile_apply
            })) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (request.name.isNotEmpty()) Text(request.name, fontWeight = FontWeight.Bold)
                Text(stringResource(when (request.action) {
                    "delete" -> R.string.profile_delete_confirm
                    "undo" -> R.string.profile_undo_confirm
                    else -> R.string.profile_apply_confirm
                }))
            } },
            confirmButton = { TextButton(enabled = !busy && connection.connected, onClick = { model.confirm(library) }) {
                Text(stringResource(if (request.action == "delete") R.string.profile_delete else R.string.vault_closed_continue))
            } },
            dismissButton = { TextButton(onClick = model::dismissRequest) { Text(stringResource(R.string.saved_cancel)) } })
    }
}
