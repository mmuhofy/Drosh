package com.iris.irisshell.data.block

import com.iris.irisshell.domain.block.Block
import com.iris.irisshell.domain.block.BlockRepository
import com.iris.irisshell.domain.block.BlockState
import com.iris.irisshell.domain.block.CommandBoundaryDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory implementation of [BlockRepository].
 *
 * Holds a single list of blocks for the active session. When a session
 * closes, the caller invokes [clear] so the next session starts empty.
 *
 * Scrollback cap: [MAX_BLOCKS] (oldest blocks dropped on overflow —
 * whole blocks, not partial lines, per `docs/block-engine/PLAN.md` §8).
 *
 * Output buffering: incoming chunks are split on `\n`. The last
 * partial line is held back until the next chunk arrives or the
 * command completes, so we never split a logical line across two
 * output entries.
 *
 * UNTESTED — verify before use.
 */
@Singleton
class BlockRepositoryImpl @Inject constructor() : BlockRepository {

    private val _blocks = MutableStateFlow<List<Block>>(emptyList())
    override fun observe(): StateFlow<List<Block>> = _blocks.asStateFlow()

    private val _runningBlock = MutableStateFlow<Block?>(null)
    override fun observeRunningBlock(): StateFlow<Block?> = _runningBlock.asStateFlow()

    private val boundaryDetector = CommandBoundaryDetector()
    private var outputLineBuffer: StringBuilder = StringBuilder()

    override fun onCommandSubmitted(
        prompt: String,
        command: String,
        startRxBytes: Long,
        startTxBytes: Long,
    ) {
        val now = System.currentTimeMillis()
        val newBlock = Block(
            id = java.util.UUID.randomUUID().toString(),
            prompt = prompt,
            command = command,
            outputLines = emptyList(),
            state = BlockState.Running,
            startedAtMs = now,
            completedAtMs = null,
            startRxBytes = startRxBytes,
            startTxBytes = startTxBytes,
        )
        appendBlock(newBlock)
        _runningBlock.value = newBlock
        outputLineBuffer = StringBuilder()
    }

    override fun onOutputChunk(chunk: String) {
        val running = _runningBlock.value ?: return
        if (chunk.isEmpty()) return

        outputLineBuffer.append(chunk)
        val buffered = outputLineBuffer.toString()
        val parts = buffered.split('\n')
        // All but the last part are complete lines; the last is the
        // partial line that may continue in the next chunk.
        val completeLines = parts.dropLast(1)
        outputLineBuffer = StringBuilder(parts.last())

        if (completeLines.isEmpty()) return

        val updated = running.copy(outputLines = running.outputLines + completeLines)
        _runningBlock.value = updated
        replaceBlock(updated)
    }

    override fun onBootOutput(chunk: String) {
        if (chunk.isEmpty()) return
        // Lazily create a boot-only block the first time we see
        // pre-command output (e.g. .zshrc welcome message).
        val current = _runningBlock.value
        if (current == null) {
            val boot = Block(
                id = java.util.UUID.randomUUID().toString(),
                prompt = "",
                command = "",
                outputLines = chunk.split('\n'),
                state = BlockState.Success(exitCode = 0),
                startedAtMs = System.currentTimeMillis(),
                completedAtMs = System.currentTimeMillis(),
                startRxBytes = 0,
                startTxBytes = 0,
            )
            appendBlock(boot)
            return
        }
        // Already in a running block — defer to normal path.
        onOutputChunk(chunk)
    }

    override fun onCommandCompleted(exitCode: Int) {
        val running = _runningBlock.value ?: return
        // Flush any trailing partial line into the output.
        val trailing = outputLineBuffer.toString()
        val finalLines = if (trailing.isNotEmpty()) {
            outputLineBuffer = StringBuilder()
            running.outputLines + trailing
        } else running.outputLines

        val finalState = if (exitCode == 0) BlockState.Success(exitCode) else BlockState.Error(exitCode)
        val completed = running.copy(
            outputLines = finalLines,
            state = finalState,
            completedAtMs = System.currentTimeMillis(),
        )
        _runningBlock.value = null
        replaceBlock(completed)
    }

    override fun onCommandCancelled() {
        val running = _runningBlock.value ?: return
        val cancelled = running.copy(
            state = BlockState.Cancelled,
            completedAtMs = System.currentTimeMillis(),
        )
        _runningBlock.value = null
        replaceBlock(cancelled)
    }

    override fun setCollapsed(blockId: String, collapsed: Boolean) {
        val current = _blocks.value
        val idx = current.indexOfFirst { it.id == blockId }
        if (idx < 0) return
        val target = current[idx]
        if (target.isCollapsed == collapsed) return
        val updated = target.copy(isCollapsed = collapsed)
        _blocks.value = current.toMutableList().apply { this[idx] = updated }
    }

    override fun updateRunningCounters(blockId: String, currentRxBytes: Long, currentTxBytes: Long) {
        val current = _blocks.value
        val idx = current.indexOfFirst { it.id == blockId }
        if (idx < 0) return
        val target = current[idx]
        if (target.state !is BlockState.Running) return
        if (target.currentRxBytes == currentRxBytes && target.currentTxBytes == currentTxBytes) return
        val updated = target.copy(
            currentRxBytes = currentRxBytes,
            currentTxBytes = currentTxBytes,
        )
        _blocks.value = current.toMutableList().apply { this[idx] = updated }
        if (_runningBlock.value?.id == blockId) {
            _runningBlock.value = updated
        }
    }

    override fun currentCommand(): String? = _runningBlock.value?.command

    override fun bumpRunningBlock(blockId: String) {
        val current = _blocks.value
        val idx = current.indexOfFirst { it.id == blockId }
        if (idx < 0) return
        val target = current[idx]
        if (target.state !is BlockState.Running) return
        _blocks.value = current.toMutableList().apply { this[idx] = target.copy() }
        if (_runningBlock.value?.id == blockId) {
            _runningBlock.value = _runningBlock.value?.copy()
        }
    }

    override fun clear() {
        _blocks.value = emptyList()
        _runningBlock.value = null
        outputLineBuffer = StringBuilder()
    }

    /**
     * Detect a prompt boundary in the provided buffer rows. Used by the
     * upper layer (e.g. a coroutine that polls the terminal buffer)
     * to decide when to close a running block via [onCommandCompleted]
     * and open a fresh Idle block.
     */
    fun detectPromptBoundary(lines: List<String>): Boolean =
        boundaryDetector.detectPromptReady(lines) !is com.iris.irisshell.domain.block.CommandBoundary.None

    private fun appendBlock(block: Block) {
        val current = _blocks.value
        val updated = current + block
        _blocks.value = if (updated.size > MAX_BLOCKS) {
            updated.takeLast(MAX_BLOCKS)
        } else updated
    }

    private fun replaceBlock(block: Block) {
        val current = _blocks.value
        val idx = current.indexOfFirst { it.id == block.id }
        if (idx < 0) {
            appendBlock(block)
            return
        }
        _blocks.value = current.toMutableList().apply { this[idx] = block }
    }

    private companion object {
        const val MAX_BLOCKS = 200
    }
}
