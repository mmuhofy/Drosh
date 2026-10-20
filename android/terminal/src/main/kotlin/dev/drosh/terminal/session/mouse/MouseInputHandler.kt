/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.drosh.terminal.session.mouse

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView

/**
 * Screen-side half of the mouse pad bridge: turns the Drosh Keyboard's
 * private input commands into real mouse events on the terminal emulator,
 * and owns the on-screen pointer.
 *
 * Why the app and not the IME: an IME window only covers the keyboard area,
 * so it cannot draw a pointer over the terminal, and it may not inject touch
 * events into another app. The emulator, meanwhile, already knows everything
 * a mouse needs — the active mouse protocol (SGR vs X10), the screen metrics
 * and the cursor anchor — so the translation happens where the knowledge is.
 *
 * Two behaviors, chosen by the running application:
 * - mouse tracking enabled (vim, tmux, htop, fzf, …): a click produces real
 *   press/release events at the pointer cell.
 * - mouse tracking disabled (plain shell): a click *places the cursor*, but
 *   only on the editable part of the prompt line — the OSC 133 prompt-start
 *   mark says where the command begins, and the movement itself goes through
 *   arrow keys (via [TerminalView.handleKeyCode]), so the shell's own line
 *   editor stays in sync. Historical output and the prompt itself never move
 *   the cursor.
 *
 * The pointer is a hollow box sized from the renderer metrics, so it follows
 * the zoom level. Pushing it against the top or bottom edge auto-scrolls the
 * scrollback (shell only — a TUI redraws its own screen instead).
 *
 * The wire protocol mirrors `dev.drosh.ime.keyboard.mouse.MouseProtocol`; both
 * sides are signed with the same key, so no third party can reach it either.
 *
 * UNTESTED — verify on device.
 */
object MouseInputHandler {

    const val ACTION = "dev.drosh.MOUSE"

    const val OP_OPEN = "open"
    const val OP_CLOSE = "close"
    const val OP_MOVE = "move"
    const val OP_CLICK = "click"

    const val KEY_OP = "op"
    const val KEY_DX = "dx"
    const val KEY_DY = "dy"
    const val KEY_BUTTON = "button"

    private const val BUTTON_LEFT = 0
    private const val BUTTON_RIGHT = 2

    /** Pixels of pad travel per terminal cell. App-side sensitivity. */
    private const val CELL_PX_PER_DP = 13f

    /** Mild acceleration, matching the prototype: fast drags cover more. */
    private const val ACCEL_DIVISOR = 70f

    private const val EDGE_DWELL_MS = 250L
    private const val EDGE_SCROLL_FIRST_MS = 110L
    private const val EDGE_SCROLL_MIN_MS = 30L
    private const val EDGE_IDLE_STOP_MS = 250L

    private var terminalView: TerminalView? = null
    private var pointerView: View? = null

    private var visible = false
    private var col = 0
    private var row = 0
    private var accX = 0f
    private var accY = 0f
    private var cellPx = CELL_PX_PER_DP

    /** -1 = pressed against the top edge, +1 = against the bottom, 0 = inside. */
    private var edgePush = 0
    private var edgeSince = 0L
    private var autoScrolling = false
    private var nextScrollAt = 0L
    private var lastMoveAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scrollTick = object : Runnable {
        override fun run() {
            val view = terminalView ?: return
            val emulator = emulator() ?: return
            if (!visible || edgePush == 0 || emulator.isMouseTrackingActive()) {
                stopAutoScroll()
                return
            }
            // still receiving pad movement? a lifted finger stops sending
            if (System.currentTimeMillis() - lastMoveAt > EDGE_IDLE_STOP_MS) {
                stopAutoScroll()
                return
            }
            val top = view.getTopRow()
            val limit = -emulator.getScreen().activeTranscriptRows
            val next = (top - edgePush).coerceIn(limit, 0)
            if (next != top) view.setTopRow(next)
            val delay = (if (nextScrollAt == 0L) EDGE_SCROLL_FIRST_MS else nextScrollAt)
                .coerceAtMost(EDGE_SCROLL_MIN_MS)
            nextScrollAt = (delay * 0.85f).toLong().coerceAtLeast(EDGE_SCROLL_MIN_MS)
            mainHandler.postDelayed(this, delay)
        }
    }

    /** True while the pad owns mouse input; the terminal ignores its own. */
    fun isActive(): Boolean = visible

    fun attach(view: TerminalView, pointer: View) {
        terminalView = view
        pointerView = pointer
        cellPx = CELL_PX_PER_DP * view.resources.displayMetrics.density
    }

    fun detach() {
        if (visible) close()
        terminalView = null
        pointerView = null
    }

    fun handleCommand(action: String?, data: Bundle?): Boolean {
        if (action != ACTION || data == null) return false
        val emulator = emulator() ?: return false
        when (data.getString(KEY_OP)) {
            OP_OPEN -> open(emulator)
            OP_CLOSE -> close()
            OP_MOVE -> move(
                data.getInt(KEY_DX, 0),
                data.getInt(KEY_DY, 0),
            )
            OP_CLICK -> click(emulator, data.getInt(KEY_BUTTON, BUTTON_LEFT))
            else -> return false
        }
        return true
    }

    private fun emulator(): TerminalEmulator? = terminalView?.emulatorForMouse

    private fun open(emulator: TerminalEmulator) {
        visible = true
        accX = 0f
        accY = 0f
        edgePush = 0
        stopAutoScroll()
        // anchor on the emulator cursor: where the shell already is
        col = emulator.getCursorCol().coerceIn(0, emulator.mColumns - 1)
        row = emulator.getCursorRow().coerceIn(0, emulator.mRows - 1)
        pointerView?.visibility = View.VISIBLE
        positionPointer(emulator)
    }

