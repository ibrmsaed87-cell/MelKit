package com.spinel.melkit.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import com.spinel.melkit.MainActivity
import com.spinel.melkit.R
import java.util.UUID

object PushNotifications {
    const val CHANNEL = "melkit_updates"
    const val TOPIC = "melkit_updates"
    const val OPEN = "com.spinel.melkit.OPEN_NOTIFICATION"

    fun register(context: Context) {
        createChannel(context)
        // The topic is app-specific even though the Firebase project hosts other apps.
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.notifications_channel),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notifications_description)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)

    fun openSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }
    }

    fun show(context: Context, message: RemoteMessage) {
        createChannel(context)
        if (!allowed(context)) return
        val title = (message.notification?.title ?: message.data["title"] ?: context.getString(R.string.app_name)).take(200)
        val body = (message.notification?.body ?: message.data["body"]
            ?: context.getString(R.string.notifications_fallback_body)).take(2000)
        val id = (message.messageId ?: UUID.randomUUID().toString()).hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = OPEN
            data = Uri.parse("melkit-notification://message/$id")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            PlayStoreLinks.fromData(message.data)?.let { putExtra("url", it) }
        }
        val pending = PendingIntent.getActivity(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFACFA6B.toInt())
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        // Permission can change between allowed() and notify().
        try { NotificationManagerCompat.from(context).notify(id, notification) } catch (_: SecurityException) { }
    }
}
