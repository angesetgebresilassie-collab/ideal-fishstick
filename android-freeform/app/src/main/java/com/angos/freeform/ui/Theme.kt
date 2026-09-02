package com.angos.freeform.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** macOS Sonoma-ish palette. */
object MacColors {
    val Accent = Color(0xFF0A84FF)          // system blue
    val TrafficRed = Color(0xFFFF5F57)
    val TrafficAmber = Color(0xFFFEBC2E)
    val TrafficGreen = Color(0xFF28C840)

    val GlassTint = Color(0x40FFFFFF)
    val GlassTintDark = Color(0x4014141A)
    val GlassBorder = Color(0x55FFFFFF)
    val GlassBorderDark = Color(0x33FFFFFF)

    val TextPrimary = Color(0xFF1C1C1E)
    val TextPrimaryDark = Color(0xFFF2F2F7)
    val TextSecondary = Color(0x991C1C1E)
    val TextSecondaryDark = Color(0x99F2F2F7)
}

private val LightScheme = lightColorScheme(
    primary = MacColors.Accent,
    onPrimary = Color.White,
    background = Color(0xFFEDEDF2),
    onBackground = MacColors.TextPrimary,
    surface = Color(0x66FFFFFF),
    onSurface = MacColors.TextPrimary,
    error = MacColors.TrafficRed
)

private val DarkScheme = darkColorScheme(
    primary = MacColors.Accent,
    onPrimary = Color.White,
    background = Color(0xFF16161A),
    onBackground = MacColors.TextPrimaryDark,
    surface = Color(0x6614141A),
    onSurface = MacColors.TextPrimaryDark,
    error = MacColors.TrafficRed
)

/** SF Pro is not shippable; system sans at SF-like weights is the closest legal match. */
private val MacTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, letterSpacing = (-0.6).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, letterSpacing = (-0.2).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, letterSpacing = (-0.1).sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp
    )
)

@Composable
fun MacFreeformTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = MacTypography,
        content = content
    )
}
