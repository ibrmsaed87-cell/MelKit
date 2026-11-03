package com.spinel.melkit.storage

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spinel.melkit.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class GameEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val regular: Boolean,
    val size: Long,
    val modified: Long,
    val type: String,
) {
    companion object {
        fun fromJson(item: JSONObject) = GameEntry(
            item.getString("path"), item.getString("name"), item.getBoolean("directory"),
            item.getBoolean("regular"), item.getLong("size"), item.getLong("modified"), item.getString("type"),
        )
    }
}

enum class LibraryIssue {
    START_SHIZUKU, UPDATE_SHIZUKU, PERMISSION_DENIED, CONNECT_FAILED,
    MISSING_FOLDER, ACCESS_DENIED, READ_FAILED, SOURCE_CHANGED, EXPORT_FAILED, PARTIAL_EXPORT, STORAGE_PERMISSION,
}

data class ReadProof(val name: String, val bytes: Long, val sha256: String, val exported: Boolean,
    val savedPath: String? = null)

data class GameLibraryState(
    val connected: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val path: String = "",
    val offset: Int = 0,
    val total: Int = 0,
    val hasNext: Boolean = false,
    val entries: List<GameEntry> = emptyList(),
    val issue: LibraryIssue? = null,
    val proof: ReadProof? = null,
    val previewRevision: Int = 0,
)

