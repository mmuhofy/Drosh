package com.iris.irisshell.design.system

import androidx.compose.ui.graphics.Color

/**
 * Design tokens — the Iris Shell palette.
 *
 * Originally defined inside `:app/.../ui/theme/IrisTheme.kt` (Phase 2), the
 * tokens are promoted to `:design-system` so every module that renders
 * Compose — `:app`, `:ui`, and future `:agent` HUDs — sees the exact same
 * hex values without duplicating them. Per MEMORYBANK.md §5 — Visual
 * Identity:
 *
 *   - Background #14171B (dark), Surface #1C2025, SurfaceVariant #252A30
 *   - Outline #343A43, BorderSubtle #343A43
 *   - Primary (terminal blue) #719FFF, OnPrimary #14171B
 *   - Text #F0F2F4, TextSecondary #A8AEB6, TextMuted #747B85, TextDisabled #585F69
 *   - Success #22C55E, Error #EF4444, Warning #F59E0B, Build #719FFF
 *
 * Iris Shell is dark-only on v1.0 — dark mode (#14171B) is the default
 * background. Surface levels use subtle luminance steps for clear visual hierarchy.
 * The blue accent (#719FFF) replaces the previous gold, evoking terminal cursor cyan
 * and VS Code's professional dark theme.
 */
val IrisBackground: Color = Color(0xFF14171B)
val IrisSurface: Color = Color(0xFF1C2025)
val IrisSurfaceVariant: Color = Color(0xFF252A30)
val IrisSurfaceLow: Color = Color(0xFF191C20)
val IrisSurfaceHigh: Color = Color(0xFF272A2E)
val IrisSurfaceContainerLowest: Color = Color(0xFF0B0E12)
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
