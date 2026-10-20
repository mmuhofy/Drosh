package dev.drosh.engine.view

import android.view.KeyEvent
import dev.drosh.engine.input.AndroidKeys
import dev.drosh.engine.input.Encoder
import dev.drosh.engine.input.TerminalInputView

/**
 * Wires the 1×1 [input] ([TerminalInputView]) — the soft keyboard and hardware
 * keys — to the session, honouring the armed [TerminalView.stickyCtrl] from an
 * external extra-keys row. Keeping the key handling here separates it from the
 * touch/gesture path in [TerminalGestures].
 */
internal class TerminalIme(private val view: TerminalView) {

    /** The focused text editor the IME talks to; 1×1 px so it stays out of sight. */
    val input: TerminalInputView = TerminalInputView(view.context).apply {
        onText = { text ->
            // A soft keyboard commits text; the row's armed Ctrl turns the first
            // character into a control code, which is how a phone sends Ctrl+C.
            view.session.write(Encoder.text(text, Encoder.Mods(ctrl = view.stickyCtrl)))
            view.stickyCtrl = false
        }
        onBackspace = { view.session.write(byteArrayOf(0x7f)) }
        onKey = { keyCode, event -> handleHardwareKey(keyCode, event) }
    }

    /**
     * Send a key that arrived as a key event: a hardware keyboard, or an IME
     * that reports keys instead of committing text.
     *
     * Returning `false` lets the IME handle it, which is what must happen for a
     * plain printable key — the text path already carries those.
     */
    private fun handleHardwareKey(keyCode: Int, event: KeyEvent): Boolean {
        val named = AndroidKeys.named(keyCode)
        if (named != null) {
            val mods = AndroidKeys.mods(event, view.stickyCtrl)
            Encoder.sequence(named, mods, view.appCursor())?.let(view.session::write)
            view.stickyCtrl = false
            return true
        }

        // A printable key while Ctrl (or Alt) is held: Ctrl+C has to become 0x03
        // even though the character would normally travel as text.
        val armed = view.stickyCtrl || event.isAltPressed
        val codepoint = event.unicodeChar
        if (armed && codepoint != 0) {
            val character = String(Character.toChars(codepoint))
            view.session.write(Encoder.text(character, AndroidKeys.mods(event, view.stickyCtrl)))
            view.stickyCtrl = false
            return true
        }

        return false
    }
}
