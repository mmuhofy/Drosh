package dev.drosh.terminal.engine.native

/**
 * Selection, as the engine's API expresses it.
 *
 * ## Why the engine answers
 *
 * Word and line boundaries depend on the semantic escape characters the engine
 * was configured with and on the cell widths it computed. A second
 * implementation would disagree with what the renderer highlights, and the
 * disagreement shows as a selection that copies one word and highlights two.
 *
 * ## Snapshot, not state
 *
 * The engine's selection is a snapshot the caller owns, not a "current
 * selection" on the terminal. Each call here takes a position and returns the
 * text for that selection, which is why there is no "extend" — a drag re-asks
 * with a new range rather than growing a selection in place.
 */
object NativeSelection {

    /** The word under a viewport cell, or "". */
    fun selectWord(handle: Long, row: Int, column: Int): String {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return ""
        return nativeSelectWord(handle, row, column)
    }

    /** The line under a viewport cell, or "". */
    fun selectLine(handle: Long, row: Int, column: Int): String {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return ""
        return nativeSelectLine(handle, row, column)
    }

    /** Everything the engine holds, or "". */
    fun selectAll(handle: Long): String {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return ""
        return nativeSelectAll(handle)
    }

    /**
     * The selection gesture: a press, a drag, a release.
     *
     * The gesture is the engine's own, so it counts the clicks: the first press
     * selects a cell, the second a word, the third a line, and a drag extends
     * whatever the press selected. The selection it makes is the terminal's own,
     * which is what puts the selected cells in the frame.
     */
    fun press(handle: Long, row: Int, column: Int, behavior: Int): IntArray? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return nativeSelectionPress(handle, row, column, behavior)
    }

    /** A drag, with the geometry the engine reads the cells with. */
    fun drag(
        handle: Long,
        row: Int,
        column: Int,
        columns: Int,
        cellWidth: Int,
        screenHeight: Int,
    ): IntArray? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return nativeSelectionDrag(
            handle, row, column, columns, cellWidth, screenHeight,
        )
    }

    /** The end of a gesture. */
    fun release(handle: Long, row: Int, column: Int, behavior: Int): IntArray? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return nativeSelectionRelease(handle, row, column, behavior)
    }

    /** The text of the terminal's current selection, or "". */
    fun text(handle: Long): String {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return ""
        return nativeSelectionText(handle) ?: ""
    }

    /**
     * The word between two points, for a drag.
     *
     * The engine's API for exactly this: the nearest word between where the
     * finger went down and where it is now.
     */
    fun selectWordBetween(
        handle: Long,
        row1: Int,
        column1: Int,
        row2: Int,
        column2: Int,
    ): String {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return ""
        return nativeSelectWordBetween(handle, row1, column1, row2, column2) ?: ""
    }

    private external fun nativeSelectWordBetween(
        handle: Long,
        row1: Int,
        column1: Int,
        row2: Int,
        column2: Int,
    ): String?

    /**
     * What a click selects.
     *
     * The engine counts the clicks and has a table of what each count selects;
     * the host sets the table, so the host decides what the user's gesture
     * meant.
     */
    const val BEHAVIOR_CELL = 0
    const val BEHAVIOR_WORD = 1
    const val BEHAVIOR_LINE = 2

    private external fun nativeSelectWord(handle: Long, row: Int, column: Int): String
    private external fun nativeSelectLine(handle: Long, row: Int, column: Int): String
    private external fun nativeSelectAll(handle: Long): String
    private external fun nativeSelectionPress(
        handle: Long,
        row: Int,
        column: Int,
        behavior: Int,
    ): IntArray?
    private external fun nativeSelectionDrag(
        handle: Long,
        row: Int,
        column: Int,
        columns: Int,
        cellWidth: Int,
        screenHeight: Int,
    ): IntArray?
    private external fun nativeSelectionRelease(
        handle: Long,
        row: Int,
        column: Int,
        behavior: Int,
    ): IntArray?
    private external fun nativeSelectionText(handle: Long): String?
}
