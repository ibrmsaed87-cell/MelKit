package com.spinel.melkit.storage

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess

/** Scoped game access in Shizuku's dedicated process. */
class GameLibraryService : IGameLibrary.Stub() {
    private var choiceToken = ""
    private var choiceRoot = ""
    private var choiceStarted = 0L
    private var choiceSnapshot: List<ModVault.Item> = emptyList()
    private var scanResult: FileScan.Result? = null
    private var scanToken = ""
    private var scanRoot = ""
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

    @Synchronized
    override fun restoreFile(storageRoot: String, relativePath: String, source: ParcelFileDescriptor,
        expectedBytes: Long, expectedSha: String): String {
        return ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            try {
                val policy = paths(storageRoot)
                policy.resolve(relativePath, false)
                if (isModPath(relativePath)) checkModRecovery(storageRoot)
                NewFileRestore.restore(policy.resolve("", true), relativePath, input, expectedBytes, expectedSha)
                JSONObject().put("status", "ok").toString()
            } catch (_: ModProfiles.NeedsRecovery) {
                JSONObject().put("status", "recovery").toString()
            } catch (_: NewFileRestore.AlreadyExists) {
                JSONObject().put("status", "exists").toString()
            } catch (_: NewFileRestore.SourceChanged) {
                JSONObject().put("status", "changed").toString()
            } catch (_: NewFileRestore.PartialCopy) {
                JSONObject().put("status", "partial").toString()
            } catch (_: Exception) {
                JSONObject().put("status", "failed").toString()
            }
        }
    }

    private fun metadata(file: File, relativePath: String): JSONObject {
        val stat = Os.stat(file.path)
        val isDirectory = OsConstants.S_ISDIR(stat.st_mode)
        val type = if (isDirectory) "folder" else when (file.extension.lowercase(Locale.ROOT)) {
            "melmod" -> "mod"
            "melmap" -> "map"
            "melworld" -> "world"
            "melsave" -> "save"
            else -> "other" // Unknown format is never classified as corruption.
        }
        return JSONObject().put("path", relativePath).put("name", relativePath.substringAfterLast('/'))
            .put("directory", isDirectory).put("regular", OsConstants.S_ISREG(stat.st_mode))
            .put("size", stat.st_size).put("modified", stat.st_mtime * 1000L).put("type", type)
    }

    private fun vault(storageRoot: String): ModVault {
        val root = paths(storageRoot).resolve("", true)
        check(OsConstants.S_ISDIR(Os.stat(root.path).st_mode))
        return ModVault(root, File(File(storageRoot).canonicalFile, "Download/MelKit/DisabledMods"))
    }

    @Synchronized
    override fun listMods(storageRoot: String, offset: Int, filter: Int): String = response {
        require(offset >= 0 && filter in 0..2)
        val snapshot = vault(storageRoot).list()
        val activeCount = snapshot.items.count { it.id.isEmpty() }
        val filtered = snapshot.items.filter { filter == 0 || (if (filter == 1) it.id.isEmpty() else it.id.isNotEmpty()) }
        val entries = JSONArray()
        filtered.drop(offset).take(PAGE_SIZE).forEach { item ->
            entries.put(JSONObject().put("id", item.id).put("path", item.path)
                .put("name", item.path.substringAfterLast('/')).put("state", item.state)
                .put("size", item.bytes).put("modified", item.modified))
        }
        JSONObject().put("entries", entries).put("total", filtered.size)
            .put("active", activeCount).put("disabled", snapshot.items.size - activeCount)
            .put("warnings", snapshot.warnings).put("next", offset + entries.length() < filtered.size)
    }

    @Synchronized
    override fun profiles(storageRoot: String, action: String, payload: String): String {
        return try {
            require(payload.toByteArray(Charsets.UTF_8).size <= 400_000)
            val manager = ModProfiles(vault(storageRoot), File(File(storageRoot).canonicalFile, "Download/MelKit/ModProfiles"))
            val request = JSONObject(payload)
            val result = when (action) {
                "state" -> {
                    val state = manager.state()
                    val items = JSONArray()
                    state.profiles.forEach { profile ->
                        items.put(JSONObject().put("id", profile.id).put("name", profile.name)
                            .put("count", profile.paths.size).put("active", profile.id == state.activeId))
                    }
                    JSONObject().put("items", items).put("core", state.core.size)
                        .put("undo", state.undo).put("recovery", state.recovery).put("warnings", state.warnings)
                        .put("previousName", state.previousName).put("targetName", state.targetName).put("switchedAt", state.switchedAt)
                }
                "editor" -> {
                    val profile = manager.profile(request.optString("id"))
                    choiceSnapshot = vault(storageRoot).list().items.distinctBy { it.path }
                    choiceToken = UUID.randomUUID().toString()
                    choiceRoot = storageRoot
                    choiceStarted = SystemClock.elapsedRealtime()
                    choices(0).put("id", profile.id).put("name", profile.name)
                        .put("paths", JSONArray(profile.paths.toList())).put("token", choiceToken)
                }
                "choices" -> {
                    require(choiceToken.isNotEmpty() && choiceRoot == storageRoot && request.getString("token") == choiceToken)
                    require(SystemClock.elapsedRealtime() - choiceStarted < 300_000)
                    val offset = request.getInt("offset")
                    require(offset >= 0)
                    choices(offset)
                }
                "save" -> {
                    val array = request.getJSONArray("paths")
                    require(array.length() <= 5000)
                    val selected = (0 until array.length()).map { array.getString(it) }.toSet()
                    JSONObject().put("id", manager.saveProfile(request.optString("id"), request.optString("name"), selected))
                }
                "delete" -> { manager.delete(request.getString("id")); JSONObject() }
                "apply" -> { manager.apply(request.getString("id")); JSONObject() }
                "undo" -> { manager.undo(); JSONObject() }
                else -> throw IllegalArgumentException("Unknown profile operation")
            }
            val response = result.put("status", "ok").toString()
            if (response.toByteArray(Charsets.UTF_8).size > 400_000) JSONObject().put("status", "limit").toString() else response
        } catch (_: ModProfiles.NeedsRecovery) { JSONObject().put("status", "recovery").toString()
        } catch (_: ModProfiles.MissingMod) { JSONObject().put("status", "missing_mod").toString()
        } catch (_: ModProfiles.RolledBack) { JSONObject().put("status", "rolled_back").toString()
        } catch (_: ModProfiles.Limit) { JSONObject().put("status", "limit").toString()
        } catch (_: ModVault.Changed) { JSONObject().put("status", "changed").toString()
        } catch (_: ModVault.Conflict) { JSONObject().put("status", "exists").toString()
        } catch (_: Exception) { JSONObject().put("status", "incomplete").toString() }
    }

    private fun choices(offset: Int): JSONObject {
        val entries = JSONArray()
        choiceSnapshot.drop(offset).take(PAGE_SIZE).forEach { item ->
            entries.put(JSONObject().put("path", item.path).put("name", item.path.substringAfterLast('/')))
        }
        return JSONObject().put("entries", entries).put("next", offset + entries.length() < choiceSnapshot.size)
    }

    @Synchronized
    override fun scanFiles(storageRoot: String, action: String, payload: String): String {
        return try {
            require(payload.length <= 32_768)
            val request = JSONObject(payload)
            val gameRoot = paths(storageRoot).resolve("", true)
            val result = when (action) {
                "start" -> {
                    scanResult = null
                    val found = FileScan(gameRoot, GamePreviewReader::validJsonObject).run()
                    scanResult = found
                    scanRoot = storageRoot
                    scanToken = UUID.randomUUID().toString()
                    scanPage(found, 0)
                }
                "page" -> {
                    val found = currentScan(storageRoot, request) ?: return scanStatus("expired")
                    scanPage(found, request.getInt("offset"))
                }
                "vault" -> {
                    val offset = request.optInt("offset", 0)
                    require(offset >= 0)
                    val stored = quarantineVault(storageRoot, gameRoot).stored()
                    val entries = JSONArray()
                    stored.items.drop(offset).take(PAGE_SIZE).forEach { item ->
                        entries.put(JSONObject().put("id", item.id).put("path", item.path)
                            .put("size", item.bytes).put("state", item.state))
                    }
                    JSONObject().put("entries", entries).put("total", stored.items.size).put("warnings", stored.warnings)
                        .put("offset", offset).put("next", offset + entries.length() < stored.items.size)
                }
                "quarantine" -> {
                    val found = currentScan(storageRoot, request) ?: return scanStatus("expired")
                    val indices = request.getJSONArray("indices")
                    require(indices.length() in 1..PAGE_SIZE)
                    val selection = (0 until indices.length()).map { found.findings[indices.getInt(it)] }
                    require(selection.distinct().size == indices.length() && selection.all { it.selectable })
                    val quarantine = quarantineVault(storageRoot, gameRoot)
                    if (selection.any { isModPath(it.path) }) checkModRecovery(storageRoot)
                    scanResult = null
                    val engine = FileScan(gameRoot, GamePreviewReader::validJsonObject)
                    val moved = JSONArray()
                    var status = "ok"
                    for (entry in selection) {
                        try {
                            engine.quarantine(entry, quarantine)
                            moved.put(entry.path)
                        } catch (error: Exception) {
                            status = scanError(error)
                            break
                        }
                    }
                    JSONObject().put("moved", moved.length()).put("paths", moved).put("status", status)
                }
                "restore" -> {
                    val quarantine = quarantineVault(storageRoot, gameRoot)
                    val id = request.getString("id")
                    val path = quarantine.storedPath(id)
                    if (isModPath(path)) checkModRecovery(storageRoot)
                    quarantine.enable(id)
                    scanResult = null
                    JSONObject().put("path", path)
                }
                else -> throw IllegalArgumentException("Unknown scan action")
            }
            if (!result.has("status")) result.put("status", "ok")
            val body = result.toString()
            if (body.length > 400_000) scanStatus("limit") else body
        } catch (error: Exception) { scanStatus(scanError(error)) }
    }

    private fun currentScan(storageRoot: String, request: JSONObject): FileScan.Result? =
        scanResult?.takeIf { scanRoot == storageRoot && scanToken == request.optString("token") }

    private fun scanPage(result: FileScan.Result, offset: Int): JSONObject {
        require(offset >= 0)
        val entries = JSONArray()
        result.findings.drop(offset).take(PAGE_SIZE).forEachIndexed { index, item ->
            entries.put(JSONObject().put("index", offset + index).put("path", item.path).put("issue", item.issue)
                .put("reference", item.reference).put("size", item.bytes).put("selectable", item.selectable)
                .put("retained", item.retained))
        }
        return JSONObject().put("entries", entries).put("token", scanToken).put("files", result.files)
            .put("total", result.findings.size).put("partial", result.partial).put("offset", offset)
            .put("next", offset + entries.length() < result.findings.size)
    }

    private fun quarantineVault(storageRoot: String, gameRoot: File): ModVault = ModVault.forQuarantine(
        gameRoot, File(File(storageRoot).canonicalFile, "Download/MelKit/Quarantine"),
    )

    private fun isModPath(path: String) = path.startsWith("Mods/", ignoreCase = true)

    private fun checkModRecovery(storageRoot: String, manager: ModVault = vault(storageRoot)) {
        ModProfiles(manager, File(File(storageRoot).canonicalFile, "Download/MelKit/ModProfiles")).requireIdle()
    }

    private fun scanStatus(status: String) = JSONObject().put("status", status).toString()

    private fun scanError(error: Exception): String = when (error) {
        is ModVault.Changed, is NewFileRestore.SourceChanged -> "changed"
        is ModVault.Conflict, is NewFileRestore.AlreadyExists -> "exists"
        is ModVault.NoSpace -> "space"
        is ModProfiles.NeedsRecovery -> "recovery"
        is SecurityException -> "denied"
        is FileNotFoundException -> "missing"
        else -> "incomplete"
    }

    @Synchronized
    override fun toggleMod(storageRoot: String, relativePath: String, vaultId: String, enable: Boolean,
        expectedBytes: Long, expectedModified: Long): String {
        val status = try {
            val manager = vault(storageRoot)
            checkModRecovery(storageRoot, manager)
            if (enable) manager.enable(vaultId) else manager.disable(relativePath, expectedBytes, expectedModified)
            "ok"
        } catch (_: ModProfiles.NeedsRecovery) { "recovery"
        } catch (_: ModVault.Conflict) { "exists"
        } catch (_: NewFileRestore.AlreadyExists) { "exists"
        } catch (_: ModVault.Changed) { "changed"
        } catch (_: NewFileRestore.SourceChanged) { "changed"
        } catch (_: ModVault.NoSpace) { "space"
        } catch (_: Exception) { "incomplete" }
        return JSONObject().put("status", status).toString()
    }

    override fun readModPreview(storageRoot: String, relativePath: String, vaultId: String): ByteArray = try {
        val file = if (vaultId.isNotEmpty()) vault(storageRoot).previewFile(vaultId) else {
            require(relativePath.startsWith("Mods/") && relativePath.endsWith(".melmod", ignoreCase = true))
            paths(storageRoot).resolve(relativePath, false)
        }
        check(OsConstants.S_ISREG(Os.stat(file.path).st_mode))
        GamePreviewReader.read(file)
    } catch (_: Exception) { byteArrayOf() }

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


