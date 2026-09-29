package dev.drosh.ui.setup.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextDisabled
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshWarning

/**
 * Setup-screen design tokens — now delegates to the shared `:design-system`
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
