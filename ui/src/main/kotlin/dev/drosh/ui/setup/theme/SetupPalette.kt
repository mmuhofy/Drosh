package dev.drosh.ui.setup.theme

import androidx.compose.ui.graphics.Color

/**
 * Setup-screen specific design tokens.
 *
 * Values mirror MEMORYBANK.md §5 — Visual Identity. We duplicate them here
 * (rather than depend on `:app`'s `DroshTheme`) so `:ui` stays free of
 * cross-module dependencies per AGENT.md §110-139.
 *
 * Once the `:design-system` consolidation lands, these tokens should move to
 * that module and the inlined duplicates here will be deleted.
 */
internal object SetupPalette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0A0A0A)
    val SurfaceVariant = Color(0xFF121212)
    val Outline = Color(0xFF252525)
    val BorderSubtle = Color(0xFF1E1E1E)

    val Primary = Color(0xFF3B82F6)
    val OnPrimary = Color(0xFF050505)

    val Text = Color(0xFFE8E8E8)
    val TextSecondary = Color(0xFFA0A0A0)
    val TextMuted = Color(0xFF787878)
    val TextDisabled = Color(0xFF585858)

    val Success = Color(0xFF22C55E)
    val Error = Color(0xFFEF4444)
    val Warning = Color(0xFFF59E0B)
    val MonoLog = Color(0xFFB0B0B0)

    val PulseHalo = Color(0x403B82F6)
    val PulseHaloStrong = Color(0x803B82F6)
}
