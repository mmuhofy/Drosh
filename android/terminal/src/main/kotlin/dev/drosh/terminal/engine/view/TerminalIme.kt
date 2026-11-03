package dev.drosh.terminal.engine.view

import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.drosh.terminal.engine.input.AndroidKeys
import dev.drosh.terminal.engine.input.Encoder
import dev.drosh.terminal.engine.input.TerminalInputView

/**
 * Wires the keyboard to the session.
 *
 * ## The shape of this file
 *
 * It is Termux's `TerminalView.onKeyDown` and `inputCodePoint`, reduced to what
 * a terminal needs and kept in the same order. The order is the point: a key
 * that carries a sequence (an arrow, a function key) must be answered by the
 * encoder *before* the unicode path is tried, because `getUnicodeChar` on
 * `KEYCODE_DPAD_UP` returns nothing and on `KEYCODE_ENTER` returns `\n` — and a
 * terminal wants CR from the encoder, not LF from the keyboard map.
 *
 * ## The three layers
 *
 * 1. **Named keys** — arrows, F1–F12, Home/End, PgUp/PgDn, Insert/Delete, Tab,
 *    Escape, Enter, Backspace. [Encoder] owns the sequences, including the
 *    DECCKM form of the cursor keys: vim asks for `ESC O A` and would print `A`
 *    for `ESC [ A`.
 * 2. **Modified characters** — Ctrl and Alt. Ctrl+C has to become `0x03` even
 *    though the character would otherwise travel as text.
 * 3. **Plain characters** — `getUnicodeChar` with the modifiers that a keyboard
 *    map understands. Shift and AltGr compose characters (`Shift+2` is `@`,
 *    `AltGr+e` is `€`), and Ctrl and the left Alt must be stripped first or the
 *    map refuses to compose at all — Termux does the same and documents why.
 *
 * ## Dead keys
 *
 * A combining accent (`^`, `~`, `` ` `` on some layouts) arrives as a key with
 * `COMBINING_ACCENT` set, and the *next* key is what it modifies. Termux keeps
 * one accent pending and combines it with the following key; without that,
 * typing `´` then `e` produces two characters instead of `é`.
 */
internal class TerminalIme(private val view: TerminalView) {

    /** The focused text editor the IME talks to; 1×1 px so it stays out of sight. */
    val input: TerminalInputView = TerminalInputView(view.context).apply {
        onBytes = { bytes -> view.session.write(bytes) }
        onKey = { keyCode, event -> handleKey(keyCode, event) }
        stickyCtrl = { view.stickyCtrl }
        stickyAlt = { view.stickyAlt }
        appCursor = { view.appCursor() }
        onActivity = { view.onInputActivity() }
    }

    /**
     * The accent waiting for the key it modifies.
     *
     * Termux's `mCombiningAccent`: a dead key sets it, and the next character
     * either combines with it or is preceded by it.
     */
    private var pendingAccent = 0

    /**
     * A key that arrived as a key event: a hardware keyboard, or an IME that
     * reports keys instead of committing text.
     *
     * Returning `false` lets the IME handle it, which is what must happen for a
     * plain printable key — the text path already carries those, and encoding
     * them twice would type every letter twice.
     */
    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        // A named key: the encoder owns the sequence.
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

        // The unicode path, with the modifiers a keyboard map understands.
        //
        // Ctrl and the left Alt are cleared first: they are ours, not the
        // keyboard map's, and leaving them in makes `getUnicodeChar` return 0
        // for every letter. Shift and AltGr stay, because they are what compose
        // a character in the first place.
        var bitsToClear = KeyEvent.META_CTRL_MASK
        val rightAlt = event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0
        if (!rightAlt) {
            // The left Alt is an ESC prefix for the shell (`Alt+B` is a word
            // back), not a compose key.
            bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var effective = event.metaState and bitsToClear.inv()
        if (event.isShiftPressed) {
            effective = effective or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }

        val result = event.getUnicodeChar(effective)
        if (result == 0) {
            return false
        }

        if (result and KeyCharacterMap.COMBINING_ACCENT != 0) {
            // A dead key. The accent it holds is sent if another one replaces
            // it, and otherwise waits for the next character.
            if (pendingAccent != 0) {
                view.session.write(Encoder.text(String(Character.toChars(pendingAccent))))
            }
            pendingAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }

        var codePoint = result
        if (pendingAccent != 0) {
            val combined = KeyCharacterMap.getDeadChar(pendingAccent, codePoint)
            if (combined > 0) {
                codePoint = combined
            } else {
                // The accent does not combine with this key: the accent is a
                // character of its own and the key is what follows it.
                view.session.write(Encoder.text(String(Character.toChars(pendingAccent))))
            }
            pendingAccent = 0
        }

        // A hardware keyboard's printable key: the IME does not deliver text
        // for one, so it has to be encoded here. Without this a physical
        // keyboard typed nothing at all.
        view.session.write(Encoder.text(String(Character.toChars(codePoint))))
        return true
    }
}
