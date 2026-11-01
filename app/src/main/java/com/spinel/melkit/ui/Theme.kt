package com.spinel.melkit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MelkitMint = Color(0xFFA4F56A)
val MelkitBackground = Color(0xFF101513)
val MelkitSurface = Color(0xFF1B2420)

private val Colors = darkColorScheme(
    primary = MelkitMint,
    onPrimary = Color(0xFF15230E),
    primaryContainer = Color(0xFF29472A),
    onPrimaryContainer = MelkitMint,
    background = MelkitBackground,
    onBackground = Color(0xFFF1F5F1),
    surface = MelkitSurface,
    onSurface = Color(0xFFF1F5F1),
    surfaceVariant = Color(0xFF26322C),
    onSurfaceVariant = Color(0xFFB6C2B9),
    outline = Color(0xFF405046),
    error = Color(0xFFFFB4AB),
)

@Composable
fun MelKitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
