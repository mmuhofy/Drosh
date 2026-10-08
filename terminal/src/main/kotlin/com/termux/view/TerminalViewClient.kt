package com.termux.view

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession

/**
 * The interface for communication between [TerminalView] and its client.
 * It allows for getting various configuration options from the client and for
 * sending back data to the client like logs, key events, both hardware and IME.
 * It must be set for the [TerminalView] through [TerminalView.setTerminalViewClient].
 */
interface TerminalViewClient {

    /**
     * The terminal font size a double-tap returns to.
     *
     * Asked of the client rather than hard-coded, so the reset target can follow
     * the app rather than a constant baked into a vendored view. It is the app's
     * default — not the current size: after a pinch, the current size is whatever
     * the pinch produced, and a reset to that would do nothing.
     */
    fun defaultFontSizeSp(): Float

    /**
     * Called on every frame of a pinch, with the size the terminal has settled
     * on and the point the fingers are centred on.
     *
     * A notification, not a request: the view has already applied the size. What
     * the client does with it is display-only work — a size chip, a preview —
     * because the alternative (routing the size back through a flow) makes a
     * 60 Hz gesture drive recomposition of the whole screen.
     */
    fun onZoom(textSizeSp: Float, focusX: Float, focusY: Float)

    /**
     * Called once when the fingers lift, with the size to keep.
     *
     * This is the only point at which a zoom is worth persisting: writing on
     * every frame would be a storage write per 16ms, for a value that is still
     * changing.
     */
    fun onZoomEnd(textSizeSp: Float)

    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    fun onSingleTapUp(e: MotionEvent)

    fun shouldBackButtonBeMappedToEscape(): Boolean

    fun shouldEnforceCharBasedInput(): Boolean

    fun shouldUseCtrlSpaceWorkaround(): Boolean

    fun isTerminalViewSelected(): Boolean

    fun copyModeChanged(copyMode: Boolean)

    fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean

    fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean

    fun onLongPress(event: MotionEvent): Boolean

    fun readControlKey(): Boolean

    fun readAltKey(): Boolean

    fun readShiftKey(): Boolean

    fun readFnKey(): Boolean

    fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean

    fun onEmulatorSet()

    fun logError(tag: String?, message: String?)

    fun logWarn(tag: String?, message: String?)

    fun logInfo(tag: String?, message: String?)

    fun logDebug(tag: String?, message: String?)

    fun logVerbose(tag: String?, message: String?)

    fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?)

    fun logStackTrace(tag: String?, e: Exception?)
}
