package dev.drosh.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class TerminalSessionClientImpl : TerminalSessionClient {

    var cursorStyle: Int? = null

    var onTextChanged: ((TerminalSession) -> Unit)? = null
    var onTitleChanged: ((TerminalSession) -> Unit)? = null
    var onSessionFinished: ((TerminalSession) -> Unit)? = null
    var onPidChanged: ((TerminalSession, Int) -> Unit)? = null

    /**
     * Fires when a session enters or leaves the alternate screen buffer,
     * carrying the session it happened to.
     *
     * The session is part of the callback because the old single-boolean shape
     * could not survive two panes: the flag was one field for every session,
     * so a `vim` opening in the background pane was silently deduplicated
     * against whatever the foreground pane's emulator last reported — the
     * transition was dropped, or worse, delivered with the wrong value. Each
     * session's state is tracked separately and the listener is told which one
     * moved.
     */
    var onAltBufferChanged: ((TerminalSession, Boolean) -> Unit)? = null
    var clipboard: ClipboardManager? = null
    var terminalView: com.termux.view.TerminalView? = null

    /**
     * Last known alternate-buffer state per session.
     *
     * Keyed by session rather than kept as one value because "is a TUI on
     * screen" is a property of a session, not of the terminal. Entries are
     * dropped in [onSessionFinished] — without that, every session the user
     * has ever run would be retained for the life of the process.
     */
    private val altBufferStates = HashMap<TerminalSession, Boolean>()

    override fun onTextChanged(changedSession: TerminalSession) {
        onTextChanged?.invoke(changedSession)
        val altActive = changedSession.emulator?.isAlternateBufferActive() ?: false
        val previous = altBufferStates[changedSession]
        if (previous == altActive) return
        altBufferStates[changedSession] = altActive
        onAltBufferChanged?.invoke(changedSession, altActive)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChanged?.invoke(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        altBufferStates.remove(finishedSession)
        onSessionFinished?.invoke(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text != null) {
            clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: return
            if (text.isNotBlank()) {
                // The session that asked for the paste, not whichever view
                // happens to be registered. Those were the same thing while
                // there was one terminal; with a second pane open, long-pressing
                // in the background pane pasted into the foreground one.
                (session?.emulator ?: terminalView?.mEmulator)?.paste(text)
            }
        }
    }

    override fun onBell(session: TerminalSession) {
    }

    override fun onColorsChanged(session: TerminalSession) {
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        onPidChanged?.invoke(session, pid)
    }

    override fun getTerminalCursorStyle(): Int? = cursorStyle

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
