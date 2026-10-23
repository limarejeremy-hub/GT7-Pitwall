package com.gt7pitwall.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object PitColors {
    val Orange = Color(0xFFFF6D00)
    val Cyan = Color(0xFF00E5FF)
    val Green = Color(0xFF00E676)
    val Yellow = Color(0xFFFFD600)
    val Red = Color(0xFFFF1744)
    val Purple = Color(0xFFD500F9)
    val Muted = Color(0xFF8A94A6)
}

private val Scheme = darkColorScheme(
    primary = PitColors.Orange,
    onPrimary = Color.Black,
    secondary = PitColors.Cyan,
    onSecondary = Color.Black,
    background = Color(0xFF0A0C10),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF12161C),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF1B2129),
    onSurfaceVariant = Color(0xFFB4BDCB),
    error = PitColors.Red,
)

/** Thème sombre unique : lisible en roulant, économe sur l'écran AMOLED du Fold. */
@Composable
fun PitwallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
