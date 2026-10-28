package dev.drosh.terminal.engine.view

import android.view.KeyEvent
import dev.drosh.terminal.engine.input.AndroidKeys
import dev.drosh.terminal.engine.input.Encoder
import dev.drosh.terminal.engine.input.TerminalInputView

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
        onBackspace = { count ->
            // The IME may delete a whole word at once; each character it
            // removed is one backspace on the session.
            repeat(count) { view.session.write(byteArrayOf(0x7f)) }
        }
        onKey = { keyCode, event -> handleHardwareKey(keyCode, event) }
    }

    /**
     * Send a key that arrived as a key event: a hardware keyboard, or an IME
     * that reports keys instead of committing text.
     *
     * Returning `false` lets the IME handle it, which is what must happen for a
     * plain printable key — the text path already carries those. The composition
     * has already been flushed by [TerminalInputView] before this is called, so
     * a key can never overtake text the user typed before it.
     */
    private fun handleHardwareKey(keyCode: Int, event: KeyEvent): Boolean {
        // Ctrl+Shift+D toggles the host's frame overlay, and is taken before
        // anything is sent to the shell: 0x04 is EOF, and a terminal that exits
        // because the user asked for a number is a terminal nobody debugs
        // twice.
        if (keyCode == KeyEvent.KEYCODE_D &&
            event.isCtrlPressed && event.isShiftPressed
        ) {
            view.toggleDebugOverlay()
            return true
        }

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

        // A hardware keyboard's printable key: the IME path does not deliver
        // text for it, so it has to be encoded here. Termux sends it the same
        // way (`inputCodePoint` from the key handler). Without this a physical
        // keyboard typed nothing at all.
        if (codepoint != 0 && !event.isCtrlPressed && !event.isAltPressed) {
            val character = String(Character.toChars(codepoint))
            view.session.write(Encoder.text(character))
            return true
        }

        return false
    }
}
