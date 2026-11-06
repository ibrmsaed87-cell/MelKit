package com.spinel.melkit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.spinel.melkit.R
import com.spinel.melkit.share.*
import com.spinel.melkit.storage.*

@Composable
internal fun DirectShareScreen(model: ShareViewModel, library: GameLibraryViewModel,
    connection: GameLibraryState, saved: SavedCopiesViewModel, copies: SavedCopiesState,
    bundles: Boolean = false, guide: () -> Unit) {
    val context = LocalContext.current
    val pageState by model.state.collectAsState()
    val transfer by model.transfer.collectAsState()
    var page by rememberSaveable { mutableStateOf(if (bundles) "pick" else "menu") }
    var source by rememberSaveable { mutableStateOf("game") }
    var pending by rememberSaveable { mutableStateOf("") }
    var editingCover by rememberSaveable { mutableStateOf(false) }
    var restore by remember { mutableStateOf<ReceivedFile?>(null) }
    var save by remember { mutableStateOf<ReceivedFile?>(null) }
    val prompt = stringResource(R.string.share_scan_prompt)
    val qr = rememberLauncherForActivityResult(ScanContract()) { result ->
        val value = result.contents
        if (value != null && model.receive(context, value)) page = "transfer"
    }
    val perform: (String) -> Unit = { action ->
        when (action) {
            "send" -> {
                page = "transfer"
                model.send(context)
            }
            "send_bundle" -> {
                val uri = pageState.bundleUri
                if (uri != null && !model.transfer.value.active) {
                    model.reset(clearQueue = false)
                    page = "transfer"
                    model.sendBundle(context, uri)
                }
            }
            else -> qr.launch(ScanOptions().setCaptureActivity(MelKitShareCaptureActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(true)
                .setBeepEnabled(false).setPrompt(prompt))
        }
    }
    val networkPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending
        pending = ""
        if (action.isNotEmpty() && context.checkSelfPermission(MelKitShareConnection.permission()) == PackageManager.PERMISSION_GRANTED) {
            perform(action)
        } else model.issue("PERMISSION")
    }
    val begin: (String) -> Unit = { action ->
        model.issue(null)
        if (context.checkSelfPermission(MelKitShareConnection.permission()) == PackageManager.PERMISSION_GRANTED) perform(action)
        else {
            pending = action
            val permissions = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
            } else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            networkPermissions.launch(permissions)
        }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.addDocuments(uris)
    }
    val bundleDestination = if (bundles) {
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let(model::saveBundle)
        }
    } else null
    val bundleSource = if (bundles) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(model::importBundle)
        }
    } else null
    val readStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) saved.refresh() else model.issue("STORAGE_PERMISSION")
    }
    val writeStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val file = save
        save = null
        if (allowed && file != null) model.save(file) else if (!allowed) model.issue("STORAGE_PERMISSION")
    }
    LaunchedEffect(transfer.phase) {
        if (!bundles) {
            if (transfer.active) page = "transfer"
            if (transfer.phase == "complete" && !transfer.sending) model.refreshReceived()
        }
    }
    LaunchedEffect(page, pageState.bundleBatch) {
        if (page == "history" || (page == "contents" && pageState.bundleBatch != null)) model.refreshReceived()
    }
    val entryPage = if (bundles) "pick" else "menu"
    val returnPage = if (!bundles && page == "contents") "history" else entryPage
    BackHandler(enabled = page != entryPage && !transfer.active && !pageState.busy) {
        page = returnPage
    }

    val screen = if (!bundles && transfer.active) "transfer" else page
    key(screen) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(when (screen) {
                    "pick" -> if (bundles) R.string.bundle_choose else R.string.share_choose
                    "history" -> R.string.share_received_files
                    "contents" -> R.string.bundle_contents
                    "transfer" -> R.string.share_transfer
                    else -> R.string.sharing
                }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (pageState.busy || (screen == "pick" && source == "game" && connection.busy)) {
                item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
            pageState.issue?.let { error -> item { ShareError(error, bundles || screen == "contents") } }

            when (screen) {
                "menu" -> {
                    item {
                        ShareCard(R.string.share_direct, R.string.sharing_description) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(enabled = !pageState.busy, onClick = {
                                    model.reset(clearQueue = true)
                                    source = "game"
                                    if (connection.connected && !connection.busy) library.browse("")
                                    page = "pick"
                                }) { Text(stringResource(R.string.send)) }
                                FilledTonalButton(enabled = !pageState.busy, onClick = {
                                    model.reset(clearQueue = false)
                                    begin("receive")
                                }) { Text(stringResource(R.string.receive)) }
                            }
                            TextButton(onClick = { page = "history" }) { Text(stringResource(R.string.share_received_files)) }
                        }
                    }
                    item {
                        ShareCard(R.string.share_app_apk, R.string.share_app_apk_body) {
                            FilledTonalButton(enabled = !pageState.busy, onClick = { model.shareApk(context) }) {
                                Text(stringResource(R.string.share_app_apk))
                            }
                        }
                    }
                    item {
                        ShareCard(R.string.share_project_link, R.string.share_project_body) {
                            FilledTonalButton(enabled = !pageState.busy, onClick = { model.shareLink(context) }) {
                                Text(stringResource(R.string.share_project_link))
                            }
                        }
                    }
                }
                "pick" -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.share_queue_count, pageState.queue.size))
                            val canUseSelection = pageState.queue.isNotEmpty() && !pageState.busy && !connection.busy
                            if (bundles) {
                                pageState.draftCover?.let { CoverPreview(it, Modifier.size(88.dp)) }
                                TextButton(enabled = !pageState.busy, onClick = { editingCover = true }) {
                                    Text(stringResource(R.string.cover_edit))
                                }
                                FilledTonalButton(enabled = canUseSelection, onClick = {
                                    bundleDestination?.launch("MelKit-Bundle.zip")
                                }) { Text(stringResource(R.string.bundle_create)) }
                                pageState.bundleUri?.let { uri ->
                                    Text(stringResource(R.string.bundle_saved), color = MelkitMint)
                                    FilledTonalButton(enabled = !pageState.busy && !transfer.active,
                                        onClick = { begin("send_bundle") }) { Text(stringResource(R.string.bundle_send_direct)) }
                                    TextButton(enabled = !pageState.busy, onClick = { model.shareBundle(context, uri) }) {
                                        Text(stringResource(R.string.share_other_apps))
                                    }
                                }
                                TextButton(enabled = !pageState.busy, onClick = { bundleSource?.launch(arrayOf("*/*")) }) {
                                    Text(stringResource(R.string.bundle_import))
                                }
                                if (pageState.bundleBatch != null) {
                                    Text(stringResource(R.string.bundle_verified), color = MelkitMint)
                                    TextButton(enabled = !pageState.busy, onClick = { page = "contents" }) {
                                        Text(stringResource(R.string.bundle_contents))
                                    }
                                }
                            } else {
                                FilledTonalButton(enabled = canUseSelection, onClick = { begin("send") }) {
                                    Text(stringResource(R.string.share_create_qr))
                                }
                            }
                            TextButton(enabled = !pageState.busy, onClick = { documents.launch(arrayOf("*/*")) }) {
                                Text(stringResource(R.string.share_choose_documents))
                            }
                            TabRow(selectedTabIndex = if (source == "game") 0 else 1, containerColor = MelkitBackground,
                                contentColor = MelkitMint) {
                                Tab(selected = source == "game", onClick = { source = "game" },
                                    text = { Text(stringResource(R.string.share_from_game)) })
                                Tab(selected = source == "saved", onClick = {
                                    source = "saved"
                                    if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                                        != PackageManager.PERMISSION_GRANTED) readStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                    else saved.refresh()
                                }, text = { Text(stringResource(R.string.backups)) })
                            }
                        }
                    }
                    if (pageState.queue.isNotEmpty()) {
                        items(pageState.queue, key = { "queue:" + it.path }) { file ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SharePath(file.path, Modifier.weight(1f))
                                TextButton(enabled = !pageState.busy, onClick = { model.remove(file.path) }) {
                                    Text(stringResource(R.string.share_remove))
                                }
                            }
                        }
                    }
                    if (source == "game") {
                        if (!connection.connected) item {
                            Text(stringResource(R.string.saved_connect_help))
                            FilledTonalButton(enabled = !connection.busy, onClick = library::connect) {
                                Text(stringResource(R.string.library_connect))
                            }
                            TextButton(onClick = guide) { Text(stringResource(R.string.shizuku_guide)) }
                        } else {
                            item {
                                Text(stringResource(R.string.share_close_game))
                                if (connection.path.isNotEmpty()) TextButton(enabled = !connection.busy, onClick = {
                                    library.browse(connection.path.substringBeforeLast('/', ""))
                                }) { Text(stringResource(R.string.share_parent)) }
                                if (connection.issue != null) Text(stringResource(R.string.share_source_access))
                                else if (connection.loaded && connection.entries.isEmpty()) Text(stringResource(R.string.share_empty_source))
                            }
                            items(connection.entries.filter { it.directory || (it.regular && ShareWire.validPath(it.path) && it.size > 0) },
                                key = { "game:" + it.path }) { entry ->
                                if (entry.directory) TextButton(enabled = !connection.busy, onClick = { library.browse(entry.path) }) {
                                    SharePath(entry.path)
                                } else PickShareRow(entry.path, model.selected(entry.path), !connection.busy && !pageState.busy) {
                                    model.toggleGame(entry, library)
                                }
                            }
                            if (connection.offset > 0 || connection.hasNext) item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = !connection.busy && connection.offset > 0,
                                        onClick = { library.browse(connection.path, (connection.offset - 40).coerceAtLeast(0)) }) {
                                        Text(stringResource(R.string.library_previous))
                                    }
                                    TextButton(enabled = !connection.busy && connection.hasNext,
                                        onClick = { library.browse(connection.path, connection.offset + 40) }) {
                                        Text(stringResource(R.string.library_next))
                                    }
                                }
                            }
                        }
                    } else {
                        if (copies.busy) item { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                        if (copies.issue != null) item { Text(stringResource(R.string.share_source_access)) }
                        else if (copies.loaded && copies.copies.none { ShareWire.validPath(it.path) }) {
                            item { Text(stringResource(R.string.share_empty_source)) }
                        }
                        items(copies.copies.filter { ShareWire.validPath(it.path) }, key = { "saved:" + it.uri }) { file ->
                            PickShareRow(file.path, model.selected(file.path), !pageState.busy) { model.toggleSaved(file) }
                        }
                    }
                }
                "transfer" -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when {
                                transfer.phase == "complete" -> Text(stringResource(if (transfer.sending) R.string.share_sent else R.string.share_received))
                                transfer.phase == "cancelled" -> Text(stringResource(R.string.share_cancelled))
                                transfer.error.isNotEmpty() -> ShareError(transfer.error, bundles)
                                else -> Text(stringResource(MelKitShareService.phaseLabel(transfer.phase)))
                            }
                            transfer.ticket?.let { ticket ->
                                val image = remember(ticket) { runCatching { makeQr(ticket.encode()) }.getOrNull() }
                                if (image != null) Image(image.asImageBitmap(), stringResource(R.string.share_scan_prompt),
                                    Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).background(Color.White))
                                else ShareError("BAD_QR")
                            }
                            transfer.progress?.let { p ->
                                val percent = if (transfer.phase == "complete") 100 else (p.bytes * 100 / p.total).toInt().coerceAtMost(99)
                                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.share_progress, percent, p.index, p.count))
                                SharePath(p.path)
                            }
                            if (transfer.active && transfer.phase != "finishing") {
                                TextButton(onClick = { MelKitShareService.cancel(context) }) { Text(stringResource(R.string.saved_cancel)) }
                            } else if (!transfer.active && !pageState.busy && bundles) {
                                TextButton(onClick = { model.reset(clearQueue = false); page = "pick" }) {
                                    Text(stringResource(R.string.share_bundle))
                                }
                            } else if (!transfer.active && !bundles) {
                                if (transfer.sending && pageState.queue.isNotEmpty() && transfer.phase != "complete") {
                                    TextButton(onClick = { model.reset(clearQueue = false); page = "pick" }) {
                                        Text(stringResource(R.string.share_choose))
                                    }
                                }
                                FilledTonalButton(onClick = { page = "history" }) { Text(stringResource(R.string.share_received_files)) }
                                TextButton(onClick = { model.reset(clearQueue = false); page = "menu"; begin("receive") }) {
                                    Text(stringResource(R.string.share_receive_next))
                                }
                                TextButton(onClick = { model.reset(clearQueue = false); page = "menu" }) {
                                    Text(stringResource(R.string.sharing))
                                }
                            }
                        }
                    }
                }
                "history", "contents" -> {
                    if (screen == "contents") item {
                        pageState.receivedCover?.let { CoverPreview(it, Modifier.size(120.dp)) }
                        TextButton(enabled = !pageState.busy, onClick = { page = returnPage }) {
                            Text(stringResource(if (bundles) R.string.bundle_choose else R.string.share_received_files))
                        }
                    }
                    if (pageState.saved || pageState.imported) item {
                        Text(stringResource(if (pageState.imported) R.string.share_imported else R.string.share_saved), color = MelkitMint)
                        if (pageState.imported) TextButton(onClick = { model.openGame(context) }) { Text(stringResource(R.string.share_open_game)) }
                    }
                    val received = if (screen == "contents") pageState.received.filter { it.batch == pageState.bundleBatch }
                        else pageState.received
                    if (!connection.connected && received.any { ShareWire.validPath(it.path) }) item {
                        Text(stringResource(R.string.share_restore_help))
                        FilledTonalButton(enabled = !connection.busy, onClick = library::connect) {
                            Text(stringResource(R.string.library_connect))
                        }
                        TextButton(onClick = guide) { Text(stringResource(R.string.shizuku_guide)) }
                    }
                    if (!pageState.busy && received.isEmpty()) item { Text(stringResource(R.string.share_no_received)) }
                    items(received, key = { it.batch + "/" + it.path }) { file ->
                        ShareCard(null, null) {
                            val archive = ShareWire.validBundlePath(file.path)
                            SharePath(if (archive) file.file.name else file.path)
                            Text(Formatter.formatFileSize(context, file.bytes))
                            if (archive) {
                                FilledTonalButton(enabled = !pageState.busy, onClick = {
                                    model.openReceivedBundle(file)
                                    page = "contents"
                                }) { Text(stringResource(R.string.bundle_open_received)) }
                            } else {
                                FilledTonalButton(enabled = !pageState.busy && connection.connected && !connection.busy,
                                    onClick = { restore = file }) { Text(stringResource(R.string.share_import)) }
                                TextButton(enabled = !pageState.busy, onClick = {
                                    if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        != PackageManager.PERMISSION_GRANTED) {
                                        save = file
                                        writeStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    } else model.save(file)
                                }) { Text(stringResource(R.string.share_save_downloads)) }
                                TextButton(enabled = !pageState.busy, onClick = { model.shareReceived(context, file) }) {
                                    Text(stringResource(R.string.share_other_apps))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (bundles && editingCover) {
        CoverEditor(pageState.draftCover, save = { model.setBundleCover(it); true }) { editingCover = false }
    }
    restore?.let { file ->
        AlertDialog(onDismissRequest = { restore = null }, title = { Text(stringResource(R.string.share_import)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SharePath(file.path)
                Text(stringResource(R.string.share_close_game))
            } }, confirmButton = {
                TextButton(enabled = connection.connected && !connection.busy && !pageState.busy, onClick = {
                    restore = null
                    model.restore(file, library)
                }) { Text(stringResource(R.string.vault_closed_continue)) }
            }, dismissButton = { TextButton(onClick = { restore = null }) { Text(stringResource(R.string.saved_cancel)) } })
    }
}

@Composable
private fun SharePath(path: String, modifier: Modifier = Modifier) {
    Text(path, modifier, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr))
}

@Composable
private fun PickShareRow(path: String, selected: Boolean, enabled: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { toggle() }),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        SharePath(path, Modifier.weight(1f))
    }
}

