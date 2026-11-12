package com.spinel.melkit.share

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spinel.melkit.BuildConfig
import com.spinel.melkit.ads.AdManager
import com.spinel.melkit.ads.AdPlacement
import com.spinel.melkit.storage.DownloadCopy
import com.spinel.melkit.storage.CoverPhoto
import com.spinel.melkit.storage.GameEntry
import com.spinel.melkit.storage.GameLibraryViewModel
import com.spinel.melkit.storage.OperationJournal
import com.spinel.melkit.storage.OperationKind
import com.spinel.melkit.storage.PreviewBitmap
import com.spinel.melkit.storage.PreviewImages
import com.spinel.melkit.storage.SavedCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipException

internal data class ReceivedFile(val file: File, val path: String, val batch: String, val bytes: Long)
internal data class SharePage(val queue: List<ShareInput> = emptyList(), val received: List<ReceivedFile> = emptyList(),
    val busy: Boolean = false, val issue: String? = null, val imported: Boolean = false, val saved: Boolean = false,
    val savedPath: String? = null, val bundleStatus: String? = null, val bundleName: String? = null,
    val bundleUri: Uri? = null, val bundleBatch: String? = null,
    val restoreSelection: Set<String> = emptySet(), val batchRestore: BatchRestore.Snapshot? = null,
    val draftCover: Bitmap? = null, val receivedCover: Bitmap? = null, val draftName: String = "",
    val preflight: Map<String, String> = emptyMap(), val contentWarnings: Map<String, String> = emptyMap(),
    val duplicateContent: Map<String, String> = emptyMap(), val exportResults: Map<String, String> = emptyMap(),
    val openedUri: Uri? = null, val openedName: String = "", val editorId: String? = null,
    val editing: Boolean = false, val dirty: Boolean = false, val sourceWritable: Boolean = false,
    val bundleBackupPath: String? = null)

