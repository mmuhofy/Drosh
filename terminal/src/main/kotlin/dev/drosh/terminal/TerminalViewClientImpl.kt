package dev.drosh.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import dev.drosh.domain.UrlDetector
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalViewClientImpl(
    private val onScaleChange: ((Float) -> Float)? = null,
    val extraKeyState: ExtraKeyState? = null,
    private val context: android.content.Context? = null,
    private val onUrlClick: ((String) -> Unit)? = null,
) : TerminalViewClient {

    var scrollLocked: Boolean = false
    var terminalView: TerminalView? = null
    var onCopyModeChanged: ((Boolean) -> Unit)? = null

    override fun onScale(scale: Float): Float {
        return onScaleChange?.invoke(scale) ?: 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val view = terminalView ?: return
        val emulator = view.mEmulator ?: return
        val colRow = view.getColumnAndRow(e, false)
        val col = colRow[0]
        val row = colRow[1]
        if (col < 0 || row < 0) return
        val screen = emulator.getScreen() ?: return
        val word = screen.getWordAtLocation(col, row)
        if (word.isNullOrBlank()) return
        val urls = UrlDetector.findUrls(word)
        if (urls.isNotEmpty()) {
            onUrlClick?.invoke(urls[0].url)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        onCopyModeChanged?.invoke(copyMode)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = extraKeyState?.readCtrl() ?: false
    override fun readAltKey(): Boolean = extraKeyState?.readAlt() ?: false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() { }

    override fun logError(tag: String?, message: String?) {
        if (tag != null && message != null) Log.e(tag, message)
    }
    override fun logWarn(tag: String?, message: String?) {
        if (tag != null && message != null) Log.w(tag, message)
    }
    override fun logInfo(tag: String?, message: String?) {
        if (tag != null && message != null) Log.i(tag, message)
    }
    override fun logDebug(tag: String?, message: String?) {
        if (tag != null && message != null) Log.d(tag, message)
    }
    override fun logVerbose(tag: String?, message: String?) {
        if (tag != null && message != null) Log.v(tag, message)
    }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        val t = tag ?: "Drosh"
        val m = message ?: ""
        if (e != null) Log.e(t, m, e) else Log.e(t, m)
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        if (e != null) Log.e(tag ?: "Drosh", "", e)
    }
}
