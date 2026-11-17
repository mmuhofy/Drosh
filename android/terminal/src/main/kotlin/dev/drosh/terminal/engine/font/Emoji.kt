package dev.drosh.terminal.engine.font

/**
 * Whether a codepoint is drawn as a colour glyph.
 *
 * A terminal's text has two kinds of glyph: the ones the monospace face draws
 * in one colour, and the ones the platform's emoji font draws in its own. The
 * engine hands the renderer a codepoint and not a presentation, so the choice
 * is made here.
 *
 * ## Why a table and not a font query
 *
 * `Paint.hasGlyph` answers "does this face have this character", not "is this
 * character an emoji": the emoji font has a glyph for `→` too, and drawing it
 * in colour would be wrong. The blocks below are the ranges where an emoji is
 * the *expected* drawing — the emoji blocks proper and the symbols Unicode
 * defaults to emoji presentation.
 *
 * A symbol in one of these ranges that the emoji font draws in one colour is
 * still drawn correctly: its colour atlas entry is just a monochrome mask.
 * The reverse — a text symbol drawn from the emoji font — is the one case this
 * can get wrong, and it is why the ranges stop at the blocks rather than
 * swallowing every pictograph.
 */
object Emoji {

    /** The ranges an emoji is the expected drawing in, inclusive. */
    private val RANGES: IntArray = intArrayOf(
        // Misc symbols, dingbats, and the arrows and shapes that default to
        // emoji presentation.
        0x2194, 0x2199,
        0x21A9, 0x21AA,
        0x231A, 0x231B,
        0x23E9, 0x23F3,
        0x23F8, 0x23FA,
        0x25AA, 0x25AB,
        0x25B6, 0x25B6,
        0x25C0, 0x25C0,
        0x25FB, 0x25FE,
        0x2600, 0x27BF,
        0x2934, 0x2935,
        0x2B05, 0x2B07,
        0x2B1B, 0x2B1C,
        0x2B50, 0x2B50,
        0x2B55, 0x2B55,
        0x3030, 0x3030,
        0x303D, 0x303D,
        0x3297, 0x3297,
        0x3299, 0x3299,
        // The emoji blocks themselves.
        0x1F000, 0x1F02F,
        0x1F0A0, 0x1F0FF,
        0x1F10D, 0x1F10F,
        0x1F12F, 0x1F12F,
        0x1F16C, 0x1F171,
        0x1F17E, 0x1F17F,
        0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A,
        0x1F1AD, 0x1F1E5,
        0x1F201, 0x1F20F,
        0x1F21A, 0x1F21A,
        0x1F22F, 0x1F22F,
        0x1F232, 0x1F23A,
        0x1F23C, 0x1F23F,
        0x1F249, 0x1F3FA,
        0x1F400, 0x1F53D,
        0x1F546, 0x1F64F,
        0x1F680, 0x1F6FF,
        0x1F774, 0x1F77F,
        0x1F7D5, 0x1F7FF,
        0x1F80C, 0x1F80F,
        0x1F848, 0x1F84F,
        0x1F85A, 0x1F85F,
        0x1F888, 0x1F88F,
        0x1F8AE, 0x1F8FF,
        0x1F90C, 0x1F93A,
        0x1F93C, 0x1F945,
        0x1F947, 0x1FAFF,
        0x1FC00, 0x1FFFD,
    )

    /**
     * Whether [codepoint] should be drawn from the colour atlas.
     *
     * Cheap enough to ask once per cell: the ranges are sorted and the search
     * is a binary one, so the cost is a handful of comparisons.
     */
    fun isEmoji(codepoint: Int): Boolean {
        var low = 0
        var high = RANGES.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = RANGES[middle * 2]
            val end = RANGES[middle * 2 + 1]
            when {
                codepoint < start -> high = middle - 1
                codepoint > end -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}