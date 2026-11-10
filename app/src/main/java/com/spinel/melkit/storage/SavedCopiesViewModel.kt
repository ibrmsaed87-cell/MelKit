package com.spinel.melkit.storage

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

data class SavedCopy(val uri: Uri, val name: String, val path: String, val bytes: Long, val modified: Long) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val restorable: Boolean get() = NewFileRestore.supported(path)
}

data class SavedCopiesState(
    val copies: List<SavedCopy> = emptyList(), val busy: Boolean = false, val loaded: Boolean = false,
    val issue: String? = null, val request: SavedCopy? = null, val restored: String? = null, val revision: Int = 0,
    val conflict: SavedCopy? = null,
)

class SavedCopiesViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SavedCopiesState())
    val state = mutableState.asStateFlow()
    private val resolver = application.contentResolver
    private data class Preview(val bitmap: Bitmap?)
    private val previews = object : LruCache<String, Preview>(4096) {
        override fun sizeOf(key: String, value: Preview): Int =
            ((value.bitmap?.allocationByteCount ?: 0) / 1024).coerceAtLeast(1)
    }
    private val previewMutex = Mutex()

    fun refresh() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, issue = null) }
        viewModelScope.launch {
            try {
                val copies = withContext(Dispatchers.IO) { readCopies() }
                mutableState.update { it.copy(copies = copies, loaded = true, busy = false, revision = it.revision + 1) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: SecurityException) {
                mutableState.update { it.copy(busy = false, issue = "permission") }
            } catch (_: Exception) {
                mutableState.update { it.copy(busy = false, issue = "read") }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readCopies(): List<SavedCopy> {
        val copies = mutableListOf<SavedCopy>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH)
            val query = resolver.query(collection, columns,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/MelKit/%"), null)
                ?: throw IOException("Cannot list saved copies")
            query.use { cursor ->
                while (cursor.moveToNext()) {
                    val prefix = "${Environment.DIRECTORY_DOWNLOADS}/MelKit/"
                    val directory = cursor.getString(4) ?: continue
                    if (!directory.startsWith(prefix)) continue
                    val name = cursor.getString(1) ?: continue
                    val path = directory.removePrefix(prefix) + name
                    if (runCatching { ExportPaths.fileName(path) }.isFailure) continue
                    copies += SavedCopy(ContentUris.withAppendedId(collection, cursor.getLong(0)), name,
                        path, cursor.getLong(2), cursor.getLong(3) * 1000)
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(getApplication<Application>(), Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED) throw SecurityException()
            val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MelKit").canonicalFile
            if (!root.exists()) return emptyList()
            val visited = mutableSetOf<String>()
            fun visit(directory: File) {
                val safe = directory.canonicalFile
                if (!safe.path.startsWith(root.path + "/") && safe != root) return
                if (!visited.add(safe.path)) return
                val children = safe.listFiles() ?: throw IOException("Cannot list saved folder")
                children.forEach { child ->
                    val file = child.canonicalFile
                    if (!file.path.startsWith(root.path + "/")) return@forEach
                    if (file.isDirectory) visit(file)
                    else if (file.isFile) {
                        val path = file.path.removePrefix(root.path + "/")
                        if (runCatching { ExportPaths.fileName(path) }.isSuccess) {
                            copies += SavedCopy(Uri.fromFile(file), file.name, path, file.length(), file.lastModified())
                        }
                    }
                }
            }
            visit(root)
        }
        return copies.sortedWith(compareByDescending<SavedCopy> { it.modified }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    private fun openCopy(copy: SavedCopy): ParcelFileDescriptor = if (copy.uri.scheme == "file") {
        ParcelFileDescriptor.open(File(requireNotNull(copy.uri.path)), ParcelFileDescriptor.MODE_READ_ONLY)
    } else resolver.openFileDescriptor(copy.uri, "r") ?: throw IOException("Saved copy unavailable")

    suspend fun preview(copy: SavedCopy, revision: Int): Bitmap? {
        val key = "$revision:${copy.uri}:${copy.bytes}:${copy.modified}"
        return previewMutex.withLock {
            previews.get(key)?.let { return@withLock it.bitmap }
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(openCopy(copy)).use { input ->
                        PreviewBitmap.decode(GamePreviewReader.readStream(input, copy.extension))
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { null }
            }
            previews.put(key, Preview(bitmap))
            bitmap
        }
    }

    fun requestRestore(copy: SavedCopy) {
        if (!mutableState.value.busy && copy.restorable) mutableState.update {
            it.copy(request = copy, conflict = null, restored = null, issue = null)
        }
    }
    fun dismissRequest() { mutableState.update { it.copy(request = null) } }
    fun dismissResult() { mutableState.update { it.copy(restored = null) } }
    fun dismissConflict() { mutableState.update { it.copy(conflict = null) } }

    fun restore(library: GameLibraryViewModel, asCopy: Boolean = false) {
        val copy = (if (asCopy) mutableState.value.conflict else mutableState.value.request) ?: return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(request = null, conflict = null, restored = null, busy = true, issue = null) }
        viewModelScope.launch {
            val result = library.restoreSavedResult(copy.path, copy.bytes, asCopy, openSource = { openCopy(copy) })
            val publish = {
                mutableState.update { it.copy(busy = false,
                    restored = if (result.status == "ok") result.path else null,
                    conflict = if (result.status == "exists" && !asCopy) copy else null,
                    issue = if (result.status == "ok" || (result.status == "exists" && !asCopy)) null else result.status) }
                if (result.status == "ok") library.connect()
            }
            if (result.status == "ok") library.adBreak(com.spinel.melkit.ads.AdPlacement.RESTORE, publish)
            else publish()
        }
    }
    override fun onCleared() { previews.evictAll(); super.onCleared() }
}
