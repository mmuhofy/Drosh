package dev.drosh.terminal.engine.render

/**
 * What one frame did, as numbers.
 *
 * The terminal is drawn by a Rust engine through a GL surface, and every
 * symptom of it going wrong — a black pane, a row of missing colour, text that
 * stops updating — looks the same from outside: an empty rectangle. This is the
 * frame saying what it did.
 *
 * A public value of its own rather than a nested type: the host draws it and
 * the renderer is `internal` to this module, so the numbers have to cross the
 * boundary without the renderer crossing with them.
 */
data class FrameReport(
    val frames: Int,
    val frameMillis: Float,
    val buildMillis: Float,
    val drawMillis: Float,
    val columns: Int,
    val rows: Int,
    val glyphs: Int,
    val coloredCells: Int,
    val atlasPercent: Int,
    val damage: Int,
    val background: Int,
    val gl: String,
    /** The first few coloured backgrounds, as `raw→#hex`. */
    val samples: List<String> = emptyList(),
    /**
     * The live pinch scale, 1 when there is none.
     *
     * The one number that says whether a gesture is being drawn at all: a
     * pinch that reports 1.0 on every frame is a pinch whose scale never
     * reached the renderer.
     */
    val pinchScale: Float = 1f,
    /** The cell step actually drawn, in pixels. */
    val cellStepX: Float = 0f,
    val cellStepY: Float = 0f,
    /** The surface the frame was drawn on. */
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
)
