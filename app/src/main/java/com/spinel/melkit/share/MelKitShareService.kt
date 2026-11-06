package com.spinel.melkit.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.spinel.melkit.MainActivity
import com.spinel.melkit.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

internal data class ShareInput(val path: String, val size: Long, val open: () -> InputStream,
    val verifySource: () -> Unit = {})

internal data class ShareTransfer(val phase: String = "idle", val sending: Boolean = false,
    val ticket: MelKitShareTicket? = null, val progress: ShareWire.Progress? = null, val error: String = "") {
    val active get() = phase in listOf("preparing", "connecting", "waiting", "sending", "receiving", "verifying", "finishing")
}

/** Transfer lifetime belongs to the foreground service, independent of the screen and QR camera. */
class MelKitShareService : Service() {
    private class Run(val control: ShareWire.Control = ShareWire.Control()) {
        var stage: File? = null
        @Volatile var finished = false
        var lastProgress = 0L
    }
    private sealed interface Start {
        data class Send(val inputs: List<ShareInput>) : Start
        data class Receive(val ticket: MelKitShareTicket) : Start
    }
    private val main = Handler(Looper.getMainLooper())
    private var run: Run? = null
    private var ending = false
    private var connection: MelKitShareConnection? = null
    private var lock: PowerManager.WakeLock? = null
    private var lastNotification = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            val current = run ?: return
            if (state.value.phase in listOf("preparing", "sending", "receiving", "verifying") &&
                System.nanoTime() - current.control.lastActivity > 60_000_000_000L) {
                finish("error", "TIMEOUT")
            } else main.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The same worker serializes cleanup with file writes and the next session.
        worker.execute {
            receivedDirectory(this).listFiles().orEmpty().filter {
                it.name.startsWith(".") && it.name.endsWith(".part") &&
                    ShareWire.validBatchId(it.name.removePrefix(".").removeSuffix(".part"))
            }.forEach { it.deleteRecursively() }
            File(cacheDir, "share-outbox").deleteRecursively()
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (run != null) finish("cancelled") else if (!ending) stopSelf()
            return START_NOT_STICKY
        }
        if (run != null || ending) return START_NOT_STICKY
        val start = pending.also { pending = null }
        if (start == null) { stopSelf(); return START_NOT_STICKY }
        val current = Run()
        run = current
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, localized().getString(R.string.share_transfer), NotificationManager.IMPORTANCE_LOW),
                )
            }
            startForeground(NOTIFICATION, notification(state.value))
            lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MelKit:share").apply { acquire(7_200_000L) }
            when (start) {
                is Start.Send -> prepare(current, start.inputs)
                is Start.Receive -> {
                    check(MelKitShareTicket.parse(start.ticket.encode()) != null) { "BAD_QR" }
                    val receiver = MelKitShareConnection(this, { host, _ -> receive(current, host, start.ticket) },
                        { connectionError(current, it) })
                    connection = receiver
                    receiver.start(false, start.ticket)
                }
            }
            main.postDelayed(watchdog, 5000)
        } catch (error: Exception) { finish("error", errorCode(error)) }
        return START_NOT_STICKY
    }

    private fun prepare(current: Run, inputs: List<ShareInput>) {
        val directory = File(cacheDir, "share-outbox/" + UUID.randomUUID())
        current.stage = directory
        work(current) {
            require(inputs.size in 1..ShareWire.MAX_FILES) { "INVALID_FILE" }
            require(inputs.all { ShareWire.validPath(it.path) }) { "INVALID_FILE" }
            require(inputs.distinctBy { ShareWire.pathKey(it.path) }.size == inputs.size) { "DUPLICATE_NAME" }
            require(directory.mkdirs()) { "SPACE" }
            var remaining = ShareWire.MAX_TOTAL
            val files = inputs.mapIndexed { index, input ->
                current.control.touch()
                val source = current.control.track(input.open())
                val entry = try {
                    source.use { ShareWire.stage(File(directory, index.toString()), input.path, it, input.size,
                        minOf(ShareWire.MAX_FILE, remaining), current.control) }
                } finally { current.control.untrack(source) }
                input.verifySource()
                remaining -= entry.size
                entry
            }
            main.post { if (run === current) host(current, files) }
        }
    }

    private fun host(current: Run, files: List<ShareWire.Entry>) {
        publish(state.value.copy(phase = "connecting"))
        try {
            val sender = MelKitShareConnection(this, { address, group ->
                work(current) {
                    val server = current.control.track(ServerSocket())
                    server.use {
                        it.bind(InetSocketAddress(address, 0))
                        val ticket = MelKitShareTicket(UUID.randomUUID().toString(), MelKitShareTicket.key(),
                            group.networkName.orEmpty(), group.passphrase.orEmpty(), it.localPort,
                            System.currentTimeMillis() + 600_000)
                        require(MelKitShareTicket.parse(ticket.encode()) != null) { "GROUP_INFO" }
                        main.post {
                            if (run === current) connection?.advertise(ticket.id) {
                                if (run === current) publish(state.value.copy(phase = "waiting", ticket = ticket))
                            }
                        }
                        ShareWire.send(it, ticket.id, ticket.key, files, current.control,
                            { progress(current, "sending", it) }, { verifying(current) })
                    }
                    current.finished = true
                    main.post { if (run === current) finish("complete") }
                }
            }, { connectionError(current, it) })
            connection = sender
            sender.start(true)
        } catch (error: Exception) { finish("error", errorCode(error)) }
    }

    private fun receive(current: Run, address: String, ticket: MelKitShareTicket) = work(current) {
        val deadline = System.nanoTime() + 45_000_000_000L
        var connected: Socket? = null
        while (connected == null) {
            current.control.check()
            val socket = current.control.track(Socket())
            try {
                socket.connect(InetSocketAddress(address, ticket.port), 3000)
                connected = socket
            } catch (error: IOException) {
                current.control.untrack(socket)
                socket.close()
                if (System.nanoTime() >= deadline) throw error
                Thread.sleep(500)
            }
        }
        ShareWire.receive(connected, ticket.id, ticket.key, receivedDirectory(this), current.control,
            { progress(current, "receiving", it) }, { verifying(current) })
        current.finished = true
        main.post { if (run === current) finish("complete") }
    }

    private fun work(current: Run, task: () -> Unit) {
        worker.execute {
            try { current.control.check(); task() }
            catch (error: Exception) {
                main.post { if (run === current) finish("error", errorCode(error)) }
            }
        }
    }

    private fun progress(current: Run, phase: String, progress: ShareWire.Progress) {
        val now = SystemClock.elapsedRealtime()
        if (now - current.lastProgress < 150 && progress.bytes != progress.total) return
        current.lastProgress = now
        main.post { if (run === current) publish(state.value.copy(phase = phase, progress = progress, ticket = null)) }
    }
    private fun verifying(current: Run) {
        main.post { if (run === current) publish(state.value.copy(phase = "verifying")) }
    }
    private fun connectionError(current: Run, error: String) {
        if (error == "CONNECTION_LOST") main.postDelayed({
            if (run === current && !current.finished) finish("error", error)
        }, 1000)
        else if (run === current) finish("error", error)
    }

    private fun publish(value: ShareTransfer) {
        mutableState.value = value
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotification >= 1000 && (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(value)) }
            lastNotification = now
        }
    }

    private fun finish(phase: String, error: String = "") {
        val current = run ?: return
        run = null
        ending = true
        main.removeCallbacks(watchdog)
        current.control.close()
        val closing = connection
        connection = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        current.stage?.let { directory -> worker.execute { directory.deleteRecursively() } }
        val result = state.value.copy(phase = phase, ticket = null, error = error)
        mutableState.value = state.value.copy(phase = "finishing", ticket = null)
        val complete = {
            ending = false
            mutableState.value = result
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (closing == null) complete() else {
            try { closing.close(complete) } catch (_: Exception) { complete() }
        }
    }

    private fun localized(): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        val locale = locales[0] ?: return this
        val configuration = android.content.res.Configuration(resources.configuration)
        configuration.setLocale(locale)
        return createConfigurationContext(configuration)
    }

    private fun notification(value: ShareTransfer): Notification {
        val ctx = localized()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val cancel = PendingIntent.getService(this, 1, Intent(this, MelKitShareService::class.java).setAction(CANCEL), flags)
        val percent = value.progress?.let { (it.bytes * 100 / it.total).toInt().coerceAtMost(99) } ?: 0
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(ctx.getString(phaseLabel(value.phase))).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(true).setProgress(100, percent, value.progress == null)
            .addAction(0, ctx.getString(R.string.saved_cancel), cancel).build()
    }

    override fun onDestroy() {
        if (run != null) finish("error", "INTERRUPTED")
        super.onDestroy()
    }

    companion object {
        internal const val OPEN = "com.spinel.melkit.OPEN_SHARING"
        private const val CANCEL = "com.spinel.melkit.CANCEL_SHARING"
        private const val CHANNEL = "melkit-transfer"
        private const val NOTIFICATION = 48
        private val worker = Executors.newSingleThreadExecutor()
        private val mutableState = MutableStateFlow(ShareTransfer())
        internal val state = mutableState.asStateFlow()
        private var pending: Start? = null

        internal fun receivedDirectory(context: Context) = File(context.filesDir, "share-received")
        internal fun reset() { if (!state.value.active) mutableState.value = ShareTransfer() }
        internal fun send(context: Context, files: List<ShareInput>) = submit(context, Start.Send(files), true)
        internal fun receive(context: Context, ticket: MelKitShareTicket) = submit(context, Start.Receive(ticket), false)
        internal fun cancel(context: Context) {
            if (state.value.active) context.startService(Intent(context, MelKitShareService::class.java).setAction(CANCEL))
        }
        private fun submit(context: Context, start: Start, sending: Boolean) {
            if (state.value.active) return
            val failure = try { MelKitShareConnection.preflight(context) }
            catch (error: Exception) { errorCode(error) }
            failure?.let {
                mutableState.value = ShareTransfer(phase = "error", error = it)
                return
            }
            pending = start
            mutableState.value = ShareTransfer(phase = if (sending) "preparing" else "connecting", sending = sending)
            try { ContextCompat.startForegroundService(context, Intent(context, MelKitShareService::class.java)) }
            catch (error: Exception) {
                pending = null
                mutableState.value = ShareTransfer(phase = "error", error = errorCode(error))
            }
        }
        internal fun phaseLabel(phase: String): Int = when (phase) {
            "preparing" -> R.string.share_preparing
            "connecting" -> R.string.share_connecting
            "waiting" -> R.string.share_waiting
            "sending" -> R.string.share_sending
            "receiving" -> R.string.share_receiving
            "verifying" -> R.string.share_verifying
            "finishing" -> R.string.share_finishing
            else -> R.string.share_transfer
        }
        private fun errorCode(error: Exception): String = when (error) {
            is java.net.SocketTimeoutException -> "TIMEOUT"
            is java.net.SocketException, is java.io.EOFException -> "CONNECTION_LOST"
            is SecurityException -> "PERMISSION"
            else -> error.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "IO"
        }
    }
}
