package dev.drosh.terminal.engine.view

import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.input.Selection
import dev.drosh.terminal.engine.input.SelectionText

/**
 * Owns the [Selection] state and the operations that change it: word/line pick,
 * drag-extend, clear, and reading the selected text back. Keeping it apart from
 * the view lets the gestures and the controller share one source of truth, and
 * the renderer reads [selection] to draw the highlight.
 */
internal class SelectionOverlay(private val view: TerminalView) {

    val selection = Selection()

    val isEmpty: Boolean get() = selection.isEmpty

    /** Clear the selection (tap in empty space, new session). */
    fun clear() {
        selection.clear()
        view.notifySelectionChanged()
    }

    /** Pick the word around (row, col) — double tap / long press. */
    fun selectWord(row: Int, col: Int) {
        val cells = view.session.cells ?: return
        val columns = view.session.columns
        if (columns <= 0 || view.session.rows <= 0) {
            return
        }

        val range = Selection.wordRange(row, col, columns) { r, c ->
            val at = (r * columns + c) * NativeCore.CELL_BYTES
            cells.getInt(at + NativeCore.CELL_OFFSET_CODEPOINT).toChar()
        }

        selection.select(range[0], range[1], range[2], range[3])
        view.notifySelectionChanged()
    }

    /** Pick the whole line containing (row, col). */
    fun selectLine(row: Int, col: Int) {
        val columns = view.session.columns
        if (columns <= 0 || view.session.rows <= 0) {
            return
        }
        selection.select(row, 0, row, columns - 1)
        view.notifySelectionChanged()
    }

    /** Select the entire visible grid. */
    fun selectAll() {
        val columns = view.session.columns
        val rows = view.session.rows
        if (columns <= 0 || rows <= 0) {
            return
        }
        selection.select(0, 0, rows - 1, columns - 1)
        view.notifySelectionChanged()
    }

    /** Drag the selection to (row, col). */
    fun extend(row: Int, col: Int) {
        selection.extend(row, col)
        view.requestRender()
    }

    /** The selected text, or "" when nothing is selected. */
    fun copyText(): String {
        val cells = view.session.cells ?: return ""
        if (selection.isEmpty) {
            return ""
        }
        return SelectionText.extract(cells, view.session.columns, selection)
    }
}
