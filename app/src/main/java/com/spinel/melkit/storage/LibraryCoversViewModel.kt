package com.spinel.melkit.storage

import android.app.Application
import android.graphics.Bitmap
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal class LibraryCoversViewModel(application: Application) : AndroidViewModel(application) {
    private val directory = File(application.filesDir, "library-covers")
    private val journal = OperationJournal.get(application)
    private val mutex = Mutex()
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()

    suspend fun load(path: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!NewFileRestore.supported(path)) return@withContext null
        mutex.withLock {
            runCatching {
                file(path).openRead().use { input ->
                    PreviewBitmap.decode(PreviewImages.limited(input, PreviewImages.MAX_IMAGE_BYTES.toLong()).readBytes())
                }
            }.getOrNull()
        }
    }

    suspend fun save(path: String, image: Bitmap?): Boolean = withContext(Dispatchers.IO) {
        if (!NewFileRestore.supported(path)) return@withContext false
        mutex.withLock {
            val saved = runCatching {
                val cover = file(path)
                if (image == null) {
                    cover.delete()
                    check(!cover.baseFile.exists())
                } else {
                    val bytes = CoverPhoto.encode(image)
                    check(bytes.size <= PreviewImages.MAX_IMAGE_BYTES)
                    check(directory.isDirectory || directory.mkdirs())
                    val output = cover.startWrite()
                    try {
                        output.write(bytes)
                        cover.finishWrite(output)
                    } catch (failure: Exception) {
                        cover.failWrite(output)
                        throw failure
                    }
                }
            }.isSuccess
            if (saved) {
                mutableRevision.update { it + 1 }
                journal.add(OperationKind.EDIT_COVER, path)
            }
            saved
        }
    }

    private fun file(path: String): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$hash.png"))
    }
}
