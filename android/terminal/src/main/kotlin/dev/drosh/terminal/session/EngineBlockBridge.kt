package dev.drosh.terminal.session

import dev.drosh.domain.block.BlockEngineState
import dev.drosh.domain.block.BlockRepository
import dev.drosh.domain.block.ShellPhase
import dev.drosh.domain.block.ShellStateSource
import dev.drosh.terminal.engine.native.NativeSession

/**
 * The engine-event-driven replacement for the transcript-diff block wire.
 *
 * The Rust half records `OSC 133` marks on the PTY tap and serialises them into
 * the wire format documented in `native/src/block.rs`. This bridge drains that
 * wire once per frame, parses it, and drives [BlockRepository]. There is no
 * transcript snapshot, no rolling anchor and no prompt regex: the boundary of
 * a command is the mark the shell wrote, not a guess about a screen.
 *
 * State (phase, directory, editor) still comes from [ShellStateSource] for the
 * UI; it no longer decides where a block begins or ends. Alternate-screen
 * suppression happens on the native side before a record is ever emitted.
 */
class EngineBlockBridge(
    private val blockRepository: BlockRepository,
) : BlockEngineState {

    private val styles = SgrStyledLineParser()

    /** The id of the block the engine is currently recording, when there is one. */
    private var activeId: String? = null
    private var activeSessionId: String? = null
    private var shellStateSource: ShellStateSource? = null
    private var lastCwd: String? = null

    @Volatile
    override var lastPrompt: String = DEFAULT_PROMPT
        private set

    /**
     * Drain and apply the engine's records for the focused session.
     *
     * // UNTESTED — verify before use: event ordering across frames and the
     * handover when focus moves between two busy sessions.
     */
    fun drainEvents(session: ShellSession, sessionId: String?) {
        if (activeSessionId != null && sessionId != activeSessionId) return
        if (activeSessionId == null && sessionId != null) {
            // focusPane binds a null id because an engine session is not an
            // app-kind session. The first drain with a real id is the binding.
            activeSessionId = sessionId
            blockRepository.setActiveSession(sessionId)
        }
        val handle = (session as? GhosttySession)?.terminalHandle ?: return
        val wire = NativeSession.drainBlockEvents(handle)
        if (wire.isEmpty()) return

        for (record in BlockWireParser.parse(wire, styles)) {
            when (record) {
                is ParsedRecord.Started -> {
                    lastPrompt = record.prompt
                    lastCwd = record.cwd
                    activeId = record.id
                    styles.reset()
                    blockRepository.onCommandSubmitted(
                        prompt = record.prompt,
                        command = record.command,
                        // The engine session carries no traffic-stat counters;
                        // network deltas stay zero rather than being invented.
                        startRxBytes = 0L,
                        startTxBytes = 0L,
                    )
                }
                is ParsedRecord.Output -> {
                    if (record.id != activeId) continue
                    blockRepository.onOutputChunk(record.text, record.lines)
                }
                is ParsedRecord.Finished -> {
                    if (record.id != activeId) continue
                    blockRepository.onCommandCompleted(record.exitCode)
                    activeId = null
                    styles.reset()
                }
            }
        }
    }

    /**
     * Rebind to another session without discarding its block history.
     *
     * [shellState] is kept only for the UI's phase/directory/editor readouts;
     * block boundaries come from the engine's records now.
     */
    fun onSessionChanged(sessionId: String?, shellState: ShellStateSource? = null) {
        shellStateSource = shellState
        if (activeSessionId == sessionId) return
        activeSessionId = sessionId
        activeId = null
        styles.reset()
        lastCwd = null
        lastPrompt = DEFAULT_PROMPT
        blockRepository.setActiveSession(sessionId)
    }

    /**
     * Re-seed after a restart. The engine's recorder belongs to the new process,
     * so there is no transcript to re-anchor — just drop records from the old
     * handle that a restart may have left queued.
     */
    fun reanchorEngine(session: ShellSession) {
        val handle = (session as? GhosttySession)?.terminalHandle ?: return
        NativeSession.drainBlockEvents(handle)
        activeId = null
        styles.reset()
    }

    /**
     * Kept for the command-submit use case: the engine's `S` record now opens
     * the block, so an echoed command needs no bookkeeping here.
     */
    fun onCommandSubmitted(command: String) = Unit

    /** Clear the active session's blocks and any half-parsed output. */
    fun reset() {
        activeId = null
        styles.reset()
        blockRepository.clear()
    }

    // --- BlockEngineState: state only, never a block boundary -------------

    private val reportedState: dev.drosh.domain.block.ShellState?
        get() = shellStateSource?.currentState()

    override val lastDir: String
        get() = reportedState?.workingDirectory
            ?.let { lastSegmentOf(it) }
            ?: lastCwd?.let { lastSegmentOf(it) }
            ?: PROMPT_DIR_REGEX.find(lastPrompt)?.groupValues?.getOrNull(1)
                ?.let { lastSegmentOf(it) }
            ?: "~"

    override val awaitingShellInput: Boolean
        get() = when (reportedState?.phase) {
            ShellPhase.PROMPT, ShellPhase.TYPING -> true
            ShellPhase.RUNNING, ShellPhase.FINISHED -> false
            null, ShellPhase.UNKNOWN -> true
        }

    override val shellPhase: ShellPhase get() = reportedState?.phase ?: ShellPhase.UNKNOWN

    override val workingDirectory: String? get() = reportedState?.workingDirectory

    override val editorTarget: String? get() = reportedState?.editorTarget

    private fun lastSegmentOf(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { "~" }

    private companion object {
        const val DEFAULT_PROMPT = "muhofy@drosh:~/Drosh$"
        val PROMPT_DIR_REGEX = Regex(""":([^:$#❯➜]*)$""")
    }
}
