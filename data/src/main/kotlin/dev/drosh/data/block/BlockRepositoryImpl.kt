package dev.drosh.data.block

import dev.drosh.domain.block.Block
import dev.drosh.domain.block.BlockRepository
import dev.drosh.domain.block.BlockState
import dev.drosh.domain.block.CommandBoundaryDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory implementation of [BlockRepository].
 *
 * Holds one list of blocks **per session**, keyed by the session's persistent
 * id. Block mode is only a view over the same sessions classic mode uses, so
 * switching sessions has to swap which list is showing rather than throw the
 * old one away — that is what made block mode look like it had a single
 * permanent session while classic mode had many.
 *
 * [observe] and [observeRunningBlock] always report the active session; the
 * per-session state is swapped in by [setActiveSession].
 *
 * Scrollback cap: [MAX_BLOCKS] per session (oldest blocks dropped on overflow
 * — whole blocks, not partial lines, per `docs/MEMORYBANK.md` §7 "Block-Based Output").
 *
 * Output buffering: incoming chunks are split on `\n`. The last partial line
 * is held back until the next chunk arrives or the command completes, so a
 * logical line is never split across two output entries. The partial line is
 * buffered per session too.
 *
 * UNTESTED — verify before use.
 */
@Singleton
class BlockRepositoryImpl @Inject constructor() : BlockRepository {

    /** Blocks plus the in-flight command buffer for a single session. */
    private data class SessionBlocks(
        val blocks: List<Block> = emptyList(),
        val running: Block? = null,
        val partialLine: StringBuilder = StringBuilder(),
    )

    private val _blocks = MutableStateFlow<List<Block>>(emptyList())
    override fun observe(): StateFlow<List<Block>> = _blocks.asStateFlow()

    private val _runningBlock = MutableStateFlow<Block?>(null)
    override fun observeRunningBlock(): StateFlow<Block?> = _runningBlock.asStateFlow()

    private val boundaryDetector = CommandBoundaryDetector()

    private val store = MutableStateFlow<Map<String?, SessionBlocks>>(emptyMap())
    private var activeSessionId: String? = null

    override fun setActiveSession(sessionId: String?) {
        if (activeSessionId == sessionId) return
        activeSessionId = sessionId
        publishActive()
    }

    /** Applies [transform] to the active session's state and republishes it. */
    private inline fun mutate(transform: (SessionBlocks) -> SessionBlocks) {
        val key = activeSessionId
        val updated = store.value.toMutableMap()
        updated[key] = transform(updated[key] ?: SessionBlocks())
        store.value = updated
        publishActive()
    }

