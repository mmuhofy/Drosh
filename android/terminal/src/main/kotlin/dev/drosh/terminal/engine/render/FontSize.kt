package dev.drosh.terminal.engine.render

/**
 * The terminal's text at one size: the pixel size the glyphs are rasterised
 * at, and the cell geometry measured at that size.
 *
 * The two belong together — a cell size without the size it was measured at
 * cannot be rasterised, and a pixel size without the geometry cannot lay a
 * grid out — so they travel as one value from the measurement to the frame
 * that draws it, and from the frame that draws it back to the session.
 */
internal data class FontSize(
    /** The size the glyphs are rasterised at, in pixels. */
    val px: Float,
    /** The cell geometry at [px]. */
    val metrics: CellMetrics,
) {
    companion object {
        /**
         * Measure the cell at `px`.
         *
         * `null` when the font stack cannot be measured at that size — the
         * caller then keeps the size it had, because a terminal with no cell
         * geometry cannot lay a grid out.
         */
        fun measure(px: Float): FontSize? {
            if (px <= 0f) {
                return null
            }
            val metrics = CellMetrics.fromCore(px) ?: return null
            return FontSize(px, metrics)
        }
    }
}
