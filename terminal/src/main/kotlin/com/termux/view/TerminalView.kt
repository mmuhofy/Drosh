package com.termux.view

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.roundToInt
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Scroller

import androidx.annotation.RequiresApi

import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.textselection.TextSelectionCursorController

import dev.drosh.terminal.SearchHighlightOverlay
import dev.drosh.domain.terminal.TerminalZoom

import java.util.Properties
import kotlin.math.abs

/**
 * How long after a resize new output is taken to be the shell's reaction to that
 * resize rather than something the user did.
 *
 * Comfortably longer than the chrome transition that causes the resize. The
 * shell's repaint is scheduled by its own SIGWINCH handler and arrives a frame
 * or two later, so a window shorter than the transition would let the repaint
 * that closes it be mistaken for new output.
 *
 * Drosh-added.
 */
private const val RESIZE_GRACE_MILLIS = 400L

/** View displaying and interacting with a [TerminalSession]. */
class TerminalView(context: Context, attributes: AttributeSet?) : View(context, attributes) {

    /** The currently displayed terminal session, whose emulator is [mEmulator]. */
    @JvmField
    var mTermSession: TerminalSession? = null

    /** Our terminal emulator whose session is [mTermSession]. */
    @JvmField
    var mEmulator: TerminalEmulator? = null

    @JvmField
    var mRenderer: TerminalRenderer? = null

    @JvmField
    var mClient: TerminalViewClient? = null

    private var mTextSelectionCursorController: TextSelectionCursorController? = null

    /**
     * The selected region in view pixels, or null if nothing is selected.
     *
     * Rows are resolved through the same [TextSelectionCursorController.getPointY]
     * the handles use, so the menu cannot disagree with them about where the
     * selection is.
     */
    fun selectionBounds(): Rect? {
        val c = mTextSelectionCursorController ?: return null
        val y1 = c.selY1
        val y2 = c.selY2
        if (y1 < 0 || y2 < y1) return null
        val r = mRenderer ?: return null
        val left = minOf(c.selX1, c.selX2) * r.mFontWidth
        val right = maxOf(c.selX1, c.selX2) * r.mFontWidth
        val top = getPointY(y1).toFloat()
        val bottom = getPointY(y2 + 1).toFloat()
        if (right <= left || bottom <= top) return null
        return Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
    }

    /** Ends the selection, as if the user had tapped away. */
    fun dismissSelection() = stopTextSelectionMode()

    fun pasteFromClipboard() {
        mTermSession?.onPasteTextFromClipboard()
    }

    /**
     * Selects the whole visible screen.
     *
     * A grid has no document, so this is the honest equivalent of select-all:
     * everything the terminal is currently showing.
     */
    fun selectAll() {
        val c = mTextSelectionCursorController ?: return
        val emu = mEmulator ?: return
        c.selectAll(0, emu.mRows - 1, 0, emu.mColumns - 1)
    }

    /** Points the selection menu at whoever is drawing it. */
    private var selectionMenuListener: (() -> Unit)? = null
    private var usePlatformSelectionMenu = true

    /**
     * Points the selection menu at whoever is drawing it.
     *
     * The settings are held here as well as pushed to the controller, because
     * the controller is built lazily on first use — long after the view is
     * registered — so an install that only touched the controller found nothing
     * to touch and the platform ActionMode stayed on.
     */
    fun installSelectionMenu(enabled: Boolean, listener: (() -> Unit)?) {
        usePlatformSelectionMenu = enabled
        selectionMenuListener = if (enabled) null else listener
        mTextSelectionCursorController?.apply {
            usePlatformActionMode = enabled
            onChanged = if (enabled) null else listener
        }
    }

    /** Applies a pending menu install to a controller that has just been built. */
    private fun applySelectionMenuTo(controller: TextSelectionCursorController) {
        controller.usePlatformActionMode = usePlatformSelectionMenu
        controller.onChanged = selectionMenuListener
    }

    /** Re-publishes the selection once, for when the menu first appears. */
    fun notifySelectionChanged() {
        mTextSelectionCursorController?.onChanged?.invoke()
    }

    private var mTerminalCursorBlinkerHandler: Handler? = null
    private var mTerminalCursorBlinkerRunnable: TerminalCursorBlinkerRunnable? = null
    private var mTerminalCursorBlinkerRate: Int = 0
    private var mCursorInvisibleIgnoreOnce: Boolean = false

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0. */
    @JvmField
    var mTopRow: Int = 0

    @JvmField
    var mDefaultSelectors: IntArray = intArrayOf(-1, -1, -1, -1)


    @JvmField
    var mScaleFactor: Float = 1f

    /**
     * Font size at the moment a pinch began, in sp. 0 when no pinch is running.
     *
     * The zoom is computed from this rather than from the previous frame, so a
     * long gesture cannot drift and shrinking back returns to the size the
     * gesture started from.
     */
    private var mZoomGestureBaseSp: Float = 0f

    /**
     * True once the current pinch has moved far enough to count as a zoom.
     *
     * Latched for the rest of the gesture, and reset when it ends. The dead
     * zone exists to ignore a two-finger landing wobble — it is not a limit on
     * how far a zoom may travel, so once a gesture has crossed it, coming back
     * to the origin has to be able to undo what going out did.
     */
    private var mZoomGesturePassedDeadZone: Boolean = false

    internal lateinit var mGestureRecognizer: GestureAndScaleRecognizer

    /** Keep track of where mouse touch event started which we report as mouse scroll. */
    private var mMouseScrollStartX: Int = -1
    private var mMouseScrollStartY: Int = -1

    /** Keep track of the time when a touch event leading to sending mouse scroll events started. */
    private var mMouseStartDownTime: Long = -1

    lateinit var mScroller: Scroller

    /**
     * Sub-line scroll position, in pixels, always within one line spacing.
     *
     * Scrolling used to move by whole rows: onScroll kept the leftover pixels in
     * mScrollRemainder and discarded them at the end, so a slow drag advanced
     * one line at a time in visible jumps however little the finger moved. That
     * remainder is kept here instead and the whole grid is drawn shifted by it,
     * so the motion tracks the finger. When the offset crosses a full line,
     * mTopRow moves by one and the offset wraps.
     *
     * A terminal is a grid, so this is motion of the existing rows only. No row
     * is ever half-created and every row stays on its baseline.
     */
    @JvmField var mScrollOffsetPx: Float = 0f

    /** What was left in from scrolling movement. */
    @JvmField var mScrollRemainder: Float = 0f

    /**
     * When the emulator was last resized by [updateSize], in uptime millis.
     *
     * A resize is not free: the PTY is sent a SIGWINCH and an interactive shell
     * answers by redrawing its prompt, and that redraw is indistinguishable from
     * new output by the time it arrives. Left alone, [onScreenUpdated] treats it
     * as output and throws the viewport to the live edge — so the change the app
     * made to its own chrome came back as a scroll to the bottom, and undid
     * itself.
     *
     * Recording the time is how the two are told apart: output arriving inside
     * [RESIZE_GRACE_MILLIS] of a resize is the shell answering us.
     */
    private var mResizedAtUptime: Long = 0L

    /** Whether output arriving now is a reaction to a resize this app caused. */
    private fun justResized(): Boolean =
        SystemClock.uptimeMillis() - mResizedAtUptime < RESIZE_GRACE_MILLIS

    @JvmField
    var spaceKeyDown: Boolean = false

    private var spaceDragActive: Boolean = false
    private var spaceDragMoved: Boolean = false
    private var horizontalDragAccumulator: Float = 0f
    private var lastDragX: Float = 0f
    private var pendingSpaceRunnable: Runnable? = null

    /** If non-zero, this is the last unicode code point received if that was a combining character. */
    @JvmField
    var mCombiningAccent: Int = 0

    /**
     * The current AutoFill type returned for [View.getAutofillType] by [getAutofillType].
     *
     * The default is [AUTOFILL_TYPE_NONE] so that AutoFill UI, like toolbar above keyboard
     * is not shown automatically, like on Activity starts/View create.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private var mAutoFillType: Int = AUTOFILL_TYPE_NONE

    /**
     * The current AutoFill type returned for [View.getImportantForAutofill] by [getImportantForAutofill].
     *
     * The default is [IMPORTANT_FOR_AUTOFILL_NO] so that view is not considered important for AutoFill.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private var mAutoFillImportance: Int = IMPORTANT_FOR_AUTOFILL_NO

    /**
     * The current AutoFill hints returned for [View.getAutofillHints] by [getAutofillHints].
     */
    private var mAutoFillHints: Array<String> = emptyArray()

    private val mAccessibilityEnabled: Boolean

    var searchHighlightOverlay: SearchHighlightOverlay? = null

