package dev.drosh.design.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The palette, in one set per theme.
 *
 * Values are not shared between themes and cannot be derived by inverting the
 * dark ones: light text on a dark surface reads at 1.08:1, which is not a
 * palette, it is a bug. Each set was checked against the surface it sits on —
 * text and semantic colours at AA or better, surfaces separated by tone rather
 * than contrast, the way a light theme has to work.
 *
 * Dark is the default. Light exists because a terminal that is only ever dark
 * is unusable in daylight, and because the system asks for one.
 */
@Immutable
data class DroshThemeColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val surfaceContainerLowest: Color,
    val outline: Color,
    val borderSubtle: Color,
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val build: Color,
    /**
     * Item tile in a settings group. Deliberately not [surfaceVariant]: that is
     * a fill for containers, and a tile is a different object. It has to be
     * lighter than [background] in both themes, which surfaceVariant is in dark
     * but not in light.
     */
    val tile: Color,
    /** A tile that is the current choice. Carries a check and accent text too, so the tone is not the only signal. */
    val tileSelected: Color,
    /** A tile under a finger. */
    val tilePressed: Color,
    /** Unfilled end of a slider or a switch. */
    val track: Color,
)

private val DarkColors = DroshThemeColors(
    background = Color(0xFF0E0E0E),
    surface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF242424),
    surfaceLow = Color(0xFF151515),
    surfaceHigh = Color(0xFF2E2E2E),
    surfaceContainerLowest = Color(0xFF080808),
    outline = Color(0xFF3A3A3A),
    borderSubtle = Color(0xFF3A3A3A),
    primary = Color(0xFF4C9EFF),
    onPrimary = Color(0xFF0E0E0E),
    // Near-white rather than pure white: pure #FFFFFF blooms on OLED panels.
    text = Color(0xFFF2F2F2),
    textSecondary = Color(0xFFB4B4B4),
    // 4.5:1 on the tile, not just on the background — supporting text sits on tiles.
    textMuted = Color(0xFF8A8A8A),
    textDisabled = Color(0xFF4D4D4D),
    success = Color(0xFF3DD68C),
    error = Color(0xFFF2555A),
    warning = Color(0xFFF0B429),
    build = Color(0xFF4C9EFF),
    tile = Color(0xFF242424),
    tileSelected = Color(0xFF2A2F38),
    tilePressed = Color(0xFF2A2A2A),
    track = Color(0xFF33363A),
)

private val LightColors = DroshThemeColors(
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF3),
    surfaceLow = Color(0xFFF9FAFB),
    surfaceHigh = Color(0xFFE3E7EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Color(0xFFC9CFD8),
    borderSubtle = Color(0xFFC9CFD8),
    // Darker than the dark theme's accent, so it clears AA on a light field.
    primary = Color(0xFF0B6BCB),
    onPrimary = Color(0xFFFFFFFF),
    text = Color(0xFF14181D),
    textSecondary = Color(0xFF4B535E),
    textMuted = Color(0xFF6C7480),
    textDisabled = Color(0xFF9AA2AE),
    success = Color(0xFF1B7F47),
    error = Color(0xFFC42B31),
    warning = Color(0xFF8A5A00),
    build = Color(0xFF0B6BCB),
    tile = Color(0xFFFFFFFF),
    tileSelected = Color(0xFFE4EEFC),
    tilePressed = Color(0xFFF0F2F5),
    track = Color(0xFFCFD5DE),
)

/**
 * The palette in force. Defaults to dark so a subtree composed before
 * [DroshTheme] — a preview, a test — still gets something sensible.
 */
val LocalDroshColors = staticCompositionLocalOf { DarkColors }

/** Picks the palette for a theme choice: [dark] true for the dark set. */
@Composable
fun provideDroshColors(dark: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDroshColors provides if (dark) DarkColors else LightColors,
        content = content,
    )
}

// The names below read through the composition local rather than holding a
// value, so every existing call site keeps working and picks up the theme
// without being touched. They are composable for that reason: there is no
// value to read outside a composition.

val DroshBackground: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.background

val DroshSurface: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.surface

val DroshSurfaceVariant: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.surfaceVariant

val DroshSurfaceLow: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.surfaceLow

val DroshSurfaceHigh: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.surfaceHigh

val DroshSurfaceContainerLowest: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.surfaceContainerLowest

val DroshOutline: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.outline

val DroshBorderSubtle: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.borderSubtle

val DroshPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.primary

val DroshOnPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.onPrimary

val DroshText: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.text

val DroshTextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.textSecondary

val DroshTextMuted: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.textMuted

val DroshTextDisabled: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.textDisabled

val DroshSuccess: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.success

val DroshError: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.error

val DroshWarning: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.warning

val DroshBuild: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.build

val DroshTile: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.tile

val DroshTileSelected: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.tileSelected

val DroshTilePressed: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.tilePressed

val DroshTrack: Color
    @Composable @ReadOnlyComposable get() = LocalDroshColors.current.track
