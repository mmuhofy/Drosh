package dev.drosh.design.system

import androidx.compose.ui.graphics.Color

/**
 * Design tokens — the Drosh palette.
 *
 * Originally defined inside `:app/.../ui/theme/DroshTheme.kt` (Phase 2), the
 * tokens are promoted to `:design-system` so every module that renders
 * Compose — `:app`, `:ui`, and future `:agent` HUDs — sees the exact same
 * hex values without duplicating them.
 *
 * Palette revision (this pass) — replaces the earlier blue-tinted dark
 * scheme (#14171B/#1C2025/#719FFF) with a neutral anthracite grayscale
 * plus a cyan accent:
 *
 *   - Background #111111 (terminal floor — lowest layer)
 *   - Surface #1C1C1C (TopBar, App Actions strip, general cards)
 *   - SurfaceSidebar #1F1F1F (session switcher / sidebar panel)
 *   - SurfaceInputBox #292929 (the input box itself, and block dividers)
 *   - SurfaceSheetCard #2A2A2A (cards/rows inside bottom sheets — agent
 *     panel, overflow menu)
 *   - SurfacePressed #333333 (transient pressed/hover state on any element)
 *   - Border #464646 (all card/input edges)
 *
 * Elevation reads as luminance: the deeper a layer sits behind the terminal
 * (background) the darker it is; the more a layer floats above it (sidebar,
 * bottom sheet, pressed state) the lighter it is. No shadows — consistent
 * with the near-zero-animation, minimal-chrome direction (Open Decision #8).
 *
 * Color is reserved for meaning only: Success/Warning/Error stay saturated
 * because they carry information (exit codes, alerts). Cyan (#06B6D4)
 * replaces the prior blue (#5B8DEF) as the brand accent — used for the
 * Drosh logo/mascot, link underlines, and subtle selection tints.
 */
val DroshBackground: Color = Color(0xFF111111)
val DroshSurface: Color = Color(0xFF1C1C1C)
val DroshSurfaceSidebar: Color = Color(0xFF1F1F1F)
val DroshSurfaceInputBox: Color = Color(0xFF292929)
val DroshSurfaceSheetCard: Color = Color(0xFF2A2A2A)
val DroshSurfacePressed: Color = Color(0xFF333333)
val DroshOutline: Color = Color(0xFF464646)
val DroshBorderSubtle: Color = Color(0xFF464646)

// Legacy aliases kept for call sites not yet migrated to the named tokens
// above. Prefer the specific token (DroshSurfaceSidebar, DroshSurfaceSheetCard,
// etc.) in new code; these will be removed once all usages are audited.
val DroshSurfaceVariant: Color = DroshSurfaceInputBox
val DroshSurfaceLow: Color = DroshBackground
val DroshSurfaceHigh: Color = DroshSurfacePressed
val DroshSurfaceContainerLowest: Color = Color(0xFF0A0A0A)

/**
 * Cyan accent — replaces the prior blue (#5B8DEF) as the brand accent.
 * Used for the Drosh logo/mascot, link underlines, and subtle selection
 * tints in the classic terminal overlay.
 */
val DroshPrimary: Color = Color(0xFF06B6D4)
val DroshOnPrimary: Color = Color(0xFF111111)

val DroshText: Color = Color(0xFFEDEDED)
val DroshTextSecondary: Color = Color(0xFFA0A0A0)
val DroshTextMuted: Color = Color(0xFF707070)
val DroshTextDisabled: Color = Color(0xFF505050)

val DroshSuccess: Color = Color(0xFF22C55E)
val DroshError: Color = Color(0xFFEF4444)
val DroshWarning: Color = Color(0xFFF59E0B)
val DroshBuild: Color = Color(0xFF06B6D4)
