package dev.drosh.terminal.session

import dev.drosh.domain.settings.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The theme as the engine's table.
 *
 * Worth a test because it is a table a person reads colours through: the ANSI
 * slots are the theme's, the cube and the ramp are the standard ones, and the
 * one flag the app owns — bold-brightening — is expressed by making two halves
 * of the table equal.
 */
class EnginePaletteTest {

    private val palette = ThemePalette(
        background = "#101010",
        foreground = "#E0E0E0",
        black = "#000000",
        red = "#AA0000",
        green = "#00AA00",
        yellow = "#AA5500",
        blue = "#0000AA",
        magenta = "#AA00AA",
        cyan = "#00AAAA",
        white = "#AAAAAA",
        brightBlack = "#555555",
        brightRed = "#FF5555",
        brightGreen = "#55FF55",
        brightYellow = "#FFFF55",
        brightBlue = "#5555FF",
        brightMagenta = "#FF55FF",
        brightCyan = "#55FFFF",
        brightWhite = "#FFFFFF",
    )

    @Test
    fun `ansi slots are the theme's own colours`() {
        val table = buildEnginePalette(palette, boldBrightens = true)

        assertEquals(0xFF000000.toInt(), table[0])
        assertEquals(0xFFAA0000.toInt(), table[1])
        assertEquals(0xFFAAAAAA.toInt(), table[7])
        assertEquals(0xFFFF5555.toInt(), table[9])
        assertEquals(0xFFFFFFFF.toInt(), table[15])
    }

    @Test
    fun `the cube starts at 16 and the ramp at 232`() {
        val table = buildEnginePalette(palette, boldBrightens = true)

        // Black, and opaque: the cube and the ramp are written as ARGB, and a
        // colour without its alpha byte is a transparent one.
        assertEquals(0xFF000000.toInt(), table[16])
        // r=0, g=0, b=5 is the cube's own blue, at 16 + 5.
        assertEquals(0xFF0000FF.toInt(), table[21])
        // r=g=b=5 is white, the cube's last entry.
        assertEquals(0xFFFFFFFF.toInt(), table[231])
        assertEquals(0xFF080808.toInt(), table[232])
        assertEquals(0xFFEEEEEE.toInt(), table[255])
    }

    @Test
    fun `bold-brightening off makes the bright half equal the normal half`() {
        val off = buildEnginePalette(palette, boldBrightens = false)

        for (index in 0 until 8) {
            assertEquals(off[index], off[index + 8])
        }
    }

    @Test
    fun `bold-brightening on keeps the theme's bright colours`() {
        val on = buildEnginePalette(palette, boldBrightens = true)

        assertNotEquals(on[8], on[0])
        assertNotEquals(on[9], on[1])
    }

    @Test
    fun `a colour that is not a colour is black rather than a crash`() {
        assertEquals(0, buildEnginePalette(palette.copy(red = "nope"), true)[1])
    }
}