    /**
     * The link layer, a different View for a different job. Search
     * highlighting is [searchHighlightOverlay]; URLs are
     * [TerminalUrlOverlay], which groups soft-wrapped rows into logical lines
     * and owns the press feedback. Keeping them apart is what stopped the
     * search overlay's URL branch from crashing the draw pass — the grouping
     * rules now live in TerminalUrlOverlay.logicalLineGroups, one function
     * for both the draw path and the tap path.
     */
    var urlOverlay: TerminalUrlOverlay? = null

    /**
     * Notified whenever [mTopRow] changes, with the new value. 0 means the live
     * edge — the prompt. Negative means the viewport has been scrolled back into
     * the transcript, which is what takes the app fullscreen.
     *
     * Drosh-added. Read this rather than polling: the view's own
     * [com.termux.terminal.TerminalSession.mTerminalFileDescriptor] output path
     * never fires while the user scrolls an idle shell, so a poll timed on
     * output would miss the exact moment the state changes.
     */
    var onScrollPositionChanged: ((Int) -> Unit)? = null

    /**
     * Bumped every time the screen content changes. Used by the top bar to
     * resample its backdrop only when there is something new behind it, rather
     * than on a blind timer — resampling draws the whole terminal a second
     * time, which is not something to do for no reason.
     */
    @Volatile var contentGeneration: Int = 0
        private set

    init {
        mGestureRecognizer = GestureAndScaleRecognizer(context, object : GestureAndScaleRecognizer.Listener {
            var scrolledWithFinger = false

            override fun onUp(event: MotionEvent): Boolean {
                mScrollRemainder = 0.0f
                if (mEmulator != null && mEmulator!!.isMouseTrackingActive() && !event.isFromSource(InputDevice.SOURCE_MOUSE) && !isSelectingText && !scrolledWithFinger) {
                    // Quick event processing when mouse tracking is active - do not wait for check of double tapping
                    // for zooming.
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true)
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, false)
                    return true
                }
                scrolledWithFinger = false
                return false
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (mEmulator == null) return true

                if (isSelectingText) {
                    stopTextSelectionMode()
                    return true
                }
                requestFocus()
                mClient?.onSingleTapUp(event)
                return true
            }

            override fun onScroll(e: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (mEmulator == null) return true

                if (mEmulator!!.isMouseTrackingActive() && e.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    // If moving with mouse pointer while pressing button, report that instead of scroll.
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                } else {
                    scrolledWithFinger = true
                    scrollByPixels(e, distanceY)
                }
                return true
            }

            override fun onScaleBegin() {
                // Where this gesture starts, so the size is recomputed from the
                // gesture's own origin rather than from the last frame's
                // rounded value.
                mZoomGestureBaseSp = textSizeSp
                mScaleFactor = 1f
                mZoomGesturePassedDeadZone = false
            }

            override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
                if (mEmulator == null || isSelectingText) return true
                // One event, one bounded step. A finger that moves 40% between
                // two events — or a third finger arriving — would otherwise
                // take several steps in a single frame and read as a jump.
                val step = scale.coerceIn(1f / TerminalZoom.MAX_STEP_FACTOR, TerminalZoom.MAX_STEP_FACTOR)

                // Total finger travel this gesture, so a long pinch does not
                // drift by a rounding error per frame.
                mScaleFactor *= step

                // Dead zone, in log-scale so it is symmetric: 4% either way
                // reads as no movement at this size, and testing it per event
                // would accumulate — 1.04^n passes the threshold during a
                // gesture that was never a zoom.
                //
                // Latched once crossed, and that latch is the whole point of
                // tracking it separately: without it, returning the fingers to
                // where they started puts the total back inside the dead zone
                // and the terminal keeps the size from the peak of the pinch —
                // a zoom that cannot be undone by the same gesture.
                if (!mZoomGesturePassedDeadZone) {
                    if (Math.abs(Math.log(mScaleFactor.toDouble())) < TerminalZoom.DEAD_ZONE) return true
                    mZoomGesturePassedDeadZone = true
                }

                // From the gesture's origin, not from the current size: past
                // the dead zone the terminal goes to where the fingers
                // actually are, rather than ramping from the size it stalled
                // at.
                val base = if (mZoomGestureBaseSp > 0f) mZoomGestureBaseSp else textSizeSp
                zoomTo(base * mScaleFactor, focusX, focusY)
                mClient?.onZoom(textSizeSp, focusX, focusY)
                return true
            }

            override fun onScaleEnd() {
                // The gesture is over: the dead zone is per-gesture, and the
                // size is now worth persisting.
                mScaleFactor = 1f
                mZoomGestureBaseSp = 0f
                mZoomGesturePassedDeadZone = false
                mClient?.onZoomEnd(textSizeSp)
            }

            override fun onFling(e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (mEmulator == null) return true
                // Do not start scrolling until last fling has been taken care of:
                if (!mScroller.isFinished) return true

                val mouseTrackingAtStartOfFling = mEmulator!!.isMouseTrackingActive()
                val SCALE = 0.25f
                if (mouseTrackingAtStartOfFling) {
                    mScroller.fling(0, 0, 0, -(velocityY * SCALE).toInt(), 0, 0, -mEmulator!!.mRows / 2, mEmulator!!.mRows / 2)
                } else {
                    mScroller.fling(0, mTopRow, 0, -(velocityY * SCALE).toInt(), 0, 0, -mEmulator!!.getScreen().activeTranscriptRows, 0)
                }

                post(object : Runnable {
                    private var mLastY = 0

                    override fun run() {
                        if (mouseTrackingAtStartOfFling != mEmulator!!.isMouseTrackingActive()) {
                            mScroller.abortAnimation()
                            return
                        }
                        if (mScroller.isFinished) { snapToWholeRow(); return }
                        val more = mScroller.computeScrollOffset()
                        val newY = mScroller.currY
                        if (mouseTrackingAtStartOfFling) {
                            doScroll(e2, newY - mLastY)
                        } else {
                            // Momentum goes through the same pixel path as a
                            // drag. Assigning mTopRow from the scroller's currY
                            // advanced a whole number of rows per frame — which
                            // is precisely the jitter at the ends: the scroller
                            // eases, but the viewport jumped several lines at a
                            // time and slammed into the limit. Converting the
                            // row delta to pixels lets it carry a sub-line
                            // remainder like everything else.
                            val spacing = mRenderer?.mFontLineSpacing ?: return
                            scrollByPixels(e2, ((newY - mTopRow) * spacing).toFloat())
                        }
                        mLastY = newY
                        if (more) post(this)
                    }
                })

                return true
            }

            override fun onDown(x: Float, y: Float): Boolean {
                return false
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                // Do not treat is as a single confirmed tap - it may be followed by zoom.
                //
                // A second finger means a pinch, so a double-tap that grows
                // into a pinch must not have already moved the font: the
                // ScaleGestureDetector claims the gesture once the second
                // finger lands, but the first tap has been delivered by then.
                if (mGestureRecognizer.isInProgress()) return false
                if (mEmulator == null || isSelectingText) return false

                // Deferred to the next frame rather than applied here. This
                // callback arrives before the second tap has been
                // disambiguated — the gesture detector calls this on the
                // second ACTION_DOWN, and whether that tap is the start of a
                // double-tap or the end of a pinch is only known on the
                // following frame. Zooming now and un-zooming a moment later
                // is exactly the flicker this is avoiding, so the work waits
                // until the gesture has settled.
                post { if (!mGestureRecognizer.isInProgress()) resetToDefaultSize(event.x, event.y) }
                return false
            }

