package dev.drosh.terminal.engine.input

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * The keyboard's end of the terminal.
 *
 * ## The model: Termux's, which is the one that survives real keyboards
 *
 * `TerminalView.onCreateInputConnection` in termux-app, kept close to the
 * original because every deviation from it has been a bug here:
 *
 * 1. **`TYPE_NULL`** — the IME is told the field takes no text, so it offers no
 *    suggestions, no autocorrect, and no word to replace. A terminal has no use
 *    for a spell-checker over shell commands, and a keyboard that "fixes" `ls`
 *    to `is` is a keyboard fighting the user.
 *
 * 2. **No `IME_ACTION_NONE`.** Termux's own note: it makes newlines impossible
 *    on some on-screen keyboards (Android TV's, for one). A terminal that cannot
 *    send Enter is not a terminal.
 *
 * 3. **One buffer, drained on every path.** The `Editable` is written by the IME
 *    and read back in one place, [sendToTerminal]. A commit sends it, a finished
 *    composition sends it, and it is emptied either way — so text cannot be sent
 *    twice and cannot be left behind.
 *
 * 4. **`setComposingText` is not overridden.** Composed text accumulates in the
 *    buffer and goes out when the word is committed. That is right for a
 *    terminal: the user typing `merhaba` does not want `m`, `me`, `mer` to
 *    appear and be deleted; the shell sees the word when it is a word. A
 *    predictive keyboard that commits a letter at a time still works — each
 *    commit is one letter.
 *
 * 5. **`\n` becomes `\r`.** The AOSP keyboard and its descendants send `\n` as
 *    *text* when Enter is pressed, not as a key event. A terminal expects CR:
 *    the tty's `ICRNL` turns it into a newline for the shell, and a raw-mode
 *    program expects CR. Without this, Enter on those keyboards does nothing —
 *    which is exactly the "I pressed Enter and nothing happened" this file was
 *    rewritten for.
 *
 * 6. **A control character is Ctrl.** Some keyboards (penti and others) send
 *    Ctrl+letter as the control character itself rather than as a modified
 *    letter. Reading `codePoint <= 31` as "Ctrl is held" is what makes those
 *    keyboards work.
 *
 * ## What this class does not do
 *
 * It does not encode escape sequences. That is [Encoder]'s job — arrows,
 * function keys, the DECCKM form of the cursor keys — and this only decides
 * *which* codepoint or which named key arrived. Keeping the two apart is what
 * lets the encoder be tested on its own.
 */
internal class TerminalInputView(context: Context) : View(context) {

    /** Bytes for the session, in the order they were produced. */
    var onBytes: ((ByteArray) -> Unit)? = null

    /** A key that arrived as a key event; `true` when it was handled. */
    var onKey: ((Int, KeyEvent) -> Boolean)? = null

    /**
     * Whether Ctrl is armed from the extra-keys row.
     *
     * A phone has no Ctrl key, so the row arms one; every path that produces a
     * character asks here.
     */
    var stickyCtrl: () -> Boolean = { false }

    /**
     * Whether Alt is armed from the extra-keys row.
     */
    var stickyAlt: () -> Boolean = { false }

    /** Whether the terminal asked for application cursor keys (`DECCKM`). */
    var appCursor: () -> Boolean = { false }

    /** Called after anything is sent, so the cursor can be shown again. */
    var onActivity: () -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // A terminal has no text cursor of its own here: the editor is 1x1 and
        // invisible, and the caret the user sees is the engine's.
        setBackgroundColor(0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        // No fullscreen editor: the terminal draws its own screen, and a
        // fullscreen IME would cover it with an editor the user cannot use.
        // `IME_ACTION_NONE` is deliberately absent — see the class comment.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

        // The buffer the IME writes into, owned here.
        //
        // `BaseInputConnection(this, true)` builds one of its own and the
        // `editable` getter re-resolves it through the view — which is not an
        // `Editable`, so the answer is not the object the IME wrote. Holding it
        // makes [drain] read exactly what the keyboard put in.
        val buffer = android.text.SpannableStringBuilder()
        connectionEditable = buffer

        return object : BaseInputConnection(this@TerminalInputView, false) {
            override fun getEditable(): android.text.Editable = buffer

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                // The whole buffer, not just this commit: the base class appends
                // to it, and a composition that was never finished is in there
                // too.
                drain()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                drain()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Everything before the cursor was already sent, so a delete is
                // that many backspaces on the session. The stock Samsung
                // keyboard with "auto check spelling" sends `leftLength > 1`
                // when the user holds the key.
                repeat(beforeLength) { send(byteArrayOf(DEL.toByte())) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // Whatever the IME composed but has not committed goes out
                    // first, so a key can never overtake the text typed before
                    // it. This is the ordering rule, and it is the only place
                    // it has to be stated.
                    drain()
                    if (onKey?.invoke(event.keyCode, event) == true) {
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // A hardware keyboard reaches the view directly, not through the input
        // connection, so the composition is drained here too.
        drain()
        return if (onKey?.invoke(keyCode, event) == true) true else super.onKeyDown(keyCode, event)
    }

    /**
     * Send everything the IME has put in the buffer, and empty it.
     *
     * The one place text leaves this view. `Editable` is read as codepoints, not
     * chars, so a surrogate pair is one character and a combining mark stays
     * attached to what it modifies.
     */
    private fun drain() {
        val buffer = connectionEditable ?: return
        if (buffer.isEmpty()) {
            return
        }

        val text = buffer.toString()
        buffer.clear()
        if (text.isNotEmpty()) {
            sendToTerminal(text)
        }
    }

    /**
     * The buffer the IME writes into.
     *
     * Held rather than asked for: `BaseInputConnection.editable` re-resolves
     * through the view, and this view is not an `Editable`, so the answer is not
     * the object the IME wrote. Keeping the object the connection was built
     * with is the only reliable read.
     */
    private var connectionEditable: android.text.Editable? = null

    /**
     * Text the IME committed or finished composing: codepoints, one at a time.
     *
     * Termux's `sendTextToTerminal`, with the two cases it exists for:
     *
     * - a **control character** means the keyboard is reporting Ctrl itself
     *   (`penti` and friends), so it is read as Ctrl+letter rather than as the
     *   raw byte;
     * - a **newline** is a CR: the AOSP keyboard sends `\n` for Enter as text,
     *   and a terminal that writes `\n` to a raw-mode program is a program that
     *   does not see a line.
     */
    private fun sendToTerminal(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)

            var point = codePoint
            var ctrl = false
            if (point <= 0x1f && point != ESC) {
                // The keyboard sent a control character: it is reporting a
                // modifier, not a character.
                point = if (point == '\n'.code) '\r'.code else point
                ctrl = true
                point = when (point) {
                    0x1f -> '_'.code
                    0x1e -> '^'.code
                    0x1d -> ']'.code
                    0x1c -> '\\'.code
                    else -> point + 96
                }
            }

            sendCodePoint(point, ctrl = ctrl || stickyCtrl())
        }
    }

    /**
     * One codepoint to the session, as bytes.
     *
     * Ctrl is the ASCII definition (`code & 0x1f`), Alt is an ESC prefix — the
     * same two rules [Encoder] documents.
     */
    private fun sendCodePoint(codePoint: Int, ctrl: Boolean) {
        if (codePoint < 0) {
            return
        }

        val alt = stickyAlt()
        if (ctrl) {
            val character = if (codePoint in 0..0x10ffff) {
                String(Character.toChars(codePoint))
            } else {
                return
            }
            Encoder.text(character, Encoder.Mods(ctrl = true, alt = alt)).let(::send)
            return
        }

        // A printable character. Alt is an ESC prefix, which is how readline
        // gets Alt+B and Alt+F.
        if (alt) {
            send(byteArrayOf(ESC.toByte()))
        }
        val bytes = if (codePoint < 0x80) {
            byteArrayOf(codePoint.toByte())
        } else {
            String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        }
        send(bytes)
    }

    private fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        onActivity()
        onBytes?.invoke(bytes)
    }

    private companion object {
        const val ESC = 0x1b
        const val DEL = 0x7f
    }
}
