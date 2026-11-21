package dev.drosh.terminal.engine.font

/**
 * Splits a row of codepoints into shaping runs.
 *
 * Inspired by: github.com/ghostty-org/ghostty — `src/font/shaper/run.zig`
 * (`RunIterator.next`).
 * Adapted for Drosh — dev.drosh.
 *
 * ## Why runs and not the whole row
 *
 * Shaping a whole row at once would let a ligature join two cells that the
 * grid has to keep separate, and it would let a style change in the middle of
 * the row be drawn with the style of one end. A run is the maximal span of a
 * row that can be shaped together: one font, one style, one presentation, and
 * no break the terminal has asked for.
 *
 * ## The rules, in the order Ghostty applies them
 *
 * 1. Trim the empty cells on the right.
 * 2. Skip invisible cells.
 * 3. Break when the style changes — except the background colour, which is
 *    painted per cell anyway.
 * 4. Break the `f`+`l`/`i` and `s`+`t` ligatures on purpose: a terminal's
 *    grid is one cell per codepoint, and a ligature that joins two of them
 *    draws a glyph in two cells' worth of space.
 * 5. Break when the presentation changes — text versus emoji.
 * 6. Break at the cursor.
 *
 * The rules that need the engine's grapheme table (ZWJ sequences, combining
 * marks) are not applied here: the frame carries one codepoint per cell, and
 * a grapheme that spans cells is handled by the atlas as a single mask.
 */
object RunSplitter {

    /** A run: a span of one row that is shaped together. */
    data class Run(
        /** Where the run starts, in cells. */
        val start: Int,
        /** Where the run ends, exclusive, in cells. */
        val end: Int,
        /** The codepoints, in order. */
        val codepoints: IntArray,
    ) {
        val length: Int get() = end - start
        val text: String get() = String(codepoints.map { Character.toChars(it) }.flatten().toCharArray())
    }

    /**
     * Split [codepoints] into runs.
     *
     * @param codepoints the row's cells, left to right. Zero is an empty cell.
     * @param styles the style id of each cell, or null when every cell shares
     *   one style. The id is whatever the caller uses to mean "same style".
     * @param cursor the column the cursor sits on, or -1 when there is none.
     */
    fun split(codepoints: IntArray, styles: IntArray? = null, cursor: Int = -1): List<Run> {
        if (codepoints.isEmpty()) return emptyList()

        // Trim the empty cells on the right.
        var max = codepoints.size
        while (max > 0 && codepoints[max - 1] == 0) max -= 1
        if (max == 0) return emptyList()

        val runs = mutableListOf<Run>()
        var i = 0
        while (i < max) {
            // Skip invisible cells.
            while (i < max && isInvisible(codepoints[i])) i += 1
            if (i >= max) break

            val start = i
            val style = styles?.getOrNull(i)
            val presentation = presentationOf(codepoints[i])
            val codepointsAcc = mutableListOf<Int>()

            while (i < max) {
                if (isInvisible(codepoints[i])) break
                if (isSpacer(codepoints[i])) { i += 1; continue }

                // The cursor breaks the run around it.
                if (cursor >= 0 && i == cursor) break
                if (cursor >= 0 && i == cursor + 1 && start < cursor) break

                // Style change.
                if (styles != null && styles.getOrNull(i) != style) break

                // Presentation change: text versus emoji.
                if (presentationOf(codepoints[i]) != presentation) break

                // The bad-ligature guard: a terminal's grid is one cell per
                // codepoint, and these two pairs would join them.
                if (i > start && isBadLigature(codepoints[i - 1], codepoints[i])) break

                val cp = if (codepoints[i] == 0) ' '.code else codepoints[i]
                codepointsAcc.add(cp)
                i += 1
            }

            if (codepointsAcc.isNotEmpty()) {
                runs.add(
                    Run(
                        start = start,
                        end = i,
                        codepoints = codepointsAcc.toIntArray(),
                    ),
                )
            }
        }
        return runs
    }

    /** Whether a codepoint is invisible and carries no glyph. */
    private fun isInvisible(codepoint: Int): Boolean =
        codepoint == 0 || codepoint == ' '.code

    /** Whether a codepoint is a wide-character spacer. */
    private fun isSpacer(codepoint: Int): Boolean =
        codepoint == 0xE000 // placeholder; the engine's width=0 cells are the real spacers

    /** The presentation a codepoint is drawn with. */
    private fun presentationOf(codepoint: Int): Int =
        if (Emoji.isEmoji(codepoint)) 1 else 0

    /** The two pairs Ghostty breaks on purpose. */
    private fun isBadLigature(previous: Int, current: Int): Boolean {
        if (previous == 'f'.code && (current == 'l'.code || current == 'i'.code)) return true
        if (previous == 's'.code && current == 't'.code) return true
        return false
    }
}
