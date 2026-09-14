package com.iris.irisshell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.OutfitFontFamily

/**
 * Iris Shell design tokens.
 *
 * Per MEMORYBANK.md §5 — Visual Identity:
 *  - Background: #14171B (dark), Surface: #1C2025, SurfaceVariant: #252A30
 *  - Primary (terminal blue): #719FFF, OnPrimary: #14171B
 *  - Text: #F0F2F4, secondary #A8AEB6, muted #747B85, disabled #585F69
 *  - Success: #22C55E, Error: #EF4444, Warning: #F59E0B
 *  - Build/Compile: #719FFF (matches accent)
 *
 * Iris Shell is dark-only in v1.0 — we ignore the system dark/light switch so
 * the blue accent (#719FFF) and dark surfaces stay consistent.
 *
 * Typography is sourced from Outfit Regular (bundled TTF at
 * `res/font/outfit_regular.ttf`). Originally lifted from
 * `com.rk.terminal.ui.theme.OutfitFontFamily` in
 * https://github.com/RohitKushvaha01/ReTerminal so the entire app — top bar,
 * setup story, terminal chrome — shares the same letterforms as the host
 * shell.
 */
val IrisBackground: Color = Color(0xFF14171B)
val IrisSurface: Color = Color(0xFF1C2025)
val IrisSurfaceVariant: Color = Color(0xFF252A30)
val IrisOutline: Color = Color(0xFF343A43)
val IrisBorderSubtle: Color = Color(0xFF343A43)

val IrisPrimary: Color = Color(0xFF719FFF)
val IrisOnPrimary: Color = Color(0xFF14171B)

val IrisText: Color = Color(0xFFF0F2F4)
val IrisTextSecondary: Color = Color(0xFFA8AEB6)
val IrisTextMuted: Color = Color(0xFF747B85)
val IrisTextDisabled: Color = Color(0xFF585F69)

val IrisSuccess: Color = Color(0xFF22C55E)
val IrisError: Color = Color(0xFFEF4444)
val IrisWarning: Color = Color(0xFFF59E0B)
val IrisBuild: Color = Color(0xFF719FFF)

private val IrisDarkColors = darkColorScheme(
    primary = IrisPrimary,
    onPrimary = IrisOnPrimary,
    secondary = IrisTextSecondary,
    background = IrisBackground,
    surface = IrisSurface,
    surfaceVariant = IrisSurfaceVariant,
    outline = IrisOutline,
    error = IrisError,
    onError = IrisPrimary,
)

/**
 * Full Material 3 typography table bound to Outfit. The shape mirrors the
 * reference ReTerminal `Typography { ... }` block — same scale, weight
 * assignments, and sizes — so any title/body/label token feels at home in
 * a ReTerminal-style shell.
 */
private val IrisTypography = Typography(
    displayLarge = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 57.sp),
    displayMedium = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 45.sp),
    displaySmall = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 36.sp),
    headlineLarge = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

/**
 * Compose theme for the entire app.
 *
 * Iris Shell is dark-only in v1.0 — we ignore the system dark/light switch so
 * the blue accent (#719FFF) and dark surfaces stay consistent.
 */
@Composable
fun IrisTheme(content: @Composable () -> Unit) {
    // The system dark mode flag is intentionally ignored — Iris Shell mandate.
    @Suppress("UNUSED_VARIABLE") val isDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = IrisDarkColors,
        typography = IrisTypography,
        content = content,
    )
}
