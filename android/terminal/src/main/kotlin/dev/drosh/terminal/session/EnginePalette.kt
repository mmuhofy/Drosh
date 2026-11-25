package dev.drosh.terminal.session

import dev.drosh.domain.settings.ThemePalette

/**
 * The theme, as the engine's palette.
 *
 * This used to be a private member of the session manager, where nothing could
 * reach it — including a test. It is a pure function of the theme and one flag,
 * so it lives on its own: the manager applies the colours, this decides them.
 *
 * ## Why the whole 256
 *
 * The engine resolves a program's colour number through a table it holds. The
 * first sixteen are the theme's ANSI slots; the rest are the standard 6x6x6
 * cube and the grey ramp, which is what every terminal with a sixteen-colour
 * theme fills them with. A program asking for colour 208 gets a colour rather
 * than whatever the library happened to have.
 *
 * ## Bold-brightening, without a bold-brightening flag
 *
 * The engine resolves a bold cell to the bright half of the palette itself, and
 * the C ABI has no switch for it. The way to say "bold is not brighter" is to
 * give the bright slots the same colours as the normal ones — the table then
 * agrees with the setting, and the renderer never has to un-brighten a cell.
 */
internal fun buildEnginePalette(palette: ThemePalette, boldBrightens: Boolean): IntArray {
    val out = IntArray(256)
    fun put(index: Int, hex: String) {
        out[index] = parseHexColor(hex) ?: 0
    }
    put(0, palette.black)
    put(1, palette.red)
    put(2, palette.green)
    put(3, palette.yellow)
    put(4, palette.blue)
    put(5, palette.magenta)
    put(6, palette.cyan)
    put(7, palette.white)
    put(8, palette.brightBlack)
    put(9, palette.brightRed)
    put(10, palette.brightGreen)
    put(11, palette.brightYellow)
    put(12, palette.brightBlue)
    put(13, palette.brightMagenta)
    put(14, palette.brightCyan)
    put(15, palette.brightWhite)

    if (!boldBrightens) {
        for (index in 0 until 8) {
            out[index + 8] = out[index]
        }
    }

    // The 6x6x6 colour cube: levels 0, 95, 135, 175, 215, 255.
    val levels = intArrayOf(0, 95, 135, 175, 215, 255)
    var index = 16
    for (r in 0 until 6) {
        for (g in 0 until 6) {
            for (b in 0 until 6) {
                out[index] = (levels[r] shl 16) or (levels[g] shl 8) or levels[b]
                index += 1
            }
        }
    }
    // The grey ramp: 8, 18, 28, ... 238.
    for (step in 0 until 24) {
        val value = 8 + step * 10
        out[232 + step] = (value shl 16) or (value shl 8) or value
    }
    return out
}

/** "#RRGGBB" as a colour, or null when it is not one. */
internal fun parseHexColor(hex: String): Int? {
    val body = hex.removePrefix("#")
    if (body.length != 6) return null
    val value = body.toIntOrNull(16) ?: return null
    return 0xFF000000.toInt() or value
}
