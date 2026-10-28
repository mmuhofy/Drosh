package dev.drosh.terminal.engine.input

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * The keyboard's end of the terminal: one invisible text editor the IME talks
 * to, whose content is handed to the session as it arrives.
 *
 * ## Why an editable and not just callbacks
 *
 * Termux's recipe (`TerminalView.onCreateInputConnection`), kept because it is
 * the one that survives real keyboards. A predictive IME does not send a
 * character when the key is pressed — it *composes* it, reports the growing
 * word through `setComposingText`, and only commits when the word ends. If the
 * terminal reads nothing until the commit, every letter of a word arrives at
 * once, after the fact; and if a key event (Enter, Tab, an arrow) is handled
 * while a composition is still open, that composition lands *after* the key —
 * which is exactly "I pressed Enter and the previous letter appeared".
 *
 * So the editable is the buffer, and it is drained on every path: a commit, a
 * finished composition, and — the part that fixes the ordering — **before any
 * key event is sent**. A key never overtakes text that was typed before it.
 *
 * ## Why `TYPE_NULL`
 *
 * The IME is told the field takes no text at all, so it does not offer
 * suggestions, autocorrect or a word to replace. A terminal has no use for a
 * spell-checker over shell commands, and a keyboard that "fixes" `ls` to `is`
 * is a keyboard fighting the user. Termux does the same
 * (`shouldEnforceCharBasedInput` is off by default).
 */
internal class TerminalInputView(context: Context) : View(context) {

    /** Committed text, in the order it was typed. */
    var onText: ((String) -> Unit)? = null

    /** A backspace: the IME deleted characters it never showed. */
    var onBackspace: ((Int) -> Unit)? = null

    /** Returns true when the key was handled. */
    var onKey: ((Int, KeyEvent) -> Boolean)? = null

    /**
     * The IME's buffer.
     *
     * Holds what the keyboard has composed but not yet committed. `null` until
     * the input connection is created — the IME is the only writer, and it can
     * only write through a connection.
     */
    private var editing: android.text.Editable? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // A terminal has no text cursor of its own here: the editor is 1x1 and
        // invisible, and the caret the user sees is the engine's.
        setBackgroundColor(0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // `TYPE_NULL` is Termux's default and the reason its input feels
        // immediate: no suggestions, no autocorrect, no word to replace.
        outAttrs.inputType = InputType.TYPE_NULL
        // No fullscreen editor, no "send" button: a terminal sends newlines
        // itself. `IME_ACTION_NONE` is deliberately absent — some keyboards
        // then refuse to send a newline at all (Android TV's, for one).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

        val connection = object : BaseInputConnection(this@TerminalInputView, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Composed text is *not* sent yet: the keyboard may still
                // replace it (a swipe, an autocorrect, a candidate). It is held
                // in the editable and sent when it is committed — or when a key
                // arrives, which is the ordering fix.
                super.setComposingText(text, newCursorPosition)
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Everything before the cursor was already sent, so a delete is
                // that many backspaces on the session. Some keyboards send a
                // whole word at once when the user holds the key.
                if (beforeLength > 0) {
                    onBackspace?.invoke(beforeLength)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // The ordering fix. A composition still open when a key
                    // arrives would otherwise be committed *after* the key —
                    // Enter, then the letter that was already typed. Flushing
                    // first makes the session see the text before the key, in
                    // the order the user typed them.
                    flush()
                    if (onKey?.invoke(event.keyCode, event) == true) {
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }

        editing = connection.editable
        return connection
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // A hardware keyboard reaches the view directly, not through the input
        // connection, so the composition is flushed here too.
        flush()
        return if (onKey?.invoke(keyCode, event) == true) true else super.onKeyDown(keyCode, event)
    }

    /**
     * Send everything the IME has composed, and empty the buffer.
     *
     * Called from every path that can put text in front of the session, so the
     * order the user typed is the order the session reads. A composition that
     * is only *partially* typed is still text the user meant to send — a
     * keyboard that abandons a word when a key arrives would silently swallow
     * it.
     */
    private fun flush() {
        val buffer = editing ?: return
        if (buffer.isEmpty) {
            return
        }

        val text = buffer.toString()
        buffer.clear()
        if (text.isNotEmpty()) {
            onText?.invoke(text)
        }
    }
}
