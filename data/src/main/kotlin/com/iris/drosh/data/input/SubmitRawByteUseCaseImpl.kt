package com.iris.irisshell.data.input

import com.iris.irisshell.domain.input.SubmitRawByteUseCase
import com.iris.irisshell.terminal.TerminalManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a raw byte sequence into the active terminal session's PTY
 * without CR termination. See `SubmitRawByteUseCase` for the contract.
 *
 * Implementation notes:
 *  - If no session is active, the call is a silent no-op (matches the
 *    convention of `SubmitBlockCommandUseCaseImpl`).
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
        val session = terminalManager.currentSession ?: return
        session.write(bytes, 0, bytes.size)
    }
}