@Composable
private fun ShareCard(title: Int?, description: Int?, body: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(stringResource(title), fontWeight = FontWeight.Bold)
            if (description != null) Text(stringResource(description))
            body()
        }
    }
}

@Composable
private fun ShareError(code: String, bundles: Boolean = false) {
    val message = when (code) {
        "PERMISSION", "STORAGE_PERMISSION" -> R.string.share_permission
        "WIFI_OFF" -> R.string.share_wifi_off
        "LOCATION_OFF" -> R.string.share_location
        "UNSUPPORTED" -> R.string.share_unsupported
        "EXISTING_GROUP", "BUSY" -> R.string.share_existing
        "TIMEOUT" -> R.string.share_timeout
        "BAD_QR", "INCOMPATIBLE" -> R.string.share_bad_qr
        "SPACE" -> R.string.vault_space
        "CHECKSUM" -> if (bundles) R.string.bundle_checksum else R.string.share_checksum
        "INVALID_BUNDLE" -> R.string.bundle_invalid
        "INVALID_FILE", "LIMIT" -> R.string.share_invalid_file
        "DUPLICATE_NAME" -> R.string.share_duplicate_path
        "FILE_CHANGED", "SOURCE_ACCESS" -> R.string.share_source_access
        "SESSION_EXISTS" -> R.string.share_already_received
        "RESTORE_EXISTS" -> R.string.share_restore_exists
        "RESTORE_RECOVERY" -> R.string.profile_recovery_body
        "RESTORE_CONNECT" -> R.string.share_restore_help
        "GAME_MISSING" -> R.string.share_game_missing
        "SPLIT_APK" -> R.string.share_split_apk
        else -> if (bundles) R.string.bundle_failed else R.string.share_failed
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
}

private fun makeQr(value: String): Bitmap {
    val side = 512
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, side, side)
    val pixels = IntArray(side * side) { offset ->
        if (matrix[offset % side, offset / side]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
}
