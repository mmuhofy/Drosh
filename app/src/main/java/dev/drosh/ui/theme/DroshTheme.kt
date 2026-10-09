package dev.drosh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.ui.agent.components.provideCodePalette
import dev.drosh.ui.agent.components.provideMarkdownTheme
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.design.system.provideDroshColors

@Composable
private fun schemeFor(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = DroshPrimary,
        onPrimary = DroshOnPrimary,
        secondary = DroshTextSecondary,
        background = DroshBackground,
        surface = DroshSurface,
        surfaceVariant = DroshSurfaceVariant,
        outline = DroshOutline,
        error = DroshError,
        onError = DroshPrimary,
    )
} else {
    lightColorScheme(
        primary = DroshPrimary,
        onPrimary = DroshOnPrimary,
        secondary = DroshTextSecondary,
        background = DroshBackground,
        surface = DroshSurface,
        surfaceVariant = DroshSurfaceVariant,
        outline = DroshOutline,
        error = DroshError,
        onError = DroshPrimary,
    )
}

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
 * The colours themselves live in `:design-system` (DroshColors.kt) and are
 * imported here. They used to be duplicated in this file, which meant two
 * copies to keep in step and one of them silently drifted out of date.
 *
 * Drosh is dark-only in v1.0 — the system dark/light switch is ignored so
 * the accent and dark surfaces stay consistent.
 */
/**
 * @param dark forced theme; null follows the system.
 *
 * The system setting used to be ignored outright. That made the app
 * unusable in daylight, and it is not something a caller should have to
 * argue for: the theme is a preference, so it gets a preference.
 */
@Composable
fun DroshTheme(
    dark: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val useDark = dark ?: isSystemInDarkTheme()
    provideDroshColors(dark = useDark) {
        MaterialTheme(
            colorScheme = schemeFor(useDark),
            typography = DroshTypography,
            content = {
                // The syntax palette follows the theme, and is provided here rather
                // than read from the app palette at each use site: it is a separate
                // set of colours entirely, so it needs its own scope to follow the
                // theme rather than a second theme lookup per token.
                provideCodePalette(dark = useDark) {
                    // Markdown text, headings and spacing, expressed in the app's own
                    // tokens. Without this the renderer falls back to its defaults,
                    // which are Material's, and an assistant answer stops matching
                    // the message it sits under.
                    provideMarkdownTheme(dark = useDark) { content() }
                }
            },
        )
    }
}
