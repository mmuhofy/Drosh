package dev.drosh.terminal.session.input

import dev.drosh.domain.input.InputIntent
import dev.drosh.domain.input.SendKeyIntentUseCase
import dev.drosh.terminal.session.ExtraKeyState
import dev.drosh.terminal.session.TerminalManager

/**
 * Implementation of [SendKeyIntentUseCase] for `:terminal`. Bridges
 * the UI's intent-based dispatch to the terminal the user is working in
 * (or no-op if there is none).
 *
 * The manager is asked which terminal has the focus, and it answers with
 * that terminal's writer: the vendored session's writer, or the engine's.
 * A writer rather than a session, because an engine-backed pane has no
 * session of the app's kind — asking the manager for one would have made
 * the extra-keys row do nothing on the engine's path.
 *
 * A new `InputDispatcher` is allocated per call — this is intentional:
 * the dispatcher is a tiny stateless-wrapper over modifier state and
 * the writer, and the focused terminal can change between intents (session
 * swap) without requiring a re-bind notification.
 *
 * Block-mode interrupt semantics (`Ctrl+C` → raw `\u0003`) live in
 * `InputBarViewModel` — that layer decides when to bypass the
 * dispatcher and use `SubmitRawByteUseCase` directly.
 *
 * UNTESTED — verify on device.
 */
class SendKeyIntentUseCaseImpl(
    private val state: ExtraKeyState,
    private val manager: TerminalManager,
) : SendKeyIntentUseCase {

    override fun dispatch(intent: InputIntent) {
        val dispatcher = InputDispatcher(
            extraKeyState = state,
            writer = manager.focusedWriter(),
            keyEventSink = null,
        )
        dispatcher.dispatch(intent)
    }
}
