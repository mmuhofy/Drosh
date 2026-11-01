package dev.drosh.terminal.engine.input

import android.content.Context
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * The keyboard's end of the terminal.
 *
 * ## The problem this file exists to solve
 *
 * A terminal is not a text field. An IME is built for one: it composes a word,
 * shows candidates, and rewrites what it already sent when the user picks a
 * different one. A shell has no such thing — once a byte reaches the PTY it is
 * typed, and there is no taking it back. Every bug this file has had came from
 * pretending otherwise.
 *
 * The symptoms were: pressing Enter showed the *previous* letters afterwards;
 * the letters arrived one at a time, out of order; and backspace sometimes
 * deleted twice.
 *
 * ## The model
 *
 * **Composing is a word being built, and a terminal does not want to see it.**
 * So:
 *
 * - `setComposingText` stores the word and sends **nothing**. The user typing
 *   `merhaba` should not see `m`, `me`, `mer` appear and be deleted as the
 *   keyboard revises its guess.
 * - `commitText` sends the word **once** and clears it. That is the moment the
 *   user accepted it.
 * - `finishComposingText` sends whatever is still composing — a keyboard that
 *   finished a word without committing it (a physical keyboard, or an IME that
 *   reports keys) has still typed it.
 * - `sendKeyEvent` sends the composition **first**, then the key. This is the
 *   ordering rule and the only place it has to be stated: Enter can never
 *   overtake the letters typed before it.
 *
 * Termux's `TerminalView` and Rin's `TerminalSurface` both do a version of
 * this; the difference here is that the composition is *dropped* rather than
 * sent as it grows, because a shell cannot un-type a character and the
 * flicker of watching a keyboard revise itself is worse than a word appearing
 * at once.
 *
 * ## Two details that are not obvious
 *
 * **`\n` is `\r`.** The AOSP keyboard sends `\n` as *text* when Enter is
 * pressed, not as a key event. A terminal expects CR: the tty's `ICRNL` turns
 * it into a newline for the shell, and a raw-mode program expects CR. Without
 * this, Enter on those keyboards does nothing at all.
 *
 * **A control character is Ctrl.** Some keyboards (`penti` and others) send
 * Ctrl+letter as the control byte rather than as a modified letter. Reading
 * `codePoint <= 0x1f` as "Ctrl is held" is what makes those keyboards work.
 */
internal class TerminalInputView(context: Context) : View(context) {

    /** Bytes for the session, in the order they were produced. */
    var onBytes: ((ByteArray) -> Unit)? = null

    /** A key that arrived as a key event; `true` when it was handled. */
    var onKey: ((Int, KeyEvent) -> Boolean)? = null

    /** Whether Ctrl is armed from the extra-keys row. */
    var stickyCtrl: () -> Boolean = { false }

    /** Whether Alt is armed from the extra-keys row. */
    var stickyAlt: () -> Boolean = { false }

    /** Whether the terminal asked for application cursor keys (`DECCKM`). */
    var appCursor: () -> Boolean = { false }

    /** Called after anything is sent, so the cursor can be shown again. */
    var onActivity: () -> Unit = {}

    /**
     * The word the keyboard is building, and has not committed.
     *
     * Owned here rather than read back from the connection: `BaseInputConnection`'s
     * `editable` getter re-resolves through the view, and this view is not an
     * `Editable`, so the answer is not the object the IME wrote. Holding it is
     * the only reliable read.
     */
    private val composing = SpannableStringBuilder()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // A terminal has no text cursor of its own here: the editor is 1x1 and
        // invisible, and the caret the user sees is the engine's.
        setBackgroundColor(0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // `TYPE_NULL`: the IME is told the field takes no text, so it offers no
        // suggestions and no autocorrect. A terminal has no use for a
        // spell-checker over shell commands.
        outAttrs.inputType = InputType.TYPE_NULL
        // `IME_ACTION_NONE` is deliberately absent: Termux's own note says it
        // makes newlines impossible on some on-screen keyboards (Android TV's,
        // for one), and a terminal that cannot send Enter is not a terminal.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this@TerminalInputView, false) {

            /**
             * The buffer the IME writes into.
             *
             * The same object as [composing], so what the keyboard wrote and
             * what this sends are never two different things.
             */
            override fun getEditable(): android.text.Editable = composing

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Held, not sent. The keyboard may still replace it — a swipe,
                // an autocorrect, a candidate list — and a shell cannot take a
                // character back once it has it.
                composing.clear()
                if (!text.isNullOrEmpty()) {
                    composing.append(text)
                }
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // The word the user accepted. Sent once, and the buffer is
                // emptied so the next commit does not send it again.
                if (!text.isNullOrEmpty()) {
                    sendText(text.toString())
                }
                composing.clear()
                return true
            }

            override fun finishComposingText(): Boolean {
                // A composition that ended without a commit is still text the
                // user typed — a physical keyboard, or an IME that reports keys
                // rather than commits. Sending it here is what keeps it from
                // being silently dropped.
                flushComposing()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Everything before the cursor was already sent, so a delete is
                // that many backspaces. The stock Samsung keyboard with "auto
                // check spelling" sends `beforeLength > 1` when the user holds
                // the key.
                composing.clear()
                repeat(beforeLength) { send(byteArrayOf(DEL)) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // The ordering rule. A composition still open when a key
                    // arrives would otherwise be sent *after* the key — Enter,
                    // then the letters that were already typed.
                    flushComposing()
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
        // connection, so the composition is flushed here too.
        flushComposing()
        return if (onKey?.invoke(keyCode, event) == true) true else super.onKeyDown(keyCode, event)
    }

    /**
     * Send whatever is composing, and empty it.
     *
     * The one place a pending word leaves this view. Called before any key, and
     * when a composition ends without a commit.
     */
    private fun flushComposing() {
        if (composing.isEmpty()) {
            return
        }
        val text = composing.toString()
        composing.clear()
        sendText(text)
    }

    /**
     * Text the IME produced: codepoints, one at a time.
     *
     * Termux's `sendTextToTerminal`, with the two cases it exists for — a
     * control character is Ctrl, and a newline is a CR.
     */
    private fun sendText(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)

            var point = codePoint
            var ctrl = false
            if (point <= 0x1f && point != ESC.toInt()) {
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
     * Ctrl is the ASCII definition (`code & 0x1f`); Alt is an ESC prefix, which
     * is how readline gets Alt+B and Alt+F.
     */
    private fun sendCodePoint(codePoint: Int, ctrl: Boolean) {
        if (codePoint < 0 || codePoint > 0x10ffff) {
            return
        }

        val alt = stickyAlt()
        if (ctrl) {
            val character = String(Character.toChars(codePoint))
            send(Encoder.text(character, Encoder.Mods(ctrl = true, alt = alt)))
            return
        }

        if (alt) {
            send(byteArrayOf(ESC))
        }
        send(
            if (codePoint < 0x80) {
                byteArrayOf(codePoint.toByte())
            } else {
                String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            },
        )
    }

    private fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        onActivity()
        onBytes?.invoke(bytes)
    }

    private companion object {
        const val ESC: Byte = 0x1b
        const val DEL: Byte = 0x7f
    }
}
