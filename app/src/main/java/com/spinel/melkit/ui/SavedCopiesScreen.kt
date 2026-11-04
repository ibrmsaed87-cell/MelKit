package com.spinel.melkit.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.spinel.melkit.R
import com.spinel.melkit.storage.GameLibraryState
import com.spinel.melkit.storage.GameLibraryViewModel
import com.spinel.melkit.storage.SavedCopiesState
import com.spinel.melkit.storage.SavedCopiesViewModel

@Composable
fun SavedCopiesScreen(state: SavedCopiesState, model: SavedCopiesViewModel,
    library: GameLibraryViewModel, libraryState: GameLibraryState) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    LaunchedEffect(Unit) { model.refresh() }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.saved_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.saved_body))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = model::refresh, enabled = !state.busy) { Text(stringResource(R.string.library_refresh)) }
                    if (!libraryState.connected) TextButton(onClick = library::connect,
                        enabled = !libraryState.busy && !state.busy) { Text(stringResource(R.string.library_connect)) }
                }
                if (state.busy || libraryState.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        state.issue?.let { issue ->
            item {
                Column {
                    Text(stringResource(when (issue) {
                        "permission" -> R.string.saved_permission
                        "read" -> R.string.saved_read_error
                        "exists" -> R.string.saved_exists
                        "changed" -> R.string.library_source_changed
                        "partial" -> R.string.saved_partial
                        "connect" -> R.string.library_start_shizuku
                        "busy" -> R.string.library_working
                        "recovery" -> R.string.profile_recovery_body
                        else -> R.string.saved_restore_error
                    }), color = MaterialTheme.colorScheme.error)
                    if (issue == "permission" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        TextButton(onClick = { permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }) {
                            Text(stringResource(R.string.saved_allow))
                        }
                    }
                }
            }
        }
        libraryState.issue?.let {
            if (!libraryState.connected) item { Text(stringResource(R.string.saved_connect_help)) }
        }
        if (state.loaded && state.copies.isEmpty() && state.issue == null && !state.busy) {
            item { Text(stringResource(R.string.saved_empty)) }
        }
        items(state.copies, key = { it.uri.toString() }) { copy ->
            val preview by produceState<Bitmap?>(null, copy, state.revision) {
                value = null
                value = model.preview(copy, state.revision)
            }
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val bitmap = preview
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Outlined.Description, null, tint = MelkitMint)
                        }
                        Text(copy.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr))
                    }
                    Text(Formatter.formatFileSize(context, copy.bytes))
                    Text("Download/MelKit/${copy.path}", style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                    if (copy.restorable) FilledTonalButton(onClick = { model.requestRestore(copy) },
                        enabled = !state.busy && !libraryState.busy && libraryState.connected) {
                        Text(stringResource(R.string.saved_restore))
                    }
                }
            }
        }
    }
    state.request?.let { copy ->
        AlertDialog(onDismissRequest = model::dismissRequest,
            title = { Text(stringResource(R.string.saved_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(copy.name)
                    Text(stringResource(R.string.saved_confirm_body))
                    Text(copy.path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                }
            },
            confirmButton = { TextButton(onClick = { model.restore(library) }) { Text(stringResource(R.string.saved_restore)) } },
            dismissButton = { TextButton(onClick = model::dismissRequest) { Text(stringResource(R.string.saved_cancel)) } })
    }
    state.restored?.let { path ->
        AlertDialog(onDismissRequest = model::dismissResult,
            title = { Text(stringResource(R.string.saved_restored)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.saved_restored_body))
                Text(path, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
            } },
            confirmButton = { TextButton(onClick = model::dismissResult) { Text(stringResource(R.string.library_done)) } })
    }
}

