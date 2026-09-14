package com.iris.irisshell.domain.input

/**
 * Dispatches a single [InputIntent] to the active surface (TerminalView
 * in classic mode, PTY byte stream + BasicTextField in block mode).
 *
 * Implementations live in `:terminal` (concrete `InputDispatcher`).
 * The UI depends on this narrow interface so it can stay clear of the
 * `:terminal` module — see AGENT.md §139.
 *
 * UNTESTED — verify before use.
 */
fun interface SendKeyIntentUseCase {
    fun dispatch(intent: InputIntent)
}
