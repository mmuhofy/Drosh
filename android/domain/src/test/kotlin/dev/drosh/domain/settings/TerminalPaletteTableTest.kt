package dev.drosh.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette as the engine's table.
 *
 * The table is the engine's wire format (`bridge/cell.rs` encodes a colour as
 * the index into this same table), so its layout is alacritty's, index for
 * index. These tests pin the layout, not just the arithmetic: a table that is
 * one slot out would make the engine draw the wrong colour, and nothing would
 * say so.
 */
class TerminalPaletteTableTest {

    @Test
    fun `the table holds every colour the engine names`() {
        val table = TerminalPaletteTable.of(ThemePalette())
        assertEquals(TerminalPaletteTable.SIZE, table.size)
    }

    @Test
    fun `the named colours are the theme's own`() {
        val palette = ThemePalette(
            background = "#101010",
            foreground = "#f0f0f0",
            cursor = "#ff00ff",
            black = "#010101",
            red = "#020202",
            green = "#030303",
            yellow = "#040404",
            blue = "#050505",
            magenta = "#060606",
            cyan = "#070707",
            white = "#080808",
            brightBlack = "#090909",
            brightRed = "#0a0a0a",
            brightGreen = "#0b0b0b",
            brightYellow = "#0c0c0c",
            brightBlue = "#0d0d0d",
            brightMagenta = "#0e0e0e",
            brightCyan = "#0f0f0f",
            brightWhite = "#101010",
        )
        val table = TerminalPaletteTable.of(palette)

        assertEquals(0x010101, table[TerminalPaletteTable.BLACK])
        assertEquals(0x020202, table[TerminalPaletteTable.RED])
        assertEquals(0x0a0a0a, table[TerminalPaletteTable.BRIGHT_RED])
        assertEquals(0xf0f0f0, table[TerminalPaletteTable.FOREGROUND])
        assertEquals(0x101010, table[TerminalPaletteTable.BACKGROUND])
        assertEquals(0xff00ff, table[TerminalPaletteTable.CURSOR])
    }

    @Test
    fun `the dim colours are the same hue, scaled down`() {
        val table = TerminalPaletteTable.of(ThemePalette(red = "#ff0000"))
        // alacritty's factor: 0.66. 255 * 0.66 = 168.
        assertEquals(0xa80000, table[TerminalPaletteTable.DIM_RED])
    }

    @Test
    fun `the colour cube is alacritty's`() {
        val table = TerminalPaletteTable.of(ThemePalette())
        // alacritty's own steps: 0, then 55 + 40 * level.
        assertEquals(0x000000, table[16])
        assertEquals(0x00005f, table[17])
        // Index 52 is r1 g0 b0: 95, 0, 0.
        assertEquals(0x5f0000, table[16 + 36])
    }

    @Test
    fun `the grey ramp is alacritty's`() {
        val table = TerminalPaletteTable.of(ThemePalette())
        assertEquals(0x080808, table[232])
        assertEquals(0xeeeeee, table[255])
    }

    @Test
    fun `a colour that is not a hex triple costs the colour, not the terminal`() {
        val table = TerminalPaletteTable.of(ThemePalette(red = "not a colour"))
        assertEquals(0, table[TerminalPaletteTable.RED])
    }

    @Test
    fun `the bright foreground is the bright white`() {
        val table = TerminalPaletteTable.of(ThemePalette(brightWhite = "#fefefe"))
        assertEquals(0xfefefe, table[TerminalPaletteTable.BRIGHT_FOREGROUND])
    }

    @Test
    fun `the table has a colour at every index the engine can name`() {
        val table = TerminalPaletteTable.of(ThemePalette())
        // A zero is a legitimate colour, so the check is on the *named* slots
        // being reachable rather than on any slot being non-zero.
        val named = listOf(
            TerminalPaletteTable.BLACK,
            TerminalPaletteTable.WHITE,
            TerminalPaletteTable.BRIGHT_WHITE,
            TerminalPaletteTable.FOREGROUND,
            TerminalPaletteTable.BACKGROUND,
            TerminalPaletteTable.CURSOR,
            TerminalPaletteTable.DIM_WHITE,
            TerminalPaletteTable.DIM_FOREGROUND,
        )
        assertTrue(named.all { it in table.indices })
    }
}
