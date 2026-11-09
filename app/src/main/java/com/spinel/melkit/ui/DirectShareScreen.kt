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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
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
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DirectShareScreen(model: ShareViewModel, library: GameLibraryViewModel,
    connection: GameLibraryState, saved: SavedCopiesViewModel, copies: SavedCopiesState,
    bundles: Boolean = false, guide: () -> Unit) {
    val context = LocalContext.current
    val pageState by model.state.collectAsState()
    val transfer by model.transfer.collectAsState()
    var page by rememberSaveable { mutableStateOf(if (bundles) "bundle_menu" else "menu") }
    var source by rememberSaveable { mutableStateOf("game") }
    var pending by rememberSaveable { mutableStateOf("") }
    var editingCover by rememberSaveable { mutableStateOf(false) }
    var restore by remember { mutableStateOf<ReceivedFile?>(null) }
    var confirmBatch by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf<ReceivedFile?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.saved_location, pageState.savedPath.orEmpty())
    val openFolderLabel = stringResource(R.string.open_saved_folder)
    val issueMessage = pageState.issue?.let { stringResource(shareErrorText(it, bundles || page == "contents")) }
    val importedMessage = stringResource(R.string.share_imported)
    LaunchedEffect(pageState.savedPath, pageState.issue, pageState.imported) {
        val path = pageState.savedPath
        when {
            path != null -> {
                val result = snackbar.showSnackbar(savedMessage, actionLabel = openFolderLabel,
                    withDismissAction = true, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) openSavedFolder(context, path)
            }
            issueMessage != null -> snackbar.showSnackbar(issueMessage, withDismissAction = true,
                duration = SnackbarDuration.Long)
            pageState.imported -> snackbar.showSnackbar(importedMessage)
        }
    }
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
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
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
    LaunchedEffect(pageState.bundleUri) {
        if (bundles && pageState.bundleUri != null && page == "pick") page = "bundle_ready"
    }
    LaunchedEffect(pageState.bundleBatch) {
        if (bundles && pageState.bundleBatch != null) page = "contents"
    }
    LaunchedEffect(page, pageState.bundleBatch) {
        if (page == "history" || (page == "contents" && pageState.bundleBatch != null)) {
            model.state.first { !it.busy }
            model.refreshReceived()
        }
    }
    val entryPage = if (bundles) "bundle_menu" else "menu"
    val returnPage = if (!bundles && page == "contents") "history" else entryPage
    BackHandler(enabled = page != entryPage && !transfer.active && !pageState.busy) {
        model.clearFeedback()
        page = returnPage
    }

    val screen = when {
        transfer.active -> "transfer"
        pageState.batchRestore?.finished == false -> "contents"
        else -> page
    }
    val bundleFiles = if (screen == "contents") pageState.received.filter {
        it.batch == pageState.bundleBatch && ShareWire.validPath(it.path)
    } else emptyList()
    val selectedCount = bundleFiles.count { it.path in pageState.restoreSelection }
    val batch = pageState.batchRestore
    val outcomes = batch?.entries?.associateBy { it.path }.orEmpty()
    val restoreToolbar = screen == "contents" && bundleFiles.isNotEmpty()
    val listState = remember(screen) { LazyListState() }
    val batchMessage = batch?.let {
        stringResource(R.string.bulk_finished_message, it.restored, it.skipped, it.failed, it.notAttempted)
    }
    LaunchedEffect(batch?.finished) {
        if (batch?.finished == true && screen == "contents") {
            listState.scrollToItem(0)
            batchMessage?.let { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        key(screen) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                top = 16.dp, bottom = when {
                    restoreToolbar && batch != null && !batch.finished -> 160.dp
                    screen == "pick" || restoreToolbar -> 100.dp
                    else -> 20.dp
                }),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(when (screen) {
                        "pick" -> if (bundles) R.string.bundle_choose else R.string.share_choose
                        "history" -> R.string.share_received_files
                        "contents" -> R.string.bundle_contents
                        "transfer" -> R.string.share_transfer
                        "bundle_ready" -> R.string.bundle_ready
                        "bundle_menu" -> R.string.share_bundle
                        else -> R.string.sharing
                    }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if ((pageState.busy && batch == null) || (screen == "pick" && source == "game" && connection.busy)) {
                    item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                }
                pageState.issue?.let { error -> item { ShareError(error, bundles || screen == "contents") } }

                when (screen) {
                    "bundle_menu" -> {
                        item {
                            ShareCard(R.string.bundle_new, R.string.bundle_new_help) {
                                FilledTonalButton(modifier = Modifier.fillMaxWidth(), enabled = !pageState.busy, onClick = {
                                    model.closeBundleSession()
                                    source = "game"
                                    if (connection.connected && !connection.busy) library.browse("")
                                    page = "pick"
                                }) {
                                    Icon(Icons.Outlined.Add, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.bundle_new))
                                }
                            }
                        }
                        item {
                            ShareCard(R.string.bundle_import, R.string.bundle_open_help) {
                                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !pageState.busy, onClick = {
                                    model.clearFeedback()
                                    bundleSource?.launch(arrayOf("*/*"))
                                }) {
                                    Icon(Icons.Outlined.FolderOpen, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.bundle_import))
                                }
                            }
                        }
                    }
                    "bundle_ready" -> {
                        if (pageState.bundleStatus == "saved" && pageState.issue == null) item {
                            ShareNotice(stringResource(R.string.bundle_saved))
                        }
                        item {
                            ShareCard(null, null) {
                                pageState.draftCover?.let { CoverPreview(it, Modifier.size(96.dp)) }
                                pageState.bundleName?.let { SharePath(it) }
                                pageState.bundleUri?.let { uri ->
                                    FilledTonalButton(modifier = Modifier.fillMaxWidth(), enabled = !pageState.busy && !transfer.active,
                                        onClick = { begin("send_bundle") }) { Text(stringResource(R.string.bundle_send_direct)) }
                                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !pageState.busy,
                                        onClick = { model.shareBundle(context, uri) }) {
                                        Icon(Icons.Outlined.Share, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.share_other_apps))
                                    }
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = { model.clearFeedback(); page = entryPage }) {
                                Text(stringResource(R.string.bundle_home))
                            }
                        }
                    }
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
                                if (bundles) {
                                    OutlinedButton(enabled = !pageState.busy, onClick = { model.clearFeedback(); page = entryPage }) {
                                        Text(stringResource(R.string.bundle_home))
                                    }
                                }
                                ShareCard(null, null) {
                                    Text(stringResource(R.string.share_queue_count, pageState.queue.size), fontWeight = FontWeight.Bold)
                                    if (bundles) Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        pageState.draftCover?.let { CoverPreview(it, Modifier.size(64.dp)) }
                                        OutlinedButton(enabled = !pageState.busy, onClick = { editingCover = true }) {
                                            Text(stringResource(R.string.cover_edit))
                                        }
                                    }
                                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !pageState.busy, onClick = {
                                        model.clearFeedback()
                                        documents.launch(arrayOf("*/*"))
                                    }) {
                                        Icon(Icons.Outlined.FolderOpen, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.share_choose_documents))
                                    }
                                }
                                TabRow(selectedTabIndex = if (source == "game") 0 else 1, containerColor = MelkitBackground,
                                    contentColor = MelkitMint) {
                                    Tab(selected = source == "game", onClick = { model.clearFeedback(); source = "game" },
                                        text = { Text(stringResource(R.string.share_from_game)) })
                                    Tab(selected = source == "saved", onClick = {
                                        model.clearFeedback()
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
                                Surface(shape = RoundedCornerShape(12.dp), color = MelkitSurface) {
                                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        SharePath(file.path, Modifier.weight(1f))
                                        IconButton(enabled = !pageState.busy, onClick = { model.remove(file.path) }) {
                                            Icon(Icons.Outlined.Close, stringResource(R.string.share_remove))
                                        }
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
                                    if (connection.path.isNotEmpty()) OutlinedButton(enabled = !connection.busy, onClick = {
                                        library.browse(connection.path.substringBeforeLast('/', ""))
                                    }) { Text(stringResource(R.string.share_parent)) }
                                    if (connection.issue != null) Text(stringResource(R.string.share_source_access))
                                    else if (connection.loaded && connection.entries.isEmpty()) Text(stringResource(R.string.share_empty_source))
                                }
                                items(connection.entries.filter { it.directory || (it.regular && ShareWire.validPath(it.path) && it.size > 0) },
                                    key = { "game:" + it.path }) { entry ->
                                    if (entry.directory) OutlinedCard(enabled = !connection.busy, onClick = {
                                        model.clearFeedback(); library.browse(entry.path)
                                    }) {
                                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Icon(Icons.Outlined.Folder, null, tint = MelkitMint)
                                            SharePath(entry.name, Modifier.weight(1f))
                                        }
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
                                    TextButton(onClick = { model.reset(clearQueue = false); page = if (pageState.bundleUri != null) "bundle_ready" else entryPage }) {
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
                            OutlinedButton(enabled = !pageState.busy, onClick = { model.clearFeedback(); page = returnPage }) {
                                Text(stringResource(if (bundles) R.string.bundle_home else R.string.share_received_files))
                            }
                            if (pageState.bundleStatus == "verified") ShareNotice(stringResource(R.string.bundle_verified))
                        }
                        if (screen == "contents" && bundleFiles.isNotEmpty()) item {
                            ShareCard(null, null) {
                                Text(stringResource(R.string.bulk_selected_count, selectedCount), fontWeight = FontWeight.Bold)
                                OutlinedButton(enabled = !pageState.busy, onClick = model::selectAllForRestore) {
                                    Text(stringResource(if (selectedCount == bundleFiles.size) R.string.bulk_deselect_all
                                        else R.string.bulk_select_all))
                                }
                                Text(stringResource(R.string.bulk_existing_help), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (screen == "contents" && batch != null) item {
                            RestoreBatchSummary(batch)
                            if (batch.finished && batch.restored > 0) OutlinedButton(onClick = { model.openGame(context) }) {
                                Text(stringResource(R.string.share_open_game))
                            }
                        }
                        if (pageState.saved || pageState.imported) item {
                            if (pageState.imported) {
                                ShareNotice(stringResource(R.string.share_imported))
                                OutlinedButton(onClick = { model.openGame(context) }) { Text(stringResource(R.string.share_open_game)) }
                            } else pageState.savedPath?.let { path ->
                                ShareCard(R.string.saved_copy_ready, null) {
                                    SharePath(path)
                                    OutlinedButton(onClick = { openSavedFolder(context, path) }) {
                                        Text(stringResource(R.string.open_saved_folder))
                                    }
                                }
                            }
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
                                if (screen == "contents" && !archive) {
                                    PickShareRow(file.path, file.path in pageState.restoreSelection, !pageState.busy) {
                                        model.selectForRestore(file.path)
                                    }
                                    outcomes[file.path]?.let { outcome -> RestoreFileResult(outcome) }
                                } else SharePath(if (archive) file.file.name else file.path)
                                Text(Formatter.formatFileSize(context, file.bytes))
                                if (archive) {
                                    FilledTonalButton(enabled = !pageState.busy, onClick = {
                                        model.openReceivedBundle(file)
                                        page = "contents"
                                    }) { Text(stringResource(R.string.bundle_open_received)) }
                                } else {
                                    FilledTonalButton(enabled = !pageState.busy && connection.connected && !connection.busy,
                                        onClick = { restore = file }) { Text(stringResource(R.string.share_import)) }
                                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !pageState.busy, onClick = {
                                        if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                            != PackageManager.PERMISSION_GRANTED) {
                                            save = file
                                            writeStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        } else model.save(file)
                                    }) { Text(stringResource(R.string.share_save_downloads)) }
                                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !pageState.busy, onClick = { model.shareReceived(context, file) }) {
                                        Text(stringResource(R.string.share_other_apps))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (screen == "pick") {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MelkitSurface, shadowElevation = 4.dp) {
                FilledTonalButton(modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    enabled = pageState.queue.isNotEmpty() && !pageState.busy && !connection.busy, onClick = {
                        if (bundles) {
                            model.clearFeedback()
                            bundleDestination?.launch("MelKit-Bundle.zip")
                        } else begin("send")
                    }) { Text(stringResource(if (bundles) R.string.bundle_create else R.string.share_create_qr)) }
            }
        }
        if (restoreToolbar) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MelkitSurface, shadowElevation = 4.dp) {
                if (batch != null && !batch.finished) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.bulk_progress, batch.completed, batch.total))
                        LinearProgressIndicator(progress = { batch.completed.toFloat() / batch.total }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !batch.stopRequested,
                            onClick = model::stopRestore) {
                            Text(stringResource(if (batch.stopRequested) R.string.bulk_stop_requested else R.string.bulk_stop))
                        }
                    }
                } else {
                    FilledTonalButton(modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        enabled = selectedCount > 0 && !pageState.busy && connection.connected && !connection.busy,
                        onClick = { confirmBatch = true }) {
                        Text(stringResource(R.string.bulk_restore_action, selectedCount))
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    if (bundles && editingCover) {
        CoverEditor(pageState.draftCover, save = { model.setBundleCover(it); true }) { editingCover = false }
    }
    if (confirmBatch) {
        AlertDialog(onDismissRequest = { confirmBatch = false },
            title = { Text(stringResource(R.string.bulk_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bulk_restore_confirm, selectedCount))
                    Text(stringResource(R.string.share_close_game))
                }
            }, confirmButton = {
                TextButton(enabled = selectedCount > 0 && connection.connected && !connection.busy && !pageState.busy,
                    onClick = { confirmBatch = false; model.restoreSelected(library) }) {
                    Text(stringResource(R.string.vault_closed_continue))
                }
            }, dismissButton = {
                TextButton(onClick = { confirmBatch = false }) { Text(stringResource(R.string.saved_cancel)) }
            })
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
private fun RestoreBatchSummary(batch: BatchRestore.Snapshot) {
    ShareCard(if (batch.finished) R.string.bulk_result_title else R.string.bulk_progress_title, null) {
        Text(stringResource(R.string.bulk_progress, batch.completed, batch.total))
        LinearProgressIndicator(progress = { batch.completed.toFloat() / batch.total }, modifier = Modifier.fillMaxWidth())
        if (batch.current.isNotEmpty()) SharePath(batch.current)
        if (batch.stopRequested && !batch.finished) Text(stringResource(R.string.bulk_stop_help))
        Text(stringResource(R.string.bulk_counts, batch.restored, batch.skipped, batch.failed))
        if (batch.finished && batch.notAttempted > 0) {
            Text(stringResource(R.string.bulk_not_attempted_count, batch.notAttempted))
        }
        if (batch.finished && (batch.failed > 0 || batch.notAttempted > 0)) {
            Text(stringResource(R.string.bulk_retry_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (batch.finished && batch.stopReason.isNotEmpty() && batch.stopReason != "cancelled") {
            Text(stringResource(restoreResultText(batch.stopReason)), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RestoreFileResult(entry: BatchRestore.Entry) {
    val color = when (entry.outcome) {
        BatchRestore.Outcome.RESTORED -> MelkitMint
        BatchRestore.Outcome.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(stringResource(restoreResultText(entry.status)), color = color, style = MaterialTheme.typography.bodySmall)
}

private fun restoreResultText(status: String): Int = when (status) {
    "ok" -> R.string.bulk_restored
    "exists" -> R.string.bulk_skipped
    "running" -> R.string.bulk_running
    "waiting" -> R.string.bulk_waiting
    "not_attempted", "cancelled" -> R.string.bulk_not_attempted
    "connect" -> R.string.share_restore_help
    "recovery" -> R.string.profile_recovery_body
    "busy" -> R.string.bulk_busy
    "partial" -> R.string.bulk_partial
    "changed" -> R.string.bulk_changed
    "unconfirmed" -> R.string.bulk_unconfirmed
    else -> R.string.bulk_failed
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
private fun ShareNotice(message: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = if (error) colors.errorContainer else MelkitSurface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null,
                tint = if (error) colors.onErrorContainer else MelkitMint)
            Text(message, Modifier.weight(1f), color = if (error) colors.onErrorContainer else colors.onSurface)
        }
    }
}

@Composable
private fun ShareError(code: String, bundles: Boolean = false) {
    ShareNotice(stringResource(shareErrorText(code, bundles)), error = true)
}

private fun shareErrorText(code: String, bundles: Boolean): Int {
    return when (code) {
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
}

private fun makeQr(value: String): Bitmap {
    val side = 512
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, side, side)
    val pixels = IntArray(side * side) { offset ->
        if (matrix[offset % side, offset / side]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
}
