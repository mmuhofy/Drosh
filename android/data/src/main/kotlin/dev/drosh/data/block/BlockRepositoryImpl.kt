package dev.drosh.data.block

import android.os.Handler
import android.os.Looper
import dev.drosh.domain.block.Block
import dev.drosh.domain.block.BlockRepository
import dev.drosh.domain.block.BlockState
import dev.drosh.domain.block.CommandBoundaryDetector
import dev.drosh.domain.block.StyledLine
import dev.drosh.domain.block.StyledSpan
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
 * — whole blocks, not partial lines).
 *
 * Output buffering: incoming chunks are split on `\n`. The last partial line
 * is held back until the next chunk arrives or the command completes, so a
 * logical line is never split across two output entries. The partial line is
 * buffered per session too. Styled lines are buffered in parallel with the
 * plain text, so [Block.outputRich] and [Block.outputLines] stay in step.
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
        /**
         * The running block's output, appended to in place.
         *
         * A running block's [Block.outputLines] is left empty here and filled
         * from this list when the block is published — see [publishActive].
         * The list is append-only for the life of the command, so a thousand
         * chunks cost a thousand `add`s instead of a thousand copies of
         * everything written so far.
         */
        val liveLines: MutableList<String> = mutableListOf(),
        /** Styled counterpart of [liveLines], filled when the engine sends styles. */
        val liveRichLines: MutableList<StyledLine> = mutableListOf(),
        /**
         * Spans of a line that ended without a newline. The next chunk continues
         * that line; the parser's style state carries across chunks too.
         */
        val partialRichSpans: MutableList<StyledSpan> = mutableListOf(),
        /** A session gets one history snapshot, never one per mode switch. */
        val historySeeded: Boolean = false,
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

    /**
     * Applies [transform] to the active session's state and republishes it.
     *
     * [publishImmediately] is for the changes a person caused — a command
     * submitted, a block collapsed — where the UI should not wait a frame.
     * Everything else takes the scheduled publish: output chunks arrive faster
     * than anything can be read, and one publish per frame is what the display
     * can show anyway.
     */
    private inline fun mutate(
        publishImmediately: Boolean = false,
        transform: (SessionBlocks) -> SessionBlocks,
    ) {
        val key = activeSessionId
        val updated = store.value.toMutableMap()
        updated[key] = transform(updated[key] ?: SessionBlocks())
        store.value = updated
        if (publishImmediately) publishActive() else schedulePublish()
    }

    /** The frame's publish, when one is not already waiting. */
    @Volatile
    private var publishScheduled: Boolean = false

    private val publishHandler = Handler(Looper.getMainLooper())

    /**
     * Publishes on the next frame, coalescing whatever arrived since.
     *
     * A `yes` in block mode produces chunks far faster than frames. Publishing
     * each one meant copying every line written so far, once per chunk — a
     * command printing 10,000 lines copied 50 million list entries, and the
     * garbage collector paid for it in pauses the user could see.
     */
    private fun schedulePublish() {
        if (publishScheduled) return
        publishScheduled = true
        publishHandler.postDelayed({
            publishScheduled = false
            publishActive()
        }, PUBLISH_FRAME_MS)
    }

    private fun publishActive() {
        val stored = store.value[activeSessionId] ?: SessionBlocks()
        val running = stored.running
        // A running block's lines live in liveLines until they are published,
        // so the block the UI sees is the buffer frozen into a list.
        val snapshot = if (running != null &&
            (stored.liveLines.isNotEmpty() || stored.liveRichLines.isNotEmpty())
        ) {
            running.copy(
                outputLines = stored.liveLines.toList(),
                outputRich = stored.liveRichLines.toList(),
            )
        } else {
            null
        }
        _blocks.value = if (snapshot != null) replaceIn(stored.blocks, snapshot) else stored.blocks
        _runningBlock.value = snapshot ?: running
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
        mutate(publishImmediately = true) { current ->
            // The engine's S event can arrive while a UI-initiated command is
            // still marked running. Close it first: two running blocks would
            // make output land in whichever one happened to be published last.
            val closed = closeRunning(current)
            closed.copy(
                blocks = capped(closed.blocks + newBlock),
                running = newBlock,
                partialLine = StringBuilder(),
                liveLines = mutableListOf(),
                liveRichLines = mutableListOf(),
                partialRichSpans = mutableListOf(),
            )
        }
    }

    /**
     * Finalise a running block that has not reported a finish, and clear its
     * in-flight buffers. Cancelled says what happened honestly: no status was
     * reported, so calling it a success would invent one.
     */
    private fun closeRunning(current: SessionBlocks): SessionBlocks {
        val running = current.running ?: return current
        val trailing = current.partialLine.toString()
        val lines = if (trailing.isNotEmpty()) current.liveLines + trailing else current.liveLines.toList()
        val rich = finalRichLines(current)
        val interrupted = running.copy(
            outputLines = lines,
            outputRich = rich,
            state = BlockState.Cancelled,
            completedAtMs = System.currentTimeMillis(),
        )
        return current.copy(
            blocks = replaceIn(current.blocks, interrupted),
            running = null,
            partialLine = StringBuilder(),
            liveLines = mutableListOf(),
            liveRichLines = mutableListOf(),
            partialRichSpans = mutableListOf(),
        )
    }

    /** The styled lines to publish when a command ends, including a trailing partial. */
    private fun finalRichLines(current: SessionBlocks): List<StyledLine> {
        val partial = current.partialRichSpans
        return if (partial.isNotEmpty()) {
            current.liveRichLines + StyledLine(partial.toList())
        } else {
            current.liveRichLines.toList()
        }
    }

    override fun onOutputChunk(chunk: String, lines: List<StyledLine>) {
        if (chunk.isEmpty() && lines.isEmpty()) return

        mutate { current ->
            // No command running: there is nothing for output to belong to.
            if (current.running == null) return@mutate current

            // Plain text keeps the existing line buffering, so copy and search
            // see exactly what the terminal printed.
            val partial = current.partialLine
            if (chunk.isNotEmpty()) {
                partial.append(chunk)
                val parts = partial.toString().split('\n')
                current.liveLines.addAll(parts.dropLast(1))
                partial.clear()
                partial.append(parts.last())
            }

            // The styled parser emits a final line for a trailing partial. Hold
            // it back until the rest of that line arrives, merging it into the
            // first styled line of the next chunk.
            var richPartial = current.partialRichSpans
            if (lines.isNotEmpty()) {
                val hasTail = !chunk.endsWith("\n")
                val completeRich = if (hasTail) lines.dropLast(1) else lines
                val tail = if (hasTail) lines.lastOrNull() else null
                if (completeRich.isNotEmpty()) {
                    val first = completeRich.first()
                    val merged = if (richPartial.isEmpty()) {
                        first
                    } else {
                        first.copy(spans = richPartial + first.spans)
                    }
                    current.liveRichLines.add(merged)
                    current.liveRichLines.addAll(completeRich.drop(1))
                    richPartial = mutableListOf()
                }
                if (tail != null) {
                    richPartial = (richPartial + tail.spans).toMutableList()
                }
            }
            current.copy(partialLine = partial, partialRichSpans = richPartial)
        }
    }

    override fun seedHistorySnapshot(lines: List<String>) {
        val snapshot = lines.takeLast(HISTORY_SNAPSHOT_MAX_LINES)
        if (snapshot.isEmpty()) return

        mutate(publishImmediately = true) { current ->
            // One snapshot per session, and only while the view is still empty:
            // a real engine-driven block is a better record than a snapshot of
            // the same screen and must never be buried under one.
            if (current.historySeeded || current.running != null || current.blocks.isNotEmpty()) {
                return@mutate current
            }
            val now = System.currentTimeMillis()
            val history = Block(
                id = java.util.UUID.randomUUID().toString(),
                prompt = HISTORY_SNAPSHOT_LABEL,
                command = "",
                outputLines = snapshot,
                state = BlockState.History,
                startedAtMs = now,
                completedAtMs = now,
                startRxBytes = 0L,
                startTxBytes = 0L,
            )
            current.copy(blocks = capped(current.blocks + history), historySeeded = true)
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
                current.liveLines.addAll(completeLines)
                current.copy(partialLine = StringBuilder(parts.last()))
            }
        }
    }

    override fun onCommandCompleted(exitCode: Int) {
        mutate(publishImmediately = true) { current ->
            val running = current.running ?: return@mutate current
            // Flush any trailing partial line into the output. The lines
            // accumulated during the command are the buffer's, not the
            // running block's — see SessionBlocks.liveLines.
            val trailing = current.partialLine.toString()
            val finalLines = if (trailing.isNotEmpty()) {
                current.liveLines + trailing
            } else {
                current.liveLines.toList()
            }

            val finalState =
                if (exitCode == 0) BlockState.Success(exitCode) else BlockState.Error(exitCode)
            val completed = running.copy(
                outputLines = finalLines,
                outputRich = finalRichLines(current),
                state = finalState,
                completedAtMs = System.currentTimeMillis(),
            )
            current.copy(
                blocks = replaceIn(current.blocks, completed),
                running = null,
                partialLine = StringBuilder(),
                liveLines = mutableListOf(),
                liveRichLines = mutableListOf(),
                partialRichSpans = mutableListOf(),
            )
        }
    }

    override fun onCommandCancelled() {
        mutate(publishImmediately = true) { current ->
            val running = current.running ?: return@mutate current
            val trailing = current.partialLine.toString()
            val cancelled = running.copy(
                outputLines = if (trailing.isNotEmpty()) current.liveLines + trailing else current.liveLines.toList(),
                outputRich = finalRichLines(current),
                state = BlockState.Cancelled,
                completedAtMs = System.currentTimeMillis(),
            )
            current.copy(
                blocks = replaceIn(current.blocks, cancelled),
                running = null,
                partialLine = StringBuilder(),
                liveLines = mutableListOf(),
                liveRichLines = mutableListOf(),
                partialRichSpans = mutableListOf(),
            )
        }
    }

    override fun setCollapsed(blockId: String, collapsed: Boolean) {
        mutate(publishImmediately = true) { current ->
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
        mutate(publishImmediately = true) { SessionBlocks() }
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

        /** Upper bound on a history snapshot's lines, independent of the caller. */
        const val HISTORY_SNAPSHOT_MAX_LINES = 200

        /** Visible label on the read-only history block. */
        const val HISTORY_SNAPSHOT_LABEL = "history snapshot"

        /** One frame. Output chunks land faster than this; the display does not. */
        const val PUBLISH_FRAME_MS = 16L
    }
}
