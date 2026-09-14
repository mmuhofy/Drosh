package com.iris.irisshell.terminal.input

import com.iris.irisshell.domain.input.InputIntent
import com.iris.irisshell.domain.input.SendKeyIntentUseCase
import com.iris.irisshell.terminal.ExtraKeyState
import com.iris.irisshell.terminal.TerminalManager

/**
 * Implementation of [SendKeyIntentUseCase] for `:terminal`. Bridges
 * the UI's intent-based dispatch to the active [TerminalSession] (or
 * no-op if no session is active).
 *
 * A new `InputDispatcher` is allocated per call — this is intentional:
 * the dispatcher is a tiny stateless-wrapper over modifier state and
 * the active session pointer, and `terminalManager.currentSession` can
 * change between intents (session swap) without requiring a re-bind
 * notification.
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
        val session = manager.currentSession
        val dispatcher = InputDispatcher(
            extraKeyState = state,
            session = session,
            keyEventSink = null,
        )
        dispatcher.dispatch(intent)
    }
}
