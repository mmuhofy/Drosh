package com.iris.irisshell.domain.block

/**
 * A single terminal "block": one prompt + command + output cycle.
 *
 * Inspired by the HUD terminal concept (`docs/mockups/my-wroted-block-output-mockup.html`)
 * — each block is rendered as one card with:
 *   - a HUD header (exit code, duration, network delta, copy, collapse)
 *   - a connected body (input row always visible + collapsible output)
 *
 * Persistence: in-memory only (MVP). Blocks are dropped when the session
 * closes. A 5000-line ring buffer inside the engine prevents unbounded growth.
 *
 * Command boundary detection is handled by the `BlockEngine` via three
 * layered signals (OSC 133, ANSI cursor, prompt regex) — see
 * `docs/block-engine/PLAN.md` §7.
 *
 * UNTESTED — verify before use.
 */
data class Block(
    /** Runtime UUID; not persisted. */
    val id: String,
    /** Prompt line shown above the command, e.g. "muhofy@drosh:~/Drosh$". */
    val prompt: String,
    /** Command text the user entered, e.g. "ls -la". */
    val command: String,
    /** Output lines, ANSI stripped. Rendered as monospace text. */
    val outputLines: List<String>,
    /** Current state — see `BlockState`. */
    val state: BlockState,
    /** Wall clock at block start (milliseconds). */
    val startedAtMs: Long,
    /** Wall clock at block completion (milliseconds), null while running. */
    val completedAtMs: Long?,
    /** Snapshot of session-cumulative RX bytes at block start. */
    val startRxBytes: Long,
    /** Snapshot of session-cumulative TX bytes at block start. */
    val startTxBytes: Long,
    /** Latest snapshot of session-cumulative RX bytes (updated while running). */
    val currentRxBytes: Long = startRxBytes,
    /** Latest snapshot of session-cumulative TX bytes (updated while running). */
    val currentTxBytes: Long = startTxBytes,
    /** Output collapsed by user? Body output row hidden if true. */
    val isCollapsed: Boolean = false,
) {
    /**
     * Elapsed time in milliseconds.
     * - Running: time since `startedAtMs` until now.
     * - Completed: time between `startedAtMs` and `completedAtMs`.
     */
    fun elapsedMs(now: Long): Long = when (state) {
        is BlockState.Success, is BlockState.Error, BlockState.Cancelled ->
            (completedAtMs ?: startedAtMs) - startedAtMs
        BlockState.Idle, BlockState.Running -> now - startedAtMs
    }

    /** Network delta between block start and the latest sample. */
    val networkDelta: NetworkDelta
        get() = NetworkDelta(
            rxBytes = (currentRxBytes - startRxBytes).coerceAtLeast(0),
            txBytes = (currentTxBytes - startTxBytes).coerceAtLeast(0),
        )

    /** Exit code if the block completed (Success or Error), otherwise null. */
    val exitCode: Int?
        get() = when (state) {
            is BlockState.Success -> state.exitCode
            is BlockState.Error -> state.exitCode
            else -> null
        }
}
