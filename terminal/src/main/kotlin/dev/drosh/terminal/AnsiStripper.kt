package dev.drosh.terminal

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
 *
 * [SIMPLE_ESC_REGEX] covers ESC plus one byte. The original range was
 * `@-_` (0x40-0x5F) — only the final-byte range — which misses the parameter
 * bytes a few real sequences use on their own: ESC = (DECKPAM), ESC > (DECKPNM),
 * ESC 7 (DECSC), ESC 8 (DECRC). That left `ESC =` visible in block output, which
 * is what AnsiStripperTest caught. Space, `[` and `\` stay excluded on purpose
 * so a malformed sequence cannot have its introducer eaten and leave the
 * remainder behind as text.
 */
object AnsiStripper {

    private val CSI_REGEX = Regex("\\x1b\\[[0-9;?]*[ -/]*[@-~]")
    private val OSC_REGEX = Regex("\\x1b\\][^\\x07\\x1b]*(?:\u0007|\u001b\\\\)")
    private val SIMPLE_ESC_REGEX = Regex("\\x1b[0-9=><@-_]")

    fun strip(input: String): String {
        if (input.isEmpty()) return input
        return input
            .replace(OSC_REGEX, "")
            .replace(CSI_REGEX, "")
            .replace(SIMPLE_ESC_REGEX, "")
    }
}