/** Owns Shizuku listeners and transfers across rotations; releases the process on exit. */
class GameLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GameLibraryState())
    val state = mutableState.asStateFlow()
    @Suppress("DEPRECATION")
    private val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    private var remote: IGameLibrary? = null
    private var binding = false
    private var operation: Job? = null
    private var bindTimeout: Job? = null
    private var pendingExport: GameEntry? = null
    private data class PreviewResult(val bitmap: Bitmap?)
    private val previewMutex = Mutex()
    private val previews = object : LruCache<String, PreviewResult>(4 * 1024) {
        override fun sizeOf(key: String, value: PreviewResult): Int =
            ((value.bitmap?.allocationByteCount ?: 0) / 1024).coerceAtLeast(1)
    }
    private val args = Shizuku.UserServiceArgs(ComponentName(application, GameLibraryService::class.java))
        .daemon(false).processNameSuffix("library").tag("melkit-read-library")
        .debuggable(false).version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!binding) return
            binding = false
            bindTimeout?.cancel()
            if (!binder.pingBinder()) {
                fail(LibraryIssue.CONNECT_FAILED)
                return
            }
            remote = IGameLibrary.Stub.asInterface(binder)
            mutableState.update { it.copy(connected = true) }
            loadFolder(mutableState.value.path, 0)
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
    }

    private val received = Shizuku.OnBinderReceivedListener {
        mutableState.update {
            if (it.issue == LibraryIssue.START_SHIZUKU) it.copy(issue = null) else it
        }
    }
    private val dead = Shizuku.OnBinderDeadListener { disconnected() }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_ACCESS) {
            if (result == PackageManager.PERMISSION_GRANTED) bind()
            else fail(LibraryIssue.PERMISSION_DENIED)
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }

    fun connect() {
        if (mutableState.value.busy) return
        try {
            when {
                !Shizuku.pingBinder() -> fail(LibraryIssue.START_SHIZUKU)
                Shizuku.isPreV11() -> fail(LibraryIssue.UPDATE_SHIZUKU)
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bind()
                Shizuku.shouldShowRequestPermissionRationale() -> fail(LibraryIssue.PERMISSION_DENIED)
                else -> {
                    mutableState.update { it.copy(busy = true, issue = null) }
                    Shizuku.requestPermission(REQUEST_ACCESS)
                }
            }
        } catch (_: Exception) {
            fail(LibraryIssue.CONNECT_FAILED)
        }
    }

    private fun bind() {
        mutableState.update { it.copy(busy = true, issue = null) }
        if (remote?.asBinder()?.pingBinder() == true) {
            loadFolder(mutableState.value.path, mutableState.value.offset)
            return
        }
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
            bindTimeout?.cancel()
            bindTimeout = viewModelScope.launch {
                delay(20_000)
                if (binding) {
                    binding = false
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    fail(LibraryIssue.CONNECT_FAILED)
                }
            }
        } catch (_: Exception) {
            binding = false
            fail(LibraryIssue.CONNECT_FAILED)
        }
    }

    fun browse(path: String, offset: Int = 0) {
        if (!mutableState.value.busy && mutableState.value.connected) loadFolder(path, offset)
    }

    private fun loadFolder(path: String, offset: Int) {
        val service = authorizedService() ?: return
        operation = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, issue = null, proof = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    checked(service.list(storageRoot, path, offset))
                }
                val array = result.getJSONArray("entries")
                val entries = List(array.length()) { GameEntry.fromJson(array.getJSONObject(it)) }
                mutableState.update {
                    it.copy(connected = true, loaded = true, busy = false, path = path,
                        offset = offset, total = result.getInt("total"), hasNext = result.getBoolean("next"), entries = entries,
                        previewRevision = it.previewRevision + 1)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail((error as? AccessFailure)?.issue ?: LibraryIssue.READ_FAILED)
            }
        }
    }

    suspend fun preview(entry: GameEntry, revision: Int): Bitmap? {
        if (!entry.regular || entry.type !in listOf("mod", "map", "save", "world")) return null
        val key = "$revision:${entry.path}:${entry.size}:${entry.modified}"
        return previewMutex.withLock {
            previews.get(key)?.let { return@withLock it.bitmap }
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val service = remote ?: return@withContext null
                    if (!service.asBinder().pingBinder()) return@withContext null
                    checkUnchanged(service, entry)
                    val bytes = service.readPreview(storageRoot, entry.path)
                    checkUnchanged(service, entry)
                    PreviewBitmap.decode(bytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            currentCoroutineContext().ensureActive()
            previews.put(key, PreviewResult(bitmap))
            bitmap
        }
    }

    suspend fun restoreSaved(path: String, expectedBytes: Long, openSource: () -> ParcelFileDescriptor): String {
        if (mutableState.value.busy) return "busy"
        val service = authorizedService() ?: return "connect"
        mutableState.update { it.copy(busy = true, issue = null) }
        return try {
            withContext(Dispatchers.IO) {
                val proof = ParcelFileDescriptor.AutoCloseInputStream(openSource()).use { digest(it) }
                if (proof.first != expectedBytes) return@withContext "changed"
                openSource().use { descriptor ->
                    JSONObject(service.restoreFile(storageRoot, path, descriptor, proof.first, proof.second)).getString("status")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "failed"
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun verifyRead(entry: GameEntry) {
        if (mutableState.value.busy || !mutableState.value.connected || !entry.regular) return
        val service = authorizedService() ?: return
        operation = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, issue = null, proof = null) }
            try {
                val proof = withContext(Dispatchers.IO) {
                    checkUnchanged(service, entry)
                    val (bytes, hash) = ParcelFileDescriptor.AutoCloseInputStream(
                        service.openRead(storageRoot, entry.path),
                    ).use { digest(it) }
                    if (bytes != entry.size) throw AccessFailure(LibraryIssue.SOURCE_CHANGED)
                    checkUnchanged(service, entry)
                    ReadProof(entry.name, bytes, hash, false)
                }
                mutableState.update { it.copy(busy = false, proof = proof) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail((error as? AccessFailure)?.issue ?: LibraryIssue.READ_FAILED)
            }
        }
    }

    fun prepareExport(entry: GameEntry): Boolean {
        if (mutableState.value.busy || !mutableState.value.connected || !entry.regular) return false
        if (authorizedService() == null) return false
        pendingExport = entry
        mutableState.update { it.copy(busy = true, issue = null, proof = null) }
        return true
    }

    fun exportPrepared(storageGranted: Boolean) {
        val entry = pendingExport ?: return
        pendingExport = null
        if (!storageGranted) {
            fail(LibraryIssue.STORAGE_PERMISSION)
            return
        }
        val service = authorizedService() ?: return
        operation = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, issue = null, proof = null) }
            var destination: DownloadCopy? = null
            try {
                val proof = withContext(Dispatchers.IO) {
                    checkUnchanged(service, entry)
                    val copy = DownloadCopy.create(getApplication<Application>(), entry.path)
                    destination = copy
                    val copied = ParcelFileDescriptor.AutoCloseInputStream(
                        service.openRead(storageRoot, entry.path),
                    ).use { input ->
                        copy.openOutput().use { output ->
                            digest(input) { buffer, count -> output.write(buffer, 0, count) }
                        }
                    }
                    if (copied.first != entry.size) throw AccessFailure(LibraryIssue.SOURCE_CHANGED)
                    checkUnchanged(service, entry)
                    val verified = copy.openInput().use { digest(it) }
                    if (verified != copied) throw IOException("Copy verification failed")
                    val sourceVerified = ParcelFileDescriptor.AutoCloseInputStream(
                        service.openRead(storageRoot, entry.path),
                    ).use { digest(it) }
                    if (sourceVerified != copied) throw AccessFailure(LibraryIssue.SOURCE_CHANGED)
                    checkUnchanged(service, entry)
                    val savedPath = copy.publish()
                    ReadProof(entry.name, copied.first, copied.second, true, savedPath)
                }
                mutableState.update { it.copy(busy = false, proof = proof) }
            } catch (error: Exception) {
                // Only remove this newly created Downloads copy, never a game file or older copy.
                // Cleanup also runs after cancellation (for example, leaving the app).
                val removed = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { destination?.remove() ?: true }.getOrDefault(false)
                }
                if (error is CancellationException) throw error
                fail(if (!removed) LibraryIssue.PARTIAL_EXPORT else
                    (error as? AccessFailure)?.issue ?: LibraryIssue.EXPORT_FAILED)
            }
        }
    }

    private fun checkUnchanged(service: IGameLibrary, entry: GameEntry) {
        val current = GameEntry.fromJson(checked(service.info(storageRoot, entry.path)))
        if (!current.regular || current.size != entry.size || current.modified != entry.modified) {
            throw AccessFailure(LibraryIssue.SOURCE_CHANGED)
        }
    }

    private suspend fun digest(input: InputStream, onBytes: ((ByteArray, Int) -> Unit)? = null): Pair<Long, String> {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            onBytes?.invoke(buffer, count)
            sha.update(buffer, 0, count)
            total += count
        }
        return total to sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun checked(raw: String): JSONObject {
        val json = JSONObject(raw)
        when (json.getString("status")) {
            "ok" -> return json
            "missing" -> throw AccessFailure(LibraryIssue.MISSING_FOLDER)
            "denied" -> throw AccessFailure(LibraryIssue.ACCESS_DENIED)
            else -> throw AccessFailure(LibraryIssue.READ_FAILED)
        }
    }

    fun dismissProof() { mutableState.update { it.copy(proof = null) } }

    private fun authorizedService(): IGameLibrary? {
        return try {
            if (!Shizuku.pingBinder()) {
                disconnected()
                null
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                fail(LibraryIssue.PERMISSION_DENIED)
                null
            } else {
                val service = remote
                if (service?.asBinder()?.pingBinder() != true) {
                    fail(LibraryIssue.CONNECT_FAILED)
                    null
                } else service
            }
        } catch (_: Exception) {
            fail(LibraryIssue.CONNECT_FAILED)
            null
        }
    }

    private fun fail(issue: LibraryIssue) {
        val disconnected = issue == LibraryIssue.START_SHIZUKU || issue == LibraryIssue.UPDATE_SHIZUKU ||
            issue == LibraryIssue.PERMISSION_DENIED || issue == LibraryIssue.CONNECT_FAILED
        mutableState.update { it.copy(busy = false, issue = issue, connected = it.connected && !disconnected) }
    }

    private fun disconnected() {
        binding = false
        remote = null
        operation?.cancel()
        bindTimeout?.cancel()
        mutableState.update { it.copy(connected = false, busy = false, issue = LibraryIssue.START_SHIZUKU) }
    }

    override fun onCleared() {
        previews.evictAll()
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        super.onCleared()
    }

    private class AccessFailure(val issue: LibraryIssue) : IOException()
    companion object { private const val REQUEST_ACCESS = 201 }
}

