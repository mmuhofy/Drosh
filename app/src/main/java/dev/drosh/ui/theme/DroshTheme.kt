package dev.drosh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceInputBox
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily

/**
 * Drosh design tokens.
 *
 * Token VALUES now live in `:design-system` (DroshColors.kt) — the single
 * source of truth every module (`:app`, `:ui`, future `:agent` HUDs) reads
 * from. This file only wires those tokens into the Material 3 color scheme;
 * it no longer redeclares hex values (previous revision duplicated the full
 * palette here, which had drifted out of sync with :design-system).
 *
 * Palette is a neutral anthracite grayscale (Background #111111 → Surface
 * #1C1C1C → SurfaceInputBox #292929 → Border #464646), replacing the earlier
 * blue-tinted scheme. See DroshColors.kt for the full elevation ladder and
 * the rationale (color reserved for meaning; brand blue #5B8DEF lives only
 * in the mark/mascot, never in chrome).
 *
 * Drosh is dark-only in v1.0 — we ignore the system dark/light switch so
 * the anthracite surfaces stay consistent regardless of device theme.
 *
 * Typography is sourced from Outfit Regular (bundled TTF at
 * `res/font/outfit_regular.ttf`). Originally lifted from
 * `com.rk.terminal.ui.theme.OutfitFontFamily` in
 * https://github.com/RohitKushvaha01/ReTerminal so the entire app — top bar,
 * setup story, terminal chrome — shares the same letterforms as the host
 * shell.
 */
private val DroshDarkColors = darkColorScheme(
    primary = DroshPrimary,
    onPrimary = DroshOnPrimary,
    secondary = DroshTextSecondary,
    background = DroshBackground,
    surface = DroshSurface,
    surfaceVariant = DroshSurfaceInputBox,
    outline = DroshOutline,
    error = DroshError,
    onError = DroshPrimary,
)

/**
 * Full Material 3 typography table bound to Outfit. The shape mirrors the
 * reference ReTerminal `Typography { ... }` block — same scale, weight
 * assignments, and sizes — so any title/body/label token feels at home in
 * a ReTerminal-style shell.
 */
private val DroshTypography = Typography(
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
 * Drosh is dark-only in v1.0 — we ignore the system dark/light switch so
 * the anthracite surfaces stay consistent regardless of device theme.
 */
@Composable
fun DroshTheme(content: @Composable () -> Unit) {
    // The system dark mode flag is intentionally ignored — Drosh mandate.
    @Suppress("UNUSED_VARIABLE") val isDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DroshDarkColors,
        typography = DroshTypography,
        content = content,
    )
}
