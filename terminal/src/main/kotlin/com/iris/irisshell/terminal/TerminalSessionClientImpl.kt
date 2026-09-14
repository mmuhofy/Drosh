package com.iris.irisshell.terminal

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
    var onAltBufferChanged: ((Boolean) -> Unit)? = null
    var clipboard: ClipboardManager? = null
    var terminalView: com.termux.view.TerminalView? = null

    private var lastAltBufferState: Boolean = false

    override fun onTextChanged(changedSession: TerminalSession) {
        onTextChanged?.invoke(changedSession)
        val altActive = changedSession.emulator?.isAlternateBufferActive() ?: false
        if (altActive != lastAltBufferState) {
            lastAltBufferState = altActive
            onAltBufferChanged?.invoke(altActive)
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChanged?.invoke(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
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
                terminalView?.mEmulator?.paste(text)
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
