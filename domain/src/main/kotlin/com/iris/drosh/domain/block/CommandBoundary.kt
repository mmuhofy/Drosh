package com.iris.irisshell.domain.block

/**
 * Result of a single command-boundary scan.
 *
 * `atLine` is the buffer row index (0-based) where the boundary was
 * found. `exitCode` is reserved for future use (a v2 feature — see
 * `docs/block-engine/PLAN.md` §7).
 */
sealed class CommandBoundary {
    /** New prompt is visible at row `atLine` — previous block closed. */
    data class PromptReady(val atLine: Int) : CommandBoundary()

    /** No boundary found in this scan. */
    object None : CommandBoundary()
}
