package dev.drosh.terminal.engine.core


/**
 * The colours of one cell of the visible grid.
 *
 * The app draws chrome over the terminal and wants it in the terminal's own
 * colours: the background a program painted, the colour of the text on it.
 * With the Termux renderer the app could read the pixels back; with the GL
 * renderer there are no pixels to read, only the grid — so the engine reports
 * the colours of the cells themselves.
 *
 * The colours arrive encoded the way the cell records encode them: a tag in
 * the top two bits ([NativeCore.COLOR_TAG_NAMED] and its neighbours) and the
 * colour itself in the rest. [foregroundRgb] and [backgroundRgb] resolve them
 * through the palette the renderer uses, so an `OSC 4/10/11/12` override shows
 * up here too.
 *
 * @property row the visible row, counted from the top of the viewport
 * @property column the visible column, counted from the left
 * @property foreground the foreground colour, encoded
 * @property background the background colour, encoded
 * @property blank the cell holds no character
 */
data class CellColor(
    val row: Int,
    val column: Int,
    val foreground: Int,
    val background: Int,
    val blank: Boolean = true,
) {
    /** The foreground colour as `0xRRGGBB`. */
    val foregroundRgb: Int get() = Palette.resolve(foreground, Palette.DEFAULT_FOREGROUND)

    /** The background colour as `0xRRGGBB`. */
    val backgroundRgb: Int get() = Palette.resolve(background, Palette.DEFAULT_BACKGROUND)

    companion object {
        /** A colour is a 32-bit value; anything outside that is not one. */
        private const val MAX_COLOR = 0xffff_ffffL

        /**
         * Parse the engine's answer: newline-separated
         * `row:column:foreground:background:blank` records.
         *
         * A record that is not in that shape is skipped rather than guessed
         * at, and an `error:` record — what the engine answers for an unknown
         * handle — is skipped too: a missing cell is better than a cell in
         * row zero.
         */
        fun parse(record: String): List<CellColor> =
            record.lineSequence()
                .filter { it.isNotEmpty() && !it.startsWith("error:") }
                .mapNotNull { line ->
                    val parts = line.split(':')
                    if (parts.size < 4) return@mapNotNull null
                    val row = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val column = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val foreground = color(parts[2]) ?: return@mapNotNull null
                    val background = color(parts[3]) ?: return@mapNotNull null
                    val blank = parts.getOrNull(4)?.toIntOrNull()?.let { it != 0 } ?: true
                    CellColor(row, column, foreground, background, blank)
                }
                .toList()

        /**
         * The 32-bit wire value of a colour, or null when it is not one.
         *
         * A colour with the "truecolour" tag is larger than a signed 32-bit
         * number, so it is read as a long and narrowed back to the bits the
         * engine sent.
         */
        private fun color(value: String): Int? {
            val number = value.toLongOrNull() ?: return null
            if (number < 0 || number > MAX_COLOR) return null
            return number.toInt()
        }
    }
}
