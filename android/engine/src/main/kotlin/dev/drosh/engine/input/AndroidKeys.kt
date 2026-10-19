package dev.drosh.engine.input

import android.view.KeyEvent

/**
 * Android key codes → [Encoder]. Kept separate and thin: everything worth
 * testing is in the encoder, this only names the keys.
 */
internal object AndroidKeys {

    /** The key as the encoder knows it, or `null` for keys we do not send. */
    fun named(keyCode: Int): Encoder.Named? = when (keyCode) {
        KeyEvent.KEYCODE_TAB -> Encoder.Named.Tab
        KeyEvent.KEYCODE_ESCAPE -> Encoder.Named.Escape
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Encoder.Named.Enter
        KeyEvent.KEYCODE_DEL -> Encoder.Named.Backspace
        KeyEvent.KEYCODE_DPAD_UP -> Encoder.Named.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> Encoder.Named.Down
        KeyEvent.KEYCODE_DPAD_RIGHT -> Encoder.Named.Right
        KeyEvent.KEYCODE_DPAD_LEFT -> Encoder.Named.Left
        KeyEvent.KEYCODE_MOVE_HOME -> Encoder.Named.Home
        KeyEvent.KEYCODE_MOVE_END -> Encoder.Named.End
        KeyEvent.KEYCODE_PAGE_UP -> Encoder.Named.PageUp
        KeyEvent.KEYCODE_PAGE_DOWN -> Encoder.Named.PageDown
        KeyEvent.KEYCODE_INSERT -> Encoder.Named.Insert
        KeyEvent.KEYCODE_FORWARD_DEL -> Encoder.Named.Delete
        KeyEvent.KEYCODE_F1 -> Encoder.Named.F1
        KeyEvent.KEYCODE_F2 -> Encoder.Named.F2
        KeyEvent.KEYCODE_F3 -> Encoder.Named.F3
        KeyEvent.KEYCODE_F4 -> Encoder.Named.F4
        KeyEvent.KEYCODE_F5 -> Encoder.Named.F5
        KeyEvent.KEYCODE_F6 -> Encoder.Named.F6
        KeyEvent.KEYCODE_F7 -> Encoder.Named.F7
        KeyEvent.KEYCODE_F8 -> Encoder.Named.F8
        KeyEvent.KEYCODE_F9 -> Encoder.Named.F9
        KeyEvent.KEYCODE_F10 -> Encoder.Named.F10
        KeyEvent.KEYCODE_F11 -> Encoder.Named.F11
        KeyEvent.KEYCODE_F12 -> Encoder.Named.F12
        else -> null
    }

    /**
     * The modifiers of a key event, with the extra-keys row's sticky Ctrl added
     * in (a phone has no Ctrl key of its own).
     */
    fun mods(event: KeyEvent, stickyCtrl: Boolean): Encoder.Mods = Encoder.Mods(
        ctrl = stickyCtrl || event.isCtrlPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
    )
}