            override fun onLongPress(event: MotionEvent) {
                if (mGestureRecognizer.isInProgress()) return
                if (mClient?.onLongPress(event) == true) return
                if (!isSelectingText) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startTextSelectionMode(event)
                }
            }
        })
        mScroller = Scroller(context)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        mAccessibilityEnabled = am.isEnabled
    }

    /**
     * @param client The [TerminalViewClient] interface implementation to allow
     *               for communication between [TerminalView] and its client.
     */
    fun setTerminalViewClient(client: TerminalViewClient) {
        this.mClient = client
    }

    /**
     * Sets whether terminal view key logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    fun setIsTerminalViewKeyLoggingEnabled(value: Boolean) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value
    }

    /**
     * Attach a [TerminalSession] to this view.
     *
     * @param session The [TerminalSession] this view will be displaying.
     */
    fun attachSession(session: TerminalSession): Boolean {
        if (session == mTermSession) return false
        mTopRow = 0
        mScrollOffsetPx = 0f

        mTermSession = session
        mEmulator = null
        mCombiningAccent = 0

        updateSize()

        // Wait with enabling the scrollbar until we have a terminal to get scroll position from.
        isVerticalScrollBarEnabled = true

        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Ensure that inputType is only set if TerminalView is selected view with the keyboard and
        // an alternate view is not selected, like an EditText.
        if (mClient!!.isTerminalViewSelected()) {
            if (mClient!!.shouldEnforceCharBasedInput()) {
                // Some keyboards seems do not reset the internal state on TYPE_NULL.
                outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                // Using InputType.NULL is the most correct input type and avoids issues with other hacks.
                outAttrs.inputType = InputType.TYPE_NULL
            }
        } else {
            // Corresponds to android:inputType="text"
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, true) {

            /**
             * Where the shell's cursor is, as far as this connection can tell.
             *
             * Not the real position — that lives in the shell's line editor and
             * is not observable from here. It is a running estimate advanced by
             * what we send and rebased by every selection change, which is what
             * a keyboard needs in order to compute a meaningful delta.
             */
            private var imeCursor: Int = 0

            /** Recent committed text, so getTextBeforeCursor is not always empty. */
            private val typedMirror = StringBuilder()

            /*
             * A terminal has no notion of a composing region: there is nothing
             * to pre-edit and then commit, the bytes have to reach the PTY as
             * they are typed.
             *
             * These two are deliberately no-ops, and that is load-bearing.
             * `editable` is a local mirror that commitText() hands to the PTY
             * and then clears. If an IME calls setComposingRegion()/setComposingText()
             * in between, BaseInputConnection stores a composing region that
             * keeps referring to offsets in that mirror. Once the mirror is
             * cleared the markers dangle, and the next finishComposingText()
             * resolves them against mutated content and puts already-sent text
             * back into the editable — which commitText() then sends to the PTY
             * a second time. Symptom: typing one character re-inserts several
             * characters, including ones typed earlier.
             *
             * Keyboards that compose regardless of inputType trigger this.
             * Gboard does not, so it never showed up before Drosh Keyboard.
             */
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient!!.logInfo(LOG_TAG, "IME: setComposingText() ignored")
                }
                return true
            }

            override fun setComposingRegion(start: Int, end: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient!!.logInfo(LOG_TAG, "IME: setComposingRegion($start, $end) ignored")
                }
                return true
            }

            /**
             * Moves the cursor by translating the IME's selection change into
             * arrow key events.
             *
             * A terminal has no editable buffer for an IME to point into. The
             * only thing that can move a shell's cursor is the terminal itself,
             * via the arrow escape sequences. Left unhandled,
             * `setSelection` reached `BaseInputConnection` and was applied to a
             * mirror that is cleared after every commit, so it clamped to zero
             * and did nothing.
             *
             * That breaks any keyboard which moves the cursor through
             * `setSelection` rather than through arrow keys — FUTO for one,
             * which is also how it corrupts input: the IME keeps a selection it
             * believes in, and the next commit flushes a mirror that no longer
             * agrees with it.
             *
             * Only the horizontal delta is honoured. The IME cannot know the
             * shell's true cursor position, so [imeCursor] is our own running
             * estimate: advanced by each committed text, and rebased by each
             * selection change.
             */
            override fun setSelection(start: Int, end: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient!!.logInfo(LOG_TAG, "IME: setSelection($start, $end)")
                }
                // A selection range cannot be represented in a terminal; collapse
                // it to a caret so the estimate stays meaningful.
                val target = if (start == end) start else minOf(start, end)
                val delta = target - imeCursor
                if (delta != 0) {
                    moveCursor(delta)
                    imeCursor = target
                }
                return true
            }

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
                if (n <= 0) return ""
                val length = minOf(n, typedMirror.length)
                if (length == 0) return ""
                return typedMirror.substring(typedMirror.length - length, typedMirror.length)
            }

            // Nothing follows the caret in a terminal: the shell owns that line,
            // not this connection.
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""

            // A terminal cannot represent a selection through the IME.
            override fun getSelectedText(n: Int): CharSequence? = ""

            private fun moveCursor(delta: Int) {
                val step = if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                var remaining = abs(delta)
                // Batched so a long jump is one burst of events, not hundreds.
                while (remaining > 0) {
                    val chunk = minOf(remaining, CURSOR_MOVE_BATCH)
                    repeat(chunk) { handleKeyCode(step, 0) }
                    remaining -= chunk
                }
            }

            override fun finishComposingText(): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient!!.logInfo(LOG_TAG, "IME: finishComposingText()")
                super.finishComposingText()

                sendTextToTerminal(editable!!)
                editable!!.clear()
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient!!.logInfo(LOG_TAG, "IME: commitText(\"$text\", $newCursorPosition)")
                }
                super.commitText(text, newCursorPosition)

                if (mEmulator == null) return true

                val content = editable!!
                sendTextToTerminal(content)
                content.clear()
                // Keep the running cursor estimate in step with what the shell
                // has actually received, so a later setSelection computes its
                // delta against reality rather than against a stale mirror.
                advanceCursor(content)
                return true
            }

            /**
             * Records committed text and moves the estimate forward.
             *
             * Bounded on purpose: this exists to let a keyboard read a little
             * context back, not to mirror the whole command line.
             */
            private fun advanceCursor(sent: CharSequence) {
                val length = sent.length
                if (length == 0) return
                imeCursor += length
                typedMirror.append(sent)
                if (typedMirror.length > TYPED_MIRROR_MAX) {
                    val excess = typedMirror.length - TYPED_MIRROR_MAX
                    typedMirror.delete(0, excess)
                    imeCursor -= excess
                    if (imeCursor < 0) imeCursor = 0
                }
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient!!.logInfo(LOG_TAG, "IME: deleteSurroundingText($leftLength, $rightLength)")
                }
                // The stock Samsung keyboard with 'Auto check spelling' enabled sends leftLength > 1.
                val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                for (i in 0 until leftLength) sendKeyEvent(deleteKey)
                // Backspaces move the shell's cursor left, so the estimate has
                // to follow or the next setSelection overshoots.
                if (leftLength > 0) imeCursor = (imeCursor - leftLength).coerceAtLeast(0)
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            fun sendTextToTerminal(text: CharSequence) {
                stopTextSelectionMode()

                if (text.length == 1 && text[0] == ' ') {
                    if (spaceKeyDown) {
                        return
                    }
                    spaceKeyDown = true
                    spaceDragActive = false
                    spaceDragMoved = false
                    horizontalDragAccumulator = 0f
                    lastDragX = 0f
                    pendingSpaceRunnable = Runnable {
                        if (spaceKeyDown && !spaceDragMoved) {
                            mTermSession?.write(" ")
                        }
                        spaceKeyDown = false
                        spaceDragActive = false
                        spaceDragMoved = false
                        horizontalDragAccumulator = 0f
                        lastDragX = 0f
                    }
                    postDelayed(pendingSpaceRunnable, SPACE_DRAG_TIMEOUT_MS)
                    return
                }

                val textLengthInChars = text.length
                var i = 0
                while (i < textLengthInChars) {
                    val firstChar = text[i]
                    var codePoint: Int
                    if (Character.isHighSurrogate(firstChar)) {
                        if (++i < textLengthInChars) {
                            codePoint = Character.toCodePoint(firstChar, text[i])
                        } else {
                            // At end of string, with no low surrogate following the high:
                            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR
                        }
                    } else {
                        codePoint = firstChar.code
                    }

                    // Check onKeyDown() for details.
                    if (mClient!!.readShiftKey())
                        codePoint = Character.toUpperCase(codePoint)

                    var ctrlHeld = false
                    if (codePoint <= 31 && codePoint != 27) {
                        if (codePoint == '\n'.code) {
                            // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                            // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                            // key.
                            codePoint = '\r'.code
                        }

                        // E.g. penti keyboard for ctrl input.
                        ctrlHeld = true
                        codePoint = when (codePoint) {
                            31 -> '_'.code
                            30 -> '^'.code
                            29 -> ']'.code
                            28 -> '\\'.code
                            else -> codePoint + 96
                        }
                    }

                    inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, ctrlHeld, false)
                    i++
                }
            }
        }
    }

    override fun computeVerticalScrollRange(): Int {
        return mEmulator?.getScreen()?.activeRows ?: 1
    }

    override fun computeVerticalScrollExtent(): Int {
        return mEmulator?.mRows ?: 1
    }

    override fun computeVerticalScrollOffset(): Int {
        return if (mEmulator == null) 1 else mEmulator!!.getScreen().activeRows + mTopRow - mEmulator!!.mRows
    }

    fun onScreenUpdated() {
        onScreenUpdated(false)
    }

    fun onScreenUpdated(skipScrolling: Boolean) {
        var skipScroll = skipScrolling
        if (mEmulator == null) return

        val rowsInHistory = mEmulator!!.getScreen().activeTranscriptRows
        if (mTopRow < -rowsInHistory) {
            mTopRow = -rowsInHistory
            mScrollOffsetPx = legalScrollOffset(mTopRow, mScrollOffsetPx)
        }

        if (isSelectingText || mEmulator!!.isAutoScrollDisabled()) {
            // Do not scroll when selecting text.
            val rowShift = mEmulator!!.getScrollCounter()
            if (-mTopRow + rowShift > rowsInHistory) {
                // .. unless we're hitting the end of history transcript, in which
                // case we abort text selection and scroll to end.
                //
                // Not on the alternate buffer. A TUI has no transcript at all,
                // so rowsInHistory is 0 and *any* line feed it emits reads as
                // having run off the end — which killed the selection the moment
                // you scrolled in vim. There is nothing to run off into.
                if (isSelectingText && !mEmulator!!.isAlternateBufferActive())
                    stopTextSelectionMode()

                if (mEmulator!!.isAutoScrollDisabled()) {
                    mTopRow = -rowsInHistory
                    mScrollOffsetPx = legalScrollOffset(mTopRow, mScrollOffsetPx)
                    skipScroll = true
                }
            } else {
                skipScroll = true
                mTopRow -= rowShift
                decrementYTextSelectionCursors(rowShift)
            }
        }

        // New output pulls the viewport back to the live edge. That is right for
        // output the shell produced on its own, and wrong for the repaint a shell
        // produces because *we* resized it — see [justResized].
        if (!skipScroll && mTopRow != 0 && !justResized()) {
            // Scroll down if not already there.
            if (mTopRow < -3) {
                // Awaken scroll bars only if scrolling a noticeable amount
                awakenScrollBars()
            }
            mTopRow = 0
        }

        mEmulator!!.clearScrollCounter()

        contentGeneration++
        invalidate()
        searchHighlightOverlay?.invalidate()
        urlOverlay?.invalidate()
        onScrollPositionChanged?.invoke(mTopRow)
        if (mAccessibilityEnabled) contentDescription = text
    }

    /**
     * This must be called by the hosting activity in [Activity.onContextMenuClosed]
     * when context menu for the [TerminalView] is started by
     * [TextSelectionCursorController.ACTION_MORE] is closed.
     */
    fun onContextMenuClosed(menu: Menu) {
        // Unset the stored text since it shouldn't be used anymore and should be cleared from memory
        unsetStoredSelectedText()
    }

