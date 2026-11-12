package com.spinel.melkit.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Replace only after a verified sibling exists. Keep the previous ZIP as a recovery copy. */
internal class BundleDocumentWriter(private val context: Context) {
    private val resolver = context.contentResolver
    private val recovery = context.getSharedPreferences("bundle_edit_recovery_v1", Context.MODE_PRIVATE)

    fun writable(uri: Uri): Boolean = try {
        if (uri.scheme == "file") ownedFile(uri).canWrite()
        else resolver.query(uri, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && (it.getInt(0) and Document.FLAG_SUPPORTS_RENAME) != 0 &&
                (it.getInt(0) and Document.FLAG_SUPPORTS_WRITE) != 0
        } ?: false
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

    fun replace(source: Uri, original: File, edited: File, name: String, tree: Uri?): Uri {
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
        val folderTree = tree ?: throw IOException("BUNDLE_FOLDER_REQUIRED")
        check(source.authority == folderTree.authority) { "BUNDLE_FOLDER_REQUIRED" }
        val parent = DocumentsContract.buildDocumentUriUsingTree(folderTree, DocumentsContract.getTreeDocumentId(folderTree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folderTree, DocumentsContract.getTreeDocumentId(folderTree))
        val sourceId = DocumentsContract.getDocumentId(source)
        val siblings: List<Pair<String, String>> = resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildList<Pair<String, String>> {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        } ?: throw IOException("BUNDLE_FOLDER_REQUIRED")
        check(siblings.any { it.first == sourceId }) { "BUNDLE_FOLDER_REQUIRED" }
        check(siblings.none { it.first != sourceId && it.second.equals(name, true) }) { "DUPLICATE_NAME" }
        val scopedSource = DocumentsContract.buildDocumentUriUsingTree(folderTree, sourceId)
        val originalName = siblings.first { it.first == sourceId }.second
        val marker = UUID.randomUUID().toString()
        var staged: Uri? = DocumentsContract.createDocument(resolver, parent, "application/zip", "MelKit-edit-$marker.zip")
            ?: throw IOException("SAVE_FAILED")
        var backup: Uri? = null
        var published: Uri? = null
        try {
            resolver.openOutputStream(requireNotNull(staged), "wt")?.use { output ->
                edited.inputStream().use { ContentBundle.copy(it, output, ContentBundle.MAX_ARCHIVE, null) }
            } ?: throw IOException("SAVE_FAILED")
            check(MessageDigest.isEqual(hash(edited), digest(requireNotNull(staged)))) { "CHECKSUM" }
            check(MessageDigest.isEqual(oldHash, digest(source))) { "FILE_CHANGED" }
            // Record before moving either document. The old ZIP is never deleted, even on success.
            check(recovery.edit().putString(marker, scopedSource.toString() + "\n" + staged + "\n" + originalName).commit())
            backup = DocumentsContract.renameDocument(resolver, scopedSource, "MelKit-before-edit-$marker.zip")
                ?: throw IOException("BUNDLE_READ_ONLY")
            check(recovery.edit().putString(marker, backup.toString() + "\n" + staged + "\n" + originalName).commit())
            published = DocumentsContract.renameDocument(resolver, requireNotNull(staged), name) ?: throw IOException("SAVE_FAILED")
            staged = null
            check(MessageDigest.isEqual(hash(edited), digest(requireNotNull(published)))) { "CHECKSUM" }
            recovery.edit().remove(marker).commit()
            return requireNotNull(published)
        } catch (error: Exception) {
            // Keep both versions if a provider cannot roll back; the durable original remains discoverable.
            if (backup != null) runCatching {
                if (published != null) DocumentsContract.renameDocument(resolver, requireNotNull(published), "MelKit-uncommitted-$marker.zip")
                DocumentsContract.renameDocument(resolver, requireNotNull(backup), originalName)
            }
            throw error
        } finally {
            staged?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }
    }

    companion object {
        fun hash(file: File): ByteArray = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            ContentBundle.copy(input, null, ContentBundle.MAX_ARCHIVE, digest)
            digest.digest()
        }
    }
}
