package com.spinel.melkit.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.spinel.melkit.R
import com.spinel.melkit.storage.*
import kotlinx.coroutines.flow.first

@Composable
fun ModVaultScreen(state: ModVaultState, model: ModVaultViewModel, library: GameLibraryViewModel,
    connection: GameLibraryState, guide: () -> Unit) {
    val context = LocalContext.current
    val busy = state.busy || connection.busy
    LaunchedEffect(connection.connected) {
        if (connection.connected) {
            val ready = library.state.first { !it.busy || !it.connected }
            if (ready.connected) model.refresh(library)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.toggler), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.vault_body))
                Text(stringResource(R.string.vault_location), style = MaterialTheme.typography.bodySmall)
                if (!connection.connected) Text(stringResource(R.string.saved_connect_help))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !busy, onClick = {
                        if (connection.connected) model.refresh(library) else library.connect()
                    }) { Text(stringResource(if (connection.connected) R.string.library_refresh else R.string.library_connect)) }
                    if (!connection.connected) TextButton(onClick = guide) { Text(stringResource(R.string.shizuku_guide)) }
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        state.issue?.let { issue -> item {
            Text(stringResource(when (issue) {
                "exists" -> R.string.vault_conflict
                "changed" -> R.string.vault_changed
                "space" -> R.string.vault_space
                "connect" -> R.string.saved_connect_help
                "busy" -> R.string.library_working
                "incomplete" -> R.string.vault_incomplete
                else -> R.string.vault_read_error
            }), color = MaterialTheme.colorScheme.error)
        } }
        if (state.warnings > 0) item { Text(stringResource(R.string.vault_warning), color = MaterialTheme.colorScheme.error) }
        state.result?.let { result -> item {
            Text(stringResource(if (result == "enabled") R.string.vault_enabled_done else R.string.vault_disabled_done), color = MelkitMint)
        } }
        if (state.loaded && state.entries.isEmpty() && state.issue == null && connection.connected && !busy) item {
            Text(stringResource(R.string.vault_empty))
        }
        items(state.entries, key = { if (it.id.isEmpty()) "active:${it.path}" else it.id }) { entry ->
            val preview by produceState<Bitmap?>(null, entry, state.revision, connection.connected) {
                if (connection.connected) value = library.previewMod(entry, state.revision)
            }
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            val image = preview
                            if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                            else Icon(Icons.Outlined.Tune, null, tint = MelkitMint)
                        }
                        Text(entry.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr))
                    }
                    Text(stringResource(when (entry.status) {
                        "active" -> R.string.vault_active
                        "disabled" -> R.string.vault_disabled
                        else -> R.string.vault_recovery
                    }))
                    Text(Formatter.formatFileSize(context, entry.bytes))
                    Text(entry.path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                    FilledTonalButton(enabled = connection.connected && !busy, onClick = { model.request(entry) }) {
                        Text(stringResource(if (entry.id.isEmpty()) R.string.vault_disable else R.string.vault_enable))
                    }
                }
            }
        }
        if (state.offset > 0 || state.hasNext) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !busy && connection.connected && state.offset > 0,
                    onClick = { model.refresh(library, (state.offset - 40).coerceAtLeast(0)) }) { Text(stringResource(R.string.library_previous)) }
                TextButton(enabled = !busy && connection.connected && state.hasNext,
                    onClick = { model.refresh(library, state.offset + 40) }) { Text(stringResource(R.string.library_next)) }
            }
        }
    }
    state.request?.let { entry ->
        val enable = entry.id.isNotEmpty()
        AlertDialog(onDismissRequest = model::dismissRequest,
            title = { Text(stringResource(if (enable) R.string.vault_enable else R.string.vault_disable)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.name)
                Text(stringResource(if (enable) R.string.vault_confirm_enable else R.string.vault_confirm_disable))
            } },
            confirmButton = { TextButton(enabled = connection.connected && !busy, onClick = { model.confirm(library) }) {
                Text(stringResource(R.string.vault_closed_continue))
            } },
            dismissButton = { TextButton(onClick = model::dismissRequest) { Text(stringResource(R.string.saved_cancel)) } })
    }
}
