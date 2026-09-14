package com.iris.irisshell.domain.block

import kotlinx.coroutines.flow.StateFlow

/**
 * Public interface of the block engine, exposed to `:ui`.
 *
 * The implementation lives in `:data` and is injected via Hilt
 * (`BlockRepositoryImpl`). UI consumers observe the block list and
 * forward user actions (run/cancel) through the same interface.
 *
 * State transitions are exposed as `StateFlow` for Compose collection.
 * Mutations go through dedicated methods — never via state setters.
 *
 * See `docs/block-engine/PLAN.md` §3.
 */
interface BlockRepository {

    /** Current block list for the active session. In-memory only. */
    fun observe(): StateFlow<List<Block>>

    /** Currently-running block, or null if none. */
    fun observeRunningBlock(): StateFlow<Block?>

    /**
     * Notify the engine that the user has submitted a new command.
     * Closes any running block (as success/unknown) and opens a fresh
     * running block.
     */
    fun onCommandSubmitted(prompt: String, command: String, startRxBytes: Long, startTxBytes: Long)

    /**
     * Stream a chunk of output text. Lines are split on `\n`; trailing
     * partial lines are buffered until the next chunk.
     */
    fun onOutputChunk(chunk: String)

    /**
     * Append output to a shell-only "boot" block that has no command.
     * Used when the wire receives terminal output before any command
     * was submitted — typically the welcome message printed by .zshrc
     * or .bashrc. The block lives until [onCommandCompleted] or
     * [clear].
     */
    fun onBootOutput(chunk: String)

    /**
     * Mark the running block as completed with the given exit code.
     * Idempotent — calling twice has no effect.
     */
    fun onCommandCompleted(exitCode: Int)

    /** Mark the running block as cancelled by user action. */
    fun onCommandCancelled()

    /** Toggle the collapsed state of a specific block. */
    fun setCollapsed(blockId: String, collapsed: Boolean)

    /**
     * Update the running block's live counters (network bytes).
     * No-op if there is no running block or the id does not match.
     */
    fun updateRunningCounters(blockId: String, currentRxBytes: Long, currentTxBytes: Long)

    /**
     * Currently-running block's command string, or null if no block is
     * running. Used by [BlockEngineWire] to identify the echoed line
     * that the shell prints as the user types and strip it from output.
     */
    fun currentCommand(): String?

    /**
     * Re-emit the running block unchanged. Used by the ViewModel's
     * 500ms ticker to drive live `elapsed time` updates on the UI even
     * when network totals do not change.
     */
    fun bumpRunningBlock(blockId: String)

    /** Drop all blocks (e.g. on session close). */
    fun clear()
}
