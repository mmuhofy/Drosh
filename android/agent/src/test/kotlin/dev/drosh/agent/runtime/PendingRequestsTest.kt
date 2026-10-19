package dev.drosh.agent.runtime

import dev.drosh.domain.agent.ApprovalDecision
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cancellation guarantee, tested without a full run.
 *
 * The behaviour that matters is "a tool parked on an approval is released when
 * its run is cancelled". That lives entirely in [PendingRequests], and testing it
 * here is deterministic: no provider, no tool loop, no `runTest` child-cancellation
 * semantics in the way.
 *
 * AgentLoopTest's `cancelling a parked run…` exercises the same guarantee from
 * the outside and is ignored, because cancelling a run inside `runTest` trips the
 * harness's own uncaught-exception check. These tests assert the mechanism that
 * makes the guarantee true.
 */
class PendingRequestsTest {

    @Test
    fun `answering a registered request resumes it`() = runTest {
        val pending = PendingRequests()
        val waiter = pending.register("ap_1", "chat-1")

        val resumed = async { waiter.await() }
        pending.answer("ap_1", ApprovalDecision.Approve)

        assertEquals(ApprovalDecision.Approve, resumed.await())
    }

    @Test
    fun `answering an unknown id reports failure instead of throwing`() {
        val pending = PendingRequests()

        assertFalse(pending.answer("ap_missing", ApprovalDecision.Approve))
    }

    @Test
    fun `a request is removed once answered, so a replay reports failure`() = runTest {
        // The UI can tap a card that is already gone. Resuming the same deferred
        // twice would either throw or resume a second waiter that does not exist.
        val pending = PendingRequests()
        val waiter = pending.register("ap_1", "chat-1")
        val resumed = async { waiter.await() }

        assertTrue(pending.answer("ap_1", ApprovalDecision.Approve))
        assertFalse(pending.answer("ap_1", ApprovalDecision.Approve))
        assertEquals(ApprovalDecision.Approve, resumed.await())
    }

    @Test
    fun `a late answer to a released request does not crash the run`() = runTest {
        // declineAll is what cancellation calls. An answer arriving afterwards —
        // a queued tap, a retry from the UI — must find nothing and report false.
        val pending = PendingRequests()
        pending.register("ap_1", "chat-1")

        pending.declineAll("chat-1", "run cancelled")

        assertEquals(0, pending.pendingCount())
        assertFalse(pending.answer("ap_1", ApprovalDecision.Approve))
    }

    @Test
    fun `declineAll releases a parked tool with the given reason`() = runTest {
        // This is the exact guarantee the ignored AgentLoopTest is about.
        val pending = PendingRequests()
        val waiter = pending.register("ap_1", "chat-1")
        val resumed = async { waiter.await() }

        pending.declineAll("chat-1", "run cancelled")

        assertEquals(
            ApprovalDecision.Reject("run cancelled"),
            resumed.await(),
        )
    }

    @Test
    fun `declineAll only affects the chat it was called for`() = runTest {
        // Two chats can be parked on different cards at once; cancelling one must
        // leave the other's approval answerable.
        val pending = PendingRequests()
        val first = pending.register("ap_1", "chat-1")
        val second = pending.register("ap_2", "chat-2")

        val firstResumed = async { first.await() }
        val secondResumed = async { second.await() }

        pending.declineAll("chat-1", "run cancelled")

        assertEquals(ApprovalDecision.Reject("run cancelled"), firstResumed.await())
        assertTrue("chat-2 must still be answerable", pending.answer("ap_2", ApprovalDecision.Approve))
        assertEquals(ApprovalDecision.Approve, secondResumed.await())
    }

    @Test
    fun `pendingIds reports what is awaiting an answer`() {
        val pending = PendingRequests()
        pending.register("ap_1", "chat-1")
        pending.register("ap_2", "chat-2")

        assertEquals(setOf("ap_1", "ap_2"), pending.pendingIds())
        assertEquals(2, pending.pendingCount())
    }

    @Test
    fun `a deferred is only resumed once`() = runTest {
        // Guard against a subtle version of the replay problem: registering,
        // answering and declining must not double-resume one waiter.
        val pending = PendingRequests()
        val waiter = pending.register("ap_1", "chat-1")
        var completions = 0
        waiter.invokeOnCompletion { completions++ }

        pending.answer("ap_1", ApprovalDecision.Approve)
        pending.declineAll("chat-1", "run cancelled")

        assertEquals("the waiter must be resumed exactly once", 1, completions)
        assertTrue("and it completed successfully", waiter.isCompleted)
        assertEquals(ApprovalDecision.Approve, waiter.getCompleted())
    }
}
