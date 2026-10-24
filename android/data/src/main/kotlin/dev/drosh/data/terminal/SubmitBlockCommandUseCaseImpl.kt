package dev.drosh.data.terminal

import dev.drosh.domain.terminal.SubmitBlockCommandUseCase
import dev.drosh.terminal.session.BlockEngineWire
import dev.drosh.terminal.session.TerminalManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a command string into the currently focused terminal pane and tells
 * the [BlockEngineWire] to expect the shell's echo of that command on the next
 * output line.
 *
 * CR is used as the line terminator because PTYs expect CR (Enter key) to
 * dispatch a command.
 */
@Singleton
class SubmitBlockCommandUseCaseImpl @Inject constructor(
    private val terminalManager: TerminalManager,
    private val blockEngineWire: BlockEngineWire,
) : SubmitBlockCommandUseCase {

    override suspend fun submit(command: String) {
        if (command.isBlank()) return
        val writer = terminalManager.focusedWriter() ?: return
        blockEngineWire.onCommandSubmitted(command)
        writer.writeBytes((command + "\r").toByteArray())
    }

    override suspend fun submitRaw(line: String) {
        if (line.isEmpty()) return
        val writer = terminalManager.focusedWriter() ?: return
        // No onCommandSubmitted, so no block is opened and no echo is pending.
        writer.writeBytes((line + "\r").toByteArray())
    }
}
