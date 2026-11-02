package com.spinel.melkit.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One newly created copy in shared Downloads; never opens an existing file for writing. */
internal class DownloadCopy private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri?,
    private val file: File?,
    private val relativeDirectory: String,
) {
    fun openOutput(): OutputStream = if (uri != null) {
        resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write copy")
    } else requireNotNull(file).outputStream()

    fun openInput(): InputStream = if (uri != null) {
        resolver.openInputStream(uri) ?: throw IOException("Cannot verify copy")
    } else requireNotNull(file).inputStream()

    fun publish(): String {
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore may suffix the name when a previous copy already exists.
            val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: throw IOException("Cannot read saved filename")
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Cannot publish verified copy")
            return relativeDirectory + name
        }
        return relativeDirectory + requireNotNull(file).name
    }

    fun remove(): Boolean = if (uri != null) {
        resolver.delete(uri, null, null) > 0
    } else requireNotNull(file).let { !it.exists() || it.delete() }

    companion object {
        @Suppress("DEPRECATION")
        fun create(context: Context, contentPath: String): DownloadCopy {
            val directory = Environment.DIRECTORY_DOWNLOADS + "/" + ExportPaths.relativeDirectory(contentPath)
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, ExportPaths.fileName(contentPath))
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Cannot create Downloads entry")
                return DownloadCopy(resolver, uri, null, directory)
            }
            val file = ExportPaths.reserveLegacy(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), contentPath,
            )
            return DownloadCopy(resolver, null, file, directory)
        }
    }
}
