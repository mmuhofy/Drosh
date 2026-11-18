package dev.drosh.domain.block

/**
 * The block events the terminal engine emits, in wire order.
 *
 * The engine taps `OSC 133` on the PTY read path: `A` starts a prompt, `B` the
 * command, `C` its execution and `D` its completion (with the status). It
 * serialises those into the wire format defined in `native/src/block.rs`; the
 * terminal bridge turns each record into one of these values.
 *
 * `id` correlates the three events of one command. Output records may arrive
 * in several chunks between [Started] and [Finished].
 */
sealed interface BlockEvent {
    data class Started(
        val id: String,
        val prompt: String,
        val command: String,
        val cwd: String?,
        val startedAtMs: Long,
    ) : BlockEvent

    data class Output(val id: String, val lines: List<StyledLine>) : BlockEvent

    data class Finished(
        val id: String,
        val exitCode: Int,
        val completedAtMs: Long,
    ) : BlockEvent
}
