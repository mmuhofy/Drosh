package com.termux.view.textselection

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.isSelectionSeparator
import com.termux.terminal.WcWidth
import dev.drosh.terminal.R
import com.termux.view.TerminalView

class TextSelectionCursorController(private val terminalView: TerminalView) : CursorController {

    private val mStartHandle: TextSelectionHandleView = TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT)
    private val mEndHandle: TextSelectionHandleView = TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT)
    private var mStoredSelectedText: String? = null
    private var mIsSelectingText = false
    private var mShowStartTime = System.currentTimeMillis()

    private val mHandleHeight: Int = maxOf(mStartHandle.handleHeight, mEndHandle.handleHeight)
    private var mSelX1 = -1
    private var mSelX2 = -1
    private var mSelY1 = -1
    private var mSelY2 = -1

    // Exposed so the view can report where the selection is for a menu that is
    // not a platform ActionMode.
    val selX1: Int get() = mSelX1
    val selX2: Int get() = mSelX2
    val selY1: Int get() = mSelY1
    val selY2: Int get() = mSelY2

    /** Fires whenever the selection moves or changes size. */
    var onChanged: (() -> Unit)? = null

    /** Selects a whole rectangle of the visible grid. */
    fun selectAll(x1: Int, y1: Int, x2: Int, y2: Int) {
        if (!isActive()) return
        mSelX1 = x1
        mSelY1 = y1
        mSelX2 = x2
        mSelY2 = y2
        render()
        onChanged?.invoke()
    }

    private var mActionMode: ActionMode? = null
    val ACTION_COPY = 1
    val ACTION_PASTE = 2
    val ACTION_MORE = 3

    override fun show(event: MotionEvent) {
        setInitialTextSelectionPosition(event)
        mStartHandle.positionAtCursor(mSelX1, mSelY1, true)
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, true)

        setActionModeCallBacks()
        mShowStartTime = System.currentTimeMillis()
        mIsSelectingText = true
        onChanged?.invoke()
    }

    override fun hide(): Boolean {
        if (!isActive()) return false

        // prevent hide calls right after a show call, like long pressing the down key
        // 300ms seems long enough that it wouldn't cause hide problems if action button
        // is quickly clicked after the show, otherwise decrease it
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false
        }

        mStartHandle.hide()
        mEndHandle.hide()

        mActionMode?.finish()

        mSelY2 = -1
        mSelX2 = mSelY2
        mSelY1 = mSelX2
        mSelX1 = mSelY1
        mIsSelectingText = false
        onChanged?.invoke()

        return true
    }

    override fun render() {
        if (!isActive()) return

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false)
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false)

        mActionMode?.invalidate()
        onChanged?.invoke()
    }

    fun setInitialTextSelectionPosition(event: MotionEvent) {
        val columnAndRow = terminalView.getColumnAndRow(event, true)
        mSelX2 = columnAndRow[0]
        mSelX1 = mSelX2
        mSelY2 = columnAndRow[1]
        mSelY1 = mSelY2

        val screen = terminalView.mEmulator!!.getScreen()

        // Expand to the word around the touch. The boundary test is
        // isSelectionSeparator, not "is not a space": a TUI draws its frames
        // with box and block characters, none of which is a space, so the old
        // test made a whole border one word and a tap near it selected the
        // frame instead of the label inside it.
        fun isSeparatorAt(x: Int): Boolean {
            if (x < 0 || x >= terminalView.mEmulator!!.mColumns) return true
            val cell = screen.getSelectedText(x, mSelY1, x, mSelY1)
            return cell.isEmpty() || isSelectionSeparator(cell[0])
        }

        if (!isSeparatorAt(mSelX2)) {
            while (mSelX1 > 0 && !isSeparatorAt(mSelX1 - 1)) mSelX1--
            while (mSelX2 < terminalView.mEmulator!!.mColumns - 1 && !isSeparatorAt(mSelX2 + 1)) mSelX2++
        }
    }

    /**
     * False when the app supplies its own selection menu.
     *
     * A platform ActionMode draws Android's own floating toolbar, which cannot
     * carry the app's design system or a blur, and its positioning is only
     * reachable while the mode is alive.
     */
    var usePlatformActionMode: Boolean = true

    fun setActionModeCallBacks() {
        if (!usePlatformActionMode) {
            mActionMode = null
            return
        }
        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val show = MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT

                val clipboard = terminalView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.copy_text).setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, R.string.paste_text)
                    .setEnabled(clipboard?.hasPrimaryClip() == true).setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_MORE, Menu.NONE, R.string.text_selection_more)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (!isActive()) {
                    // Fix issue where the dialog is pressed while being dismissed.
                    return true
                }

                when (item.itemId) {
                    ACTION_COPY -> {
                        val selectedText = getSelectedText()
                        terminalView.mTermSession!!.onCopyTextToClipboard(selectedText)
                        terminalView.stopTextSelectionMode()
                    }
                    ACTION_PASTE -> {
                        terminalView.stopTextSelectionMode()
                        terminalView.mTermSession!!.onPasteTextFromClipboard()
                    }
                    ACTION_MORE -> {
                        // We first store the selected text in case TerminalViewClient needs the
                        // selected text before MORE button was pressed since we are going to
                        // stop selection mode
                        mStoredSelectedText = getSelectedText()
                        // The text selection needs to be stopped before showing context menu,
                        // otherwise handles will show above popup
                        terminalView.stopTextSelectionMode()
                        terminalView.showContextMenu()
                    }
                }

                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {}
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mActionMode = terminalView.startActionMode(callback)
            return
        }

        mActionMode = terminalView.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                return callback.onCreateActionMode(mode, menu)
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {}

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                var x1 = (mSelX1 * terminalView.mRenderer!!.mFontWidth).toInt()
                var x2 = (mSelX2 * terminalView.mRenderer!!.mFontWidth).toInt()
                // The selection's own rectangle is drawn by the renderer and
                // moves with the grid already; this is only the popup anchor, and
                // it needs the same sub-line offset applied by hand.
                val offset = terminalView.mScrollOffsetPx
                val y1 = (mSelY1 - 1 - terminalView.getTopRow()) * terminalView.mRenderer!!.mFontLineSpacing + offset.toInt()
                val y2 = (mSelY2 + 1 - terminalView.getTopRow()) * terminalView.mRenderer!!.mFontLineSpacing + offset.toInt()

                if (x1 > x2) {
                    val tmp = x1
                    x1 = x2
                    x2 = tmp
                }

                val terminalBottom = terminalView.bottom
                var top = y1 + mHandleHeight
                var bottom = y2 + mHandleHeight
                if (top > terminalBottom) top = terminalBottom
                if (bottom > terminalBottom) bottom = terminalBottom

                outRect.set(x1, top, x2, bottom)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    override fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int) {
        val screen = terminalView.mEmulator!!.getScreen()
        val scrollRows = screen.activeRows - terminalView.mEmulator!!.mRows
        if (handle === mStartHandle) {
            mSelX1 = terminalView.getCursorX(x.toFloat())
            mSelY1 = terminalView.getCursorY(y.toFloat())
            if (mSelX1 < 0) {
                mSelX1 = 0
            }

            if (mSelY1 < -scrollRows) {
                mSelY1 = -scrollRows
            } else if (mSelY1 > terminalView.mEmulator!!.mRows - 1) {
                mSelY1 = terminalView.mEmulator!!.mRows - 1
            }

            if (mSelY1 > mSelY2) {
                mSelY1 = mSelY2
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX1 = mSelX2
            }

            if (!terminalView.mEmulator!!.isAlternateBufferActive()) {
                val topRow = terminalView.getTopRow()
                val wanted = when {
                    mSelY1 <= topRow -> maxOf(topRow - 1, -scrollRows)
                    mSelY1 >= topRow + terminalView.mEmulator!!.mRows -> minOf(topRow + 1, 0)
                    else -> topRow
                }
                // Only when it actually has to move. setTopRow discards the
                // sub-line scroll offset, so calling it on every drag event
                // meant simply grabbing a handle snapped the grid onto a whole
                // row. Dragging inside the viewport must not touch it.
                if (wanted != topRow) terminalView.setTopRow(wanted)
            }

            mSelX1 = getValidCurX(screen, mSelY1, mSelX1)
        } else {
            mSelX2 = terminalView.getCursorX(x.toFloat())
            mSelY2 = terminalView.getCursorY(y.toFloat())
            if (mSelX2 < 0) {
                mSelX2 = 0
            }

            if (mSelY2 < -scrollRows) {
                mSelY2 = -scrollRows
            } else if (mSelY2 > terminalView.mEmulator!!.mRows - 1) {
                mSelY2 = terminalView.mEmulator!!.mRows - 1
            }

            if (mSelY1 > mSelY2) {
                mSelY2 = mSelY1
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX2 = mSelX1
            }

            if (!terminalView.mEmulator!!.isAlternateBufferActive()) {
                val topRow = terminalView.getTopRow()
                val wanted = when {
                    mSelY2 <= topRow -> maxOf(topRow - 1, -scrollRows)
                    mSelY2 >= topRow + terminalView.mEmulator!!.mRows -> minOf(topRow + 1, 0)
                    else -> topRow
                }
                if (wanted != topRow) terminalView.setTopRow(wanted)
            }

            mSelX2 = getValidCurX(screen, mSelY2, mSelX2)
        }

        terminalView.invalidate()
    }

    private fun getValidCurX(screen: TerminalBuffer, cy: Int, cx: Int): Int {
        val line = screen.getSelectedText(0, cy, cx, cy)
        if (!TextUtils.isEmpty(line)) {
            var col = 0
            var i = 0
            val len = line.length
            while (i < len) {
                val ch1 = line[i]
                if (ch1.code == 0) {
                    break
                }

                val wc: Int
                if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                    val ch2 = line[++i]
                    wc = WcWidth.width(Character.toCodePoint(ch1, ch2))
                } else {
                    wc = WcWidth.width(ch1.code)
                }

                val cend = col + wc
                if (cx > col && cx < cend) {
                    return cend
                }
                if (cend == col) {
                    return col
                }
                col = cend
                i++
            }
        }
        return cx
    }

    fun decrementYTextSelectionCursors(decrement: Int) {
        mSelY1 -= decrement
        mSelY2 -= decrement
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchModeChanged(isInTouchMode: Boolean) {
        if (!isInTouchMode) {
            terminalView.stopTextSelectionMode()
        }
    }

    override fun onDetached() {}

    override fun isActive(): Boolean = mIsSelectingText

    fun getSelectors(sel: IntArray?) {
        if (sel == null || sel.size != 4) {
            return
        }

        sel[0] = mSelY1
        sel[1] = mSelY2
        sel[2] = mSelX1
        sel[3] = mSelX2
    }

    /** Get the currently selected text. */
    fun getSelectedText(): String {
        return terminalView.mEmulator!!.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2)
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    val storedSelectedText: String?
        get() = mStoredSelectedText

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    fun unsetStoredSelectedText() {
        mStoredSelectedText = null
    }

    fun getActionMode(): ActionMode? = mActionMode

    /** @return true if this controller is currently used to move the start selection. */
    val isSelectionStartDragged: Boolean
        get() = mStartHandle.isDragging

    /** @return true if this controller is currently used to move the end selection. */
    val isSelectionEndDragged: Boolean
        get() = mEndHandle.isDragging
}
