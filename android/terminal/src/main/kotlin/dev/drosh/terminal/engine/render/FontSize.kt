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
        /**
         * Measured sizes, by pixel.
         *
         * A pinch asks for a new size every frame, and every ask was a JNI
         * call into the font stack — four round trips through the shaper per
         * frame while two fingers moved. Sizes repeat constantly during a
         * pinch (the gesture wanders back and forth over the same tenths), so
         * a handful of entries answer nearly all of it.
         */
        private val cache = HashMap<Int, FontSize?>(32)

        fun measure(px: Float): FontSize? {
            if (px <= 0f) {
                return null
            }
            // Keyed by the raw bits rounded to a tenth of a pixel: finer than
            // any size a pinch can intend, coarser than float noise.
            val key = (px * 10f).toInt()
            return cache.getOrPut(key) {
                val metrics = CellMetrics.fromCore(px) ?: return@getOrPut null
                FontSize(px, metrics)
            }
        }
    }
}
