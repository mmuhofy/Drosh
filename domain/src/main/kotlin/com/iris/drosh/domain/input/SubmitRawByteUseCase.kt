package com.iris.irisshell.domain.input

/**
 * Writes raw bytes directly into the active terminal session's PTY,
 * bypassing the command-submission flow used by `SubmitBlockCommandUseCase`.
 *
 * Use cases:
 *  - Send SIGINT (`\u0003`) to interrupt a running foreground process.
 *  - Send EOF (`\u0004`) to zsh/bash readline.
 *  - Send SUSP (`\u001A`) for Ctrl+Z.
 *
 * Implementations MUST NOT append CR — this is a raw byte escape hatch,
 * not a command submit. The caller decides what (if any) line terminator
 * follows.
 *
 * UNTESTED — verify before use in production.
 */
interface SubmitRawByteUseCase {
    suspend fun submit(bytes: ByteArray)
}