/**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * Fractional so a pinch can follow the fingers. The grid rounds down to
     * whole columns and [updateSize] reflows only when that count actually
     * changes, so the sizes between two grid changes cost a repaint and
     * nothing else — which is most of a pinch.
     *
     * Not the same path as [zoomTo]: this one is told a size, from Settings or
     * from a restore, and has no focus point to anchor against.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    fun setTextSize(textSize: Float) {
        if (mRenderer == null) {
            mRenderer = TerminalRenderer(textSize, Typeface.MONOSPACE)
        } else {
            mRenderer!!.updateTextSize(textSize)
        }
        updateSize()
    }

    /** The font size the terminal is drawing at, in sp. */
    val textSizeSp: Float
        get() = mRenderer?.mTextSize ?: 0f

    /**
     * Set the font size outright, keeping the cell under ([focusX], [focusY])
     * where the fingers are.
     *
     * The anchor is what makes a pinch feel attached to the content rather than
     * to the screen: without it, growing the text pushes the line being read
     * away from the fingers on every column change, and the reading position
     * has to be found again after each pinch.
     *
     * Takes the target size rather than a factor because a pinch computes it
     * from where the gesture began (see [mZoomGestureBaseSp]); a factor would
     * have to be re-derived from a size that was already rounded, which is how
     * a long gesture ends up somewhere other than where the fingers stopped.
     *
     * Called from the touch handler, so it stays cheap: the renderer re-measures
     * in place, and the reflow in [updateSize] is skipped unless the column
     * count actually moved.
     *
     * A no-op when the size lands on the one already in use. That is what makes
     * the dead zone safe to re-test per frame in a gesture that never zooms:
     * every frame computes the same size and every frame costs a comparison.
     */
fun zoomTo(textSizeSp: Float, focusX: Float, focusY: Float) {
        if (mEmulator == null || mRenderer == null) return
        val target = quantiseTextSize(textSizeSp)
        if (target == mRenderer!!.mTextSize) return

        // The row the focus is on before the change, as an absolute row index
        // — a top-row offset survives the reflow, a viewport-relative one does
        // not.
        val spacingBefore = mRenderer!!.mFontLineSpacing
        val anchoredRow = mTopRow + if (spacingBefore > 0) (focusY / spacingBefore).toInt() else 0

        mRenderer!!.updateTextSize(target)
        updateSize()

        // Put that row back under the fingers. The grid is usually shorter after
        // zooming out, in which case the bottom of the screen is empty and
        // there is nothing to pin — the clamp covers that, and it also covers
        // the other direction: a row index cannot be pushed past the live edge
        // without the terminal scrolling off its own output.
        val spacingAfter = mRenderer!!.mFontLineSpacing
        if (spacingAfter > 0) {
            val screenRowUnderFocus = (focusY / spacingAfter).toInt()
            mTopRow = (anchoredRow - screenRowUnderFocus)
                .coerceIn(-mEmulator!!.getScreen().activeTranscriptRows, 0)
        }
        invalidate()
    }

