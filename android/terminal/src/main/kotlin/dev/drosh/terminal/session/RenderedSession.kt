package dev.drosh.terminal.session

/**
 * A session a renderer can draw.
 *
 * ## Why the interface exists
 *
 * A pane is a view and a session is what runs. The pane shows *a* session at a
 * time, and the same session can be shown by different panes over its life —
 * which is the whole point of a session grid: every session stays alive, and
 * any of them can be brought to the front.
 *
 * The renderer therefore needs three things and no more: the grid to read, the
 * process to write to, and the cell it is drawn at. That is this interface, and
 * it is deliberately narrower than a session — a renderer has no business
 * knowing that a session can be stopped, renamed or closed.
 */
interface RenderedSession {

    /** The engine's grid. The renderer reads frames from it. */
    val terminalHandle: Long

    /** The process behind the grid. The renderer writes keys to it. */
    val ptyHandle: Long

    /** The cell, in pixels. */
    val cellWidth: Float

    /** The line box, in pixels. */
    val cellHeight: Float

    /** The font's own size, in pixels — what the glyphs are rasterised at. */
    val fontPx: Float

    /**
     * The last lines the session printed, oldest first.
     *
     * Read from the grid's own cells, so a preview is the same text the
     * renderer draws — a thumbnail that disagreed with the screen would be
     * worse than no thumbnail.
     */
    fun transcript(): List<String>
}
