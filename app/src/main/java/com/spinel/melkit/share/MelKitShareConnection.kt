package com.spinel.melkit.share

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Owns exactly one autonomous P2P group. The sending phone is always its owner. */
@Suppress("MissingPermission") // start() checks the permission appropriate to this Android version.
internal class MelKitShareConnection(
    private val context: Context,
    private val ready: (String, WifiP2pGroup) -> Unit,
    private val failed: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var closed = false
    private var owner = false
    private var established = false
    private var delivered = false
    private var ticket: MelKitShareTicket? = null
    private var connecting = false
    private var ownsAttempt = false
    private var advertisement: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private val timeout = Runnable { fail("TIMEOUT") }
    private val retryDiscovery = object : Runnable {
        override fun run() {
            if (!closed && !established && !connecting && Build.VERSION.SDK_INT < 29) discover()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                    if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) fail("WIFI_OFF")
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> connectionInfo()
            }
        }
    }

    fun start(asOwner: Boolean, pairing: MelKitShareTicket? = null) {
        owner = asOwner; ticket = pairing
        preflight(context)?.let { fail(it); return }
        channel = manager?.initialize(context, context.mainLooper) { fail("CONNECTION_LOST") }
        if (channel == null) { fail("UNSUPPORTED"); return }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        // These are protected framework broadcasts, including broadcasts from the Wi-Fi service.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
        main.postDelayed(timeout, 120000)
        manager!!.requestGroupInfo(channel!!) { group ->
            if (closed) return@requestGroupInfo
            if (group != null) fail("EXISTING_GROUP")
            else {
                ownsAttempt = true
                if (owner) host() else join()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun host() {
        if (Build.VERSION.SDK_INT >= 29) {
            val suffix = MelKitShareTicket.key().take(2)
            val config = WifiP2pConfig.Builder().setNetworkName("DIRECT-$suffix-MelKit")
                .setPassphrase(MelKitShareTicket.key().take(32)).enablePersistentMode(false)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).build()
            manager!!.createGroup(channel!!, config, action { connectionInfo() })
        } else manager!!.createGroup(channel!!, action { connectionInfo() })
    }

    @Suppress("MissingPermission")
    private fun join() {
        val t = ticket ?: return fail("BAD_QR")
        if (Build.VERSION.SDK_INT >= 29) {
            val config = WifiP2pConfig.Builder().setNetworkName(t.ssid).setPassphrase(t.passphrase)
                .enablePersistentMode(false).build()
            manager!!.connect(channel!!, config, action { connectionInfo() })
        } else {
            manager!!.setDnsSdResponseListeners(channel!!, { _, _, _ -> }, { _, txt, device ->
                if (!closed && !connecting && txt["session"] == t.id) {
                    connecting = true
                    val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress; groupOwnerIntent = 0 }
                    manager.connect(channel!!, config, action { connectionInfo() })
                }
            })
            serviceRequest = WifiP2pDnsSdServiceRequest.newInstance("_melkit._tcp")
            manager.addServiceRequest(channel!!, serviceRequest!!, action { discover() })
        }
    }

    @Suppress("MissingPermission")
    fun advertise(id: String, complete: () -> Unit) {
        if (closed) return
        advertisement = WifiP2pDnsSdServiceInfo.newInstance("MelKit-${id.take(12)}", "_melkit._tcp", mapOf("session" to id))
        manager!!.addLocalService(channel!!, advertisement!!, action(complete))
    }

    @Suppress("MissingPermission")
    private fun discover() {
        manager!!.discoverServices(channel!!, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { main.postDelayed(retryDiscovery, 8000) }
            override fun onFailure(reason: Int) { if (reason == WifiP2pManager.BUSY) main.postDelayed(retryDiscovery, 2000) else fail(reason(reason)) }
        })
    }

    @Suppress("MissingPermission")
    private fun connectionInfo() {
        if (closed) return
        manager?.requestConnectionInfo(channel!!) { info ->
            if (closed) return@requestConnectionInfo
            if (!info.groupFormed) {
                if (established) fail("CONNECTION_LOST")
                return@requestConnectionInfo
            }
            if (owner != info.isGroupOwner) { fail("WRONG_ROLE"); return@requestConnectionInfo }
            val host = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
            manager.requestGroupInfo(channel!!) { group ->
                if (closed || group == null || delivered) return@requestGroupInfo
                if (!owner && group.networkName != ticket?.ssid) { fail("WRONG_GROUP"); return@requestGroupInfo }
                if (owner && group.passphrase.isNullOrBlank()) {
                    fail("GROUP_INFO"); return@requestGroupInfo
                }
                val localHost = if (owner) runCatching {
                    java.net.NetworkInterface.getByName(group.`interface`)?.inetAddresses?.toList()
                        ?.filterIsInstance<java.net.Inet4Address>()
                        ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
                }.getOrNull() else host
                if (localHost.isNullOrBlank()) { fail("GROUP_INFO"); return@requestGroupInfo }
                established = true; delivered = true
                main.removeCallbacks(timeout); main.removeCallbacks(retryDiscovery)
                ready(localHost, group)
            }
        }
    }

    private fun action(success: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (closed) return
            try { success() }
            catch (_: SecurityException) { fail("PERMISSION") }
            catch (_: Exception) { fail("CONNECTION_FAILED") }
        }
        override fun onFailure(reason: Int) { fail(reason(reason)) }
    }
    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        else -> "CONNECTION_FAILED"
    }
    private fun fail(code: String) { if (!closed) failed(code) }

    @Suppress("MissingPermission")
    fun close(complete: () -> Unit) {
        if (closed) { complete(); return }
        closed = true
        main.removeCallbacksAndMessages(null)
        if (registered) { runCatching { context.unregisterReceiver(receiver) }; registered = false }
        val ch = channel
        channel = null
        if (ch == null) { complete(); return }
        var completed = false
        val finish = Runnable {
            if (!completed) {
                completed = true
                runCatching { closeChannel(ch) }
                complete()
            }
        }
        main.postDelayed(finish, 3000)
        try {
            manager?.stopPeerDiscovery(ch, null)
            manager?.cancelConnect(ch, null)
            advertisement?.let { manager?.removeLocalService(ch, it, null) }
            serviceRequest?.let { manager?.removeServiceRequest(ch, it, null) }
            // Only remove the group owned by this session.
            if (ownsAttempt && manager != null) manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { main.removeCallbacks(finish); finish.run() }
                override fun onFailure(reason: Int) { main.removeCallbacks(finish); finish.run() }
            }) else { main.removeCallbacks(finish); finish.run() }
        } catch (_: Exception) {
            main.removeCallbacks(finish)
            finish.run()
        }
    }

    private fun closeChannel(ch: WifiP2pManager.Channel) { if (Build.VERSION.SDK_INT >= 27) ch.close() }

    companion object {
        fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        fun preflight(context: Context): String? {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return "UNSUPPORTED"
            if (context.checkSelfPermission(permission()) != PackageManager.PERMISSION_GRANTED) return "PERMISSION"
            if (context.getSystemService(WifiManager::class.java)?.isWifiEnabled != true) return "WIFI_OFF"
            if (Build.VERSION.SDK_INT < 33 && context.getSystemService(LocationManager::class.java)?.isLocationEnabledCompat() != true) return "LOCATION_OFF"
            return null
        }
        private fun LocationManager.isLocationEnabledCompat(): Boolean = if (Build.VERSION.SDK_INT >= 28) isLocationEnabled
            else isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
