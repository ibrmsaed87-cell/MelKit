package com.spinel.melkit.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spinel.melkit.R
import com.spinel.melkit.share.SavedBundle
import com.spinel.melkit.share.ShareViewModel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SavedBundleCard(item: SavedBundle, model: ShareViewModel, enabled: Boolean) {
    val context = LocalContext.current
    var menu by rememberSaveable(item.id) { mutableStateOf(false) }
    var dialog by rememberSaveable(item.id) { mutableStateOf("") }
    var name by rememberSaveable(item.id, item.name) { mutableStateOf(item.name.removeSuffix(".zip")) }
    val preview by produceState<Bitmap?>(null, item.id) { value = model.bundleCover(item) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FileCardHeader(item.name, preview, Icons.Outlined.Inventory2, onDetails = { dialog = "details" }) {
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: java.util.Locale.ENGLISH).format(Date(item.created)), style = MaterialTheme.typography.bodySmall)
                Text(Formatter.formatFileSize(context, item.bytes.coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.content_file_count, item.count), style = MaterialTheme.typography.bodySmall)
                Text(item.types.joinToString { type -> stringResourceForFolder(type, context) }, style = MaterialTheme.typography.bodySmall)
            }
            if (!item.persistent) Text(stringResource(R.string.bundle_temporary_access), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(modifier = Modifier.weight(1f), enabled = enabled,
                    onClick = { model.openSavedBundle(item) }) { Text(stringResource(R.string.bundle_import)) }
                Box {
                    IconButton(enabled = enabled, onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.file_actions)) }
                    FileActionsMenu(menu, { menu = false }) {
                        FileAction(R.string.share_other_apps, Icons.Outlined.Share) { menu = false; model.shareSavedBundle(context, item) }
                        FileAction(R.string.bundle_rename, Icons.Outlined.Edit) { menu = false; dialog = "rename" }
                        FileAction(R.string.file_details, Icons.Outlined.Info) { menu = false; dialog = "details" }
                        FileAction(R.string.file_delete, Icons.Outlined.Delete) { menu = false; dialog = "delete" }
                    }
                }
            }
        }
    }
    if (dialog == "details") FileNameDialog(item.name, item.uri.toString()) { dialog = "" }
    if (dialog == "rename") AlertDialog(onDismissRequest = { dialog = "" },
        title = { Text(stringResource(R.string.bundle_rename)) }, text = {
            OutlinedTextField(name, { name = it.take(120) }, singleLine = true, label = { Text(stringResource(R.string.bundle_name)) })
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            dialog = ""; model.renameBundle(item, name)
        }) { Text(stringResource(R.string.profile_save)) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.saved_cancel)) } })
    if (dialog == "delete") AlertDialog(onDismissRequest = { dialog = "" },
        title = { Text(stringResource(R.string.file_delete)) },
        text = { Text(stringResource(R.string.delete_file_confirm, item.name)) },
        confirmButton = { TextButton(onClick = { dialog = ""; model.deleteBundle(item) }) { Text(stringResource(R.string.file_delete)) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.saved_cancel)) } })
}

internal fun stringResourceForFolder(folder: String, context: android.content.Context): String = context.getString(
    contentSections.firstOrNull { it.path.equals(folder, true) }?.title ?: R.string.library_other)
