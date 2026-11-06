package com.spinel.melkit.share

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID

/** The QR authorizes one short-lived batch and contains all connection information. */
internal data class MelKitShareTicket(
    val id: String, val key: String, val ssid: String, val passphrase: String,
    val port: Int, val expires: Long
) {
    fun encode(): String = "melkit://share?" + linkedMapOf(
        "v" to "2", "id" to id, "key" to key, "ssid" to ssid, "pass" to passphrase,
        "port" to port.toString(), "expires" to expires.toString()
    ).entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }

    companion object {
        fun key(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        fun parse(value: String, now: Long = System.currentTimeMillis()): MelKitShareTicket? = try {
            require(value.length in 100..2048)
            val uri = URI(value)
            require(uri.scheme == "melkit" && uri.rawAuthority == "share" && uri.path.isNullOrEmpty() && uri.fragment == null)
            val pairs = uri.rawQuery.split('&').map {
                val part = it.split('=', limit = 2)
                require(part.size == 2)
                part[0] to URLDecoder.decode(part[1], "UTF-8")
            }
            val args = pairs.toMap()
            require(pairs.size == 7 && args.size == 7 && args["v"] == "2")
            val id = args.getValue("id"); val key = args.getValue("key")
            val ssid = args.getValue("ssid"); val pass = args.getValue("pass")
            val port = args.getValue("port").toInt()
            val expires = args.getValue("expires").toLong()
            require(UUID.fromString(id).toString() == id && id.matches(Regex("[a-f0-9-]{36}")))
            require(key.matches(Regex("[a-f0-9]{64}")))
            require(ssid.matches(Regex("DIRECT-[A-Za-z0-9]{2}.*")) && ssid.toByteArray().size <= 32)
            require(pass.length in 8..63 && pass.all { it.code in 32..126 })
            require(port in 1024..65535 && expires > now && expires - now <= 11 * 60 * 1000)
            MelKitShareTicket(id, key, ssid, pass, port, expires)
        } catch (_: Exception) { null }
    }
}
