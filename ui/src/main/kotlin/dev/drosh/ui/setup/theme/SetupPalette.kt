package dev.drosh.ui.setup.theme

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
    val Background = DroshBackground
    val Surface = DroshSurface
    val SurfaceVariant = DroshSurfaceVariant
    val Outline = DroshOutline
    val BorderSubtle = DroshBorderSubtle

    val Primary = DroshPrimary
    val OnPrimary = DroshOnPrimary

    val Text = DroshText
    val TextSecondary = DroshTextSecondary
    val TextMuted = DroshTextMuted
    val TextDisabled = DroshTextDisabled

    val Success = DroshSuccess
    val Error = DroshError
    val Warning = DroshWarning
    val MonoLog = DroshTextSecondary

    val PulseHalo = DroshPrimary.copy(alpha = 0.4f)
    val PulseHaloStrong = DroshPrimary.copy(alpha = 0.8f)
}
