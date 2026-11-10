package com.spinel.melkit

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.spinel.melkit.ads.AdManager
import androidx.lifecycle.Lifecycle

class MainActivity : AppCompatActivity() {
    private val ads by lazy { AdManager.get(this) }
    private var shareRequest by mutableIntStateOf(0)
    private var notificationPermissionPending = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPermissionPending = false
        // Resume consent work, but never create a delayed app-open opportunity.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) ads.onResume(this, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ads.onCreate(this)
        PushNotifications.register(this)
        if (intent?.action == MelKitShareService.OPEN) { shareRequest++; ads.suppressExternalReturn() }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
        )
        setContent { MelKitTheme { MelKitApp(shareRequest) } }
        val fromNotification = intent.action == PushNotifications.OPEN || intent.hasExtra("google.message_id") || intent.hasExtra("gcm.message_id")
        if (fromNotification) ads.suppressExternalReturn()
        val notificationTap = savedInstanceState == null && NotificationNavigation.consume(this, intent)
        val preferences = getSharedPreferences("notifications", MODE_PRIVATE)
        if (!notificationTap && Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("permission_asked", false)) {
            // Record first normal launch even when permission was already granted.
            preferences.edit().putBoolean("permission_asked", true).apply()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionPending = true
                ads.suppressExternalReturn()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MelKitShareService.OPEN) { shareRequest++; ads.suppressExternalReturn() }
        if (intent.action == PushNotifications.OPEN || intent.hasExtra("google.message_id") || intent.hasExtra("gcm.message_id")) {
            ads.suppressExternalReturn()
        }
        NotificationNavigation.consume(this, intent)
    }

    override fun onResume() {
        super.onResume()
        ads.onResume(this, notificationPermissionPending)
    }

    override fun onPause() { ads.onPause(this); super.onPause() }
    override fun onStop() { ads.onStop(this, isChangingConfigurations); super.onStop() }
    override fun onDestroy() { ads.onDestroy(this); super.onDestroy() }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        // One completed gesture, irrespective of move events or the number of fingers.
        if (handled && event.actionMasked == MotionEvent.ACTION_UP) ads.onUserAction(this)
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (handled && event.action == KeyEvent.ACTION_UP && !event.isCanceled &&
            event.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BACK)) ads.onUserAction(this)
        return handled
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        ads.suppressExternalReturn()
        super.startActivity(intent, options)
    }

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ads.suppressExternalReturn()
        super.startActivityForResult(intent, requestCode, options)
    }
}

