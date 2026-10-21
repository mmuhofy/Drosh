package dev.drosh.data.input

import dev.drosh.domain.input.SubmitRawByteUseCase
import dev.drosh.terminal.session.TerminalManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a raw byte sequence into the focused terminal without CR
 * termination. See `SubmitRawByteUseCase` for the contract.
 *
 * Implementation notes:
 *  - The manager is asked which terminal has the focus and answers with that
 *    terminal's writer, so the bytes reach an engine-backed pane as well as
 *    a vendored one. If there is no terminal to write to, the call is a
 *    silent no-op (matches the convention of `SubmitBlockCommandUseCaseImpl`).
 *  - `BlockEngineWire.onCommandSubmitted()` is intentionally NOT called —
 *    raw bytes are not user-typed commands and the wire's echo-suppression
 *    would eat them on the next output frame.
 *
 * UNTESTED — verify before use in production.
 */
@Singleton
class SubmitRawByteUseCaseImpl @Inject constructor(
    private val terminalManager: TerminalManager,
) : SubmitRawByteUseCase {

    override suspend fun submit(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        terminalManager.focusedWriter()?.writeBytes(bytes)
    }
}
