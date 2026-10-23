package dev.drosh.terminal.session.input

import android.view.KeyEvent
import dev.drosh.domain.input.ExtraKey
import dev.drosh.domain.input.InputIntent
import dev.drosh.terminal.session.ExtraKeyState

/**
 * Dispatches a list of [InputIntent]s to the right surface for the
 * current render mode (classic vs block). Pure-Kotlin where possible —
 * the only Android coupling is the [KeyEvent] synthetic used to talk to
 * `TerminalView.handleKeyCode()`.
 *
 * ## What is decided here, and what is not
 *
 * The decisions are the app's: which key a button means, which modifier is
 * held, and that a held modifier applies to the key that follows it. They
 * are made once, here, and they do not change with the terminal behind the
 * pane.
 *
 * The bytes are the terminal's: the dispatcher does not encode anything, it
 * hands the key and the modifiers to a [TerminalWriter] — the vendored
 * session's writer or the engine's — and that writer makes the bytes its
 * terminal understands. The two are not the same bytes for every key, and
 * they are not meant to be: the engine's encoder is the authority for the
 * engine.
 *
 * Mode selection is done by the caller — `InputDispatcher` takes a
 * [TerminalWriter] for the byte-level writes (used in both modes) and an
 * optional [keyEventSink] for classic-mode special keys. When the sink
 * is null, the intent goes to the writer — useful for headless tests and
 * for environments where no `TerminalView` exists.
 *
 * Sticky modifier handling:
 *  - When [InputIntent.ArmModifier] fires, [extraKeyState] is updated.
 *  - The next non-modifier intent consults [extraKeyState] for sticky
 *    CTRL/ALT and folds them into the modifiers it hands to the writer
 *    before the call hits the terminal. This matches Termux's
 *    `inputCodePoint` flow: `readControlKey()` / `readAltKey()` are
 *    OR'd into `keyMod`.
 *
 * UNTESTED — verify against the Termux reference before relying on
 * every modifier+nav combination.
 */
class InputDispatcher(
    private val extraKeyState: ExtraKeyState,
    private val writer: TerminalWriter?,
    // keyEventSink removed — engine is the only terminal.) {

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
            is InputIntent.FunctionKey -> functionKey(intent.key)
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
        // The vendored view path is removed — the engine's encoder makes
        // the bytes directly. Fall through to the writer.
        // The writer makes the bytes: the vendored session takes the code
        // point, the engine's encoder takes the character and the modifiers.
        writer ?: return
        writer.writeChar(char, InputModifiers(ctrl = ctrlDown, alt = altDown))
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
        // The writer makes the bytes: the vendored key handler turns the key
        // code into a VT sequence, the engine's encoder into its own.
        writer ?: return
        writer.writeKey(keyCode, InputModifiers(ctrl = ctrlDown, alt = altDown))
    }

    /**
     * A function key, F1 to F12.
     *
     * The key code is what the row's key events would have carried, so the
     * vendored key handler and the engine's encoder both answer for it.
     */
    private fun functionKey(key: ExtraKey.FunctionKey) {
        val ctrlDown = extraKeyState.readCtrl()
        val altDown = extraKeyState.readAlt()
        val keyCode = KeyEvent.KEYCODE_F1 + (key.number - 1)
        writer ?: return
        writer.writeKey(keyCode, InputModifiers(ctrl = ctrlDown, alt = altDown))
    }

    private fun flushBytes(bytes: ByteArray) {
        writer ?: return
        writer.writeBytes(bytes)
    }

    private fun buildMetaState(ctrlDown: Boolean, altDown: Boolean): Int {
        var meta = 0
        if (ctrlDown) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altDown) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return meta
    }

    private fun ExtraKey.Navigation.toAndroidKeyCode(): Int = when (this) {
        ExtraKey.Navigation.ESC -> KeyEvent.KEYCODE_ESCAPE
        ExtraKey.Navigation.TAB -> KeyEvent.KEYCODE_TAB
        ExtraKey.Navigation.BACKSPACE -> KeyEvent.KEYCODE_DEL
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