/**
     * Return to the configured default size, if we are not already there.
     *
     * The one zoom value a pinch cannot reach on purpose: no amount of pinching
     * gets a user back to the size they started the app with, and making them
     * remember a number to undo a zoom is not a control.
     *
     * The target is the app's default, not the persisted size. After a pinch the
     * persisted size *is* the pinched one, so resetting to it would do nothing
     * the moment anyone pinched — which is exactly when a reset is wanted.
     *
     * Reports the result as a gesture so the chip appears and the size is
     * persisted: a double-tap that changed nothing has nothing to persist, and
     * one that did must not be lost on the next launch.
     */
    private fun resetToDefaultSize(focusX: Float, focusY: Float) {
        if (mEmulator == null) return
        val target = mClient?.defaultFontSizeSp() ?: TerminalZoom.DEFAULT_SP
        if (target <= 0f) return
        if (quantiseTextSize(target) == textSizeSp) return
        zoomTo(target, focusX, focusY)
        mClient?.onZoom(textSizeSp, focusX, focusY)
        mClient?.onZoomEnd(textSizeSp)
    }

    /**
     * Round a size to the 0.1sp grid, inside the limits.
     *
     * Both halves matter. Rounding alone would let a long gesture accumulate a
     * float tail (14.300000000000001) that persists and reloads as a slightly
     * different number; the clamp alone would leave the tail in the value the
     * chip showed and the user had seen.
     *
     * Re-clamped after rounding. Both limits are whole tenths today, so that is
     * belt-and-braces — but the day one is not, a value just outside could round
     * inside while a value just inside rounds out, and then the chip could show
     * a size the terminal will not draw.
     */
    private fun quantiseTextSize(value: Float): Float {
        val clamped = value.coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
        val steps = Math.round(clamped / TerminalZoom.STEP_SP)
        return (steps * TerminalZoom.STEP_SP).coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
    }

    /**
     * Override the terminal color scheme (foreground / background / cursor /
     * indexed colors) from a [Properties] map — e.g. set from a hex color
     * picker in Settings. Redraws immediately.
     */
    fun updateColors(props: Properties) {
         val colors = mEmulator?.mColors
         if (colors != null) {
             TerminalColors.COLOR_SCHEME.updateWith(props)
             colors.reset()
         }
         invalidate()
     }

    /**
     * Sets the typeface, which in turn re-measures the grid and so changes the
     * row and column counts.
     *
     * Null-safe on the renderer: this can be called before any [setTextSize],
     * and the previous `mRenderer!!` threw in that case. The size falls back to
     * the Termux default so a font arriving first still draws.
     */
    fun setTypeface(newTypeface: Typeface) {
        mRenderer = TerminalRenderer(mRenderer?.mTextSize ?: DEFAULT_TEXT_SIZE.toFloat(), newTypeface)
        updateSize()
        invalidate()
    }

    /**
     * Whether taking focus should raise the keyboard.
     *
     * Android opens the keyboard when the served view that gains focus reports
     * itself as a text editor. The terminal always did, and it also takes focus
     * on every touch down, so a tap anywhere raised the keyboard.
     *
     * This is what the toolbar button and entering the screen turn on, and what
     * a touch turns off. It gates focus only; an explicit showSoftInput still
     * works while it is false, which is what makes the button authoritative.
     */
    /**
     * Whether the IME should treat this view as a text editor right now.
     *
     * False by default, and that is the whole design: no focus path can raise
     * the keyboard, because focus is not what asks for it. Only an explicit
     * showKeyboard does, which is what the toolbar button and entering the
     * screen go through.
     *
     * It was true by default, and the terminal takes focus on touch-down, on
     * single tap, on the first layout and at the start of a text selection —
     * four ways to raise the keyboard by tapping instead of asking for it.
     */
    var raiseKeyboardOnFocus = false

    override fun onCheckIsTextEditor(): Boolean {
        return raiseKeyboardOnFocus
    }

    override fun isOpaque(): Boolean {
        return true
    }

    /**
     * Get the zero indexed column and row of the terminal view for the
     * position of the event.
     *
     * @param event The event with the position to get the column and row for.
     * @param relativeToScroll If true the column number will take the scroll
     * position into account.
     * @return Array with the column and row.
     */
    /**
     * Corrects a touch y for the sub-line scroll offset.
     *
     * While an offset is live the grid is drawn shifted by it and, because a
     * partial row shows at the top, one line higher than usual. Every
     * y-to-row conversion has to undo both or it names the wrong row — which
     * puts URL taps and text selection on a line the finger is not on, and at
     * the bottom edge produces an out-of-range row entirely.
     *
     * At offset zero this returns y unchanged, so the legacy mappings
     * (including getCursorY's hardcoded 40) keep behaving exactly as they did.
     */
    private fun yForRowLookup(y: Float): Float {
        val offset = mScrollOffsetPx
        if (offset == 0f) return y
        val spacing = mRenderer?.mFontLineSpacing ?: return y
        return y - offset - spacing
    }

    fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray {
        val y = yForRowLookup(event.y)
        val column = (event.x / mRenderer!!.mFontWidth).toInt()
        var row = ((y - mRenderer!!.mFontLineSpacingAndAscent) / mRenderer!!.mFontLineSpacing).toInt()
        if (relativeToScroll) {
            row += mTopRow
        }
        return intArrayOf(column, row)
    }

    /** Send a single mouse event code to the terminal. */
    fun sendMouseEventCode(e: MotionEvent, button: Int, pressed: Boolean) {
        val columnAndRow = getColumnAndRow(e, false)
        var x = columnAndRow[0] + 1
        var y = columnAndRow[1] + 1
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.downTime) {
                x = mMouseScrollStartX
                y = mMouseScrollStartY
            } else {
                mMouseStartDownTime = e.downTime
                mMouseScrollStartX = x
                mMouseScrollStartY = y
            }
        }
        mEmulator!!.sendMouseEvent(button, x, y, pressed)
    }

    /**
     * Moves the viewport by a pixel delta, carrying the sub-line remainder.
     *
     * A drag down is a request to go back towards the live edge, so a positive
     * distanceY decreases the offset. The offset is what actually gets drawn;
     * mTopRow only moves when a whole line has accumulated, which is what keeps
     * every row on its baseline while the pixels between them move freely.
     */
    /**
     * Restricts a sub-line offset to one the current position can actually show.
     *
     * The offset translates the grid, so the row it opens has to exist:
     *
     *   offset > 0  grid moves down, opens the row *above* topRow
     *               -> needs topRow > minTopRow
     *   offset < 0  grid moves up, opens the row *below* the last visible row
     *               -> needs topRow < 0
     *
     * Everything that moves the viewport has to go through this, not just the
     * drag. A drag can leave a few pixels of offset at the live edge, and if the
     * next batch of output snaps mTopRow back to zero underneath it, those
     * pixels become an over-scroll with no finger involved to explain it.
     */
    private fun legalScrollOffset(topRow: Int, offset: Float): Float {
        val minTopRow = if (mEmulator == null) 0 else -mEmulator!!.getScreen().activeTranscriptRows
        var out = offset
        if (topRow <= minTopRow) out = Math.min(out, 0f)
        if (topRow >= 0) out = Math.max(out, 0f)
        return out
    }

    fun scrollByPixels(event: MotionEvent, distanceYPx: Float) {
        if (mEmulator == null) return
        val spacing = mRenderer?.mFontLineSpacing?.toFloat() ?: return
        if (spacing <= 0f) return

        // Mouse tracking and the alternate buffer want discrete events; they
        // cannot use a sub-line offset.
        if (mEmulator!!.isMouseTrackingActive() || mEmulator!!.isAlternateBufferActive()) {
            val rows = (distanceYPx / spacing).toInt()
            if (rows != 0) doScroll(event, rows)
            return
        }

        val minTopRow = -mEmulator!!.getScreen().activeTranscriptRows
        // GestureDetector reports distanceY as lastY - currentY, so dragging
        // the finger *down* is negative — and that is the gesture for revealing
        // older output. The offset therefore moves opposite to the raw delta,
        // which is what the old whole-row path did too via doScroll's `up` flag.
        var offset = mScrollOffsetPx - distanceYPx
        var topRow = mTopRow

        while (offset >= spacing) {
            if (topRow > minTopRow) { topRow--; offset -= spacing } else { offset = 0f; break }
        }
        while (offset <= -spacing) {
            if (topRow < 0) { topRow++; offset += spacing } else { offset = 0f; break }
        }
        // The clamps live in legalScrollOffset, shared with every other place
        // that moves the viewport. Writing them inline here — and, worse, on
        // "topRow == 0" rather than "topRow >= 0" — is what let the grid be
        // dragged past the boundary: a positive offset at the live edge is
        // exactly the over-scroll, yet the check permitted it, and releasing
        // the finger ran snapToWholeRow() to animate the grid back.
        offset = legalScrollOffset(topRow, offset)

        if (topRow != mTopRow || offset != mScrollOffsetPx) {
            mTopRow = topRow
            mScrollOffsetPx = offset
            if (!awakenScrollBars()) invalidate()
                onScrollPositionChanged?.invoke(mTopRow)
            }
    }

    /**
     * Eases the sub-line remainder back to a whole row. Called when a fling
     * ends, so momentum does not leave the grid sitting between baselines.
     */
    private var mScrollSettle: Runnable? = null

    fun snapToWholeRow() {
        val from = mScrollOffsetPx
        // Nothing to settle, and importantly: do not cancel a running settle
        // before deciding, or a mid-flight animation would be stranded at
        // whatever offset it had reached.
        if (from == 0f) return
        mScrollSettle?.let { removeCallbacks(it) }
        val to = 0f
        val start = SystemClock.uptimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val t = Math.min(1f, (SystemClock.uptimeMillis() - start) / 140f)
                val eased = 1f - (1f - t) * (1f - t)
                mScrollOffsetPx = from + (to - from) * eased
                if (t < 1f) {
                    postDelayed(this, 16)
                } else {
                    mScrollSettle = null
                    mScrollOffsetPx = to
                    if (!awakenScrollBars()) invalidate()
                        onScrollPositionChanged?.invoke(mTopRow)
                            }
            }
        }
        mScrollSettle = runnable
        post(runnable)
    }

    /** Perform a scroll, either from dragging the screen or by scrolling a mouse wheel. */
    fun doScroll(event: MotionEvent, rowsDown: Int) {
        val up = rowsDown < 0
        val amount = Math.abs(rowsDown)
        for (i in 0 until amount) {
            if (mEmulator!!.isMouseTrackingActive()) {
                sendMouseEventCode(event, if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true)
            } else if (mEmulator!!.isAlternateBufferActive()) {
                // Send up and down key events for scrolling, which is what some terminals do to make scroll work in
                // e.g. less, which shifts to the alt screen without mouse handling.
                handleKeyCode(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0)
            } else {
                mTopRow = Math.min(0, Math.max(-mEmulator!!.getScreen().activeTranscriptRows, mTopRow + if (up) -1 else 1))
                // A wheel notch or a PageUp is a whole-line request; there is
                // no sub-line position to preserve across one.
                // Ease it back rather than zeroing it: snapping the offset
                // while the viewport is still mid-line is a visible jump.
                if (mScrollOffsetPx != 0f) snapToWholeRow()
                if (!awakenScrollBars()) invalidate()
                onScrollPositionChanged?.invoke(mTopRow)
                    }
        }
    }

    /** Overriding [View.onGenericMotionEvent]. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.action == MotionEvent.ACTION_SCROLL) {
            // Handle mouse wheel scrolling.
            val up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f
            doScroll(event, if (up) -3 else 3)
            return true
        }
        return false
    }

    private fun handleSpaceDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!spaceDragActive) {
                    spaceDragActive = true
                    spaceDragMoved = false
                    pendingSpaceRunnable?.let { removeCallbacks(it) }
                    pendingSpaceRunnable = null
                    horizontalDragAccumulator = 0f
                    lastDragX = event.x
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (spaceDragActive) {
                    val deltaX = event.x - lastDragX
                    lastDragX = event.x
                    horizontalDragAccumulator += deltaX
                    val fontWidth = mRenderer?.mFontWidth ?: 0f
                    if (fontWidth > 0) {
                        val colsToMove = (horizontalDragAccumulator / fontWidth).toInt()
                        if (colsToMove != 0) {
                            horizontalDragAccumulator -= colsToMove * fontWidth
                            spaceDragMoved = true
                            val keyCode = if (colsToMove > 0) {
                                KeyEvent.KEYCODE_DPAD_LEFT
                            } else {
                                KeyEvent.KEYCODE_DPAD_RIGHT
                            }
                            repeat(Math.abs(colsToMove)) {
                                handleKeyCode(keyCode, 0)
                            }
                            mEmulator?.setCursorBlinkState(true)
                            invalidate()
                        }
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (spaceDragActive) {
                    if (!spaceDragMoved) {
                        inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, ' '.code, false, false)
                    }
                    spaceKeyDown = false
                    spaceDragActive = false
                    spaceDragMoved = false
                    horizontalDragAccumulator = 0f
                    lastDragX = 0f
                }
                return true
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    @TargetApi(23)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mEmulator == null) return true

        if (spaceKeyDown) {
            return handleSpaceDrag(event)
        }

        val action = event.action

        if (action == MotionEvent.ACTION_DOWN) {
            // Focus still matters — the hardware keyboard needs it — but the
            // soft one must not come up just because a finger landed.
            raiseKeyboardOnFocus = false
            requestFocusFromTouch()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }

        if (isSelectingText) {
            updateFloatingToolbarVisibility(event)
            mGestureRecognizer.onTouchEvent(event)
            return true
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu()
                return true
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboardManager.primaryClip
                if (clipData != null) {
                    val clipItem = clipData.getItemAt(0)
                    if (clipItem != null) {
                        val text = clipItem.coerceToText(context)
                        if (!TextUtils.isEmpty(text)) mEmulator!!.paste(text.toString())
                    }
                }
            } else if (mEmulator!!.isMouseTrackingActive()) { // BUTTON_PRIMARY.
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP ->
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.action == MotionEvent.ACTION_DOWN)
                    MotionEvent.ACTION_MOVE ->
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                }
            }
        }

        mGestureRecognizer.onTouchEvent(event)
        return true
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient!!.logInfo(LOG_TAG, "onKeyPreIme(keyCode=$keyCode, event=$event)")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelRequestAutoFill()
            if (isSelectingText) {
                stopTextSelectionMode()
                return true
            } else if (mClient!!.shouldBackButtonBeMappedToEscape()) {
                // Intercept back button to treat it as escape:
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> return onKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> return onKeyUp(keyCode, event)
                }
            }
        } else if (mClient!!.shouldUseCtrlSpaceWorkaround() &&
            keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
            /* ctrl+space does not work on some ROMs without this workaround. */
            return onKeyDown(keyCode, event)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient!!.logInfo(LOG_TAG, "onKeyDown(keyCode=$keyCode, isSystem()=${event.isSystem}, event=$event)")
        if (mEmulator == null) return true

        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            pendingSpaceRunnable?.let { removeCallbacks(it) }
            pendingSpaceRunnable = null
            spaceKeyDown = true
            spaceDragActive = false
            spaceDragMoved = false
            horizontalDragAccumulator = 0f
            lastDragX = 0f
            return true
        }
        if (isSelectingText) {
            stopTextSelectionMode()
        }

        if (mClient!!.onKeyDown(keyCode, event, mTermSession!!)) {
            invalidate()
            return true
        } else if (event.isSystem && (!mClient!!.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event)
        } else if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mTermSession!!.write(event.characters)
            return true
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return super.onKeyDown(keyCode, event)
        }

        val metaState = event.metaState
        val controlDown = event.isCtrlPressed || mClient!!.readControlKey()
        val leftAltDown = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || mClient!!.readAltKey()
        val shiftDown = event.isShiftPressed || mClient!!.readShiftKey()
        val rightAltDownFromEvent = (metaState and KeyEvent.META_ALT_RIGHT_ON) != 0

        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (shiftDown) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK
        // https://github.com/termux/termux-app/issues/731
        if (!event.isFunctionPressed && handleKeyCode(keyCode, keyMod)) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient!!.logInfo(LOG_TAG, "handleKeyCode() took key event")
            return true
        }

        // Clear Ctrl since we handle that ourselves:
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (rightAltDownFromEvent) {
            // Let right Alt/Alt Gr be used to compose characters.
        } else {
            // Use left alt to send to terminal (e.g. Left Alt+B to jump back a word), so remove:
            bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var effectiveMetaState = event.metaState and bitsToClear.inv()

        if (shiftDown) effectiveMetaState = effectiveMetaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (mClient!!.readFnKey()) effectiveMetaState = effectiveMetaState or KeyEvent.META_FUNCTION_ON

        var result = event.getUnicodeChar(effectiveMetaState)
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient!!.logInfo(LOG_TAG, "KeyEvent#getUnicodeChar($effectiveMetaState) returned: $result")
        if (result == 0) {
            return false
        }

        val oldCombiningAccent = mCombiningAccent
        if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            // If entered combining accent previously, write it out:
            if (mCombiningAccent != 0)
                inputCodePoint(event.deviceId, mCombiningAccent, controlDown, leftAltDown)
            mCombiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            if (mCombiningAccent != 0) {
                val combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, result)
                if (combinedChar > 0) result = combinedChar
                mCombiningAccent = 0
            }
            inputCodePoint(event.deviceId, result, controlDown, leftAltDown)
        }

        if (mCombiningAccent != oldCombiningAccent) invalidate()

        return true
    }

    fun inputCodePoint(eventSource: Int, codePoint: Int, controlDownFromEvent: Boolean, leftAltDownFromEvent: Boolean) {
        var cp = codePoint
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient!!.logInfo(LOG_TAG, "inputCodePoint(eventSource=$eventSource, codePoint=$cp, controlDownFromEvent=$controlDownFromEvent, leftAltDownFromEvent=$leftAltDownFromEvent)")
        }

        if (mTermSession == null) return

        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        mEmulator?.setCursorBlinkState(true)

        val controlDown = controlDownFromEvent || mClient!!.readControlKey()
        val altDown = leftAltDownFromEvent || mClient!!.readAltKey()

        if (mClient!!.onCodePoint(cp, controlDown, mTermSession!!)) return

        if (controlDown) {
            cp = when {
                cp >= 'a'.code && cp <= 'z'.code -> cp - 'a'.code + 1
                cp >= 'A'.code && cp <= 'Z'.code -> cp - 'A'.code + 1
                cp == ' '.code || cp == '2'.code -> 0
                cp == '['.code || cp == '3'.code -> 27 // ^[ (Esc)
                cp == '\\'.code || cp == '4'.code -> 28
                cp == ']'.code || cp == '5'.code -> 29
                cp == '^'.code || cp == '6'.code -> 30 // control-^
                cp == '_'.code || cp == '7'.code || cp == '/'.code -> 31
                cp == '8'.code -> 127 // DEL
                else -> cp
            }
        }

        if (cp > -1) {
            // If not virtual or soft keyboard.
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                // Work around bluetooth keyboards sending funny unicode characters instead
                // of the more normal ones from ASCII that terminal programs expect.
                cp = when (cp) {
                    0x02DC -> 0x007E // SMALL TILDE -> TILDE (~)
                    0x02CB -> 0x0060 // MODIFIER LETTER GRAVE ACCENT -> GRAVE ACCENT (`)
                    0x02C6 -> 0x005E // MODIFIER LETTER CIRCUMFLEX ACCENT -> CIRCUMFLEX ACCENT (^)
                    else -> cp
                }
            }

            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            mTermSession!!.writeCodePoint(altDown, cp)
        }
    }

    /** Input the specified keyCode if applicable and return if the input was consumed. */
    fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        mEmulator?.setCursorBlinkState(true)

        if (handleKeyCodeAction(keyCode, keyMod))
            return true

        val term = mTermSession!!.emulator
        val code = KeyHandler.getCode(keyCode, keyMod, term!!.isCursorKeysApplicationMode(), term.isKeypadApplicationMode())
            ?: return false
        mTermSession!!.write(code)
        return true
    }

    fun handleKeyCodeAction(keyCode: Int, keyMod: Int): Boolean {
        val shiftDown = (keyMod and KeyHandler.KEYMOD_SHIFT) != 0

        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                // shift+page_up and shift+page_down should scroll scrollback history instead of
                // scrolling command history or changing pages
                if (shiftDown) {
                    val time = SystemClock.uptimeMillis()
                    val motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
                    doScroll(motionEvent, if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -mEmulator!!.mRows else mEmulator!!.mRows)
                    motionEvent.recycle()
                    return true
                }
            }
        }

        return false
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A [KeyEvent] describing the event.
     * @return Whether the event was handled.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient!!.logInfo(LOG_TAG, "onKeyUp(keyCode=$keyCode, event=$event)")

        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            pendingSpaceRunnable?.let { removeCallbacks(it) }
            pendingSpaceRunnable = null
            if (spaceKeyDown && !spaceDragMoved) {
                inputCodePoint(event.deviceId, ' '.code, false, false)
            }
            spaceKeyDown = false
            spaceDragActive = false
            spaceDragMoved = false
            horizontalDragAccumulator = 0f
            lastDragX = 0f
            return true
        }

        // Do not return for KEYCODE_BACK and send it to the client since user may be trying
        // to exit the activity.
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true

        if (mClient!!.onKeyUp(keyCode, event)) {
            invalidate()
            return true
        } else if (event.isSystem) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event)
        }

        return true
    }

    /**
     * This is called during layout when the size of this view has changed. If you were just added to the view
     * hierarchy, you're called with the old values of 0.
     */
