package dev.drosh.terminal.engine.input

/**
 * A cell selection: a linear (row-major) range from an anchor to the cell the
 * finger is on.
 *
 * A rectangular/block mode is not part of the prototype — a phone drags along
 * lines, and the linear range is what every terminal on a touch screen does.
 *
 * `contains` is called once per cell of a rebuilt row, so it does no
 * normalisation and allocates nothing: [extend] keeps the fields ordered
 * whenever the anchor moves.
 */
internal class Selection {

    private var anchorRow = -1
    private var anchorColumn = 0

    /** Top-left cell of the range; `firstRow < 0` means nothing is selected. */
    var firstRow = -1
        private set
    var firstColumn = 0
        private set

    /** Bottom-right cell of the range. */
    var lastRow = -1
        private set
    var lastColumn = 0
        private set

    /** Set whenever the range changed, so the renderer knows what to redraw. */
    private var dirty = false

    val isEmpty: Boolean get() = firstRow < 0

    /** The rows the range covers, or an empty range when nothing is selected. */
    val rows: IntRange get() = if (isEmpty) IntRange.EMPTY else firstRow..lastRow

    /** Start a selection with `row`/`column` as its anchor. */
    fun begin(row: Int, column: Int) {
        anchorRow = row
        anchorColumn = column
        set(row, column, row, column)
    }

    /** Drag the selection to `row`/`column`. */
    fun extend(row: Int, column: Int) {
        if (anchorRow < 0) {
            begin(row, column)
            return
        }
        set(anchorRow, anchorColumn, row, column)
    }

    /** Select a whole range at once (word and line selection). */
    fun select(startRow: Int, startColumn: Int, endRow: Int, endColumn: Int) {
        anchorRow = startRow
        anchorColumn = startColumn
        set(startRow, startColumn, endRow, endColumn)
    }

    fun clear() {
        if (!isEmpty) {
            dirty = true
        }
        anchorRow = -1
        firstRow = -1
        firstColumn = 0
        lastRow = -1
        lastColumn = 0
    }

    /** True once after the range changed. */
    fun consumeDirty(): Boolean {
        val was = dirty
        dirty = false
        return was
    }

    fun contains(row: Int, column: Int): Boolean {
        if (firstRow < 0 || row < firstRow || row > lastRow) {
            return false
        }
        if (row == firstRow && column < firstColumn) {
            return false
        }
        if (row == lastRow && column > lastColumn) {
            return false
        }
        return true
    }

    private fun set(startRow: Int, startColumn: Int, endRow: Int, endColumn: Int) {
        val startBeforeEnd = startRow < endRow || (startRow == endRow && startColumn <= endColumn)
        if (startBeforeEnd) {
            firstRow = startRow
            firstColumn = startColumn
            lastRow = endRow
            lastColumn = endColumn
        } else {
            firstRow = endRow
            firstColumn = endColumn
            lastRow = startRow
            lastColumn = startColumn
        }
        dirty = true
    }

    companion object {
        /**
         * Characters a word never contains — alacritty's own set
         * (`alacritty_terminal/src/term/mod.rs`, `SEMANTIC_ESCAPE_CHARS`), so a
         * double tap here breaks words exactly like a desktop terminal.
         */
        const val ESCAPE_CHARS = ",│`|:\"' ()[]{}<>\t"

        /**
         * The word around `row`/`column`, given a way to read a cell.
         *
         * Returns the range to select: the contiguous run of non-separator
         * characters around the cell, or the single cell when it *is* a
         * separator or blank.
         */
        fun wordRange(
            row: Int,
            column: Int,
            columns: Int,
            charAt: (row: Int, column: Int) -> Char,
        ): IntArray {
            val start = charAt(row, column)
            if (start == ' ' || ESCAPE_CHARS.indexOf(start) >= 0) {
                return intArrayOf(row, column, row, column)
            }

            var first = column
            while (first > 0 && !isBoundary(charAt(row, first - 1))) {
                first -= 1
            }

            var last = column
            while (last + 1 < columns && !isBoundary(charAt(row, last + 1))) {
                last += 1
            }

            return intArrayOf(row, first, row, last)
        }

        fun isBoundary(character: Char): Boolean =
            character == ' ' || ESCAPE_CHARS.indexOf(character) >= 0
    }
}
