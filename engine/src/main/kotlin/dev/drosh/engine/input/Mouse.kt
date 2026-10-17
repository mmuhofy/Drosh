package dev.drosh.engine.input

import dev.drosh.engine.core.NativeCore

/**
 * Mouse reports, xterm style.
 *
 * Every rule in here is alacritty's (`alacritty/src/input/mod.rs`, v0.17.0 —
 * `mouse_report`, `sgr_mouse_report`, `normal_mouse_report`, `mouse_moved`):
 *
 * - SGR (`?1006`) is `CSI < button ; column+1 ; line+1 M|m`, release is the
 *   same button with a lower-case `m`;
 * - the legacy form is `ESC [ M` followed by `32 + button` and the two
 *   coordinates, release is button 3, and `?1005`'s UTF-8 extension encodes a
 *   coordinate above 94 as two bytes;
 * - motion uses 32 (left), 33 (middle), 34 (right) or 35 (no button), and the
 *   wheel is 64..67;
 * - modifiers are added to the button: shift 4, alt 8, ctrl 16.
 *
 * Which events are reported at all is the engine's decision: `?1000` is
 * press/release, `?1002` adds drags, `?1003` adds bare motion. That gating lives
 * in the caller, because it depends on the terminal mode bits.
 */
internal object Mouse {

    const val BUTTON_LEFT = 0
    const val BUTTON_MIDDLE = 1
    const val BUTTON_RIGHT = 2

    const val WHEEL_UP = 64
    const val WHEEL_DOWN = 65
    const val WHEEL_LEFT = 66
    const val WHEEL_RIGHT = 67

    /** Motion report codes, as alacritty sends them. */
    const val MOTION_LEFT = 32
    const val MOTION_MIDDLE = 33
    const val MOTION_RIGHT = 34
    const val MOTION_NONE = 35

    private const val ESC = 0x1b

    /** Is any mouse-tracking mode on? (`TermMode::MOUSE_MODE`) */
    fun active(modes: Int): Boolean = modes and NativeCore.MODE_MOUSE_MODE != 0

    /**
     * One button report; `pressed = false` is a release.
     *
     * Returns `null` when the position cannot be encoded in the legacy form
     * (alacritty drops those reports instead of truncating them).
     */
    fun report(
        button: Int,
        pressed: Boolean,
        column: Int,
        line: Int,
        modes: Int,
        mods: Encoder.Mods = Encoder.Mods(),
    ): ByteArray? {
        val bits = modifierBits(mods)
        return if (modes and NativeCore.MODE_SGR_MOUSE != 0) {
            sgr(button + bits, pressed, column, line)
        } else {
            normal(if (pressed) button + bits else 3 + bits, column, line, modes)
        }
    }

    private fun sgr(button: Int, pressed: Boolean, column: Int, line: Int): ByteArray {
        val final = if (pressed) 'M' else 'm'
        return "\u001b[<$button;${column + 1};${line + 1}$final".toByteArray(Charsets.US_ASCII)
    }

    private fun normal(button: Int, column: Int, line: Int, modes: Int): ByteArray? {
        val utf8 = modes and NativeCore.MODE_UTF8_MOUSE != 0
        val max = if (utf8) 2015 else 223
        if (column >= max || line >= max) {
            return null
        }

        val out = java.io.ByteArrayOutputStream(6)
        out.write(ESC)
        out.write('['.code)
        out.write('M'.code)
        out.write(32 + button)

        if (utf8 && column >= 95) {
            writeUtf8Position(out, column)
        } else {
            out.write(32 + 1 + column)
        }

        if (utf8 && line >= 95) {
            writeUtf8Position(out, line)
        } else {
            out.write(32 + 1 + line)
        }

        return out.toByteArray()
    }

    /**
     * The `?1005` coordinate form: `0xC0 + value / 64`, `0x80 + value % 64`.
     * The `32 + 1` bias is the same as in the single-byte form.
     */
    private fun writeUtf8Position(out: java.io.ByteArrayOutputStream, position: Int) {
        val value = 32 + 1 + position
        out.write(0xC0 + value / 64)
        out.write(0x80 + (value and 63))
    }

    /** xterm's mouse modifier bits — not the keyboard's `1 + …` parameter. */
    private fun modifierBits(mods: Encoder.Mods): Int =
        (if (mods.shift) 4 else 0) + (if (mods.alt) 8 else 0) + (if (mods.ctrl) 16 else 0)
}