    private fun close() {
        stopAutoScroll()
        visible = false
        pointerView?.visibility = View.GONE
    }

    private fun move(dxPx: Int, dyPx: Int) {
        if (!visible) return
        val emulator = emulator() ?: return
        lastMoveAt = System.currentTimeMillis()
        if (dxPx == 0 && dyPx == 0) {
            // keep-alive: the finger is still down but not moving. Keep the
            // edge state exactly as it is so a hold against the edge keeps
            // auto-scrolling instead of resetting.
            return
        }
        accX += dxPx * (1f + kotlin.math.abs(dxPx) / ACCEL_DIVISOR)
        accY += dyPx * (1f + kotlin.math.abs(dyPx) / ACCEL_DIVISOR)
        var stepX = 0
        var stepY = 0
        while (accX >= cellPx) { stepX++; accX -= cellPx }
        while (accX <= -cellPx) { stepX--; accX += cellPx }
        while (accY >= cellPx) { stepY++; accY -= cellPx }
        while (accY <= -cellPx) { stepY--; accY += cellPx }

        col = (col + stepX).coerceIn(0, emulator.mColumns - 1)
        row = (row + stepY).coerceIn(0, emulator.mRows - 1)
        positionPointer(emulator)

        // Edge push: the pointer is pinned and the finger keeps dragging that
        // way — that is the auto-scroll gesture. Only with the shell's
        // scrollback; a TUI redraws its own screen and would fight us.
        val tracking = emulator.isMouseTrackingActive()
        edgePush = when {
            tracking -> 0
            dyPx < 0 && row <= 0 -> -1
            dyPx > 0 && row >= emulator.mRows - 1 -> 1
            stepY < 0 && row <= 0 -> -1
            stepY > 0 && row >= emulator.mRows - 1 -> 1
            else -> 0
        }
        if (edgePush != 0) {
            if (edgeSince == 0L) edgeSince = lastMoveAt
            if (!autoScrolling && lastMoveAt - edgeSince >= EDGE_DWELL_MS) {
                startAutoScroll()
            }
        } else {
            edgeSince = 0L
            stopAutoScroll()
        }
    }

    private fun startAutoScroll() {
        if (autoScrolling) return
        autoScrolling = true
        nextScrollAt = 0L
        mainHandler.postDelayed(scrollTick, EDGE_DWELL_MS)
    }

    private fun stopAutoScroll() {
        autoScrolling = false
        edgeSince = 0L
        mainHandler.removeCallbacks(scrollTick)
    }

    private fun click(emulator: TerminalEmulator, button: Int) {
        if (!visible) return
        if (emulator.isMouseTrackingActive()) {
            // a real mouse event; the running app decides what a click means
            emulator.sendMouseEvent(button, col + 1, row + 1, true)
            emulator.sendMouseEvent(button, col + 1, row + 1, false)
            return
        }
        // plain shell: place the cursor — but only on the editable part of
        // the prompt line. OSC 133 tells us where the command starts; without
        // it (dash, sh) fall back to the cursor's own row, where the shell's
        // line editor clamps the movement on its own.
        val integration = terminalView?.mouseSession?.shellIntegration
        val promptRow = integration?.promptStartRow ?: -1
        val promptCol = integration?.promptStartCol ?: -1
        val cursorRow = emulator.getCursorRow()
        val rowOk = if (promptRow >= 0) row == promptRow && row == cursorRow else row == cursorRow
        val colOk = promptCol < 0 || col >= promptCol
        if (!rowOk || !colOk) return
        moveCursorByColumns(emulator, col - emulator.getCursorCol())
    }

    /**
     * Moves the shell cursor by sending arrow keys rather than moving the
     * emulator cursor: the arrow escape sequences are chosen by the emulator
     * (application cursor mode and all), and the shell's line editor is what
     * actually moves — so the two never disagree.
     */
    private fun moveCursorByColumns(emulator: TerminalEmulator, delta: Int) {
        val view = terminalView ?: return
        if (delta == 0) return
        val keyCode =
            if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val count = minOf(kotlin.math.abs(delta), 512)
        repeat(count) { view.handleKeyCode(keyCode, 0) }
    }

    /**
     * Hollow box on the pointer cell, sized from the renderer metrics so it
     * follows the zoom level. Re-measured on every move: cheap, and it keeps
     * the box correct across a pinch-zoom without extra wiring.
     */
    private fun positionPointer(emulator: TerminalEmulator) {
        val view = terminalView ?: return
        val pointer = pointerView ?: return
        val renderer = view.rendererForMouse ?: return
        val fontWidth = renderer.mFontWidth
        val lineSpacing = renderer.mFontLineSpacing
        if (fontWidth <= 0f || lineSpacing <= 0) return

        val density = view.resources.displayMetrics.density
        // one cell wide like the shell's block cursor, but taller: the mouse
        // pointer reads as a cursor, not as a character cell
        val boxWidth = (fontWidth + 1.5f * density).toInt()
        val boxHeight = (lineSpacing * 1.3f).toInt()
        if (pointer.layoutParams == null ||
            pointer.layoutParams.width != boxWidth ||
            pointer.layoutParams.height != boxHeight
        ) {
            pointer.layoutParams = android.widget.FrameLayout.LayoutParams(boxWidth, boxHeight)
        }
        pointer.x = col * fontWidth - (boxWidth - fontWidth) / 2f
        pointer.y = row * lineSpacing - (boxHeight - lineSpacing) / 2f
    }
}
