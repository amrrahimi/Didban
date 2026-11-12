package com.pokervision.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Poker-table palette: deep felt greens, warm gold accent, high-contrast text. */
object PokerColors {
    val FeltDark = Color(0xFF0B1F17)
    val Felt = Color(0xFF123526)
    val FeltLight = Color(0xFF1C4A36)
    val Gold = Color(0xFFD4AF37)
    val GoldDim = Color(0xFF8C7424)
    val Ivory = Color(0xFFF3EFE2)
    val Muted = Color(0xFFA9B8AF)
    val CardRed = Color(0xFFE5484D)
    val CardBlack = Color(0xFF1A1A1A)
    val ErrorRed = Color(0xFFFF6B6B)

    // Confidence indicators: chosen for distinct luminance, not only hue (color-blind safety).
    val ConfidenceHigh = Color(0xFF4ADE80)
    val ConfidenceMedium = Color(0xFFFBBF24)
    val ConfidenceLow = Color(0xFFF87171)
}

private val DarkScheme = darkColorScheme(
    primary = PokerColors.Gold,
    onPrimary = PokerColors.FeltDark,
    primaryContainer = PokerColors.GoldDim,
    onPrimaryContainer = PokerColors.Ivory,
    secondary = PokerColors.FeltLight,
    onSecondary = PokerColors.Ivory,
    background = PokerColors.FeltDark,
    onBackground = PokerColors.Ivory,
    surface = PokerColors.Felt,
    onSurface = PokerColors.Ivory,
    surfaceVariant = PokerColors.FeltLight,
    onSurfaceVariant = PokerColors.Muted,
    error = PokerColors.ErrorRed,
    onError = PokerColors.FeltDark,
    outline = PokerColors.GoldDim,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

@Immutable
data class Spacing(val xs: Int = 4, val s: Int = 8, val m: Int = 16, val l: Int = 24, val xl: Int = 32)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** The app is dark-only by design (poker rooms, OLED battery savings). */
@Composable
fun PokerVisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}
