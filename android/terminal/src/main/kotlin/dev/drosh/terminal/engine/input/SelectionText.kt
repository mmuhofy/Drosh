package dev.drosh.terminal.engine.input

/**
 * The clipboard's view of a selection.
 *
 * ## What the engine already does
 *
 * Ghostty formats a selection as plain text: it walks the grid, skips empty
 * cells, drops wide-character spacer cells and never emits SGR or other escape
 * metadata. Its formatter is also asked to unwrap soft-wrapped lines and to
 * trim trailing whitespace, so the raw string this object sees is already the
 * characters the user selected.
 *
 * ## What this adds
 *
 * Two guarantees the engine's formatter cannot make on its own:
 *
 * - **No escape sequences, ever.** A terminal cell can legitimately hold an
 *   `ESC` when a program prints malformed output, and a copy must not carry a
 *   control sequence into a paste or a shared message. The stripper is a last
 *   line of defence, not a second terminal parser.
 * - **No trailing padding.** A row is a fixed number of cells wide; a copied
 *   line should end at its last visible character. Blank lines *inside* the
 *   selection are meaningful and stay; only the blank lines after the last
 *   character are dropped.
 *
 * Everything else is preserved exactly: interior spaces between words,
 * leading indentation, Unicode, combining marks and wide characters.
 */
internal object SelectionText {

    /** The text a clipboard should receive for [raw]. */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""

        val text = stripAnsi(raw)
        val lines = text.split('\n')
        var last = lines.lastIndex
        // Trailing blank rows are the part of the screen below the selection's
        // last line, not part of the selection. Leading and interior blanks
        // are the user's content and are left alone.
        while (last >= 0 && lines[last].trimEnd().isEmpty()) {
            last -= 1
        }
        if (last < 0) return ""

        val builder = StringBuilder(text.length)
        for (index in 0..last) {
            if (index > 0) builder.append('\n')
            builder.append(lines[index].trimEnd())
        }
        return builder.toString()
    }

    /**
     * Remove terminal escape sequences from [text].
     *
     * Handles CSI (`ESC [` … final byte), OSC (`ESC ]` … `BEL` or `ESC \`) and
     * the two-character escapes such as `ESC ( B`. A lone `ESC` at the end of
     * the string is dropped: it cannot be completed and is not printable text.
     */
    fun stripAnsi(text: String): String {
        if (text.indexOf(ESC) < 0) return text

        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character != ESC) {
                builder.append(character)
                index += 1
                continue
            }

            // An ESC starts a sequence; skip it and whatever introduces the
            // sequence, then its parameters.
            index += 1
            if (index >= text.length) break
            when (text[index]) {
                '[' -> {
                    index += 1
                    while (index < text.length) {
                        val code = text[index].code
                        index += 1
                        // A CSI's final byte is 0x40..0x7e; intermediate bytes
                        // are below that, parameters below '0'.
                        if (code in 0x40..0x7e) break
                    }
                }
                ']' -> {
                    index += 1
                    while (index < text.length) {
                        if (text[index] == BEL) {
                            index += 1
                            break
                        }
                        if (text[index] == ESC &&
                            index + 1 < text.length &&
                            text[index + 1] == '\\'
                        ) {
                            index += 2
                            break
                        }
                        index += 1
                    }
                }
                else -> {
                    // Two-character escapes (single shifts) stop there;
                    // charset designations such as ESC ( B carry one more
                    // selector byte, which is not text either.
                    val introducer = text[index]
                    index += 1
                    if (introducer in "()*+-./") {
                        index += 1
                    }
                }
            }
        }
        return builder.toString()
    }

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'
}
