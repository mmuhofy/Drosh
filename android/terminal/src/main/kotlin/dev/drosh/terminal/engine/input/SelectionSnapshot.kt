package dev.drosh.terminal.engine.input

import android.graphics.Rect

/**
 * A cell in the terminal's viewport.
 *
 * `row` is the row the renderer draws, not a row of the scrollback: a grid
 * reference that has scrolled away has no viewport cell, and is represented by
 * a negative row.
 */
internal data class SelectionCell(val row: Int, val column: Int) {

    /** Whether the cell is on the screen the user is looking at. */
    val isVisible: Boolean get() = row >= 0 && column >= 0
}

/**
 * Which viewport edge, if any, a selection drag is close enough to scroll.
 *
 * Kept apart from the view so the rule can be tested without a phone: the band
 * is about a thumb, and the two ends are symmetric. `-1` is the top edge
 * (older output), `+1` the bottom (towards the live edge), and `0` is no edge.
 */
internal object SelectionEdges {

    const val NONE = 0
    const val TOP = -1
    const val BOTTOM = 1

    fun at(y: Float, height: Float, band: Float): Int = when {
        y < band -> TOP
        y > height - band -> BOTTOM
        else -> NONE
    }
}

/** The selection's box in view pixels, without an Android type in the way. */
internal data class SelectionBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * A selection, as the chrome draws it.
 *
 * ## Why a snapshot rather than the endpoints
 *
 * The engine's selection is two grid references in the scrollback. They are
 * the truth for text and for the highlight — the engine draws the selected
 * cells itself — but they say nothing about pixels. The chrome needs rows and
 * columns of the *viewport*, and it needs them resolved at the moment it
 * draws: after a scroll or a resize a grid reference points somewhere else on
 * the screen.
 *
 * So the native side resolves the selection into this shape on demand:
 *
 * - [firstRow]/[lastRow]/[firstColumn]/[lastColumn] are the visible bounding
 *   box. A selection that runs into the scrollback keeps a box for the part
 *   that is still on screen, which is what the menu hangs on;
 * - [topLeft] and [bottomRight] are the selection's ordered endpoints, which
 *   is what the two grips sit on. Either may be off screen, in which case its
 *   row is negative and no grip is drawn for it.
 *
 * Both endpoints being off screen is normal — Select All on a long transcript
 * selects the whole scrollback — and it still has a visible box.
 */
internal class SelectionSnapshot(
    val firstRow: Int,
    val firstColumn: Int,
    val lastRow: Int,
    val lastColumn: Int,
    val topLeft: SelectionCell,
    val bottomRight: SelectionCell,
) {

    /**
     * Whether any selected cell is on the screen.
     *
     * The columns are deliberately not compared: in a wrapped selection the
     * last row usually ends left of where the first row began, and
     * `lastColumn >= firstColumn` would then reject a perfectly visible
     * paragraph.
     */
    val hasVisibleCells: Boolean
        get() = firstRow >= 0 && lastRow >= firstRow && lastColumn >= 0

    /** The top grip's cell, or null when that endpoint has scrolled away. */
    val topLeftHandle: SelectionCell?
        get() = topLeft.takeIf { it.isVisible }

    /** The bottom grip's cell, or null when that endpoint has scrolled away. */
    val bottomRightHandle: SelectionCell?
        get() = bottomRight.takeIf { it.isVisible }

    /**
     * The cell a selection action applies to.
     *
     * Word and line selection need a cell to grow from. The moving end is the
     * user's last intent, so it is the right anchor when it is visible; a
     * selection made entirely by Select All has no endpoint on screen, and the
     * caller's fallback is used.
     */
    fun actionAnchor(fallback: SelectionCell? = null): SelectionCell? =
        bottomRight.takeIf { it.isVisible }
            ?: topLeft.takeIf { it.isVisible }
            ?: fallback

    /**
     * The box's rectangle in the view's own pixels.
     *
     * The picture can hang a fraction of a row above the engine's window while
     * a finger is down, and [scrollOffsetPx] is that shift. It moves the grid
     * *down* by that much, so the box moves down with it: the shift is added,
     * not subtracted.
     *
     * Subtracted — which is what this did — the box walked the wrong way the
     * moment a drag began, one row per row of travel: the menu and the two
     * grips slid off the cells they belong to as soon as the output was
     * scrolled, and came back when it stopped.
     */
    fun box(cellWidth: Float, cellHeight: Float, scrollOffsetPx: Float): SelectionBox? {
        if (!hasVisibleCells) return null
        val width = cellWidth.coerceAtLeast(1f)
        val height = cellHeight.coerceAtLeast(1f)
        val top = firstRow * height + scrollOffsetPx
        return SelectionBox(
            left = (firstColumn * width).toInt(),
            top = top.toInt(),
            right = ((lastColumn + 1) * width).toInt(),
            bottom = (top + (lastRow - firstRow + 1) * height).toInt(),
        )
    }

    /** The box as the Android rectangle the chrome and the view use. */
    fun bounds(cellWidth: Float, cellHeight: Float, scrollOffsetPx: Float): Rect? {
        val box = box(cellWidth, cellHeight, scrollOffsetPx) ?: return null
        return Rect(box.left, box.top, box.right, box.bottom)
    }

    companion object {

        /** Values the native side writes: two box corners and two endpoints. */
        const val VALUE_COUNT = 8

        /**
         * Read the engine's eight-value answer, or null when nothing is
         * selected or no selected cell is on screen.
         */
        fun from(values: IntArray?): SelectionSnapshot? {
            if (values == null || values.size < VALUE_COUNT) return null
            val snapshot = SelectionSnapshot(
                firstRow = values[0],
                firstColumn = values[1],
                lastRow = values[2],
                lastColumn = values[3],
                topLeft = SelectionCell(values[4], values[5]),
                bottomRight = SelectionCell(values[6], values[7]),
            )
            return snapshot.takeIf { it.hasVisibleCells }
        }
    }
}
