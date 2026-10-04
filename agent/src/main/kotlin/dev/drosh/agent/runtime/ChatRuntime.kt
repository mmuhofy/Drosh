package dev.drosh.agent.runtime

import dev.drosh.domain.agent.LlmMessage
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-chat mutable state for one agent conversation.
 *
 * ## Why this is not in `:domain`
 *
 * It holds coroutine jobs and an atomic flag, and its methods are the ones that
 * decide whether a second run may start. Keeping that bookkeeping next to the
 * loop that owns it means the "only one run per chat" rule lives in exactly one
 * place.
 *
 * ## History is here, not in the UI
 *
 * The transcript the user sees and the history the model sees are different
 * things: the UI adds reasoning rows and tool-call rows, the model gets text and
 * tool results. Assembling the model's view in one place is what stops the two
 * from drifting apart, and it is why `AgentRequest` carries no history.
 *
 * History is per process and does not survive process death — it belongs to the
 * chat's runtime, not to its persisted metadata.
 */
internal class ChatRuntime(val chatId: String) {

    private val running = AtomicBoolean(false)

    /** Conversation history, oldest first. Mutated only by the owning run. */
    val history: MutableList<LlmMessage> = mutableListOf()

    /** The collecting coroutine of the active run, for [AgentLoop.cancel]. */
    @Volatile
    var job: Job? = null

    /**
     * Claim the chat for a run.
     *
     * @return false when a run is already in flight. The loop fails fast rather
     *         than interleaving two conversations over one history — which would
     *         produce a transcript neither run can explain.
     */
    fun tryClaim(): Boolean = running.compareAndSet(false, true)

    fun release() {
        running.set(false)
        job = null
    }

    val isRunning: Boolean get() = running.get()

    fun remember(message: LlmMessage) {
        history += message
    }

    /** Snapshot, so a failed turn can be rewound without aliasing [history]. */
    fun snapshot(): List<LlmMessage> = history.toList()
}
