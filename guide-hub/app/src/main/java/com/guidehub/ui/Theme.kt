package com.guidehub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens. Neutral off-white surfaces, dark navy text, a restrained teal accent, and
 * semantic tones for warnings. Text/background pairs meet WCAG AA (most exceed 7:1).
 */
object Palette {
    val Background = Color(0xFFF6F7F5)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceAlt = Color(0xFFEEF2F1)
    val Outline = Color(0xFFD3DBDA)
    val Ink = Color(0xFF13233A)
    val InkSoft = Color(0xFF4A5A6E)
    val Teal = Color(0xFF0F766E)
    val TealDark = Color(0xFF0B5A54)
    val TealSoft = Color(0xFFD9F0EC)
    val OnTealSoft = Color(0xFF0B4A45)
    val Amber = Color(0xFF9A4A07)
    val AmberSoft = Color(0xFFFDF0D9)
    val Red = Color(0xFFB42318)
    val RedSoft = Color(0xFFFDE7E5)
    val Green = Color(0xFF166534)
    val GreenSoft = Color(0xFFDDF3E4)
    val Indigo = Color(0xFF4B3FA0)
    val IndigoSoft = Color(0xFFECE9FB)

    // Names used by the diagnostics drawings.
    val Accent = Teal
    val Danger = Red
    val Warn = Amber
    val Ok = Green
    val Info = Color(0xFF2563A6)
    val Panel = SurfaceAlt
    val Dim = InkSoft
}

/** Spacing scale, in dp. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    /** Minimum touch target for primary actions (above the 48 dp guideline). */
    val touch = 56.dp
}

@Composable
fun GuideHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Teal,
            onPrimary = Color.White,
            primaryContainer = Palette.TealSoft,
            onPrimaryContainer = Palette.OnTealSoft,
            secondary = Palette.Ink,
            onSecondary = Color.White,
            secondaryContainer = Palette.SurfaceAlt,
            onSecondaryContainer = Palette.Ink,
            background = Palette.Background,
            onBackground = Palette.Ink,
            surface = Palette.Surface,
            onSurface = Palette.Ink,
            surfaceVariant = Palette.SurfaceAlt,
            onSurfaceVariant = Palette.InkSoft,
            surfaceContainer = Palette.Surface,
            surfaceContainerLow = Palette.Surface,
            surfaceContainerHigh = Palette.SurfaceAlt,
            surfaceContainerHighest = Palette.SurfaceAlt,
            outline = Palette.Outline,
            outlineVariant = Palette.Outline,
            error = Palette.Red,
            onError = Color.White,
            errorContainer = Palette.RedSoft,
            onErrorContainer = Palette.Red,
        ),
        typography = Typography(
            headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp),
        ),
        content = content,
    )
}
