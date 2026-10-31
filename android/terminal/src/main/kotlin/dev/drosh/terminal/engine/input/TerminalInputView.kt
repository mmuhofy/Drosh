package dev.drosh.terminal.engine.input

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * The keyboard's end of the terminal: one invisible text editor the IME talks
 * to, whose content is handed to the session as it arrives.
 *
 * ## Why an editable of its own
 *
 * Termux's recipe (`TerminalView.onCreateInputConnection`), kept because it is
 * the one that survives real keyboards — but with the buffer owned here rather
 * than borrowed from the base class.
 *
 * `BaseInputConnection(editable = true)` keeps its own `Editable`, and asking
 * it for that object (`connection.editable`) is not the same as holding it:
 * the getter re-resolves through the view, which does not implement
 * `Editable` at all. The first version of this class did exactly that and
 * every `flush()` was a no-op on a `null` buffer, so composed text was never
 * sent — a word arrived only if the keyboard happened to call `commitText`,
 * and a key pressed mid-word overtook the letters typed before it.
 *
 * One buffer, held in a field, written by the IME and read by [flush]. That is
 * the whole contract.
 *
 * ## Why the ordering matters
 *
 * A predictive IME does not send a character when the key is pressed — it
 * *composes* it, reports the growing word through `setComposingText`, and only
 * commits when the word ends. If a key event (Enter, Tab, an arrow) is handled
 * while a composition is still open, that composition would land *after* the
 * key: "I pressed Enter and the previous letter appeared". So every key event
 * flushes first.
 *
 * ## Why `TYPE_NULL`
 *
 * The IME is told the field takes no text at all, so it does not offer
 * suggestions, autocorrect or a word to replace. A terminal has no use for a
 * spell-checker over shell commands. Termux does the same.
 */
internal class TerminalInputView(context: Context) : View(context) {

    /** Committed text, in the order it was typed. */
    var onText: ((String) -> Unit)? = null

    /** Backspaces: how many characters the IME deleted. */
    var onBackspace: ((Int) -> Unit)? = null

    /** Returns true when the key was handled. */
    var onKey: ((Int, KeyEvent) -> Boolean)? = null

    /**
     * The buffer the IME writes into.
     *
     * Created once and held: it is the same object for the whole life of the
     * connection, which is what lets [flush] read what the keyboard wrote.
     */
    private val buffer: Editable = SpannableStringBuilder()

    /** The connection handed to the IME, so the view can drain it too. */
    private var connection: BaseInputConnection? = null

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

        val made = object : BaseInputConnection(this@TerminalInputView, false) {
            /**
             * The buffer this connection writes into.
             *
             * Overridden because the base class would otherwise build one of
             * its own on every call, and [flush] has to read the same one the
             * IME wrote.
             */
            override fun getEditable(): Editable = buffer

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text.isNullOrEmpty()) {
                    return true
                }
                // Straight to the session, not through the buffer: a commit is
                // final, and a round trip through an editable only to read it
                // back is a place for the two to disagree.
                onText?.invoke(text.toString())
                // The buffer's composition is over; anything still in it was
                // part of the word that just committed.
                buffer.clear()
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Composed text is *held*, not sent: the keyboard may still
                // replace it (a swipe, an autocorrect, a candidate). It goes
                // out when it is committed — or when a key arrives, which is
                // the ordering fix.
                buffer.clear()
                if (!text.isNullOrEmpty()) {
                    buffer.append(text)
                }
                Selection.setSelection(buffer, buffer.length)
                return true
            }

            override fun finishComposingText(): Boolean {
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
                buffer.clear()
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

        connection = made
        return made
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
     * order the user typed is the order the session reads.
     */
    private fun flush() {
        if (buffer.isEmpty()) {
            return
        }
        val text = buffer.toString()
        buffer.clear()
        if (text.isNotEmpty()) {
            onText?.invoke(text)
        }
    }

    /**
     * Finish whatever the IME is composing, right now.
     *
     * Called by the view before it closes the keyboard: an IME that is torn
     * down mid-word otherwise loses the letters it had composed but not
     * committed, which is a word the user typed and never saw.
     */
    fun commitPending() {
        connection?.finishComposingText()
        flush()
    }
}
