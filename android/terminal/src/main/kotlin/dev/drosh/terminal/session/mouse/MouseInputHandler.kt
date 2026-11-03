/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// Ported from: land/mouse-pad — dev/drosh/terminal/mouse/MouseInputHandler.kt
// Adapted for Drosh — dev.drosh
// Changes: the vendored Termux emulator is gone; the session is the Rust
// engine's (mouse reports through `Mouse.report`, cursor and modes through
// the state array), and the pointer is measured from the renderer's own cell
// metrics.

package dev.drosh.terminal.session.mouse

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.input.Encoder
import dev.drosh.terminal.engine.input.Mouse
import dev.drosh.terminal.engine.view.TerminalView

/**
 * Screen-side half of the mouse pad bridge: turns Drosh Keyboard's private
 * input commands into real mouse events, and owns the on-screen pointer.
 *
 * Why the app and not the IME: an IME window only covers the keyboard area,
 * so it cannot draw a pointer over the terminal, and it may not inject touch
 * events into another app. The session, meanwhile, already knows everything a
 * mouse needs — the active mouse protocol (SGR vs X10), the screen metrics
 * and the cursor anchor — so the translation happens where the knowledge is.
 *
 * Two behaviors, chosen by the running application:
 * - mouse tracking enabled (vim, tmux, htop, fzf, …): a click produces real
 *   press/release reports at the pointer cell.
 * - mouse tracking disabled (plain shell): a click *places the shell's
 *   cursor*, but only on the cursor's own row — the movement goes through the
 *   arrow sequences (`Encoder.sequence`), so the shell's line editor stays in
 *   sync and nothing in the output can be edited. The OSC 133 prompt-start
 *   column is not carried yet; the shell clamps the arrows at the prompt on
 *   its own, which is what the old prompt-start guard approximated.
 *
 * The pointer is a hollow box sized from the renderer's cell metrics, so it
 * follows the zoom level. Pushing it against the top or bottom edge
 * auto-scrolls the scrollback (shell only — a TUI redraws its own screen).
 *
 * The wire protocol mirrors `dev.drosh.ime.keyboard.mouse.MouseProtocol`;
 * `performPrivateCommand` only reaches the app the IME is editing into.
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
            val session = view.session
            if (!visible || edgePush == 0 || tracking(view)) {
                stopAutoScroll()
                return
            }
            // still receiving pad movement? a lifted finger stops sending
            if (System.currentTimeMillis() - lastMoveAt > EDGE_IDLE_STOP_MS) {
                stopAutoScroll()
                return
            }
            // One cell per tick, in the direction the finger is pushing: the
            // session's own remainder accumulates, so a slow edge-dwell still
            // advances.
            if (!view.hasCellMetrics) return
            val cell = view.metrics.height.coerceAtLeast(1f)
            session.scrollByPixels(-edgePush * cell, cell.toInt())
            view.requestRender()
            val delay = (if (nextScrollAt == 0L) EDGE_SCROLL_FIRST_MS else nextScrollAt)
                .coerceAtLeast(EDGE_SCROLL_MIN_MS)
            nextScrollAt = (delay * 0.85f).toLong().coerceAtLeast(EDGE_SCROLL_MIN_MS)
            mainHandler.postDelayed(this, delay)
        }
    }

    /** True while the pad owns mouse input. */
    fun isActive(): Boolean = visible

    fun attach(view: TerminalView, pointer: View) {
        terminalView = view
        pointerView = pointer
        cellPx = CELL_PX_PER_DP * view.resources.displayMetrics.density
    }

    fun detach() {
        if (visible) {
            close()
        }
        terminalView = null
        pointerView = null
    }

    /** A private command from the IME; `true` when this one consumed it. */
    fun handleCommand(action: String?, data: Bundle?): Boolean {
        if (action != ACTION || data == null) return false
        when (data.getString(KEY_OP)) {
            OP_OPEN -> open()
            OP_CLOSE -> close()
            OP_MOVE -> move(
                data.getInt(KEY_DX, 0),
                data.getInt(KEY_DY, 0),
            )
            OP_CLICK -> click(data.getInt(KEY_BUTTON, BUTTON_LEFT))
            else -> return false
        }
        return true
    }

    private fun tracking(view: TerminalView): Boolean =
        view.modes() and NativeCore.MODE_MOUSE_MODE != 0

    private fun open() {
        val view = terminalView ?: return
        visible = true
        accX = 0f
        accY = 0f
        edgePush = 0
        stopAutoScroll()
        // anchor on the session cursor: where the shell already is
        col = view.session.stateArray[NativeCore.STATE_CURSOR_COL]
            .coerceIn(0, view.session.columns - 1)
        row = view.session.stateArray[NativeCore.STATE_CURSOR_ROW]
            .coerceIn(0, view.session.rows - 1)
        pointerView?.visibility = View.VISIBLE
        positionPointer()
    }

    private fun close() {
        stopAutoScroll()
        visible = false
        pointerView?.visibility = View.GONE
    }

    private fun move(dxPx: Int, dyPx: Int) {
        if (!visible) return
        val view = terminalView ?: return
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

        val view0 = view
        col = (col + stepX).coerceIn(0, view0.session.columns - 1)
        row = (row + stepY).coerceIn(0, view0.session.rows - 1)
        positionPointer()

        // Edge push: the pointer is pinned and the finger keeps dragging that
        // way — that is the auto-scroll gesture. Only with the shell's
        // scrollback; a TUI redraws its own screen and would fight us.
        val tracking = tracking(view0)
        edgePush = when {
            tracking -> 0
            dyPx < 0 && row <= 0 -> -1
            dyPx > 0 && row >= view0.session.rows - 1 -> 1
            stepY < 0 && row <= 0 -> -1
            stepY > 0 && row >= view0.session.rows - 1 -> 1
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

    private fun click(button: Int) {
        if (!visible) return
        val view = terminalView ?: return
        val session = view.session
        if (tracking(view)) {
            // a real mouse report; the running app decides what a click means
            val modes = session.modes()
            Mouse.report(button, true, col, row, modes)?.let(session::write)
            Mouse.report(button, false, col, row, modes)?.let(session::write)
            view.requestRender()
            return
        }
        // plain shell: place the shell's cursor — but only on the cursor's own
        // row, which is the editable part of the prompt line. The arrows are
        // chosen by the encoder (application cursor mode and all), and the
        // shell's line editor is what actually moves — so the two never
        // disagree, and the shell clamps at the prompt on its own.
        val cursorRow = session.stateArray[NativeCore.STATE_CURSOR_ROW]
        if (row != cursorRow) return
        val delta = col - session.stateArray[NativeCore.STATE_CURSOR_COL]
        if (delta == 0) return
        val named = if (delta > 0) {
            dev.drosh.terminal.engine.input.AndroidKeys.named(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        } else {
            dev.drosh.terminal.engine.input.AndroidKeys.named(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        } ?: return
        val sequence = Encoder.sequence(named, Encoder.Mods(), view.appCursor()) ?: return
        repeat(minOf(kotlin.math.abs(delta), 512)) { session.write(sequence) }
        view.requestRender()
    }

    /**
     * Hollow box on the pointer cell, sized from the renderer's cell metrics
     * so it follows the zoom level. Re-measured on every move: cheap, and it
     * keeps the box correct across a pinch-zoom without extra wiring.
     */
    private fun positionPointer() {
        val view = terminalView ?: return
        val pointer = pointerView ?: return
        if (!view.hasCellMetrics) return
        val cellWidth = view.metrics.width
        val cellHeight = view.metrics.height
        if (cellWidth <= 0f || cellHeight <= 0f) return

        val density = view.resources.displayMetrics.density
        // one cell wide like the shell's block cursor, but taller: the mouse
        // pointer reads as a cursor, not as a character cell
        val boxWidth = (cellWidth + 1.5f * density).toInt()
        val boxHeight = (cellHeight * 1.3f).toInt()
        if (pointer.layoutParams == null ||
            pointer.layoutParams.width != boxWidth ||
            pointer.layoutParams.height != boxHeight
        ) {
            pointer.layoutParams = android.widget.FrameLayout.LayoutParams(boxWidth, boxHeight)
        }
        pointer.x = col * cellWidth - (boxWidth - cellWidth) / 2f
        pointer.y = row * cellHeight - (boxHeight - cellHeight) / 2f
    }
}
