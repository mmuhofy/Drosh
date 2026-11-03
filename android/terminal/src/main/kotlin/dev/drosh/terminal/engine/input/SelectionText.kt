package dev.drosh.terminal.engine.input

import dev.drosh.terminal.engine.core.NativeCore
import java.nio.ByteBuffer

/**
 * Turns a [Selection] into the text to put on the clipboard.
 *
 * The cells are read straight out of the buffer the engine writes (D5: Kotlin
 * owns it, Rust fills it), which is why this needs nothing from JNI. One line
 * per selected row, trailing spaces trimmed — what a terminal's copy does.
 */
internal object SelectionText {

    /** Cap for a clipboard write; a terminal grid is not a text editor. */
    const val MAX_CHARS = 262_144

    fun extract(cells: ByteBuffer, columns: Int, selection: Selection): String {
        if (selection.isEmpty || columns <= 0) {
            return ""
        }

        // The buffer is the session's, and the selection is the view's — and
        // after a resize the two disagree for a frame: the selection still
        // names rows and columns of the grid that was there before. Reading a
        // cell past the end is an `IndexOutOfBoundsException` in the middle of
        // a recomposition, which is the crash a large font produced.
        //
        // The buffer is the authority: a selection clipped to what it actually
        // holds is a selection of the visible cells, which is what the user can
        // see anyway.
        val available = cells.limit() / NativeCore.CELL_BYTES
        if (available <= 0) {
            return ""
        }
        val rows = available / columns
        if (rows <= 0) {
            return ""
        }

        val builder = StringBuilder()
        for (row in selection.rows) {
            if (row >= rows) {
                break
            }
            val first = if (row == selection.firstRow) selection.firstColumn else 0
            val last = if (row == selection.lastRow) selection.lastColumn else columns - 1
            if (row != selection.firstRow) {
                builder.append('\n')
            }

            val line = StringBuilder()
            for (column in first..last) {
                if (builder.length + line.length >= MAX_CHARS) {
                    break
                }

                val at = (row * columns + column) * NativeCore.CELL_BYTES
                if (at < 0 || at + NativeCore.CELL_BYTES > cells.limit()) {
                    continue
                }
                val flags = cells.getShort(at + NativeCore.CELL_OFFSET_FLAGS).toInt() and 0xffff
                // A wide character's second cell is a spacer: it has no
                // character of its own, so copying it would duplicate.
                if (flags and NativeCore.FLAG_WIDE_CHAR_SPACER != 0) {
                    continue
                }

                val codepoint = cells.getInt(at + NativeCore.CELL_OFFSET_CODEPOINT)
                if (codepoint != 0) {
                    line.appendCodePoint(codepoint)
                }
            }

            builder.append(line.trimEnd())
        }

        return builder.toString()
    }
}
