package dev.drosh.terminal.engine.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gate for the input encoder: the sequences are pinned here so a change to
 * [Encoder] cannot quietly alter what the guest receives.
 *
 * Expected values are the ones read out of alacritty (`config/bindings.rs` for
 * the APP_CURSOR, Shift+Tab and Alt forms; `input/keyboard.rs` for the
 * `~`-terminated keys and the named control characters) and from the xterm
 * modifier convention (`CSI 1;5D` = Ctrl+Left, alacritty's own test bindings).
 */
class EncoderTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun plainKeysSendTheControlCharactersXtermSends() {
        assertArrayEquals(byteArrayOf(0x09), Encoder.sequence(Encoder.Named.Tab))
        assertArrayEquals(byteArrayOf(0x0d), Encoder.sequence(Encoder.Named.Enter))
        assertArrayEquals(byteArrayOf(0x1b), Encoder.sequence(Encoder.Named.Escape))
        assertArrayEquals(byteArrayOf(0x7f), Encoder.sequence(Encoder.Named.Backspace))
    }

    @Test
    fun cursorKeysAreCsiNormallyAndSs3InApplicationMode() {
        assertArrayEquals(bytes("\u001b[A"), Encoder.sequence(Encoder.Named.Up))
        assertArrayEquals(bytes("\u001b[B"), Encoder.sequence(Encoder.Named.Down))
        assertArrayEquals(bytes("\u001b[C"), Encoder.sequence(Encoder.Named.Right))
        assertArrayEquals(bytes("\u001b[D"), Encoder.sequence(Encoder.Named.Left))
        assertArrayEquals(bytes("\u001b[H"), Encoder.sequence(Encoder.Named.Home))
        assertArrayEquals(bytes("\u001b[F"), Encoder.sequence(Encoder.Named.End))

        assertArrayEquals(bytes("\u001bOA"), Encoder.sequence(Encoder.Named.Up, appCursor = true))
        assertArrayEquals(bytes("\u001bOD"), Encoder.sequence(Encoder.Named.Left, appCursor = true))
        assertArrayEquals(bytes("\u001bOH"), Encoder.sequence(Encoder.Named.Home, appCursor = true))
        assertArrayEquals(bytes("\u001bOF"), Encoder.sequence(Encoder.Named.End, appCursor = true))
    }

    @Test
    fun modifiersUseTheXtermParameter() {
        val ctrl = Encoder.Mods(ctrl = true)
        val shift = Encoder.Mods(shift = true)
        val alt = Encoder.Mods(alt = true)

        // 1 + shift + 2*alt + 4*ctrl: Ctrl is 5, Shift is 2, Alt is 3.
        assertArrayEquals(bytes("\u001b[1;5D"), Encoder.sequence(Encoder.Named.Left, ctrl))
        assertArrayEquals(bytes("\u001b[1;2A"), Encoder.sequence(Encoder.Named.Up, shift))
        assertArrayEquals(bytes("\u001b[1;3C"), Encoder.sequence(Encoder.Named.Right, alt))
        // Modifiers win over application mode: the parameterised form is always CSI.
        assertArrayEquals(
            bytes("\u001b[1;5A"),
            Encoder.sequence(Encoder.Named.Up, ctrl, appCursor = true),
        )
    }

    @Test
    fun tildeKeysMatchAlacritty() {
        assertArrayEquals(bytes("\u001b[2~"), Encoder.sequence(Encoder.Named.Insert))
        assertArrayEquals(bytes("\u001b[3~"), Encoder.sequence(Encoder.Named.Delete))
        assertArrayEquals(bytes("\u001b[5~"), Encoder.sequence(Encoder.Named.PageUp))
        assertArrayEquals(bytes("\u001b[6~"), Encoder.sequence(Encoder.Named.PageDown))
        assertArrayEquals(bytes("\u001b[15~"), Encoder.sequence(Encoder.Named.F5))
        assertArrayEquals(bytes("\u001b[17~"), Encoder.sequence(Encoder.Named.F6))
        assertArrayEquals(bytes("\u001b[21~"), Encoder.sequence(Encoder.Named.F10))
        assertArrayEquals(bytes("\u001b[24~"), Encoder.sequence(Encoder.Named.F12))

        assertArrayEquals(
            bytes("\u001b[5;5~"),
            Encoder.sequence(Encoder.Named.PageUp, Encoder.Mods(ctrl = true)),
        )
    }

    @Test
    fun functionKeysOneToFourAreSs3() {
        assertArrayEquals(bytes("\u001bOP"), Encoder.sequence(Encoder.Named.F1))
        assertArrayEquals(bytes("\u001bOQ"), Encoder.sequence(Encoder.Named.F2))
        assertArrayEquals(bytes("\u001bOR"), Encoder.sequence(Encoder.Named.F3))
        assertArrayEquals(bytes("\u001bOS"), Encoder.sequence(Encoder.Named.F4))
    }

    @Test
    fun shiftTabIsCbtAndAltPrefixesEsc() {
        assertArrayEquals(bytes("\u001b[Z"), Encoder.sequence(Encoder.Named.Tab, Encoder.Mods(shift = true)))
        // bindings.rs: Tab+Shift+Alt is "ESC ESC [ Z".
        assertArrayEquals(
            bytes("\u001b\u001b[Z"),
            Encoder.sequence(Encoder.Named.Tab, Encoder.Mods(shift = true, alt = true)),
        )
        // bindings.rs: Alt+Backspace is "ESC DEL".
        assertArrayEquals(
            byteArrayOf(0x1b, 0x7f),
            Encoder.sequence(Encoder.Named.Backspace, Encoder.Mods(alt = true)),
        )
    }

    @Test
    fun controlCodesAreTheC0Mapping() {
        assertEquals(0x01, Encoder.controlCode('a'))
        assertEquals(0x01, Encoder.controlCode('A'))
        assertEquals(0x03, Encoder.controlCode('c'))
        assertEquals(0x04, Encoder.controlCode('d'))
        assertEquals(0x1a, Encoder.controlCode('z'))
        assertEquals(0x00, Encoder.controlCode(' '))
        assertEquals(0x00, Encoder.controlCode('@'))
        assertEquals(0x1b, Encoder.controlCode('['))
        assertEquals(0x1c, Encoder.controlCode('\\'))
        assertEquals(0x1f, Encoder.controlCode('_'))
        assertNull(Encoder.controlCode('1'))
        assertNull(Encoder.controlCode('~'))
    }

    @Test
    fun typedTextCarriesCtrlOnItsFirstCharacter() {
        assertArrayEquals(byteArrayOf(0x03), Encoder.text("c", Encoder.Mods(ctrl = true)))
        assertArrayEquals(byteArrayOf(0x08), Encoder.text("h", Encoder.Mods(ctrl = true)))
        assertArrayEquals(byteArrayOf(0x00), Encoder.text(" ", Encoder.Mods(ctrl = true)))

        // A digit has no control code: the text goes through as typed.
        assertArrayEquals("1".toByteArray(Charsets.UTF_8), Encoder.text("1", Encoder.Mods(ctrl = true)))

        // Alt prefixes ESC, Ctrl turns the first character only.
        assertArrayEquals(
            byteArrayOf(0x1b, 0x03, 'b'.code.toByte()),
            Encoder.text("cb", Encoder.Mods(ctrl = true, alt = true)),
        )
    }

    @Test
    fun utf8TextPassesThroughUnchanged() {
        assertArrayEquals("é".toByteArray(Charsets.UTF_8), Encoder.text("é"))
        assertArrayEquals("İstanbul".toByteArray(Charsets.UTF_8), Encoder.text("İstanbul"))
    }
}
