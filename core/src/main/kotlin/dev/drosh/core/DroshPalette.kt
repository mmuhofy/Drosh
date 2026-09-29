package dev.drosh.core

/**
 * Drosh's colour palette as plain ARGB integers.
 *
 * The values themselves live in `:design-system` as Compose `Color`s, which
 * is what every Compose surface uses. They cannot be read from `:terminal`
 * though: that module renders through plain Android Views and has no Compose
 * dependency, and pulling Compose in to reach a constant would be the wrong
 * trade. So the numbers are declared here as the one place both sides can
 * read, and `:design-system` wraps them.
 *
 * If you change a value, change it here. `DroshColors.kt` mirrors it.
 *
 * Surfaces are deliberately neutral, R = G = B. A palette that tints some
 * steps and not others reads as dirty rather than designed — the eye cannot
 * settle on one colour temperature. Steps: 8, 14, 21, 26, 36, 46, 58.
 */
object DroshPalette {

    // Surfaces, darkest to lightest.
    const val SURFACE_CONTAINER_LOWEST: Long = 0xFF080808
    const val BACKGROUND: Long = 0xFF0E0E0E
    const val SURFACE_LOW: Long = 0xFF151515
    const val SURFACE: Long = 0xFF1A1A1A
    const val SURFACE_VARIANT: Long = 0xFF242424
    const val SURFACE_HIGH: Long = 0xFF2E2E2E
    const val OUTLINE: Long = 0xFF3A3A3A

    // Accent.
    const val PRIMARY: Long = 0xFF4C9EFF
    const val ON_PRIMARY: Long = 0xFF0E0E0E

    // Text. Primary is near-white, not pure white: pure #FFFFFF blooms on
    // OLED panels and the difference is obvious as halation.
    const val TEXT: Long = 0xFFF2F2F2
    const val TEXT_SECONDARY: Long = 0xFFB4B4B4
    const val TEXT_MUTED: Long = 0xFF7A7A7A
    const val TEXT_DISABLED: Long = 0xFF4D4D4D

    // Semantic.
    const val SUCCESS: Long = 0xFF3DD68C
    const val ERROR: Long = 0xFFF2555A
    const val WARNING: Long = 0xFFF0B429
    const val BUILD: Long = 0xFF4C9EFF

    /** [value] narrowed to the Int that `Paint.color` and friends want. */
    fun argb(value: Long): Int = value.toInt()
}
