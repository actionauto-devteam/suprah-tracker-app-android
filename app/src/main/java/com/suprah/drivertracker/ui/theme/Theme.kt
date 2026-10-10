package com.suprah.drivertracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Suprah AI emerald, with a calm neutral ground.
private val Emerald = Color(0xFF059669)
private val EmeraldLight = Color(0xFF34D399)
private val Danger = Color(0xFFB91C1C)
private val DangerLight = Color(0xFFF87171)

/** "Waiting" amber for light and dark backgrounds. */
val Amber = Color(0xFFB45309)
val AmberLight = Color(0xFFFBBF24)

private val LightColors =
    lightColorScheme(
        primary = Emerald,
        onPrimary = Color.White,
        secondary = Color(0xFF0F766E),
        background = Color(0xFFF5F7F6),
        surface = Color.White,
        surfaceVariant = Color(0xFFE7ECEA),
        onSurface = Color(0xFF111917),
        onSurfaceVariant = Color(0xFF4B5A55),
        error = Danger,
    )

private val DarkColors =
    darkColorScheme(
        primary = EmeraldLight,
        onPrimary = Color(0xFF00281C),
        secondary = Color(0xFF5EEAD4),
        background = Color(0xFF0B1210),
        surface = Color(0xFF111A17),
        surfaceVariant = Color(0xFF1B2622),
        onSurface = Color(0xFFE5ECE9),
        onSurfaceVariant = Color(0xFF9BAAA4),
        error = DangerLight,
    )

@Composable
fun TrackerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
