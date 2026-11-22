package com.hjtk.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * HJTK's own design tokens.
 *
 * Warm paper rather than pure white, one deep green accent used sparingly,
 * hairline dividers instead of drop shadows, and a single rounded-corner scale.
 * Deliberately no gradients and no card-on-card stacking.
 */
data class HjtkPalette(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val inkSoft: Color,
    val line: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val warn: Color,
    val danger: Color,
    val success: Color,
    val bubbleUser: Color,
    val bubbleUserInk: Color
)

val LightPalette = HjtkPalette(
    background = Color(0xFFF7F5F0),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF1EEE6),
    ink = Color(0xFF17191A),
    inkSoft = Color(0xFF71756F),
    line = Color(0xFFE6E1D6),
    accent = Color(0xFF37624C),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE6EFE7),
    warn = Color(0xFFB4741F),
    danger = Color(0xFFB0442F),
    success = Color(0xFF2F6B4A),
    bubbleUser = Color(0xFF1E2320),
    bubbleUserInk = Color(0xFFF7F6F2)
)

val DarkPalette = HjtkPalette(
    background = Color(0xFF101213),
    surface = Color(0xFF191C1D),
    surfaceAlt = Color(0xFF212526),
    ink = Color(0xFFF1F0EB),
    inkSoft = Color(0xFF9BA19E),
    line = Color(0xFF2A2F30),
    accent = Color(0xFF9AC7AA),
    accentInk = Color(0xFF10170F),
    accentSoft = Color(0xFF22302A),
    warn = Color(0xFFE0A757),
    danger = Color(0xFFE38470),
    success = Color(0xFF8FC7A6),
    bubbleUser = Color(0xFFE9E7DF),
    bubbleUserInk = Color(0xFF16181A)
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val HjtkTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.5.sp,
        lineHeight = 21.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp
    )
)

val HjtkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun HjtkTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.accent,
        onPrimary = palette.accentInk,
        secondary = palette.accent,
        background = palette.background,
        onBackground = palette.ink,
        surface = palette.surface,
        onSurface = palette.ink,
        surfaceVariant = palette.surfaceAlt,
        onSurfaceVariant = palette.inkSoft,
        outline = palette.line,
        error = palette.danger
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HjtkTypography,
            shapes = HjtkShapes,
            content = content
        )
    }
}

object Hjtk {
    val palette: HjtkPalette
        @Composable get() = LocalPalette.current
}
