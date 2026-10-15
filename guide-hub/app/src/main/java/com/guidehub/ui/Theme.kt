package com.guidehub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** High-contrast palette: black background, white text, yellow accent (low-vision friendly). */
object Palette {
    val Accent = Color(0xFFFFD600)
    val Danger = Color(0xFFFF5252)
    val Warn = Color(0xFFFFAB40)
    val Ok = Color(0xFF69F0AE)
    val Info = Color(0xFF40C4FF)
    val Panel = Color(0xFF1A1A1A)
    val Dim = Color(0xFFB0B0B0)
}

@Composable
fun GuideHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Color.Black,
            secondary = Palette.Info,
            background = Color.Black,
            onBackground = Color.White,
            surface = Palette.Panel,
            onSurface = Color.White,
            error = Palette.Danger,
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 18.sp),
            bodyMedium = TextStyle(fontSize = 16.sp),
            titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}
