package dev.drosh.ui.setup.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshOnPrimary
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshSuccess
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextDisabled
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshWarning

/**
 * Setup-screen design tokens — now delegates to the shared `:theme`
 * palette so the setup screens look identical to the terminal UI without
 * duplicating hex values.
 */
internal object SetupPalette {
    val Background: Color
        @Composable @ReadOnlyComposable get() = DroshBackground
    val Surface: Color
        @Composable @ReadOnlyComposable get() = DroshSurface
    val SurfaceVariant: Color
        @Composable @ReadOnlyComposable get() = DroshSurfaceVariant
    val Outline: Color
        @Composable @ReadOnlyComposable get() = DroshOutline
    val BorderSubtle: Color
        @Composable @ReadOnlyComposable get() = DroshBorderSubtle

    val Primary: Color
        @Composable @ReadOnlyComposable get() = DroshPrimary
    val OnPrimary: Color
        @Composable @ReadOnlyComposable get() = DroshOnPrimary

    val Text: Color
        @Composable @ReadOnlyComposable get() = DroshText
    val TextSecondary: Color
        @Composable @ReadOnlyComposable get() = DroshTextSecondary
    val TextMuted: Color
        @Composable @ReadOnlyComposable get() = DroshTextMuted
    val TextDisabled: Color
        @Composable @ReadOnlyComposable get() = DroshTextDisabled

    val Success: Color
        @Composable @ReadOnlyComposable get() = DroshSuccess
    val Error: Color
        @Composable @ReadOnlyComposable get() = DroshError
    val Warning: Color
        @Composable @ReadOnlyComposable get() = DroshWarning
    val MonoLog: Color
        @Composable @ReadOnlyComposable get() = DroshTextSecondary

    val PulseHalo: Color
        @Composable @ReadOnlyComposable get() = DroshPrimary.copy(alpha = 0.4f)
    val PulseHaloStrong: Color
        @Composable @ReadOnlyComposable get() = DroshPrimary.copy(alpha = 0.8f)
}
