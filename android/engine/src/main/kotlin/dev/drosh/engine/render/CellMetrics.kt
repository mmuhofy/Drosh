package dev.drosh.engine.render

import dev.drosh.engine.core.NativeCore

/**
 * Cell geometry, as the Rust font stack reports it (`NativeCore.METRIC_*`).
 *
 * The grid is laid out from these numbers, so the cell the renderer draws into
 * is exactly the cell the font was measured for — no second opinion from
 * Android's text APIs.
 */
internal class CellMetrics(private val values: FloatArray) {

    val width = values[NativeCore.METRIC_CELL_WIDTH]
    val height = values[NativeCore.METRIC_CELL_HEIGHT]

    /** Baseline to the top of the cell (positive). */
    val ascent = values[NativeCore.METRIC_ASCENT]

    /** Baseline to the bottom of the cell (positive). */
    val descent = values[NativeCore.METRIC_DESCENT]

    /** Baseline to the centre of an underline (positive). */
    val underline = values[NativeCore.METRIC_UNDERLINE]

    /** Thickness of the underline and strikeout, scaled with the font. */
    val rule = (height * 0.06f).coerceAtLeast(1f)

    companion object {
        /**
         * Ask the core for the metrics at `px`.
         *
         * Returns `null` when the font stack is not loaded or the faces cannot be
         * measured — the caller then has a packaging problem to report, not a
         * renderer to start.
         */
        fun fromCore(px: Float): CellMetrics? {
            val values = FloatArray(NativeCore.FONT_METRICS_LEN)
            if (NativeCore.nativeFontMetrics(px, values) != NativeCore.OK) {
                return null
            }

            val metrics = CellMetrics(values)
            return if (metrics.width > 0f && metrics.height > 0f) metrics else null
        }
    }
}