override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w <= 0 || h <= 0) return
    updateSize()
}

    /** Check if the terminal size in rows and columns should be updated. */
    fun updateSize() {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0 || mTermSession == null) return

        // Set to 80 and 24 if you want to enable vttest.
        val newColumns = Math.max(4, (viewWidth / mRenderer!!.mFontWidth).toInt())
        val newRows = Math.max(4, (viewHeight - mRenderer!!.mFontLineSpacingAndAscent) / mRenderer!!.mFontLineSpacing)

        if (mEmulator == null || (newColumns != mEmulator!!.mColumns || newRows != mEmulator!!.mRows)) {
            mTermSession!!.updateSize(newColumns, newRows, mRenderer!!.mFontWidth.toInt(), mRenderer!!.mFontLineSpacing)
            mEmulator = mTermSession!!.emulator
            mClient!!.onEmulatorSet()

            // Update mTerminalCursorBlinkerRunnable inner class mEmulator on session change
            mTerminalCursorBlinkerRunnable?.setEmulator(mEmulator!!)

            // Clamped rather than zeroed. Drosh changes the grid's height on
            // purpose — the terminal's top padding follows the chrome state — and
            // zeroing here meant that every crossing of the live edge snapped the
            // user back to the newest output, which is the one thing a scrollback
            // is not for. The clamp is still needed: a shorter grid can leave the
            // old top row past the end of what the transcript still holds.
            mTopRow = mTopRow.coerceIn(-mEmulator!!.getScreen().activeTranscriptRows, 0)
            // The grid is about to be rebuilt at a new size; a sub-line offset
            // measured against the old metrics means nothing against the new.
            mScrollOffsetPx = 0f
            mResizedAtUptime = SystemClock.uptimeMillis()
            scrollTo(0, 0)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (mEmulator == null) {
            canvas.drawColor(0XFF000000.toInt())
        } else {
            // render the terminal view and highlight any selected text
            val sel = mDefaultSelectors
            mTextSelectionCursorController?.getSelectors(sel)

            mRenderer!!.render(mEmulator!!, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3], mScrollOffsetPx)

            // render the text selection handles
            renderTextSelection()
        }
    }

    val currentSession: TerminalSession?
        get() = mTermSession

    private val text: CharSequence
        get() = mEmulator!!.getScreen().getSelectedText(0, mTopRow, mEmulator!!.mColumns, mTopRow + mEmulator!!.mRows)

    fun getCursorX(x: Float): Int {
        return (x / mRenderer!!.mFontWidth).toInt()
    }

    /**
     * The row under a touch y.
     *
     * This is the same formula getColumnAndRow uses, deliberately: the two used
     * different constants, so the row chosen when a long press started a
     * selection was not the row the first drag event then landed on. The old
     * value here was a hardcoded 40 standing in for
     * mFontLineSpacingAndAscent, which is 4 at the default 14sp — roughly two
     * rows out at every font size, and different again for every other size.
     */
    fun getCursorY(y: Float): Int {
        val r = mRenderer ?: return 0
        return (((yForRowLookup(y) - r.mFontLineSpacingAndAscent) / r.mFontLineSpacing) + mTopRow).toInt()
    }

    fun getPointX(cx: Int): Int {
        val col = if (cx > mEmulator!!.mColumns) mEmulator!!.mColumns else cx
        return (col * mRenderer!!.mFontWidth).toInt()
    }

    /**
     * Inverse of [getCursorY], for placing the selection handles.
     *
     * Only the offset term is new. While a sub-line scroll is live the grid is
     * drawn shifted by mScrollOffsetPx and one line higher, because a partial
     * offset draws an extra row above the viewport; without adding both back the
     * handles were drawn over text that was not selected. The remaining origin
     * quirk — getPointY returning the row top rather than its baseline, which
     * TextSelectionHandleView compensates for by passing cy + 1 — is upstream
     * behaviour and left alone.
     */
    fun getPointY(cy: Int): Int {
        val r = mRenderer ?: return 0
        val shift = if (mScrollOffsetPx != 0f) (r.mFontLineSpacing + mScrollOffsetPx).toInt() else 0
        return (cy - mTopRow) * r.mFontLineSpacing + shift
    }

    fun getTopRow(): Int {
        return mTopRow
    }

    fun setTopRow(topRow: Int) {
        this.mTopRow = topRow
        // A programmatic row change (text selection auto-scroll) invalidates the
        // sub-line position: the offset was measured against the old row and
        // would now point past the new one.
        if (mScrollOffsetPx != 0f) {
            mScrollSettle?.let { removeCallbacks(it); mScrollSettle = null }
            mScrollOffsetPx = legalScrollOffset(topRow, 0f)
            if (!awakenScrollBars()) invalidate()
        }
    }



    /**
     * Define functions required for AutoFill API
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun autofill(value: AutofillValue) {
        if (value.isText) {
            mTermSession!!.write(value.textValue.toString())
        }

        resetAutoFill()
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun getAutofillType(): Int {
        return mAutoFillType
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun getAutofillHints(): Array<String> {
        return mAutoFillHints
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun getAutofillValue(): AutofillValue {
        return AutofillValue.forText("")
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun getImportantForAutofill(): Int {
        return mAutoFillImportance
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Synchronized
    private fun resetAutoFill() {
        // Restore none type so that AutoFill UI isn't shown anymore.
        mAutoFillType = AUTOFILL_TYPE_NONE
        mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO
        mAutoFillHints = emptyArray()
    }

    fun getAutoFillManagerService(): AutofillManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        return try {
            context?.getSystemService(AutofillManager::class.java)
        } catch (e: Exception) {
            mClient!!.logStackTraceWithMessage(LOG_TAG, "Failed to get AutofillManager service", e)
            null
        }
    }

    val isAutoFillEnabled: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

            return try {
                val autofillManager = getAutoFillManagerService()
                autofillManager?.isEnabled == true
            } catch (e: Exception) {
                mClient!!.logStackTraceWithMessage(LOG_TAG, "Failed to check if Autofill is enabled", e)
                false
            }
        }

    @Synchronized
    fun requestAutoFillUsername() {
        requestAutoFill(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(AUTOFILL_HINT_USERNAME)
            else null
        )
    }

    @Synchronized
    fun requestAutoFillPassword() {
        requestAutoFill(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(AUTOFILL_HINT_PASSWORD)
            else null
        )
    }

    @Synchronized
    fun requestAutoFill(autoFillHints: Array<String>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (autoFillHints == null || autoFillHints.isEmpty()) return

        try {
            val autofillManager = getAutoFillManagerService()
            if (autofillManager?.isEnabled == true) {
                // Update type that will be returned by `getAutofillType()` so that AutoFill UI is shown.
                mAutoFillType = AUTOFILL_TYPE_TEXT
                // Update importance that will be returned by `getImportantForAutofill()` so that
                // AutoFill considers the view as important.
                mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_YES
                // Update hints that will be returned by `getAutofillHints()` for which to show AutoFill UI.
                mAutoFillHints = autoFillHints
                autofillManager.requestAutofill(this)
            }
        } catch (e: Exception) {
            mClient!!.logStackTraceWithMessage(LOG_TAG, "Failed to request Autofill", e)
        }
    }

    @Synchronized
    fun cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mAutoFillType == AUTOFILL_TYPE_NONE) return

        try {
            val autofillManager = getAutoFillManagerService()
            if (autofillManager?.isEnabled == true) {
                resetAutoFill()
                autofillManager.cancel()
            }
        } catch (e: Exception) {
            mClient!!.logStackTraceWithMessage(LOG_TAG, "Failed to cancel Autofill request", e)
        }
    }



    /**
     * Set terminal cursor blinker rate. It must be between [TERMINAL_CURSOR_BLINK_RATE_MIN]
     * and [TERMINAL_CURSOR_BLINK_RATE_MAX], otherwise it will be disabled.
     *
     * @param blinkRate The value to set.
     * @return Returns `true` if setting blinker rate was successfully set, otherwise `false`.
     */
    @Synchronized
    fun setTerminalCursorBlinkerRate(blinkRate: Int): Boolean {
        val result: Boolean

        // If cursor blinking rate is not valid
        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mClient!!.logError(LOG_TAG, "The cursor blink rate must be in between $TERMINAL_CURSOR_BLINK_RATE_MIN-$TERMINAL_CURSOR_BLINK_RATE_MAX: $blinkRate")
            mTerminalCursorBlinkerRate = 0
            result = false
        } else {
            mClient!!.logVerbose(LOG_TAG, "Setting cursor blinker rate to $blinkRate")
            mTerminalCursorBlinkerRate = blinkRate
            result = true
        }

        if (mTerminalCursorBlinkerRate == 0) {
            mClient!!.logVerbose(LOG_TAG, "Cursor blinker disabled")
            stopTerminalCursorBlinker()
        }

        return result
    }

    /**
     * Sets whether cursor blinker should be started or stopped.
     */
    @Synchronized
    fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
        // Stop any existing cursor blinker callbacks
        stopTerminalCursorBlinker()

        if (mEmulator == null) return

        mEmulator!!.setCursorBlinkingEnabled(false)

        if (start) {
            // If cursor blinker is not enabled or is not valid
            if (mTerminalCursorBlinkerRate < TERMINAL_CURSOR_BLINK_RATE_MIN || mTerminalCursorBlinkerRate > TERMINAL_CURSOR_BLINK_RATE_MAX)
                return
            // If cursor blinder is to be started only if cursor is enabled
            else if (startOnlyIfCursorEnabled && !mEmulator!!.isCursorEnabled()) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                    mClient!!.logVerbose(LOG_TAG, "Ignoring call to start cursor blinker since cursor is not enabled")
                return
            }

            // Start cursor blinker runnable
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient!!.logVerbose(LOG_TAG, "Starting cursor blinker with the blink rate $mTerminalCursorBlinkerRate")
            if (mTerminalCursorBlinkerHandler == null)
                mTerminalCursorBlinkerHandler = Handler(Looper.getMainLooper())
            mTerminalCursorBlinkerRunnable = TerminalCursorBlinkerRunnable(mEmulator!!, mTerminalCursorBlinkerRate)
            mEmulator!!.setCursorBlinkingEnabled(true)
            mTerminalCursorBlinkerRunnable!!.run()
        }
    }

    /**
     * Cancel the terminal cursor blinker callbacks
     */
    private fun stopTerminalCursorBlinker() {
        if (mTerminalCursorBlinkerHandler != null && mTerminalCursorBlinkerRunnable != null) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient!!.logVerbose(LOG_TAG, "Stopping cursor blinker")
            mTerminalCursorBlinkerHandler!!.removeCallbacks(mTerminalCursorBlinkerRunnable!!)
        }
    }

    private inner class TerminalCursorBlinkerRunnable(
        private var mEmulator: TerminalEmulator,
        private val mBlinkRate: Int
    ) : Runnable {

        // Initialize with false so that initial blink state is visible after toggling
        private var mCursorVisible = false

        fun setEmulator(emulator: TerminalEmulator) {
            mEmulator = emulator
        }

        override fun run() {
            try {
                // Toggle the blink state and then invalidate() the view so
                // that onDraw() is called, which then calls TerminalRenderer.render()
                // which checks with TerminalEmulator.shouldCursorBeVisible() to decide whether
                // to draw the cursor or not
                mCursorVisible = !mCursorVisible
                mEmulator.setCursorBlinkState(mCursorVisible)
                invalidate()
            } finally {
                // Recall the Runnable after mBlinkRate milliseconds to toggle the blink state
                mTerminalCursorBlinkerHandler!!.postDelayed(this, mBlinkRate.toLong())
            }
        }
    }



    /**
     * Define functions required for text selection and its handles.
     */
    fun getTextSelectionCursorController(): TextSelectionCursorController {
        if (mTextSelectionCursorController == null) {
            mTextSelectionCursorController = TextSelectionCursorController(this)
            applySelectionMenuTo(mTextSelectionCursorController!!)

            val observer = viewTreeObserver
            observer?.addOnTouchModeChangeListener(mTextSelectionCursorController)
        }

        return mTextSelectionCursorController!!
    }

    private fun showTextSelectionCursors(event: MotionEvent) {
        getTextSelectionCursorController().show(event)
    }

    private fun hideTextSelectionCursors(): Boolean {
        return getTextSelectionCursorController().hide()
    }

    private fun renderTextSelection() {
        mTextSelectionCursorController?.render()
    }

    val isSelectingText: Boolean
        get() = mTextSelectionCursorController?.isActive() == true

    /** Get the currently selected text if selecting. */
    val selectedText: String?
        get() = if (isSelectingText && mTextSelectionCursorController != null)
            mTextSelectionCursorController!!.getSelectedText()
        else
            null

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    val storedSelectedText: String?
        get() = mTextSelectionCursorController?.storedSelectedText

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    fun unsetStoredSelectedText() {
        mTextSelectionCursorController?.unsetStoredSelectedText()
    }

    private val textSelectionActionMode: ActionMode?
        get() = mTextSelectionCursorController?.getActionMode()

    fun startTextSelectionMode(event: MotionEvent) {
        // Selecting is not typing. A long press is often the first thing that
        // touches the screen, so with the default on this would have raised the
        // keyboard on exactly the gesture that has nothing to do with it.
        raiseKeyboardOnFocus = false
        if (!requestFocus()) {
            return
        }

        showTextSelectionCursors(event)
        mClient!!.copyModeChanged(isSelectingText)

        invalidate()
    }

    fun stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            mClient!!.copyModeChanged(isSelectingText)
            invalidate()
        }
    }

    private fun decrementYTextSelectionCursors(decrement: Int) {
        mTextSelectionCursorController?.decrementYTextSelectionCursors(decrement)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (mTextSelectionCursorController != null) {
            viewTreeObserver.addOnTouchModeChangeListener(mTextSelectionCursorController)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (mTextSelectionCursorController != null) {
            // Might solve the following exception
            // android.view.WindowLeaked: Activity com.termux.app.TermuxActivity has leaked window android.widget.PopupWindow
            stopTextSelectionMode()

            viewTreeObserver.removeOnTouchModeChangeListener(mTextSelectionCursorController)
            mTextSelectionCursorController!!.onDetached()
        }
    }



    /**
     * Define functions required for long hold toolbar.
     */
    private val mShowFloatingToolbar = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            textSelectionActionMode?.hide(0) // hide off.
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun showFloatingToolbar() {
        if (textSelectionActionMode != null) {
            val delay = ViewConfiguration.getDoubleTapTimeout()
            postDelayed(mShowFloatingToolbar, delay.toLong())
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    fun hideFloatingToolbar() {
        if (textSelectionActionMode != null) {
            removeCallbacks(mShowFloatingToolbar)
            textSelectionActionMode!!.hide(-1)
        }
    }

    fun updateFloatingToolbarVisibility(event: MotionEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && textSelectionActionMode != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> hideFloatingToolbar()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showFloatingToolbar()
            }
        }
    }

    // ==================== KLAVYE AÇ/KAPA (GÜNCELLENDİ) ====================

    fun showKeyboard() {
        try {
            raiseKeyboardOnFocus = true
            requestFocusFromTouch()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Log.e("TerminalView", "showKeyboard failed", e)
        }
    }

    fun hideKeyboard() {
        try {
            raiseKeyboardOnFocus = false
            if (!isAttachedToWindow) {
                Log.w("TerminalView", "View is not attached to window, cannot hide keyboard")
                return
            }
            if (width <= 0 || height <= 0) {
                Log.w("TerminalView", "View has zero size, cannot hide keyboard")
                return
            }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = windowToken
            if (token != null) {
                imm.hideSoftInputFromWindow(token, 0)
            }
        } catch (e: Exception) {
            Log.e("TerminalView", "hideKeyboard failed", e)
        }
    }

    companion object {
        /** Log terminal view key and IME events. */
        private var TERMINAL_VIEW_KEY_LOGGING_ENABLED = false

        const val TERMINAL_CURSOR_BLINK_RATE_MIN = 100
        const val TERMINAL_CURSOR_BLINK_RATE_MAX = 2000

        /** The [KeyEvent] is generated from a virtual keyboard, like manually with the [KeyEvent] constructor. */
        const val KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD // -1

        /** The [KeyEvent] is generated from a non-physical device, like if 0 value is returned by [KeyEvent.getDeviceId]. */
        const val KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0

         private const val LOG_TAG = "TerminalView"

        private const val SPACE_DRAG_TIMEOUT_MS: Long = 500

        /** Upper bound on text read back by getTextBeforeCursor. */
        private const val TYPED_MIRROR_MAX = 256

        /**
         * Arrow events per batch when moving the cursor a long way, so a large
         * jump does not turn into hundreds of separate PTY writes.
         */
        private const val CURSOR_MOVE_BATCH = 16

        /**
         * Font size in px used when a typeface arrives before any size.
         *
         * Roughly 9sp at the default density — small enough to be replaced
         * immediately by the caller's own [setTextSize], and a real value
         * rather than zero, which would collapse the grid to no rows.
         */
        private const val DEFAULT_TEXT_SIZE = 24
     }
}