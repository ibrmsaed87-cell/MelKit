package com.spinel.melkit

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.spinel.melkit.share.MelKitShareService
import com.spinel.melkit.ui.MelKitApp
import com.spinel.melkit.ui.MelKitTheme
import com.spinel.melkit.push.NotificationNavigation
import com.spinel.melkit.push.PushNotifications

class MainActivity : AppCompatActivity() {
    private var shareRequest by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PushNotifications.register(this)
        if (intent?.action == MelKitShareService.OPEN) shareRequest++
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
        )
        setContent { MelKitTheme { MelKitApp(shareRequest) } }
        val notificationTap = savedInstanceState == null && NotificationNavigation.consume(this, intent)
        val preferences = getSharedPreferences("notifications", MODE_PRIVATE)
        if (!notificationTap && Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("permission_asked", false)) {
            // Record first normal launch even when permission was already granted.
            preferences.edit().putBoolean("permission_asked", true).apply()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MelKitShareService.OPEN) shareRequest++
        NotificationNavigation.consume(this, intent)
    }
}

