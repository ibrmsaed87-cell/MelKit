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
    val bundleUri: Uri? = null, val bundleBatch: String? = null,
    val draftCover: Bitmap? = null, val receivedCover: Bitmap? = null)

internal class ShareViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val journal = OperationJournal.get(application)
    private val mutableState = MutableStateFlow(SharePage())
    val state = mutableState.asStateFlow()
    val transfer = MelKitShareService.state

    private fun run(task: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, issue = null, saved = false, imported = false) }
        viewModelScope.launch {
            try { task() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                issue(error.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "IO")
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun issue(code: String?) { mutableState.update { it.copy(issue = code) } }

    fun remove(path: String) {
        val key = ShareWire.pathKey(path)
        mutableState.update { it.copy(queue = it.queue.filterNot { item -> ShareWire.pathKey(item.path) == key },
            issue = null, bundleUri = null) }
    }

    private fun add(input: ShareInput) {
        val queue = mutableState.value.queue
        val key = ShareWire.pathKey(input.path)
        when {
            !ShareWire.validPath(input.path) || input.size == 0L || input.size > ShareWire.MAX_FILE -> issue("INVALID_FILE")
            queue.any { ShareWire.pathKey(it.path) == key } -> issue("DUPLICATE_NAME")
            queue.size >= ShareWire.MAX_FILES -> issue("LIMIT")
            else -> mutableState.update { it.copy(queue = it.queue + input, bundleUri = null) }
        }
    }

    fun toggleGame(entry: GameEntry, library: GameLibraryViewModel) {
        if (selected(entry.path)) remove(entry.path)
        else library.shareInput(entry)?.let(::add) ?: issue("SOURCE_ACCESS")
    }

    fun toggleSaved(copy: SavedCopy) {
        if (selected(copy.path)) remove(copy.path)
        else add(ShareInput(copy.path, copy.bytes.takeIf { it > 0 } ?: -1, open = { open(copy.uri) }))
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
                    ShareInput("$folder/$name", bytes, open = { open(uri) })
                }
            }
        }
        selected.forEach { if (it == null) issue("INVALID_FILE") else add(it) }
        if (uris.size > ShareWire.MAX_FILES) issue("LIMIT")
    }

    private fun open(uri: Uri) = if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
        else context.contentResolver.openInputStream(uri) ?: throw IOException("SOURCE_ACCESS")

    fun setBundleCover(cover: Bitmap?) {
        mutableState.update { it.copy(draftCover = cover, bundleUri = null) }
    }

    fun saveBundle(destination: Uri) = run {
        val queue = mutableState.value.queue
        val cover = mutableState.value.draftCover
        mutableState.update { it.copy(bundleUri = null) }
        runInterruptible(Dispatchers.IO) {
            try {
                withBundleArchive { archive ->
                    ContentBundle.write(archive, queue.map { input ->
                        ContentBundle.Source(input.path, input.size, { input.open() }, { input.verifySource() })
                    }, cover?.let(CoverPhoto::encode))
                    val size = archive.length()
                    val written = MessageDigest.getInstance("SHA-256")
                    val verified = MessageDigest.getInstance("SHA-256")
                    val count = archive.inputStream().use { source ->
                        val output = context.contentResolver.openOutputStream(destination, "w") ?: throw IOException("IO")
                        output.use { ContentBundle.copy(source, it, size, written) }
                    }
                    val readBack = open(destination).use { ContentBundle.copy(it, null, size, verified) }
                    check(count == size && readBack == size && MessageDigest.isEqual(written.digest(), verified.digest())) { "CHECKSUM" }
                }
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                throw error
            }
        }
        mutableState.update { it.copy(bundleUri = destination) }
    }

    fun importBundle(uri: Uri) = run {
        val (batch, cover) = runInterruptible(Dispatchers.IO) {
            withBundleArchive { archive ->
                open(uri).use { source ->
                    archive.outputStream().use { ContentBundle.copy(source, it, ContentBundle.MAX_ARCHIVE, null) }
                }
                unpackBundle(archive)
            }
        }
        mutableState.update { it.copy(bundleBatch = batch, receivedCover = cover) }
    }

    fun openReceivedBundle(item: ReceivedFile) = run {
        mutableState.update { it.copy(bundleBatch = null, receivedCover = null) }
        val id = UUID.nameUUIDFromBytes("melkit-received-bundle:${item.batch}".toByteArray()).toString()
        val (batch, cover) = runInterruptible(Dispatchers.IO) { unpackBundle(item.file, id) }
        mutableState.update { it.copy(bundleBatch = batch, receivedCover = cover) }
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

    fun shareBundle(activity: Context, uri: Uri) {
        runCatching { shareUri(activity, uri, "application/zip", "MelKit") }.onFailure { issue("IO") }
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
            ShareInput(path, size.takeIf { it > 0 } ?: -1L, open = { open(uri) })
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

    fun refreshReceived() = run {
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
        if (status == "ok") mutableState.update { it.copy(imported = true) }
        else issue("RESTORE_" + status.uppercase(Locale.ROOT))
    }

    fun save(item: ReceivedFile) = run {
        withContext(Dispatchers.IO) {
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
                journal.add(OperationKind.COPY, copy.publish())
            } catch (error: Exception) {
                runCatching { copy.remove() }
                throw error
            }
        }
        mutableState.update { it.copy(saved = true) }
    }

    fun shareReceived(activity: Context, item: ReceivedFile) {
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
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://github.com/ibrmsaed87-cell/MelKit")
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
            issue = null, saved = false, imported = false) }
    }

    private object DiscardOutput : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
