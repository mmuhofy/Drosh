package dev.drosh.domain.settings

/**
 * The palette as the engine draws it: one table of 269 colours.
 *
 * ## Why a table and not a palette object
 *
 * A theme carries twenty-two colours — the sixteen ANSI slots, the three that
 * sit outside them, and the pair selection uses. The engine needs all 269,
 * because the terminal can name any of them: the cube (16..232), the grey ramp
 * (232..256), the foreground, the background, the cursor, and the dim variants.
 *
 * alacritty solves this the same way: its config carries the named colours and
 * its *display* carries the resolved table (`alacritty/src/display/color.rs` —
 * `impl From<&Colors> for List` fills the named slots, the cube and the ramp).
 * The table is what a renderer reads, and the renderer reads it per cell, so it
 * is the shape the engine wants.
 *
 * The layout is alacritty's, index for index, because the engine's wire format
 * is alacritty's (`bridge/cell.rs` encodes a colour as the index into this
 * same table). A different layout would mean the cell records and the table
 * disagree.
 *
 * | Indices  | Meaning                                   |
 * | -------- | ----------------------------------------- |
 * | 0..16    | The named ANSI colours                    |
 * | 16..232  | The colour cube                           |
 * | 233..256 | The grey ramp                             |
 * | 256      | The foreground                            |
 * | 257      | The background                            |
 * | 258      | The cursor                                |
 * | 259..267 | The dim colours                           |
 * | 268      | The dim foreground                        |
 */
object TerminalPaletteTable {

    /** How many colours the engine's table holds. */
    const val SIZE = 269

    /** The named ANSI colours, 0..16. */
    const val BLACK = 0
    const val RED = 1
    const val GREEN = 2
    const val YELLOW = 3
    const val BLUE = 4
    const val MAGENTA = 5
    const val CYAN = 6
    const val WHITE = 7
    const val BRIGHT_BLACK = 8
    const val BRIGHT_RED = 9
    const val BRIGHT_GREEN = 10
    const val BRIGHT_YELLOW = 11
    const val BRIGHT_BLUE = 12
    const val BRIGHT_MAGENTA = 13
    const val BRIGHT_CYAN = 14
    const val BRIGHT_WHITE = 15

    /** The foreground, the background and the cursor. */
    const val FOREGROUND = 256
    const val BACKGROUND = 257
    const val CURSOR = 258

    /** The dim variants, 259..267. */
    const val DIM_BLACK = 259
    const val DIM_RED = 260
    const val DIM_GREEN = 261
    const val DIM_YELLOW = 262
    const val DIM_BLUE = 263
    const val DIM_MAGENTA = 264
    const val DIM_CYAN = 265
    const val DIM_WHITE = 266
    const val BRIGHT_FOREGROUND = 267
    const val DIM_FOREGROUND = 268

    /**
     * The factor a dim colour is scaled by.
     *
     * alacritty's own (`alacritty/src/display/color.rs` — `DIM_FACTOR`).
     */
    private const val DIM_FACTOR = 0.66f

    /**
     * The palette as the engine's table.
     *
     * The named slots carry the theme's own colours; the cube and the ramp are
     * computed the way alacritty computes them, so a colour named by index
     * looks the same in both.
     */
    fun of(palette: ThemePalette): IntArray {
        val table = IntArray(SIZE)

        table[BLACK] = palette.black.toRgb()
        table[RED] = palette.red.toRgb()
        table[GREEN] = palette.green.toRgb()
        table[YELLOW] = palette.yellow.toRgb()
        table[BLUE] = palette.blue.toRgb()
        table[MAGENTA] = palette.magenta.toRgb()
        table[CYAN] = palette.cyan.toRgb()
        table[WHITE] = palette.white.toRgb()
        table[BRIGHT_BLACK] = palette.brightBlack.toRgb()
        table[BRIGHT_RED] = palette.brightRed.toRgb()
        table[BRIGHT_GREEN] = palette.brightGreen.toRgb()
        table[BRIGHT_YELLOW] = palette.brightYellow.toRgb()
        table[BRIGHT_BLUE] = palette.brightBlue.toRgb()
        table[BRIGHT_MAGENTA] = palette.brightMagenta.toRgb()
        table[BRIGHT_CYAN] = palette.brightCyan.toRgb()
        table[BRIGHT_WHITE] = palette.brightWhite.toRgb()

        table[FOREGROUND] = palette.foreground.toRgb()
        table[BACKGROUND] = palette.background.toRgb()
        table[CURSOR] = palette.cursor.toRgb()

        // The dim variants, alacritty's way: the same hue, scaled down.
        table[DIM_BLACK] = dim(table[BLACK])
        table[DIM_RED] = dim(table[RED])
        table[DIM_GREEN] = dim(table[GREEN])
        table[DIM_YELLOW] = dim(table[YELLOW])
        table[DIM_BLUE] = dim(table[BLUE])
        table[DIM_MAGENTA] = dim(table[MAGENTA])
        table[DIM_CYAN] = dim(table[CYAN])
        table[DIM_WHITE] = dim(table[WHITE])
        table[BRIGHT_FOREGROUND] = table[BRIGHT_WHITE]
        table[DIM_FOREGROUND] = dim(table[FOREGROUND])

        fillCube(table)
        fillGreyRamp(table)

        return table
    }

    /**
     * The colour cube, 16..232.
     *
     * alacritty's own algorithm (`alacritty/src/display/color.rs` —
     * `fill_cube`): six levels per channel, the first darker than the rest.
     */
    private fun fillCube(table: IntArray) {
        for (index in 16 until 232) {
            val offset = index - 16
            table[index] = rgb(
                level(offset / 36),
                level((offset % 36) / 6),
                level(offset % 6),
            )
        }
    }

    /**
     * The grey ramp, 232..256.
     *
     * alacritty's own (`fill_gray_ramp`): 24 steps from 8 to 238.
     */
    private fun fillGreyRamp(table: IntArray) {
        for (index in 232 until 256) {
            val value = (index - 232) * 10 + 8
            table[index] = rgb(value, value, value)
        }
    }

    private fun level(value: Int): Int = if (value == 0) 0 else value * 40 + 55

    private fun dim(color: Int): Int = rgb(
        ((color shr 16 and 0xff) * DIM_FACTOR).toInt(),
        ((color shr 8 and 0xff) * DIM_FACTOR).toInt(),
        ((color and 0xff) * DIM_FACTOR).toInt(),
    )

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    /**
     * A `#rrggbb` colour as `0xrrggbb`.
     *
     * A colour that is not a hex triple is black rather than an exception: a
     * theme file is written by hand and by a shell, and a broken colour should
     * cost the colour, not the terminal.
     */
    private fun String.toRgb(): Int {
        val text = trim()
        if (text.length != 7 || !text.startsWith("#")) return 0
        val value = text.substring(1).toIntOrNull(16) ?: return 0
        return value and 0xffffff
    }
}
