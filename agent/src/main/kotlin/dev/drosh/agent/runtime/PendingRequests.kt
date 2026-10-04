package dev.drosh.agent.runtime

import dev.drosh.domain.agent.ApprovalDecision
import kotlinx.coroutines.CompletableDeferred

/**
 * Parks a tool until the user answers, keyed by approval id.
 *
 * ## Why the id and not the chat
 *
 * A chat can have several approvals outstanding at once — a model that emits
 * parallel tool calls, each wanting consent. Keying by chat would let one answer
 * resolve the wrong request. The id travels with the `AgentApproval` the UI
 * renders, so the reply carries the same handle back.
 *
 * ## Why `tryResume` rather than a plain complete
 *
 * `answerApproval` can arrive after the run was already cancelled, which would
 * leave the deferred un-awaited forever and its tool suspended forever. Every
 * await site also selects on the call's job so cancellation releases it either
 * way, but resuming an abandoned deferred is harmless and keeps the map from
 * growing without bound.
 *
 * Safe to call from any thread: the loop runs one coroutine per chat, but the UI
 * answers from another.
 */
internal class PendingRequests {

    private data class Entry(
        val chatId: String,
        val waiter: CompletableDeferred<ApprovalDecision>,
    )

    private val pending = mutableMapOf<String, Entry>()

    /** Register a request for [chatId] and return the handle to await it with. */
    fun register(approvalId: String, chatId: String): CompletableDeferred<ApprovalDecision> =
        CompletableDeferred<ApprovalDecision>().also { waiter ->
            synchronized(pending) { pending[approvalId] = Entry(chatId, waiter) }
        }

    /**
     * Deliver an answer.
     *
     * @return false when no such request is pending. The interface surfaces that
     *         to the caller instead of silently swallowing a tap on a card that
     *         is already gone.
     */
    fun answer(approvalId: String, decision: ApprovalDecision): Boolean {
        val entry = synchronized(pending) { pending.remove(approvalId) } ?: return false
        return entry.waiter.tryResume(decision)
    }

    /**
     * Decline everything outstanding for [chatId].
     *
     * Called when a run is cancelled: the tools parked on those requests resume
     * immediately with a rejection instead of holding their coroutines until the
     * process dies.
     */
    fun declineAll(chatId: String, reason: String) {
        val abandoned = synchronized(pending) {
            val ids = pending.filterValues { it.chatId == chatId }.keys.toList()
            ids.mapNotNull { pending.remove(it) }
        }
        abandoned.forEach { it.waiter.tryResume(ApprovalDecision.Reject(reason)) }
    }

    fun pendingCount(): Int = synchronized(pending) { pending.size }

    /** Ids currently awaiting an answer, for diagnostics and tests. */
    fun pendingIds(): Set<String> = synchronized(pending) { pending.keys.toSet() }
}
