package dev.drosh.engine.input

import dev.drosh.engine.core.NativeCore
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
        if (selection.isEmpty) {
            return ""
        }

        val builder = StringBuilder()
        for (row in selection.rows) {
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
