package dev.drosh.design.system

import androidx.compose.ui.graphics.Color
import dev.drosh.core.DroshPalette

/**
 * Design tokens — the Drosh palette. The single source of truth: `:app`,
 * `:ui` and any future `:agent` surfaces all read these, so a value only
 * ever changes here.
 *
 * Rebuilt 2026-09-29 from measurements, because the previous set read as
 * muddy grey rather than as a designed dark theme. Two things were wrong,
 * and neither was a matter of taste:
 *
 * 1. **The surfaces were tinted, and inconsistently so.** They ran
 *    `#252A30` (cool), `#272A2E` (neutral) and `#343A43` (cool) against a
 *    `#14171B` base. Mixed hue under a low-chroma palette is what makes
 *    dark UIs read as dirty: the eye cannot settle on a single colour
 *    temperature. Every neutral here is now truly neutral (R=G=B), the way
 *    the surfaces of large shipping Android apps are built.
 *
 * 2. **Two of the surface tokens were the same lightness.** `SurfaceVariant`
 *    and `SurfaceHigh` both sat at L=16.7, so cards and overlays that were
 *    meant to read as separate layers did not separate at all. The ladder
 *    below steps evenly: 14, 26, 36, 46, 58, and the outline sits at 58 so
 *    borders read as hairlines without a separate token drifting from it.
 *
 * Accent and semantic colours are Drosh's own. Structure follows what
 * measured well in comparable apps: a single saturated blue, a green and a
 * red that stay distinguishable from it and from each other.
 *
 * Drosh is dark-only in v1.0.
 *
 * The values live in [dev.drosh.core.DroshPalette] as plain ARGB ints so
 * that `:terminal`, which renders through plain Views and has no Compose
 * dependency, can read the same numbers. This file only wraps them.
 */
private fun C(value: Long): Color = Color(DroshPalette.argb(value))

val DroshBackground: Color = C(DroshPalette.BACKGROUND)
val DroshSurface: Color = C(DroshPalette.SURFACE)
val DroshSurfaceVariant: Color = C(DroshPalette.SURFACE_VARIANT)
val DroshSurfaceLow: Color = C(DroshPalette.SURFACE_LOW)
val DroshSurfaceHigh: Color = C(DroshPalette.SURFACE_HIGH)
val DroshSurfaceContainerLowest: Color = C(DroshPalette.SURFACE_CONTAINER_LOWEST)

val DroshOutline: Color = C(DroshPalette.OUTLINE)
val DroshBorderSubtle: Color = C(DroshPalette.OUTLINE)

val DroshPrimary: Color = C(DroshPalette.PRIMARY)
val DroshOnPrimary: Color = C(DroshPalette.ON_PRIMARY)

/**
 * Near-white rather than pure white: pure #FFFFFF on a dark field blooms
 * on OLED panels, and the small difference is invisible on the surface but
 * obvious in the halation.
 */
val DroshText: Color = C(DroshPalette.TEXT)
val DroshTextSecondary: Color = C(DroshPalette.TEXT_SECONDARY)
val DroshTextMuted: Color = C(DroshPalette.TEXT_MUTED)
val DroshTextDisabled: Color = C(DroshPalette.TEXT_DISABLED)

val DroshSuccess: Color = C(DroshPalette.SUCCESS)
val DroshError: Color = C(DroshPalette.ERROR)
val DroshWarning: Color = C(DroshPalette.WARNING)
val DroshBuild: Color = C(DroshPalette.BUILD)