internal class ShareViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val journal = OperationJournal.get(application)
    private val mutableState = MutableStateFlow(SharePage())
    val state = mutableState.asStateFlow()
    val transfer = MelKitShareService.state
    private val catalog = SavedBundleStore(application)
    private val documentWriter = BundleDocumentWriter(application)
    private var editorDirectory: File? = null
    private var editorOriginal: File? = null
    fun editOpenedBundle() { state.value.openedUri?.let { editBundle(it, state.value.openedName) } }
    fun editSavedBundle(item: SavedBundle) = editBundle(item.uri, item.name)
    private fun editBundle(uri: Uri, name: String) = run {
        val draft = runInterruptible(Dispatchers.IO) {
            val directory = File(context.cacheDir, "bundle-editor-" + UUID.randomUUID())
            check(directory.mkdirs()) { "SAVE_FAILED" }
            try {
                val original = File(directory, "original.zip")
                openSource(uri).use { input -> original.outputStream().use { ContentBundle.copy(input, it, ContentBundle.MAX_ARCHIVE, null) } }
                val contents = File(directory, "contents")
                ContentBundle.unpack(original, contents)
                val inputs = contents.walkTopDown().filter { it.isFile && ShareWire.validPath(it.relativeTo(contents).invariantSeparatorsPath) }.map { file ->
                    val hash = BundleDocumentWriter.hash(file)
                    ShareInput(file.relativeTo(contents).invariantSeparatorsPath, file.length(), open = { file.inputStream() },
                        verifySource = { check(MessageDigest.isEqual(hash, BundleDocumentWriter.hash(file))) { "FILE_CHANGED" } })
                }.toList()
                Triple(directory, original, inputs)
            } catch (error: Exception) { directory.deleteRecursively(); throw error }
        }
        editorDirectory?.deleteRecursively()
        editorDirectory = draft.first; editorOriginal = draft.second
        val cover = withContext(Dispatchers.IO) {
            val image = File(draft.first, "contents/" + ContentBundle.COVER_FILE)
            if (image.length() in 1..PreviewImages.MAX_IMAGE_BYTES.toLong()) PreviewBitmap.decode(image.readBytes()) else null
        }
        val writable = withContext(Dispatchers.IO) { documentWriter.writable(uri) }
        mutableState.update { it.copy(queue = draft.third, openedUri = uri, openedName = name,
            draftName = name.removeSuffix(".zip"), draftCover = cover, bundleUri = null,
            editorId = UUID.randomUUID().toString(), editing = true, dirty = false, sourceWritable = writable, bundleBackupPath = null) }
    }
    fun saveEditedBundle() = run {
        val page = state.value
        val original = editorOriginal ?: throw IOException("SOURCE_ACCESS")
        val source = page.openedUri ?: throw IOException("SOURCE_ACCESS")
        val name = page.draftName.trim().removeSuffix(".zip") + ".zip"
        val result = runInterruptible(Dispatchers.IO) {
            withBundleArchive { archive ->
                writeDraft(archive, page.queue, page.draftCover)
                val uri = try { documentWriter.replace(source, original, archive, name) }
                finally { mutableState.update { it.copy(bundleBackupPath = documentWriter.lastBackupPath) } }
                // Refresh the snapshot only after replacement is verified.
                archive.copyTo(original, overwrite = true)
                mutableState.update { it.copy(openedUri = uri) }
                val item = catalog.add(uri, name, archive.length(), page.queue.map { it.path }, page.draftCover, previous = source)
                catalog.forget(source, uri)
                mutableBundles.value = catalog.read()
                uri to item.name
            }
        }
        mutableState.update { it.copy(openedUri = result.first, openedName = result.second, dirty = false) }
        AdManager.get(context).afterTask(AdPlacement.BUNDLE) {
            mutableState.update { it.copy(bundleUri = result.first, bundleName = result.second, bundleStatus = "updated") }
        }
    }
    private fun writeDraft(archive: File, queue: List<ShareInput>, cover: Bitmap?) {
        val sources = queue.map { input -> ContentBundle.Source(input.path, input.size, { input.open() }, { input.verifySource() }) }
        val original = editorOriginal
        val image = cover?.let(CoverPhoto::encode)
        if (state.value.editing && original != null) ContentBundle.writeEdited(archive, original, sources, image)
        else ContentBundle.write(archive, sources, image)
    }
    private val mutableBundles = MutableStateFlow<List<SavedBundle>>(emptyList())
    val savedBundles = mutableBundles.asStateFlow()
    init { refreshBundles() }
    fun refreshBundles() { viewModelScope.launch {
        try { mutableBundles.value = withContext(Dispatchers.IO) { catalog.read() } }
        catch (_: Exception) { issue("CATALOG_FAILED") }
    } }
    suspend fun bundleCover(item: SavedBundle): Bitmap? = withContext(Dispatchers.IO) { catalog.cover(item) }
    fun renameBundle(item: SavedBundle, name: String) = run {
        withContext(Dispatchers.IO) { catalog.rename(item, name); mutableBundles.value = catalog.read() }
    }
    fun deleteBundle(item: SavedBundle) = run {
        withContext(Dispatchers.IO) { catalog.delete(item); mutableBundles.value = catalog.read() }
    }
    fun openSavedBundle(item: SavedBundle) = run {
        withContext(Dispatchers.IO) { catalog.available(item) }
        mutableState.update { it.copy(bundleBatch = null, receivedCover = null, restoreSelection = emptySet(), received = emptyList(), preflight = emptyMap()) }
        val (batch, cover) = runInterruptible(Dispatchers.IO) {
            withBundleArchive { archive ->
                openSource(item.uri).use { source -> archive.outputStream().use { ContentBundle.copy(source, it, ContentBundle.MAX_ARCHIVE, null) } }
                unpackBundle(archive)
            }
        }
        mutableState.update { it.copy(bundleBatch = batch, receivedCover = cover, bundleStatus = "verified", openedUri = item.uri, openedName = item.name) }
    }
    fun prepareBundleShare(item: SavedBundle) {
        if (!state.value.busy) mutableState.update { it.copy(bundleUri = item.uri, bundleName = item.name) }
    }
    fun bundleName(name: String) { mutableState.update { it.copy(draftName = name.take(120), bundleUri = null, dirty = true) } }
    fun clearSelection() { if (!state.value.busy) mutableState.update { it.copy(queue = emptyList(), bundleUri = null, dirty = true) } }

    private var resetWhenIdle = false
    private var activeRestore: BatchRestore? = null

    private fun run(clearMessages: Boolean = true, task: suspend () -> Unit) {
        if (mutableState.value.busy) return
        if (clearMessages) clearFeedback()
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                task()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                issue(errorCode(error))
            } finally {
                mutableState.update { it.copy(busy = false) }
                if (resetWhenIdle) closeBundleSession()
            }
        }
    }

    private fun errorCode(error: Exception): String {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC }) return "SPACE"
        if (error is SecurityException) return "PERMISSION"
        if (error is java.io.FileNotFoundException) return "SOURCE_ACCESS"
        return error.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "IO"
    }

    fun clearFeedback() { mutableState.update { it.copy(issue = null, saved = false, imported = false,
        savedPath = null, bundleStatus = null, exportResults = emptyMap(), batchRestore = if (it.busy) it.batchRestore else null) } }

    fun issue(code: String?) {
        clearFeedback()
        mutableState.update { it.copy(issue = code) }
    }

    fun closeBundleSession() {
        if (mutableState.value.busy) { activeRestore?.requestStop(); resetWhenIdle = true; return }
        resetWhenIdle = false
        // Session state only. Published Downloads and verified received files remain on disk.
        editorDirectory?.deleteRecursively(); editorDirectory = null; editorOriginal = null
        mutableState.value = SharePage()
    }

    fun remove(path: String) {
        clearFeedback()
        val key = ShareWire.pathKey(path)
        mutableState.update { it.copy(queue = it.queue.filterNot { item -> ShareWire.pathKey(item.path) == key },
            issue = null, bundleUri = null, dirty = true) }
    }

    private fun add(input: ShareInput) {
        clearFeedback()
        val queue = mutableState.value.queue
        val key = ShareWire.pathKey(input.path)
        when {
            !ShareWire.validPath(input.path) || input.size == 0L || input.size > ShareWire.MAX_FILE -> issue("INVALID_FILE")
            queue.any { ShareWire.pathKey(it.path) == key } -> issue("DUPLICATE_NAME")
            queue.size >= ShareWire.MAX_FILES -> issue("LIMIT")
            else -> mutableState.update { it.copy(queue = it.queue + input, bundleUri = null, dirty = true) }
        }
    }

    fun toggleGame(entry: GameEntry, library: GameLibraryViewModel) {
        if (selected(entry.path)) remove(entry.path)
        else library.shareInput(entry)?.let(::add) ?: issue("SOURCE_ACCESS")
    }

    fun toggleSaved(copy: SavedCopy) {
        if (selected(copy.path)) remove(copy.path)
        else add(ShareInput(copy.path, copy.bytes.takeIf { it > 0 } ?: -1, open = { openSource(copy.uri) }))
    }

    fun selected(path: String) = mutableState.value.queue.any { ShareWire.pathKey(it.path) == ShareWire.pathKey(path) }

    fun addDocuments(uris: List<Uri>) = run {
        val selected = withContext(Dispatchers.IO) {
            uris.take(ShareWire.MAX_FILES).map { uri ->
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
                    val name = cursor.getString(0)
                    val folder = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                        "melmod" -> "Mods"
                        "melmap" -> "Maps"
                        "melsave" -> "Saves"
                        "melworld" -> "Worlds"
                        else -> return@use null
                    }
                    val bytes = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                    ShareInput("$folder/$name", bytes, open = { openSource(uri) })
                }
            }
        }
        selected.forEach { if (it == null) issue("INVALID_FILE") else add(it) }
        if (uris.size > ShareWire.MAX_FILES) issue("LIMIT")
    }

    private fun openSource(uri: Uri) = if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
        else context.contentResolver.openInputStream(uri) ?: throw IOException("SOURCE_ACCESS")

    fun setBundleCover(cover: Bitmap?) {
        clearFeedback()
        mutableState.update { it.copy(draftCover = cover, bundleUri = null, dirty = true) }
    }

    fun saveBundle(destination: Uri) = run {
        check(destination != state.value.openedUri) { "BUNDLE_SAVE_NEW_REQUIRED" }
        var archiveBytes = 0L
        val queue = mutableState.value.queue
        val cover = mutableState.value.draftCover
        mutableState.update { it.copy(bundleUri = null) }
        runInterruptible(Dispatchers.IO) {
            try {
                withBundleArchive { archive ->
                    writeDraft(archive, queue, cover)
                    val size = archive.length()
                    archiveBytes = size
                    val written = MessageDigest.getInstance("SHA-256")
                    val verified = MessageDigest.getInstance("SHA-256")
                    val count = archive.inputStream().use { source ->
                        val output = context.contentResolver.openOutputStream(destination, "w") ?: throw IOException("IO")
                        output.use { ContentBundle.copy(source, it, size, written) }
                    }
                    val readBack = openSource(destination).use { ContentBundle.copy(it, null, size, verified) }
                    check(count == size && readBack == size && MessageDigest.isEqual(written.digest(), verified.digest())) { "CHECKSUM" }
                }
            } catch (error: Exception) {
                throw error
            }
        }
        val item = try { withContext(Dispatchers.IO) {
            catalog.add(destination, mutableState.value.draftName.ifBlank { "MelKit-Bundle" } + ".zip",
                catalog.metadata(destination).second.takeIf { it >= 0 } ?: archiveBytes, queue.map { it.path }, cover).also { mutableBundles.value = catalog.read() }
        }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { throw IOException("CATALOG_FAILED", error) }
        val name = item.name
        AdManager.get(context).afterTask(AdPlacement.BUNDLE) {
            mutableState.update { it.copy(bundleUri = destination, bundleStatus = "saved", bundleName = name, dirty = false) }
        }
    }

    fun importBundle(uri: Uri) = run {
        catalog.retain(uri)
        mutableState.update { it.copy(bundleBatch = null, receivedCover = null, restoreSelection = emptySet(), preflight = emptyMap(), received = emptyList()) }
        val (batch, cover) = runInterruptible(Dispatchers.IO) {
            withBundleArchive { archive ->
                openSource(uri).use { source ->
                    archive.outputStream().use { ContentBundle.copy(source, it, ContentBundle.MAX_ARCHIVE, null) }
                }
                unpackBundle(archive)
            }
        }
        mutableState.update { it.copy(bundleBatch = batch, receivedCover = cover, bundleStatus = "verified", openedUri = uri,
            openedName = runCatching { catalog.metadata(uri).first }.getOrDefault("MelKit-Bundle.zip")) }
    }

    fun saveReceivedBundle(item: ReceivedFile, destination: Uri) = run {
        val paths = runInterruptible(Dispatchers.IO) {
            ContentBundle.verify(item.file)
            val hash = BundleDocumentWriter.hash(item.file)
            item.file.inputStream().use { input ->
                (context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("SAVE_FAILED")).use {
                    ContentBundle.copy(input, it, ContentBundle.MAX_ARCHIVE, null)
                }
            }
            val readHash = MessageDigest.getInstance("SHA-256")
            openSource(destination).use { ContentBundle.copy(it, null, ContentBundle.MAX_ARCHIVE, readHash) }
            check(MessageDigest.isEqual(hash, readHash.digest())) { "CHECKSUM" }
            java.util.zip.ZipFile(item.file).use { zip -> zip.entries().asSequence().map { it.name }.filter(ShareWire::validPath).toList() }
        }
        withContext(Dispatchers.IO) { catalog.add(destination, item.file.name, item.bytes, paths, null); mutableBundles.value = catalog.read() }
        AdManager.get(context).afterTask(AdPlacement.BUNDLE) {
            mutableState.update { it.copy(bundleStatus = "saved", bundleUri = destination, bundleName = item.file.name) }
        }
    }

    fun openReceivedBundle(item: ReceivedFile) = run {
        mutableState.update { it.copy(bundleBatch = null, receivedCover = null, restoreSelection = emptySet(), preflight = emptyMap(), received = emptyList()) }
        val id = UUID.nameUUIDFromBytes("melkit-received-bundle:${item.batch}:${item.path}".toByteArray()).toString()
        val (batch, cover) = runInterruptible(Dispatchers.IO) { unpackBundle(item.file, id) }
        mutableState.update { it.copy(bundleBatch = batch, receivedCover = cover, bundleStatus = "verified", openedUri = Uri.fromFile(item.file), openedName = item.file.name) }
    }

    private fun unpackBundle(archive: File, id: String = UUID.randomUUID().toString()): Pair<String, Bitmap?> {
        val root = MelKitShareService.receivedDirectory(context)
        check(root.isDirectory || root.mkdirs() || root.isDirectory) { "SAVE_FAILED" }
        val destination = File(root, id)
        if (!destination.isDirectory) {
            val staging = File(context.cacheDir, "${UUID.randomUUID()}.bundle-part")
            try {
                ContentBundle.unpack(archive, staging)
                check((!destination.exists() && staging.renameTo(destination)) || destination.isDirectory) { "SAVE_FAILED" }
            } catch (_: ZipException) {
                throw IOException("INVALID_BUNDLE")
            } catch (_: EOFException) {
                throw IOException("INVALID_BUNDLE")
            } finally {
                staging.deleteRecursively()
            }
        }
        val warnings = linkedMapOf<String, String>()
        val duplicates = linkedMapOf<String, String>()
        val hashes = mutableMapOf<String, String>()
        val validator = com.spinel.melkit.storage.FileScan(destination, com.spinel.melkit.storage.GamePreviewReader::validJsonObject)
        destination.walkTopDown().filter { it.isFile && ShareWire.validPath(it.relativeTo(destination).invariantSeparatorsPath) }.forEach { file ->
            val path = file.relativeTo(destination).invariantSeparatorsPath
            warnings[path] = runCatching { validator.inspectContent(path) }.getOrDefault("unreadable")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ContentBundle.copy(it, null, ShareWire.MAX_FILE, digest) }
            val hash = file.length().toString() + ":" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            hashes.putIfAbsent(hash, path)?.let { duplicates[path] = it }
        }
        mutableState.update { it.copy(contentWarnings = warnings, duplicateContent = duplicates) }
        val image = File(destination, ContentBundle.COVER_FILE)
        val cover = runCatching {
            if (image.length() in 1L..PreviewImages.MAX_IMAGE_BYTES.toLong()) PreviewBitmap.decode(image.readBytes()) else null
        }.getOrNull()
        return id to cover
    }

    private fun <T> withBundleArchive(task: (File) -> T): T {
        val archive = File.createTempFile("melkit-bundle-", ".zip", context.cacheDir)
        try { return task(archive) } finally { archive.delete() }
    }

    fun sendBundle(activity: Context, uri: Uri) = run {
        val input = withContext(Dispatchers.IO) {
            val properties = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val (name, size) = context.contentResolver.query(uri, properties, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val displayName = if (cursor.isNull(0)) "" else cursor.getString(0)
                    val length = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                    displayName to length
                }
            } ?: ("" to -1L)
            val path = "Bundles/$name".takeIf(ShareWire::validBundlePath) ?: "Bundles/MelKit-Bundle.zip"
            ShareInput(path, size.takeIf { it > 0 } ?: -1L, open = { openSource(uri) })
        }
        MelKitShareService.send(activity, listOf(input))
    }

    fun send(activity: Context) {
        val queue = mutableState.value.queue
        if (queue.isNotEmpty()) MelKitShareService.send(activity, queue.toList())
    }

    fun receive(activity: Context, value: String): Boolean {
        val ticket = MelKitShareTicket.parse(value)
        if (ticket == null) { issue("BAD_QR"); return false }
        MelKitShareService.receive(activity, ticket)
        return true
    }

    fun refreshReceived() = run(clearMessages = false) {
        val files = withContext(Dispatchers.IO) {
            val root = MelKitShareService.receivedDirectory(context)
            root.listFiles().orEmpty().filter { it.isDirectory && ShareWire.validBatchId(it.name) }
                .sortedByDescending(File::lastModified).flatMap { batch ->
                    batch.walkTopDown().maxDepth(33).filter { it.isFile }.mapNotNull { file ->
                        val path = file.relativeTo(batch).invariantSeparatorsPath
                        if (ShareWire.validPath(path) || ShareWire.validBundlePath(path)) {
                            ReceivedFile(file, path, batch.name, file.length())
                        } else null
                    }.sortedBy { it.path }.toList()
                }
        }
        mutableState.update { it.copy(received = files) }
    }

    fun restore(item: ReceivedFile, library: GameLibraryViewModel) = run {
        val status = library.restoreSaved(item.path, item.bytes) {
            ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (status == "ok") AdManager.get(context).afterTask(AdPlacement.RESTORE) {
            mutableState.update { it.copy(imported = true) }
        }
        else issue("RESTORE_" + status.uppercase(Locale.ROOT))
    }

    private fun bundleFiles(page: SharePage): List<ReceivedFile> = if (page.bundleBatch == null) emptyList() else
        page.received.filter { it.batch == page.bundleBatch && ShareWire.validPath(it.path) }

    fun inspectBundle(library: GameLibraryViewModel) {
        val batch = state.value.bundleBatch ?: return
        val paths = bundleFiles(state.value).map { it.path }
        viewModelScope.launch {
            val info = library.existingContent(paths)
            if (state.value.bundleBatch == batch) mutableState.update { it.copy(preflight = info) }
        }
    }

    fun saveSelected() = run {
        val files = bundleFiles(state.value).filter { it.path in state.value.restoreSelection }
        val results = linkedMapOf<String, String>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            results[file.path] = try { saveVerified(file); "ok" } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { errorCode(error) }
            if (results.size < files.size) mutableState.update { it.copy(exportResults = results.toMap()) }
        }
        val publish = { mutableState.update { it.copy(exportResults = results.toMap()) } }
        if (results.isNotEmpty() && results.values.all { it == "ok" }) AdManager.get(context).afterTask(AdPlacement.BACKUP, publish)
        else publish()
    }

    fun shareSelected(activity: Context) {
        val files = bundleFiles(state.value).filter { it.path in state.value.restoreSelection }
        if (files.isEmpty()) return
        runCatching {
            val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".sharefiles", it.file) })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/octet-stream")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("MelKit", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            activity.startActivity(Intent.createChooser(send, null))
        }.onFailure { issue("IO") }
    }

    fun shareGame(activity: Context, entry: GameEntry, library: GameLibraryViewModel) = run {
        val input = library.shareInput(entry) ?: throw IOException("SOURCE_ACCESS")
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share-content/" + UUID.randomUUID()).apply { check(mkdirs()) }
            File(dir, entry.name).also { target ->
                try {
                    input.open().use { source -> target.outputStream().use { ContentBundle.copy(source, it, input.size, null) } }
                    check(target.length() == input.size) { "FILE_CHANGED" }; input.verifySource()
                } catch (error: Exception) { target.delete(); throw error }
            }
        }
        shareFile(activity, file, "application/octet-stream")
    }

    fun selectForRestore(path: String) {
        val page = mutableState.value
        if (page.busy || bundleFiles(page).none { it.path == path } || page.contentWarnings[path] in listOf("invalid", "empty")) return
        clearFeedback()
        mutableState.update { it.copy(restoreSelection = if (path in it.restoreSelection)
            it.restoreSelection - path else it.restoreSelection + path) }
    }

    fun selectAllForRestore() {
        val page = mutableState.value
        if (page.busy) return
        val paths = bundleFiles(page).filter { page.contentWarnings[it.path] !in listOf("invalid", "empty") }.map { it.path }.toSet()
        clearFeedback()
        mutableState.update { it.copy(restoreSelection = if (paths.isNotEmpty() && paths == it.restoreSelection)
            emptySet() else paths) }
    }

    fun stopRestore() {
        activeRestore?.let { batch ->
            batch.requestStop()
            mutableState.update { it.copy(batchRestore = batch.snapshot()) }
        }
    }

    fun restoreSelected(library: GameLibraryViewModel) {
        val page = mutableState.value
        if (page.busy) return
        val files = bundleFiles(page).filter { it.path in page.restoreSelection }
        if (files.isEmpty() || files.size != page.restoreSelection.size || files.any { page.contentWarnings[it.path] in listOf("invalid", "empty") }) { issue("INVALID_FILE"); return }
        val batch = try { BatchRestore(files.map { it.path }) } catch (_: IllegalArgumentException) {
            issue("INVALID_FILE"); return
        }
        val byPath = files.associateBy { it.path }
        activeRestore = batch
        run {
            mutableState.update { it.copy(batchRestore = batch.snapshot()) }
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val path = batch.next() ?: break
                    mutableState.update { it.copy(batchRestore = batch.snapshot()) }
                    val item = byPath.getValue(path)
                    val status = library.restoreSaved(item.path, item.bytes) {
                        ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                    batch.complete(status)
                    val progress = batch.snapshot()
                    if (!progress.finished) mutableState.update { it.copy(batchRestore = progress) }
                }
            } finally {
                // Cancellation can arrive after the remote write finishes but before its
                // reply is delivered. Preserve that uncertainty rather than claim failure
                // or success, and leave all files that were not started untouched.
                if (batch.snapshot().current.isNotEmpty()) batch.complete("unconfirmed")
                batch.requestStop()
                val result = batch.snapshot()
                val retry = result.entries.filter { it.outcome == BatchRestore.Outcome.FAILED ||
                    it.outcome == BatchRestore.Outcome.NOT_ATTEMPTED }.map { it.path }.toSet()
                val publish = { mutableState.update { it.copy(batchRestore = result, restoreSelection = retry) } }
                if (currentCoroutineContext().isActive && !result.stopRequested && result.restored == result.total) {
                    // The complete batch is one opportunity, never one per restored file.
                    AdManager.get(context).afterTask(AdPlacement.RESTORE, publish)
                } else publish()
                activeRestore = null
            }
        }
    }

    private suspend fun saveVerified(item: ReceivedFile): String = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            val available = android.os.Environment.getExternalStorageDirectory().usableSpace
            if (available > 0 && available < item.bytes) throw IOException("SPACE")
            val copy = DownloadCopy.create(context, item.path)
            try {
                val sent = MessageDigest.getInstance("SHA-256")
                val bytes = item.file.inputStream().use { source ->
                    DigestOutputStream(copy.openOutput(), sent).use { output -> source.copyTo(output) }
                }
                val readBack = MessageDigest.getInstance("SHA-256")
                val count = copy.openInput().use { input ->
                    java.security.DigestInputStream(input, readBack).use { it.copyTo(DiscardOutput) }
                }
                check(bytes == item.bytes && count == bytes && MessageDigest.isEqual(sent.digest(), readBack.digest())) { "CHECKSUM" }
                val published = copy.publish()
                journal.add(OperationKind.COPY, published)
                published
            } catch (error: Exception) {
                runCatching { copy.remove() }
                throw error
            }
        }
    fun save(item: ReceivedFile) = run {
        val path = saveVerified(item)
        AdManager.get(context).afterTask(AdPlacement.BACKUP) {
            mutableState.update { it.copy(saved = true, savedPath = path) }
        }
    }

    fun shareReceived(activity: Context, item: ReceivedFile) {
        clearFeedback()
        runCatching { shareFile(activity, item.file, "application/octet-stream") }.onFailure { issue("IO") }
    }

    fun shareApk(activity: Context) = run {
        val apk = withContext(Dispatchers.IO) {
            val info = context.applicationInfo
            require(info.splitSourceDirs.isNullOrEmpty()) { "SPLIT_APK" }
            val source = File(info.sourceDir)
            val directory = File(context.cacheDir, "share-app").apply { mkdirs() }
            val file = File(directory, "MelKit-" + BuildConfig.VERSION_NAME + ".apk")
            if (file.length() != source.length() || file.lastModified() < source.lastModified()) {
                val temp = File.createTempFile("copy-", ".part", directory)
                try {
                    source.copyTo(temp, overwrite = true)
                    check(temp.length() == source.length() && temp.renameTo(file)) { "SAVE_FAILED" }
                } finally { temp.delete() }
            }
            file
        }
        shareFile(activity, apk, "application/vnd.android.package-archive")
    }

    private fun shareFile(activity: Context, file: File, type: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".sharefiles", file)
        shareUri(activity, uri, type, file.name)
    }

    private fun shareUri(activity: Context, uri: Uri, type: String, label: String) {
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(label, uri)
        activity.startActivity(Intent.createChooser(send, null))
    }

    fun shareLink(activity: Context) {
        clearFeedback()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://play.google.com/store/apps/details?id=" + context.packageName)
        runCatching { activity.startActivity(Intent.createChooser(send, null)) }.onFailure { issue("IO") }
    }

    fun openGame(activity: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage("com.studio27.MelonPlayground")
        if (intent == null) issue("GAME_MISSING")
        else runCatching { activity.startActivity(intent) }.onFailure { issue("IO") }
    }

    fun reset(clearQueue: Boolean) {
        MelKitShareService.reset()
        mutableState.update { it.copy(queue = if (clearQueue) emptyList() else it.queue,
            issue = null, saved = false, imported = false, savedPath = null, bundleStatus = null,
            bundleBatch = null, receivedCover = null, received = emptyList(), restoreSelection = emptySet(),
            batchRestore = null, preflight = emptyMap(), exportResults = emptyMap()) }
    }

    private object DiscardOutput : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