    private fun publishActive() {
        val current = store.value[activeSessionId] ?: SessionBlocks()
        _blocks.value = current.blocks
        _runningBlock.value = current.running
    }

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
        mutate { current ->
            current.copy(
                blocks = capped(current.blocks + newBlock),
                running = newBlock,
                partialLine = StringBuilder(),
            )
        }
    }

    override fun onOutputChunk(chunk: String) {
        if (chunk.isEmpty()) return

        mutate { current ->
            val running = current.running ?: return@mutate current
            current.partialLine.append(chunk)
            val parts = current.partialLine.toString().split('\n')
            // All but the last part are complete lines; the last is the
            // partial line that may continue in the next chunk.
            val completeLines = parts.dropLast(1)
            if (completeLines.isEmpty()) return@mutate current

            current.copy(
                blocks = replaceIn(current.blocks, running.copy(outputLines = running.outputLines + completeLines)),
                running = running.copy(outputLines = running.outputLines + completeLines),
                partialLine = StringBuilder(parts.last()),
            )
        }
    }

    override fun onBootOutput(chunk: String) {
        if (chunk.isEmpty()) return

        mutate { current ->
            val existing = current.running
            if (existing == null) {
                // Lazily create a boot-only block the first time we see
                // pre-command output (e.g. .zshrc welcome message).
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
                current.copy(blocks = capped(current.blocks + boot))
            } else {
                // Already in a running block — defer to the normal path.
                current.partialLine.append(chunk)
                val parts = current.partialLine.toString().split('\n')
                val completeLines = parts.dropLast(1)
                if (completeLines.isEmpty()) return@mutate current
                val updated = existing.copy(outputLines = existing.outputLines + completeLines)
                current.copy(
                    blocks = replaceIn(current.blocks, updated),
                    running = updated,
                    partialLine = StringBuilder(parts.last()),
                )
            }
        }
    }

    override fun onCommandCompleted(exitCode: Int) {
        mutate { current ->
            val running = current.running ?: return@mutate current
            // Flush any trailing partial line into the output.
            val trailing = current.partialLine.toString()
            val finalLines = if (trailing.isNotEmpty()) {
                running.outputLines + trailing
            } else running.outputLines

            val finalState =
                if (exitCode == 0) BlockState.Success(exitCode) else BlockState.Error(exitCode)
            val completed = running.copy(
                outputLines = finalLines,
                state = finalState,
                completedAtMs = System.currentTimeMillis(),
            )
            current.copy(
                blocks = replaceIn(current.blocks, completed),
                running = null,
                partialLine = StringBuilder(),
            )
        }
    }

    override fun onCommandCancelled() {
        mutate { current ->
            val running = current.running ?: return@mutate current
            val cancelled = running.copy(
                state = BlockState.Cancelled,
                completedAtMs = System.currentTimeMillis(),
            )
            current.copy(
                blocks = replaceIn(current.blocks, cancelled),
                running = null,
                partialLine = StringBuilder(),
            )
        }
    }

    override fun setCollapsed(blockId: String, collapsed: Boolean) {
        mutate { current ->
            val idx = current.blocks.indexOfFirst { it.id == blockId }
            if (idx < 0) return@mutate current
            val target = current.blocks[idx]
            if (target.isCollapsed == collapsed) return@mutate current
            current.copy(
                blocks = current.blocks.toMutableList().apply {
                    this[idx] = target.copy(isCollapsed = collapsed)
                }
            )
        }
    }

    override fun updateRunningCounters(blockId: String, currentRxBytes: Long, currentTxBytes: Long) {
        mutate { current ->
            val idx = current.blocks.indexOfFirst { it.id == blockId }
            if (idx < 0) return@mutate current
            val target = current.blocks[idx]
            if (target.state !is BlockState.Running) return@mutate current
            if (target.currentRxBytes == currentRxBytes && target.currentTxBytes == currentTxBytes) {
                return@mutate current
            }
            val updated = target.copy(currentRxBytes = currentRxBytes, currentTxBytes = currentTxBytes)
            current.copy(
                blocks = current.blocks.toMutableList().apply { this[idx] = updated },
                running = if (current.running?.id == blockId) updated else current.running,
            )
        }
    }

    override fun currentCommand(): String? = _runningBlock.value?.command

    override fun bumpRunningBlock(blockId: String) {
        mutate { current ->
            val idx = current.blocks.indexOfFirst { it.id == blockId }
            if (idx < 0) return@mutate current
            val target = current.blocks[idx]
            if (target.state !is BlockState.Running) return@mutate current
            val refreshed = target.copy()
            current.copy(
                blocks = current.blocks.toMutableList().apply { this[idx] = refreshed },
                running = if (current.running?.id == blockId) refreshed else current.running,
            )
        }
    }

    /** Clears only the active session. Other sessions keep their blocks. */
    override fun clear() {
        mutate { SessionBlocks() }
    }

    /** Drops every session's blocks, e.g. when the block mode is reset. */
    override fun clearAll() {
        store.value = emptyMap()
        publishActive()
    }

    /**
     * Detect a prompt boundary in the provided buffer rows. Used by the
     * upper layer (e.g. a coroutine that polls the terminal buffer)
     * to decide when to close a running block via [onCommandCompleted]
     * and open a fresh Idle block.
     */
    fun detectPromptBoundary(lines: List<String>): Boolean =
        boundaryDetector.detectPromptReady(lines) !is dev.drosh.domain.block.CommandBoundary.None

    private fun replaceIn(blocks: List<Block>, block: Block): List<Block> {
        val idx = blocks.indexOfFirst { it.id == block.id }
        if (idx < 0) return capped(blocks + block)
        return blocks.toMutableList().apply { this[idx] = block }
    }

    private fun capped(blocks: List<Block>): List<Block> =
        if (blocks.size > MAX_BLOCKS) blocks.takeLast(MAX_BLOCKS) else blocks

    private companion object {
        const val MAX_BLOCKS = 200
    }
}
