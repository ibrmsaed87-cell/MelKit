package com.spinel.melkit

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.spinel.melkit.ui.MelKitApp
import com.spinel.melkit.ui.MelKitTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
        )
        setContent { MelKitTheme { MelKitApp() } }
    }
}
