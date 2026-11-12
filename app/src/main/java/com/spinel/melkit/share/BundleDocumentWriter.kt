package com.spinel.melkit.share

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.Document
import android.system.Os
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Uses the original granted URI; never asks for or derives a parent-directory permission. */
internal class BundleDocumentWriter(private val context: Context) {
    private val resolver = context.contentResolver
    var lastBackupPath: String? = null
        private set

    fun writable(uri: Uri): Boolean = try {
        if (uri.scheme == "file") ownedFile(uri).canWrite()
        else if (uri.scheme != "content") false
        else {
            val allowsWrite = runCatching {
                resolver.query(uri, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                    !cursor.moveToFirst() || (cursor.getInt(0) and Document.FLAG_SUPPORTS_WRITE) != 0
                } ?: true
            }.getOrDefault(true)
            // Non-truncating probe checks this app's actual grant, including MediaStore ownership.
            allowsWrite && (resolver.openFileDescriptor(uri, "rw")?.use { true } ?: false)
        }
    } catch (_: Exception) { false }

    private fun ownedFile(uri: Uri): File {
        val file = File(requireNotNull(uri.path)).canonicalFile
        check(file.path.startsWith(MelKitShareService.receivedDirectory(context).canonicalPath + File.separator)) { "PERMISSION" }
        return file
    }

    private fun digest(uri: Uri): ByteArray = if (uri.scheme == "file") hash(ownedFile(uri)) else
        (resolver.openInputStream(uri) ?: throw IOException("SOURCE_ACCESS")).use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            ContentBundle.copy(input, null, ContentBundle.MAX_ARCHIVE, hash)
            hash.digest()
        }

    fun replace(source: Uri, original: File, edited: File, name: String): Uri {
        lastBackupPath = null
        check(writable(source)) { "BUNDLE_READ_ONLY" }
        check(name.isNotBlank() && name.length <= 124 && name.none { it == '/' || it == '\\' || it.code < 32 }) { "INVALID_NAME" }
        ContentBundle.verify(edited)
        val oldHash = hash(original)
        check(MessageDigest.isEqual(oldHash, digest(source))) { "FILE_CHANGED" }
        if (source.scheme == "file") {
            val file = ownedFile(source)
            val target = File(file.parentFile, name)
            check(target == file || file.parentFile!!.listFiles().orEmpty().none { it.name.equals(name, true) }) { "DUPLICATE_NAME" }
            val backup = File(file.parentFile, file.nameWithoutExtension + ".before-edit-" + UUID.randomUUID() + ".zip")
            original.copyTo(backup)
            check(MessageDigest.isEqual(hash(backup), oldHash)) { "CHECKSUM" }
            lastBackupPath = backup.path
            val staged = File.createTempFile(".melkit-edit-", ".tmp", file.parentFile)
            try {
                edited.inputStream().use { input -> java.io.FileOutputStream(staged).use { output ->
                    ContentBundle.copy(input, output, ContentBundle.MAX_ARCHIVE, null); output.fd.sync()
                } }
                ContentBundle.verify(staged)
                check(MessageDigest.isEqual(hash(edited), hash(staged))) { "CHECKSUM" }
                check(MessageDigest.isEqual(oldHash, digest(source))) { "FILE_CHANGED" }
                // POSIX rename in the same directory publishes the complete ZIP atomically.
                if (target != file) check(target.createNewFile()) { "DUPLICATE_NAME" }
                try { Os.rename(staged.path, target.path) }
                catch (error: Exception) { if (target != file && target.length() == 0L) target.delete(); throw error }
                if (target != file) {
                    check(MessageDigest.isEqual(oldHash, hash(file))) { "FILE_CHANGED" }
                    check(file.delete()) { "SAVE_FAILED" }
                }
                return Uri.fromFile(target)
            } finally { staged.delete() }
        }
        ContentBundle.verify(original)
        // Single-document grants are sufficient; no parent lookup or OpenDocumentTree is needed.
        val backup = try { backupOriginal(original) }
        catch (error: Exception) { throw IOException("BUNDLE_BACKUP_FAILED", error) }
        lastBackupPath = backup.path
        VerifiedBundleOverwrite.replace(original, edited, { backup.open() }, object : VerifiedBundleOverwrite.Target {
            override fun open() = resolver.openInputStream(source) ?: throw IOException("SOURCE_ACCESS")
            override fun overwrite(): java.io.OutputStream = overwriteStream(source)
        })
        // Keep the original URI and filename. A different destination/name uses Save as new.
        return source
    }

    private fun overwriteStream(uri: Uri): java.io.OutputStream {
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("BUNDLE_READ_ONLY")
        val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        try {
            output.channel.truncate(0L)
            output.channel.position(0L)
        } catch (error: Exception) { output.close(); throw error }
        return object : java.io.FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
            override fun close() {
                try { flush(); output.fd.sync() } finally { super.close() }
            }
        }
    }

    private data class Backup(val path: String, val open: () -> java.io.InputStream)

    @Suppress("DEPRECATION")
    private fun backupOriginal(original: File): Backup {
        val name = "MelKit-before-edit-" + UUID.randomUUID() + ".zip"
        val directory = Environment.DIRECTORY_DOWNLOADS + "/MelKit/BundleBackups/"
        val expected = hash(original)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("BUNDLE_BACKUP_FAILED")
            var published = false
            try {
                original.inputStream().use { input -> overwriteStream(uri).use { output ->
                    ContentBundle.copy(input, output, ContentBundle.MAX_ARCHIVE, null)
                } }
                check(MessageDigest.isEqual(expected, digest(uri))) { "CHECKSUM" }
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(uri, ready, null, null) == 1) { "BUNDLE_BACKUP_FAILED" }
                check(MessageDigest.isEqual(expected, digest(uri))) { "CHECKSUM" }
                published = true
                return Backup(directory + name) { resolver.openInputStream(uri) ?: throw IOException("SOURCE_ACCESS") }
            } finally {
                // This is only a newly created pending copy; never delete a published backup or source.
                if (!published) runCatching { resolver.delete(uri, null, null) }
            }
        }
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MelKit/BundleBackups")
        check(folder.isDirectory || folder.mkdirs()) { "BUNDLE_BACKUP_FAILED" }
        val file = File(folder, name)
        check(file.createNewFile()) { "BUNDLE_BACKUP_FAILED" }
        original.inputStream().use { input -> java.io.FileOutputStream(file).use { output ->
            ContentBundle.copy(input, output, ContentBundle.MAX_ARCHIVE, null); output.fd.sync()
        } }
        check(MessageDigest.isEqual(expected, hash(file))) { "CHECKSUM" }
        return Backup(file.path) { file.inputStream() }
    }

    companion object {
        fun hash(file: File): ByteArray = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            ContentBundle.copy(input, null, ContentBundle.MAX_ARCHIVE, digest)
            digest.digest()
        }
    }
}
