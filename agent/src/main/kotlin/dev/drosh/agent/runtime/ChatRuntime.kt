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

    /**
     * True when no run is attached to this runtime.
     *
     * The loop uses it to decide whether the runtime may be dropped from its
     * map: a finished run leaves one behind and the map never shrinks, so every
     * chat ever run in this process stays resident for the life of the process.
     */
    fun isIdle(): Boolean = !running.get() && job == null

    fun remember(message: LlmMessage) {
        history += message
    }

    /** Snapshot, so a caller cannot mutate the live history through the copy. */
    fun snapshot(): List<LlmMessage> = history.toList()

    /**
     * Adopt a restored conversation wholesale.
     *
     * Only called when [history] is empty — the first run of a chat in this
     * process — so a run in progress can never be clobbered by a reload.
     */
    fun restore(messages: List<LlmMessage>) {
        check(history.isEmpty()) { "refusing to restore over a live conversation" }
        history.clear()
        history += messages
    }

    /** The oldest [count] messages. */
    fun take(count: Int): List<LlmMessage> =
        if (count <= 0) emptyList() else history.take(count.coerceAtMost(history.size))

    /**
     * Replace everything before [keepFromSeq] with [summary].
     *
     * The summary is written as a user turn rather than a system one: a
     * mid-conversation system message is not something the Chat Completions
     * protocol reliably accepts, and a user turn is indistinguishable in role
     * from the report it stands in for.
     */
    fun compactTo(summary: String, keepFromSeq: Int) {
        if (keepFromSeq <= 0) return
        val kept = history.drop(keepFromSeq.coerceAtMost(history.size))
        history.clear()
        history += LlmMessage.User(summary)
        history += kept
    }
}
