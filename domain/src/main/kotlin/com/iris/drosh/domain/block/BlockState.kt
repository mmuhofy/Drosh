package com.iris.irisshell.domain.block

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
}
