package dev.drosh.terminal.engine.font

/**
 * Which adjacent cells may be shaped as one run.
 *
 * A terminal is a grid of cells, so shaping has to stop somewhere: a ligature
 * that joins two cells is drawn as one glyph across both, and a ligature that
 * joins a cell with the one after it is exactly what the font's `calt` table
 * is for. Shaping the whole row would also join unrelated words through
 * kerning and would make the cost proportional to the row on every frame.
 *
 * The policy is deliberately narrow: a run must have at least two printable
 * cells and must contain a pair from the programming-ligature alphabet. That
 * leaves prose, plain words and paths on the per-cell path, where a monospace
 * font belongs, and sends `!=`, `=>`, `->`, `::`, `&&` and friends through the
 * shaper.
 *
 * Inspired by: github.com/ghostty-org/ghostty — `src/font/shaper/run.zig`.
 * Adapted for Drosh — dev.drosh.
 */
internal object LigaturePolicy {

    /** The longest run shaped in one mask; a longer one falls back to cells. */
    const val MAX_CELLS = 32

    /**
     * Characters that participate in programming ligatures.
     *
     * The set is intentionally the operators, not every punctuation mark:
     * `;`, `,` and quotes never ligate in the programming fonts this app ships,
     * and including them would make far more rows take the shaped path.
     */
    private const val TRIGGERS = "=<>!|&+-*/%^~.:?"

    /**
     * Whether [text] is worth sending through the shaper.
     *
     * A candidate is two to [MAX_CELLS] printable characters with at least one
     * adjacent trigger pair. The gutter function is pure so the renderer can
     * ask it once per row without allocating anything.
     */
    fun canShape(codepoints: IntArray): Boolean {
        if (codepoints.size < 2 || codepoints.size > MAX_CELLS) return false
        for (index in 0 until codepoints.size - 1) {
            if (isTrigger(codepoints[index]) && isTrigger(codepoints[index + 1])) {
                return true
            }
        }
        return false
    }

    /** Whether one codepoint is part of the operator alphabet. */
    fun isTrigger(codepoint: Int): Boolean =
        codepoint in 0x21..0x7e && TRIGGERS.indexOf(codepoint.toChar()) >= 0
}
