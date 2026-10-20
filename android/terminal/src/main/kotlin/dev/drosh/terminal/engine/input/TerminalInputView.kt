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
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (onKey?.invoke(keyCode, event) == true) true else super.onKeyDown(keyCode, event)
    }
}
