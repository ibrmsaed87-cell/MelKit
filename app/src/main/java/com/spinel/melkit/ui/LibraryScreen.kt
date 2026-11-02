package com.spinel.melkit.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.core.content.ContextCompat
import com.spinel.melkit.R
import com.spinel.melkit.storage.GameEntry
import com.spinel.melkit.storage.GameLibraryState
import com.spinel.melkit.storage.GameLibraryViewModel
import com.spinel.melkit.storage.LibraryIssue
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(state: GameLibraryState, model: GameLibraryViewModel, onGuide: () -> Unit) {
    val context = LocalContext.current
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.ENGLISH
    val numbers = NumberFormat.getIntegerInstance(locale)
    val listState = rememberLazyListState()
    LaunchedEffect(state.path, state.offset) { listState.scrollToItem(0) }
    BackHandler(enabled = state.path.isNotEmpty() && state.connected && !state.busy && state.proof == null) {
        model.browse(state.path.substringBeforeLast('/', ""))
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.exportPrepared(it)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(stringResource(R.string.game_library), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            Text(stringResource(R.string.library_readonly_notice), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (state.connected) R.string.library_connected else R.string.library_connect_title),
                        fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.library_connect_body), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = model::connect, enabled = !state.busy) {
                        if (state.busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(when {
                            state.busy -> R.string.library_working
                            state.connected -> R.string.library_refresh
                            else -> R.string.library_connect
                        }))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { openShizuku(context) }) { Text(stringResource(R.string.open_shizuku)) }
                        TextButton(onClick = onGuide) { Text(stringResource(R.string.library_help)) }
                    }
                }
            }
        }
        state.issue?.let { issue ->
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(stringResource(issueText(issue)), Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        if (state.loaded) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(if (state.path.isEmpty()) R.string.library_game_folders else R.string.library_current_folder),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (state.path.isNotEmpty()) {
                        Text(state.path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                        TextButton(onClick = { model.browse(state.path.substringBeforeLast('/', "")) },
                            enabled = state.connected && !state.busy) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.padding(end = 8.dp).size(18.dp))
                            Text(stringResource(R.string.library_parent))
                        }
                    }
                }
            }
            if (state.entries.isEmpty() && state.issue == null && !state.busy) {
                item {
                    Text(stringResource(if (state.path.isEmpty()) R.string.library_no_folders else R.string.library_folder_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.entries, key = { it.path }) { entry ->
                EntryCard(entry, state.connected && !state.busy, model, state.connected, state.previewRevision,
                    onOpen = { model.browse(entry.path) },
                    onRead = { model.verifyRead(entry) },
                    onExport = {
                        if (model.prepareExport(entry)) {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                PackageManager.PERMISSION_GRANTED) {
                                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else model.exportPrepared(true)
                        }
                    })
            }
            if (state.total > 0) {
                item {
                    Column {
                        Text(stringResource(R.string.library_page, numbers.format(state.offset + 1),
                            numbers.format(state.offset + state.entries.size), numbers.format(state.total)),
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { model.browse(state.path, (state.offset - 40).coerceAtLeast(0)) },
                                enabled = state.offset > 0 && state.connected && !state.busy) {
                                Text(stringResource(R.string.library_previous))
                            }
                            TextButton(onClick = { model.browse(state.path, state.offset + state.entries.size) },
                                enabled = state.hasNext && state.connected && !state.busy) {
                                Text(stringResource(R.string.library_next))
                            }
                        }
                    }
                }
            }
        }
    }
    state.proof?.let { proof ->
        AlertDialog(
            onDismissRequest = model::dismissProof,
            title = { Text(stringResource(if (proof.exported) R.string.library_export_verified else R.string.library_read_verified)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(proof.name)
                    Text(Formatter.formatFileSize(context, proof.bytes))
                    proof.savedPath?.let { path ->
                        Text(stringResource(R.string.library_saved_location))
                        SelectionContainer {
                            Text(path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                        }
                    }
                    Text(stringResource(R.string.library_fingerprint))
                    SelectionContainer {
                        Text(proof.sha256, style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr))
                    }
                    if (proof.exported) Text(stringResource(R.string.library_original_preserved))
                }
            },
            confirmButton = { TextButton(onClick = model::dismissProof) { Text(stringResource(R.string.library_done)) } },
        )
    }
}

@Composable
private fun EntryCard(entry: GameEntry, enabled: Boolean, model: GameLibraryViewModel, connected: Boolean,
    previewRevision: Int, onOpen: () -> Unit, onRead: () -> Unit, onExport: () -> Unit) {
    val context = LocalContext.current
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.ENGLISH
    val preview by produceState<Bitmap?>(null, entry, connected, previewRevision) {
        value = null
        if (connected) value = model.preview(entry, previewRevision)
    }
    val icon = when (entry.type) {
        "folder" -> Icons.Outlined.Folder
        "mod" -> Icons.Outlined.Inventory2
        "map" -> Icons.Outlined.Map
        "world" -> Icons.Outlined.Public
        "save" -> Icons.Outlined.Save
        else -> Icons.Outlined.Description
    }
    val type = when (entry.type) {
        "folder" -> R.string.library_folder
        "mod" -> R.string.library_mod
        "map" -> R.string.library_map
        "world" -> R.string.library_world
        "save" -> R.string.library_save
        else -> R.string.library_other
    }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val bitmap = preview
                Box(Modifier.size(if (entry.directory) 30.dp else 64.dp).clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(bitmap = bitmap.asImageBitmap(), filterQuality = FilterQuality.None,
                            contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    } else {
                        Icon(icon, null, tint = MelkitMint, modifier = Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.name, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr))
                    Text(stringResource(type), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!entry.directory) {
                Text(Formatter.formatFileSize(context, entry.size), style = MaterialTheme.typography.bodyMedium)
                if (entry.modified > 0) Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                    .format(Date(entry.modified)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.directory) {
                TextButton(onClick = onOpen, enabled = enabled) { Text(stringResource(R.string.library_open_folder)) }
            } else if (entry.regular) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRead, enabled = enabled) { Text(stringResource(R.string.library_read_test)) }
                    FilledTonalButton(onClick = onExport, enabled = enabled) { Text(stringResource(R.string.library_export)) }
                }
            }
        }
    }
}

private fun issueText(issue: LibraryIssue): Int = when (issue) {
    LibraryIssue.START_SHIZUKU -> R.string.library_start_shizuku
    LibraryIssue.UPDATE_SHIZUKU -> R.string.library_update_shizuku
    LibraryIssue.PERMISSION_DENIED -> R.string.library_permission_denied
    LibraryIssue.CONNECT_FAILED -> R.string.library_connect_failed
    LibraryIssue.MISSING_FOLDER -> R.string.library_missing_folder
    LibraryIssue.ACCESS_DENIED -> R.string.library_access_denied
    LibraryIssue.READ_FAILED -> R.string.library_read_failed
    LibraryIssue.SOURCE_CHANGED -> R.string.library_source_changed
    LibraryIssue.EXPORT_FAILED -> R.string.library_export_failed
    LibraryIssue.PARTIAL_EXPORT -> R.string.library_partial_export
    LibraryIssue.STORAGE_PERMISSION -> R.string.library_storage_permission
}

private fun openShizuku(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, R.string.library_shizuku_not_installed, Toast.LENGTH_LONG).show()
    }
}

