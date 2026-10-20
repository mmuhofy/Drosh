package dev.drosh.terminal.engine.input

import dev.drosh.terminal.engine.core.NativeCore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the mouse encoder. Expected values are read out of alacritty
 * (`alacritty/src/input/mod.rs`, v0.17.0): `sgr_mouse_report`,
 * `normal_mouse_report` and the 32..35 / 64..67 button codes.
 */
class MouseTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.US_ASCII)

    private val click = NativeCore.MODE_MOUSE_REPORT_CLICK

    @Test
    fun sgrReportsUseTheButtonParameterAndMForRelease() {
        assertArrayEquals(
            bytes("\u001b[<0;5;3M"),
            Mouse.report(Mouse.BUTTON_LEFT, true, column = 4, line = 2, modes = click or NativeCore.MODE_SGR_MOUSE),
        )
        assertArrayEquals(
            bytes("\u001b[<0;5;3m"),
            Mouse.report(Mouse.BUTTON_LEFT, false, column = 4, line = 2, modes = click or NativeCore.MODE_SGR_MOUSE),
        )
    }

    @Test
    fun legacyReportsAreEscMWithBiasedBytesAndButtonThreeOnRelease() {
        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x4d, 32, 33, 33),
            Mouse.report(Mouse.BUTTON_LEFT, true, column = 0, line = 0, modes = click),
        )
        assertArrayEquals(
            // 32 + 3: xterm's "no button" release.
            byteArrayOf(0x1b, 0x5b, 0x4d, 35, 33, 33),
            Mouse.report(Mouse.BUTTON_LEFT, false, column = 0, line = 0, modes = click),
        )
    }

    @Test
    fun modifiersAreAddedToTheButtonNotTheKeyboardParameter() {
        // shift 4 + ctrl 16 = 20; the keyboard would have said 1 + 1 + 4 = 6.
        assertArrayEquals(
            bytes("\u001b[<20;1;1M"),
            Mouse.report(
                Mouse.BUTTON_LEFT,
                true,
                column = 0,
                line = 0,
                modes = click or NativeCore.MODE_SGR_MOUSE,
                mods = Encoder.Mods(ctrl = true, shift = true),
            ),
        )
    }

    @Test
    fun wheelAndMotionUseTheCodesAlacrittyUses() {
        assertArrayEquals(
            bytes("\u001b[<64;1;1M"),
            Mouse.report(Mouse.WHEEL_UP, true, column = 0, line = 0, modes = click or NativeCore.MODE_SGR_MOUSE),
        )
        assertArrayEquals(
            bytes("\u001b[<35;3;3M"),
            Mouse.report(Mouse.MOTION_NONE, true, column = 2, line = 2, modes = click or NativeCore.MODE_SGR_MOUSE),
        )
    }

    @Test
    fun utf8CoordinatesUseTwoBytesAbove94() {
        // 32 + 1 + 100 = 133 -> 0xC0 + 2, 0x80 + 5.
        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x4d, 32, 0xc2.toByte(), 0x85.toByte(), 33),
            Mouse.report(
                Mouse.BUTTON_LEFT,
                true,
                column = 100,
                line = 0,
                modes = click or NativeCore.MODE_UTF8_MOUSE,
            ),
        )
    }

    @Test
    fun positionsBeyondTheEncodingAreDroppedNotTruncated() {
        assertNull(Mouse.report(Mouse.BUTTON_LEFT, true, column = 223, line = 0, modes = click))
        assertNull(
            Mouse.report(
                Mouse.BUTTON_LEFT,
                true,
                column = 2015,
                line = 0,
                modes = click or NativeCore.MODE_UTF8_MOUSE,
            ),
        )
        assertNotNull(
            Mouse.report(
                Mouse.BUTTON_LEFT,
                true,
                column = 2014,
                line = 0,
                modes = click or NativeCore.MODE_UTF8_MOUSE,
            ),
        )
    }

    @Test
    fun trackingIsAnyOfClickDragOrMotion() {
        assertTrue(Mouse.active(NativeCore.MODE_MOUSE_REPORT_CLICK))
        assertTrue(Mouse.active(NativeCore.MODE_MOUSE_DRAG))
        assertTrue(Mouse.active(NativeCore.MODE_MOUSE_MOTION))
        assertFalse(Mouse.active(NativeCore.MODE_SGR_MOUSE))
        assertFalse(Mouse.active(NativeCore.MODE_FOCUS_IN_OUT))
    }
}
