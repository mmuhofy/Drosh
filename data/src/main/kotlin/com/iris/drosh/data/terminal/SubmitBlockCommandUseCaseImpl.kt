package com.iris.irisshell.data.terminal

import com.iris.irisshell.domain.terminal.SubmitBlockCommandUseCase
import com.iris.irisshell.terminal.BlockEngineWire
import com.iris.irisshell.terminal.TerminalManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a command string into the currently active terminal session
 * and tells the [BlockEngineWire] to expect the shell's echo of that
 * command on the next output line.
 *
 * CR is used as the line terminator because PTYs expect CR (Enter key)
 * to dispatch a command.
 */
@Singleton
class SubmitBlockCommandUseCaseImpl @Inject constructor(
    private val terminalManager: TerminalManager,
    private val blockEngineWire: BlockEngineWire,
) : SubmitBlockCommandUseCase {

    override suspend fun submit(command: String) {
        if (command.isBlank()) return
        val session = terminalManager.currentSession ?: return
        blockEngineWire.onCommandSubmitted(command)
        val payload = (command + "\r").toByteArray()
        session.write(payload, 0, payload.size)
    }
}
