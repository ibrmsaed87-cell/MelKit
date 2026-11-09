package com.spinel.melkit.push

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.spinel.melkit.R

object NotificationNavigation {
    /** Handles our PendingIntent and Firebase Console's background-notification launcher extras. */
    fun consume(activity: Activity, intent: Intent): Boolean {
        val own = intent.action == PushNotifications.OPEN
        val fromFirebase = intent.hasExtra("google.message_id") || intent.hasExtra("gcm.message_id")
        if (!own && !fromFirebase) return false
        val data = mutableMapOf<String, String>()
        for (key in PlayStoreLinks.DATA_KEYS) {
            runCatching { intent.getStringExtra(key) }.getOrNull()?.let { data[key] = it }
            intent.removeExtra(key)
        }
        // Consume first so rotation or returning from Play cannot open it a second time.
        intent.removeExtra("google.message_id")
        intent.removeExtra("gcm.message_id")
        if (own) { intent.action = Intent.ACTION_MAIN; intent.data = null }
        val url = PlayStoreLinks.fromData(data) ?: return true
        val uri = Uri.parse(url)
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
        } catch (_: ActivityNotFoundException) {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.open_link_error, Toast.LENGTH_LONG).show()
            }
        }
        return true
    }
}
