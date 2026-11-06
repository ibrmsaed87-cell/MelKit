package com.spinel.melkit

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.spinel.melkit.share.MelKitShareService
import com.spinel.melkit.ui.MelKitApp
import com.spinel.melkit.ui.MelKitTheme

class MainActivity : AppCompatActivity() {
    private var shareRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == MelKitShareService.OPEN) shareRequest++
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
        )
        setContent { MelKitTheme { MelKitApp(shareRequest) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MelKitShareService.OPEN) shareRequest++
    }
}

