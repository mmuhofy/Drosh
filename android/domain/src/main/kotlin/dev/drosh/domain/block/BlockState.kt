package dev.drosh.domain.block

/**
 * State of a single terminal block.
 *
 * Transitions:
 *   Idle → Running          (user pressed Enter)
 *   Running → Success       (exit code 0 received)
 *   Running → Error         (exit code != 0 received)
 *   Running → Cancelled    (user cancelled)
 */
sealed class BlockState {
    /** Prompt visible, no command entered yet. */
    object Idle : BlockState()

    /** Command dispatched, output streaming, exit code unknown. */
    object Running : BlockState()

    /** Command completed with exit code 0. */
    data class Success(val exitCode: Int) : BlockState()

    /** Command completed with non-zero exit code. */
    data class Error(val exitCode: Int) : BlockState()

    /** User interrupted before completion. */
    object Cancelled : BlockState()

    /**
     * A read-only snapshot of output the screen already showed before block
     * mode was opened. No command ran under it and no exit code was reported —
     * it is history, not a fabricated OSC 133 mark.
     */
    object History : BlockState()
}
