package com.spinel.melkit.storage

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.system.exitProcess

/** Runs in Shizuku's dedicated shell process, never in the application's UI process. */
class GameLibraryService : IGameLibrary.Stub() {
    override fun destroy() { exitProcess(0) }

    private fun paths(storageRoot: String): ContentPaths {
        require(storageRoot.matches(Regex("/storage/emulated/[0-9]+")))
        return ContentPaths(File(storageRoot, "Android/data/com.studio27.MelonPlayground/files"))
    }

    override fun list(storageRoot: String, relativePath: String, offset: Int): String = response {
        require(offset >= 0)
        val policy = paths(storageRoot)
        val directory = policy.resolve(relativePath, true)
        val stat = Os.stat(directory.path) // Preserve ENOENT versus EACCES.
        check(OsConstants.S_ISDIR(stat.st_mode))
        val children = directory.listFiles() ?: throw IOException("Cannot list directory")
        val content = children.filter { relativePath.isNotEmpty() || ContentPaths.isContentFolder(it.name) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }, { it.name }))
        val entries = JSONArray()
        content.drop(offset).take(PAGE_SIZE).forEach { child ->
            val childPath = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
            // Reject escaping symlinks before exposing an entry or a descriptor.
            val safe = policy.resolve(childPath, false)
            entries.put(metadata(safe, childPath))
        }
        JSONObject().put("path", relativePath).put("offset", offset).put("total", content.size)
            .put("entries", entries).put("next", offset + entries.length() < content.size)
    }

    override fun info(storageRoot: String, relativePath: String): String = response {
        metadata(paths(storageRoot).resolve(relativePath, false), relativePath)
    }

    override fun openRead(storageRoot: String, relativePath: String): ParcelFileDescriptor {
        val file = paths(storageRoot).resolve(relativePath, false)
        check(OsConstants.S_ISREG(Os.stat(file.path).st_mode))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun readPreview(storageRoot: String, relativePath: String): ByteArray = try {
        val file = paths(storageRoot).resolve(relativePath, false)
        check(OsConstants.S_ISREG(Os.stat(file.path).st_mode))
        GamePreviewReader.read(file)
    } catch (_: Exception) {
        byteArrayOf() // Fall back to the type icon; never classify a file as corrupt.
    }

    private fun metadata(file: File, relativePath: String): JSONObject {
        val stat = Os.stat(file.path)
        val isDirectory = OsConstants.S_ISDIR(stat.st_mode)
        val type = if (isDirectory) "folder" else when (file.extension.lowercase(Locale.ROOT)) {
            "melmod" -> "mod"
            "melmap" -> "map"
            "melsave" -> "save"
            else -> "other" // Unknown format is never classified as corruption.
        }
        return JSONObject().put("path", relativePath).put("name", relativePath.substringAfterLast('/'))
            .put("directory", isDirectory).put("regular", OsConstants.S_ISREG(stat.st_mode))
            .put("size", stat.st_size).put("modified", stat.st_mtime * 1000L).put("type", type)
    }

    private fun response(block: () -> JSONObject): String = try {
        block().put("status", "ok").toString()
    } catch (error: ErrnoException) {
        val status = when (error.errno) {
            OsConstants.ENOENT -> "missing"
            OsConstants.EACCES, OsConstants.EPERM -> "denied"
            else -> "unreadable"
        }
        JSONObject().put("status", status).toString()
    } catch (_: SecurityException) {
        JSONObject().put("status", "denied").toString()
    } catch (_: Exception) {
        JSONObject().put("status", "unreadable").toString()
    }

    companion object { const val PAGE_SIZE = 40 }
}

