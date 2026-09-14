package com.iris.irisshell.terminal.input

import android.view.KeyEvent
import com.iris.irisshell.domain.input.ExtraKey
import com.iris.irisshell.domain.input.InputIntent
import com.iris.irisshell.terminal.ExtraKeyState
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession

/**
 * Dispatches a list of [InputIntent]s to the right surface for the
 * current render mode (classic vs block). Pure-Kotlin where possible —
 * the only Android coupling is the [KeyEvent] synthetic used to talk to
 * `TerminalView.handleKeyCode()` and `TerminalSession.write()`.
 *
 * Mode selection is done by the caller — `InputDispatcher` takes a
 * `TerminalSession` for byte-level writes (used in both modes) and an
 * optional [keyEventSink] for classic-mode special keys. When the sink
 * is null, classic-mode navigation/control intent falls back to
 * writing the equivalent VT sequence directly to the session — useful
 * for headless tests and for environments where no `TerminalView`
 * exists.
 *
 * Sticky modifier handling:
 *  - When [InputIntent.ArmModifier] fires, [extraKeyState] is updated.
 *  - The next non-modifier intent consults [extraKeyState] for sticky
 *    CTRL/ALT and folds them into the dispatched KeyEvent's meta state
 *    before the call hits the TerminalView. This matches Termux's
 *    `inputCodePoint` flow: `readControlKey()` / `readAltKey()` are
 *    OR'd into `keyMod`.
 *
 * UNTESTED — verify against the Termux reference before relying on
 * every modifier+nav combination.
 */
class InputDispatcher(
    private val extraKeyState: ExtraKeyState,
    private val session: TerminalSession?,
    private val keyEventSink: KeyEventSink? = null,
) {

    /**
     * Callback the caller wires to its `TerminalView` so we can fire a
     * synthetic `KeyEvent` into the view. The view's own
     * `onKeyDown()` pipeline then decides whether to dispatch via
     * `handleKeyCode()` or `inputCodePoint()`.
     */
    fun interface KeyEventSink {
        fun onKeyEvent(event: KeyEvent, keyCode: Int)
    }

    /** Dispatch a list of intents in order. */
    fun dispatch(intents: List<InputIntent>) {
        intents.forEach { dispatch(it) }
    }

    fun dispatch(intent: InputIntent) {
        when (intent) {
            is InputIntent.ArmModifier -> armModifier(intent.modifier)
            is InputIntent.TypeChar -> typeChar(intent.char)
            is InputIntent.Navigate -> navigate(intent.key)
            is InputIntent.FlushBytes -> flushBytes(intent.bytes)
        }
    }

    private fun armModifier(modifier: ExtraKey.Special) {
        when (modifier) {
            ExtraKey.Special.CTRL -> extraKeyState.tapCtrl()
            ExtraKey.Special.ALT -> extraKeyState.tapAlt()
        }
    }

    private fun typeChar(char: Char) {
        val ctrlDown = extraKeyState.readCtrl()
        val altDown = extraKeyState.readAlt()
        val keyCode = char.uppercaseChar().code.let { if (ctrlDown) translateCtrl(it) else it }
        if (keyEventSink != null) {
            val meta = buildMetaState(ctrlDown = ctrlDown, altDown = altDown)
            val event = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta)
            keyEventSink.onKeyEvent(event, keyCode)
            return
        }
        // Fallback — codepoint path directly through TerminalSession.
        session ?: return
        val appendEscape = altDown
        val cp = if (ctrlDown) translateCtrl(char.code) else char.code
        session.writeCodePoint(appendEscape, cp)
    }

    private fun navigate(key: ExtraKey.Navigation) {
        val ctrlDown = extraKeyState.readCtrl()
        val altDown = extraKeyState.readAlt()
        val keyCode = key.toAndroidKeyCode()
        if (keyEventSink != null) {
            val meta = buildMetaState(ctrlDown = ctrlDown, altDown = altDown)
            val event = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta)
            keyEventSink.onKeyEvent(event, keyCode)
            return
        }
        // Fallback — synthesize the VT escape sequence and write it directly.
        session ?: return
        val keyMod = buildKeyHandlerMod(ctrlDown, altDown)
        val code = KeyHandler.getCode(
            keyCode = keyCode,
            keyMode = keyMod,
            cursorApp = false,
            keypadApplication = false,
        )
        code?.let { session.write(it) }
    }

    private fun flushBytes(bytes: ByteArray) {
        session ?: return
        if (bytes.isEmpty()) return
        session.write(bytes, 0, bytes.size)
    }

    private fun buildMetaState(ctrlDown: Boolean, altDown: Boolean): Int {
        var meta = 0
        if (ctrlDown) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altDown) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return meta
    }

    private fun buildKeyHandlerMod(ctrlDown: Boolean, altDown: Boolean): Int {
        var mod = 0
        if (ctrlDown) mod = mod or KeyHandler.KEYMOD_CTRL
        if (altDown) mod = mod or KeyHandler.KEYMOD_ALT
        return mod
    }

    /**
     * Mirrors the C0-control translation in
     * `TerminalView.inputCodePoint` (see docs/MEMORYBANK.md §8).
     */
    private fun translateCtrl(cp: Int): Int = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        ' '.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code -> 31
        '8'.code -> 127
        else -> cp
    }

    private fun ExtraKey.Navigation.toAndroidKeyCode(): Int = when (this) {
        ExtraKey.Navigation.ESC -> KeyEvent.KEYCODE_ESCAPE
        ExtraKey.Navigation.TAB -> KeyEvent.KEYCODE_TAB
        ExtraKey.Navigation.ARROW_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        ExtraKey.Navigation.ARROW_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        ExtraKey.Navigation.ARROW_UP -> KeyEvent.KEYCODE_DPAD_UP
        ExtraKey.Navigation.ARROW_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        ExtraKey.Navigation.HOME -> KeyEvent.KEYCODE_MOVE_HOME
        ExtraKey.Navigation.END -> KeyEvent.KEYCODE_MOVE_END
        ExtraKey.Navigation.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        ExtraKey.Navigation.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
    }
}
