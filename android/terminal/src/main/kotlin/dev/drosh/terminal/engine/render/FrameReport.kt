package dev.drosh.terminal.engine.render

/**
 * What one frame did, as numbers.
 *
 * The terminal is drawn by a Rust engine through a GL surface, and every
 * symptom of it going wrong — a black pane, a row of missing colour, text that
 * stops updating — looks the same from outside: an empty rectangle. This is the
 * frame saying what it did: how long the build and the draw took, how many cells
 * carried a colour, how full the glyph atlas is.
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
    /** What the background pass was handed: instances, first record, GL error. */
    val backgroundProbe: String = "",
)
