/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.drosh.terminal.mouse

import android.os.Bundle
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
 * - mouse tracking enabled (vim, tmux, htop, fzf, …): the pad produces real
 *   press / motion / release events at the pointer's cell.
 * - mouse tracking disabled (plain shell): a click places the terminal cursor
 *   on the clicked cell instead of sending an event nobody listens to.
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
    const val OP_DOWN = "down"
    const val OP_UP = "up"
    const val OP_CLICK = "click"

    const val KEY_OP = "op"
    const val KEY_DX = "dx"
    const val KEY_DY = "dy"
    const val KEY_BUTTON = "button"
    const val KEY_HELD = "held"

    /** Buttons we forward; 0 = left, 2 = right. */
    private const val BUTTON_LEFT = 0
    private const val BUTTON_RIGHT = 2

    /** Pixels of pad travel per terminal cell. App-side sensitivity. */
    private const val CELL_PX_PER_DP = 13f

    private var terminalView: TerminalView? = null
    private var pointerView: View? = null

    private var visible = false
    private var col = 0
    private var row = 0
    private var heldButton = -1
    private var accX = 0f
    private var accY = 0f
    private var cellPx = CELL_PX_PER_DP

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
            OP_MOVE -> {
                val held = data.getBoolean(KEY_HELD, false)
                move(
                    emulator,
                    data.getInt(KEY_DX, 0),
                    data.getInt(KEY_DY, 0),
                    held,
                )
            }
            OP_CLICK -> click(emulator, data.getInt(KEY_BUTTON, BUTTON_LEFT))
            OP_DOWN -> down(emulator, data.getInt(KEY_BUTTON, BUTTON_LEFT))
            OP_UP -> up(emulator, data.getInt(KEY_BUTTON, BUTTON_LEFT))
            else -> return false
        }
        return true
    }

    private fun emulator(): TerminalEmulator? = terminalView?.emulatorForMouse

    private fun open(emulator: TerminalEmulator) {
        visible = true
        accX = 0f
        accY = 0f
        heldButton = -1
        // anchor on the emulator cursor: where the shell already is
        col = emulator.getCursorCol().coerceIn(0, emulator.mColumns - 1)
        row = emulator.getCursorRow().coerceIn(0, emulator.mRows - 1)
        pointerView?.visibility = View.VISIBLE
        positionPointer()
    }

    private fun close() {
        val emulator = emulator()
        // never leave a button stuck down in the app
        if (heldButton >= 0 && emulator != null && emulator.isMouseTrackingActive()) {
            emulator.sendMouseEvent(heldButton, col + 1, row + 1, false)
        }
        heldButton = -1
        visible = false
        pointerView?.visibility = View.GONE
    }

    private fun move(emulator: TerminalEmulator, dxPx: Int, dyPx: Int, held: Boolean) {
        if (!visible) return
        accX += dxPx
        accY += dyPx
        var stepX = 0
        var stepY = 0
        while (accX >= cellPx) { stepX++; accX -= cellPx }
        while (accX <= -cellPx) { stepX--; accX += cellPx }
        while (accY >= cellPx) { stepY++; accY -= cellPx }
        while (accY <= -cellPx) { stepY--; accY += cellPx }
        if (stepX == 0 && stepY == 0) return

        col = (col + stepX).coerceIn(0, emulator.mColumns - 1)
        row = (row + stepY).coerceIn(0, emulator.mRows - 1)
        positionPointer()

        // Motion only matters while a button is held (drag-select). Hover
        // motion (button 35) needs DECSET 1003, which applications enable
        // rarely, so plain movement stays pointer-only — no wasted traffic.
        if (held && heldButton >= 0 && emulator.isMouseTrackingActive()) {
            emulator.sendMouseEvent(
                TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED + heldButton,
                col + 1,
                row + 1,
                true,
            )
        }
    }

    private fun click(emulator: TerminalEmulator, button: Int) {
        if (!visible) return
        if (emulator.isMouseTrackingActive()) {
            emulator.sendMouseEvent(button, col + 1, row + 1, true)
            emulator.sendMouseEvent(button, col + 1, row + 1, false)
        } else {
            // plain shell: put the cursor where the user clicked
            emulator.moveCursorToScreenCell(col, row)
            terminalView?.onScreenUpdated(true)
        }
    }

    private fun down(emulator: TerminalEmulator, button: Int) {
        if (!visible) return
        heldButton = button
        if (emulator.isMouseTrackingActive()) {
            emulator.sendMouseEvent(button, col + 1, row + 1, true)
        }
    }

    private fun up(emulator: TerminalEmulator, button: Int) {
        if (!visible) return
        if (emulator.isMouseTrackingActive()) {
            emulator.sendMouseEvent(button, col + 1, row + 1, false)
        } else if (heldButton >= 0) {
            // plain shell: a drag has no selection to leave behind, so the
            // gesture ends by placing the cursor at the release cell
            emulator.moveCursorToScreenCell(col, row)
            terminalView?.onScreenUpdated(true)
        }
        heldButton = -1
    }

    private fun positionPointer() {
        val view = terminalView ?: return
        val pointer = pointerView ?: return
        val fontWidth = view.rendererForMouse?.mFontWidth ?: return
        val lineSpacing = view.rendererForMouse?.mFontLineSpacing ?: return
        pointer.x = col * fontWidth - pointer.width / 3f
        pointer.y = row * lineSpacing - pointer.height / 6f
    }
}
