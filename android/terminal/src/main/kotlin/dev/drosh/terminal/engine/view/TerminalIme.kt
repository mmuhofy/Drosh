package dev.drosh.terminal.engine.view

import android.view.KeyEvent
import dev.drosh.terminal.engine.input.AndroidKeys
import dev.drosh.terminal.engine.input.Encoder
import dev.drosh.terminal.engine.input.TerminalInputView

/**
 * Wires the keyboard to the session: the 1×1 [input] the IME talks to, and the
 * hardware keys that reach the view directly.
 *
 * ## One path
 *
 * Everything that becomes bytes goes through [TerminalInputView.onBytes]. Text
 * arrives there already decoded to codepoints, and keys arrive here as key
 * codes that [Encoder] turns into sequences. There is no second place that
 * writes to the session — which is what makes the ordering rule stateable at
 * all: text is drained before a key is handled, so a key can never overtake the
 * letters typed before it.
 *
 * The previous version had three writers (the IME's text path, the hardware-key
 * path, and the view's own `onKeyDown`), each with its own idea of when to
 * flush. That is what made "I pressed Enter and the previous letter appeared"
 * possible: the letter was still in a buffer that nobody had told to empty.
 */
internal class TerminalIme(private val view: TerminalView) {

    /** The focused text editor the IME talks to; 1×1 px so it stays out of sight. */
    val input: TerminalInputView = TerminalInputView(view.context).apply {
        onBytes = { bytes -> view.session.write(bytes) }
        onKey = { keyCode, event -> handleHardwareKey(keyCode, event) }
        stickyCtrl = { view.stickyCtrl }
        stickyAlt = { view.stickyAlt }
        appCursor = { view.appCursor() }
        onActivity = { view.onInputActivity() }
    }

    /**
     * Send a key that arrived as a key event: a hardware keyboard, or an IME
     * that reports keys instead of committing text.
     *
     * Returning `false` lets the IME handle it, which is what must happen for a
     * plain printable key — the text path already carries those, and encoding
     * them twice would type every letter twice.
     */
    private fun handleHardwareKey(keyCode: Int, event: KeyEvent): Boolean {
        // Ctrl+Shift+D toggles the host's frame overlay, and is taken before
        // anything is sent to the shell: 0x04 is EOF, and a terminal that exits
        // because the user asked for a number is a terminal nobody debugs twice.
        if (keyCode == KeyEvent.KEYCODE_D && event.isCtrlPressed && event.isShiftPressed) {
            view.toggleDebugOverlay()
            return true
        }

        // A named key: the encoder owns the sequence, including the DECCKM form
        // of the cursor keys — vim asks for `ESC O A` and would print `A` for
        // `ESC [ A`.
        val named = AndroidKeys.named(keyCode)
        if (named != null) {
            val mods = AndroidKeys.mods(event, view.stickyCtrl, view.stickyAlt)
            Encoder.sequence(named, mods, view.appCursor())?.let(view.session::write)
            view.clearSticky()
            return true
        }

        // A printable key with Ctrl or Alt held. The character would normally
        // travel as text, but Ctrl+C has to become 0x03 — and the IME will not
        // do that for a hardware keyboard.
        val armed = view.stickyCtrl || view.stickyAlt || event.isCtrlPressed || event.isAltPressed
        if (armed) {
            val codePoint = event.unicodeChar
            if (codePoint != 0) {
                val character = String(Character.toChars(codePoint))
                val mods = AndroidKeys.mods(event, view.stickyCtrl, view.stickyAlt)
                view.session.write(Encoder.text(character, mods))
                view.clearSticky()
                return true
            }
        }

        // A plain printable key from a hardware keyboard: the IME does not
        // deliver text for one, so it has to be encoded here. Without this a
        // physical keyboard typed nothing at all.
        val codePoint = event.unicodeChar
        if (codePoint != 0) {
            val character = String(Character.toChars(codePoint))
            view.session.write(Encoder.text(character))
            return true
        }

        return false
    }
}
