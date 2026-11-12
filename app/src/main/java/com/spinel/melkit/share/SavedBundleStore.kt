package com.spinel.melkit.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.spinel.melkit.storage.CoverPhoto
import com.spinel.melkit.storage.PreviewBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class SavedBundle(val id: String, val uri: Uri, val name: String, val created: Long,
    val bytes: Long, val count: Int, val types: List<String>, val persistent: Boolean)

/** Catalog only; existing ZIP versions, user directories and game metadata are untouched. */
internal class SavedBundleStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("saved_bundles_v1", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private val covers = File(context.filesDir, "bundle-covers")

    fun read(): List<SavedBundle> {
        val array = JSONArray(preferences.getString("items", "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val kinds = item.getJSONArray("types")
            SavedBundle(item.getString("id"), Uri.parse(item.getString("uri")), item.getString("name"),
                item.getLong("created"), item.getLong("bytes"), item.getInt("count"),
                (0 until kinds.length()).map { kinds.getString(it) }, item.optBoolean("persistent"))
        }.sortedByDescending { it.created }
    }

    private fun write(items: List<SavedBundle>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().put("id", item.id).put("uri", item.uri.toString())
            .put("name", item.name).put("created", item.created).put("bytes", item.bytes)
            .put("count", item.count).put("types", JSONArray(item.types)).put("persistent", item.persistent)) }
        check(preferences.edit().putString("items", array.toString()).commit()) { "CATALOG_FAILED" }
    }

    fun retain(uri: Uri): Boolean {
        if (uri.scheme == "file") return runCatching { ownedFile(uri).isFile }.getOrDefault(false)
        runCatching { resolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return resolver.persistedUriPermissions.any { permission -> permission.isReadPermission &&
            (permission.uri == uri || runCatching {
                DocumentsContract.isTreeUri(uri) && permission.uri.authority == uri.authority &&
                    DocumentsContract.getTreeDocumentId(permission.uri) == DocumentsContract.getTreeDocumentId(uri)
            }.getOrDefault(false)) }

    }

    fun add(uri: Uri, fallbackName: String, bytes: Long, paths: List<String>, cover: Bitmap?, previous: Uri? = null): SavedBundle {
        val persistent = retain(uri)
        val name = metadata(uri).first.ifBlank { fallbackName }
        val existing = read().firstOrNull { it.uri == uri || (previous != null && it.uri == previous) }
        val item = SavedBundle(existing?.id ?: UUID.randomUUID().toString(), uri, name, existing?.created ?: System.currentTimeMillis(), bytes,
            paths.size, paths.map { it.substringBefore('/') }.distinct(), persistent)
        write(read().filterNot { it.uri == uri || (previous != null && it.uri == previous) } + item)
        if (cover != null) runCatching {
            check(covers.isDirectory || covers.mkdirs())
            File(covers, item.id + ".png").writeBytes(CoverPhoto.encode(cover))
        } else File(covers, item.id + ".png").delete()
        return item
    }

    fun forget(previous: Uri, current: Uri) {
        if (previous != current) write(read().filterNot { it.uri == previous })
    }

    fun metadata(uri: Uri): Pair<String, Long> = if (uri.scheme == "file") {
        val file = File(requireNotNull(uri.path)); file.name to file.length()
    } else resolver.query(uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (!it.moveToFirst()) throw IOException("BUNDLE_UNAVAILABLE")
        it.getString(0).orEmpty() to if (it.isNull(1)) -1L else it.getLong(1)
    } ?: throw IOException("BUNDLE_UNAVAILABLE")

    fun available(item: SavedBundle) {
        try { resolver.openFileDescriptor(item.uri, "r")?.use { } ?: throw IOException() }
        catch (_: Exception) { throw IOException("BUNDLE_UNAVAILABLE") }
    }

    fun rename(item: SavedBundle, requested: String) {
        val name = requested.trim().removeSuffix(".zip")
        require(name.isNotEmpty() && name.length <= 120 && name.none { it == '/' || it == '\\' || it.code < 32 }) { "INVALID_NAME" }
        available(item)
        val uri = if (item.uri.scheme == "file") {
            val file = ownedFile(item.uri)
            check(file.parentFile!!.listFiles().orEmpty().none { it.name.equals("$name.zip", true) }) { "DUPLICATE_NAME" }
            val target = File(file.parentFile, "$name.zip")
            check(target.createNewFile()) { "DUPLICATE_NAME" }
            if (!file.renameTo(target)) { target.delete(); throw IOException("RENAME_FAILED") }
            Uri.fromFile(target)
        } else DocumentsContract.renameDocument(resolver, item.uri, "$name.zip") ?: throw IOException("RENAME_FAILED")
        val updated = item.copy(uri = uri, name = metadata(uri).first, persistent = retain(uri))
        write(read().map { if (it.id == item.id) updated else it })
    }

    /** Called only from the explicit UI confirmation for this exact document. */
    fun delete(item: SavedBundle) {
        available(item)
        check(if (item.uri.scheme == "file") ownedFile(item.uri).delete()
            else DocumentsContract.deleteDocument(resolver, item.uri)) { "DELETE_FAILED" }
        write(read().filterNot { it.id == item.id })
        File(covers, item.id + ".png").delete()
        runCatching { resolver.releasePersistableUriPermission(item.uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    private fun ownedFile(uri: Uri): File {
        val file = File(requireNotNull(uri.path)).canonicalFile
        check(file.path.startsWith(MelKitShareService.receivedDirectory(context).canonicalPath + File.separator)) { "PERMISSION" }
        return file
    }

    fun cover(item: SavedBundle): Bitmap? = runCatching {
        val file = File(covers, item.id + ".png")
        if (file.length() in 1..com.spinel.melkit.storage.PreviewImages.MAX_IMAGE_BYTES.toLong()) PreviewBitmap.decode(file.readBytes()) else null
    }.getOrNull()
}
