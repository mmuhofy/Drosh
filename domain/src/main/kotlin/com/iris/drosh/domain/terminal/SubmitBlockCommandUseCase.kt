package com.iris.irisshell.domain.terminal

/**
 * Submits a user-typed command to the active terminal session's PTY.
 *
 * Used by the Block Mode UI: the Compose-only sticky input field calls
 * [submit] when the user presses Enter. The implementation in `:data`
 * writes the command (followed by CR) into the active session via
 * `TerminalManager`.
 *
 * Implementation detail: callers do not need to append a newline — the
 * use case handles line termination itself.
 */
interface SubmitBlockCommandUseCase {
    suspend fun submit(command: String)
}
