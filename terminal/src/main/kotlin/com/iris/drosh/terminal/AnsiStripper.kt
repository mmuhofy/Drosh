package com.iris.irisshell.terminal

/**
 * Strips ANSI/VT escape sequences from a terminal buffer transcript.
 *
 * Handles the common cases that show up in interactive shell output:
 *   - CSI sequences:   `ESC [ … letter`       (colors, cursor moves)
 *   - OSC sequences:   `ESC ] … BEL` or ST    (title, hyperlink)
 *   - Single-char ESC: `ESC c`, `ESC =`, etc.
 *
 * Used by [BlockEngineWire] before sending output text to the
 * `BlockRepository` — block UI renders plain text only.
 *
 * UNTESTED — verify before use.
 */
object AnsiStripper {

    private val CSI_REGEX = Regex("\\x1b\\[[0-9;?]*[ -/]*[@-~]")
    private val OSC_REGEX = Regex("\\x1b\\][^\\x07\\x1b]*(?:\u0007|\u001b\\\\)")
    private val SIMPLE_ESC_REGEX = Regex("\\x1b[@-_]")

    fun strip(input: String): String {
        if (input.isEmpty()) return input
        return input
            .replace(OSC_REGEX, "")
            .replace(CSI_REGEX, "")
            .replace(SIMPLE_ESC_REGEX, "")
    }
}
