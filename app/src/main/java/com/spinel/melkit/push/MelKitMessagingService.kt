package com.spinel.melkit.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MelKitMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Receiving a message displays it; only a notification tap can open a URL.
        PushNotifications.show(this, message)
    }

    override fun onNewToken(token: String) {
        PushNotifications.register(this)
    }
}
