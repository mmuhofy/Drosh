package dev.drosh.terminal.engine.input

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Phase A keyboard input.
 *
 * The view is invisible but reports itself as a text editor, so the soft
 * keyboard opens for it. Instead of letting it accumulate text, the input
 * connection intercepts the two things a terminal needs — committed text and
 * deletions — and keeps the editing buffer empty. This is the recipe Termux
 * uses; the sentinel-character variants break with predictive keyboards.
 *
 * Phase B replaces this with `input/encoder.kt` (escape sequences for Ctrl/Alt,
 * arrows, function keys) plus an extra-keys row (D6); Phase A only needs what it
 * takes to type `echo hello` and `ls`.
 */
internal class TerminalInputView(context: Context) : View(context) {

    var onText: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null

    /** Returns true when the key was handled. */
    var onKey: ((Int, KeyEvent) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // `TYPE_NULL` is Termux's default and the reason its input feels
        // immediate: the IME does not keep a composing buffer at all, so every
        // key arrives as a key event or a commit rather than being held until
        // the next word boundary. With a text type, predictive keyboards keep
        // the last character composed — and release it when Enter is pressed,
        // which is exactly the "the previous letter appears late" the device
        // showed.
        outAttrs.inputType = InputType.TYPE_NULL
        // No fullscreen editor, no "send" button: a terminal sends newlines itself.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this@TerminalInputView, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    onText?.invoke(text.toString())
                }
                return true
            }

            /**
             * A composing update, sent at once.
             *
             * Some keyboards compose even with `TYPE_NULL`, and a composition
             * that is only read when it finishes is a character that arrives a
             * keystroke late. Only the part that is new is sent, so a keyboard
             * that grows the composition one letter at a time does not send the
             * whole word again on every key.
             */
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val now = text?.toString().orEmpty()
                if (now.length > composed.length && now.startsWith(composed)) {
                    onText?.invoke(now.substring(composed.length))
                }
                composed = now
                return true
            }

            override fun finishComposingText(): Boolean {
                // Everything was already sent as it composed; there is nothing
                // left to flush, and flushing it again would double the word.
                composed = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { onBackspace?.invoke() }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && onKey?.invoke(event.keyCode, event) == true) {
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    /** What the IME is currently composing, so only new text is sent. */
    private var composed: String = ""

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (onKey?.invoke(keyCode, event) == true) true else super.onKeyDown(keyCode, event)
    }
}
