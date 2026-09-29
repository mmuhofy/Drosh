package dev.drosh.domain.terminal

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

    /**
     * Writes a line straight to the PTY without touching block bookkeeping.
     *
     * For interactive programs — a Python REPL, `psql`, an editor — where the
     * line is not a shell command. [submit] would record it as a new command
     * block and suppress the echo the program is about to print, both of which
     * are wrong once something other than the shell owns the terminal.
     */
    suspend fun submitRaw(line: String)
}
