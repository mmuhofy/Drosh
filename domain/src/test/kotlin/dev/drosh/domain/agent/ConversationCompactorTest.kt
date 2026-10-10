package dev.drosh.domain.agent

import dev.drosh.domain.agent.AgentApproval
import dev.drosh.domain.agent.ApprovalDecision
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the agent actually remembers is decided entirely by
 * [assembleModelHistory], so it is worth asserting on the mapping directly rather
 * than through a running loop.
 */
class AssembleModelHistoryTest {

    @Test
    fun `user and assistant turns carry through`() {
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "fix the build"),
                ChatMessage.Assistant("a1", "looking now"),
            ),
        )

        assertEquals(
            listOf(LlmMessage.User("fix the build"), LlmMessage.Assistant("looking now")),
            history,
        )
    }

    @Test
    fun `a succeeded tool call is replayed as an assistant turn with its result`() {
        // The assistant message carries the calls and the results follow it: a
        // lone tool_result with nothing before it is rejected by every protocol.
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "run it"),
                ChatMessage.Assistant("a1", "looking now"),
                ChatMessage.ToolCall(
                    id = "m3",
                    callId = "call_1",
                    name = "shell",
                    summary = "npm test",
                    state = ToolCallState.Succeeded,
                    finalOutput = "exit 0",
                    arguments = buildJsonObject { put("command", "npm test") },
                ),
            ),
        )

        assertEquals(
            listOf(
                LlmMessage.User("run it"),
                LlmMessage.Assistant(
                    text = "looking now",
                    toolCalls = listOf(LlmToolCall("call_1", "shell", buildJsonObject { put("command", "npm test") })),
                ),
                LlmMessage.ToolResultMessage("call_1", "shell", "exit 0"),
            ),
            history,
        )
    }

    @Test
    fun `a call whose arguments were never stored is dropped with its result`() {
        // A tool_use with invented arguments asks the model to run something it
        // never asked for. Dropping both halves leaves a conversation it can
        // still follow; sending either half alone is a protocol error.
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "run it"),
                ChatMessage.Assistant("a1", "looking now"),
                ChatMessage.ToolCall(
                    id = "m3",
                    callId = "call_1",
                    name = "shell",
                    summary = "npm test",
                    state = ToolCallState.Succeeded,
                    finalOutput = "exit 0",
                    // No arguments: the row predates the column.
                ),
            ),
        )

        assertEquals(listOf(LlmMessage.User("run it"), LlmMessage.Assistant("looking now")), history)
    }

    @Test
    fun `a tool-only turn gets a synthesized assistant message`() {
        // The provider went straight to a tool call, so there is no assistant row
        // — but the wire still expects one carrying the call.
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "run it"),
                ChatMessage.ToolCall(
                    id = "m2",
                    callId = "call_1",
                    name = "shell",
                    summary = "npm test",
                    state = ToolCallState.Succeeded,
                    finalOutput = "exit 0",
                    arguments = buildJsonObject { put("command", "npm test") },
                ),
            ),
        )

        assertEquals(
            listOf(
                LlmMessage.User("run it"),
                LlmMessage.Assistant(
                    text = "",
                    toolCalls = listOf(LlmToolCall("call_1", "shell", buildJsonObject { put("command", "npm test") })),
                ),
                LlmMessage.ToolResultMessage("call_1", "shell", "exit 0"),
            ),
            history,
        )
    }

    @Test
    fun `two calls in one turn share one assistant message, approval or not`() {
        // An approval row lands between the calls, so grouping must not break on
        // it — the calls still belong to the turn that made them.
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "run it"),
                ChatMessage.Assistant("a1", "two things"),
                ChatMessage.ToolCall(
                    id = "m3",
                    callId = "call_1",
                    name = "read_file",
                    summary = "a",
                    state = ToolCallState.Succeeded,
                    finalOutput = "contents",
                    arguments = buildJsonObject { put("path", "a") },
                ),
                ChatMessage.Approval(
                    id = "m4",
                    approval = AgentApproval("ap1", "u1", "call_2", "write_file", "ok?"),
                    decision = ApprovalDecision.Approve,
                ),
                ChatMessage.ToolCall(
                    id = "m5",
                    callId = "call_2",
                    name = "write_file",
                    summary = "b",
                    state = ToolCallState.Succeeded,
                    finalOutput = "written",
                    arguments = buildJsonObject { put("path", "b") },
                ),
            ),
        )

        assertEquals(
            listOf(
                LlmMessage.User("run it"),
                LlmMessage.Assistant(
                    text = "two things",
                    toolCalls = listOf(
                        LlmToolCall("call_1", "read_file", buildJsonObject { put("path", "a") }),
                        LlmToolCall("call_2", "write_file", buildJsonObject { put("path", "b") }),
                    ),
                ),
                LlmMessage.ToolResultMessage("call_1", "read_file", "contents"),
                LlmMessage.ToolResultMessage("call_2", "write_file", "written"),
            ),
            history,
        )
    }

    @Test
    fun `a failed tool call is omitted rather than sent as a result`() {
        // A tool_call id with no matching result is a protocol error the provider
        // rejects outright, and a failure teaches the model nothing by being resent.
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "run it"),
                ChatMessage.ToolCall(
                    id = "m2",
                    callId = "call_1",
                    name = "shell",
                    summary = "npm test",
                    state = ToolCallState.Failed,
                    finalOutput = "exit 127",
                ),
            ),
        )

        assertTrue(history.none { it is LlmMessage.ToolResultMessage })
    }

    @Test
    fun `a cancelled tool call is omitted`() {
        val history = assembleModelHistory(
            listOf(
                ChatMessage.ToolCall(
                    id = "m1",
                    callId = "call_1",
                    name = "write_file",
                    summary = "a.tsx",
                    state = ToolCallState.Cancelled,
                    finalOutput = "user rejected",
                ),
            ),
        )

        assertTrue(history.isEmpty())
    }

    @Test
    fun `reasoning approvals and notices never reach the model`() {
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "hi"),
                ChatMessage.Reasoning("m2", "thinking about it"),
                ChatMessage.Approval(
                    id = "m3",
                    approval = AgentApproval("ap1", "c", "call_1", "write_file", "ok?"),
                ),
                ChatMessage.Notice("m4", "step limit reached"),
                ChatMessage.Failure("m5", "401"),
            ),
        )

        assertEquals(listOf(LlmMessage.User("hi")), history)
    }

    @Test
    fun `a trailing empty streaming assistant turn is dropped`() {
        // That is what the provider sends when it goes straight to a tool call;
        // sending it back makes the model repeat the empty turn.
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "go"),
                ChatMessage.Assistant("m2", "", streaming = true),
            ),
        )

        assertEquals(1, history.size)
    }

    @Test
    fun `a finished empty assistant turn is kept`() {
        // Not streaming means the run ended there; dropping it loses a turn the
        // model genuinely produced.
        assertEquals(1, assembleModelHistory(listOf(ChatMessage.Assistant("m1", ""))).size)
    }

    @Test
    fun `a restored conversation keeps the order it had`() {
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "first"),
                ChatMessage.Assistant("a1", "one"),
                ChatMessage.User("u2", "second"),
                ChatMessage.Assistant("a2", "two"),
            ),
        )

        assertEquals(listOf("first", "one", "second", "two"), history.map { it.textOrEmpty() })
    }
}

class ConversationCompactorTest {

    /** The model-facing view the loop actually holds. */
    private fun transcript(size: Int, bodyChar: Int = 2_000): List<LlmMessage> =
        (0 until size).map { index ->
            when (index % 3) {
                0 -> LlmMessage.User("q${"_".repeat(bodyChar)}")
                1 -> LlmMessage.Assistant("a${"_".repeat(bodyChar)}")
                else -> LlmMessage.ToolResultMessage(
                    callId = "call_$index",
                    name = "shell",
                    content = "out${"_".repeat(bodyChar)}",
                )
            }
        }

    @Test
    fun `a short conversation is left alone`() {
        assertFalse(ConversationCompactor.plan(transcript(6)).shouldCompact)
    }

    @Test
    fun `a long conversation is compacted`() {
        val plan = ConversationCompactor.plan(transcript(60))

        assertTrue(plan.shouldCompact)
        assertTrue("cut at ${plan.summarizeUpToSeq}", plan.summarizeUpToSeq > 0)
    }

    @Test
    fun `the cut leaves exactly the configured tail`() {
        val messages = transcript(60)
        val plan = ConversationCompactor.plan(messages)

        assertEquals(messages.size - AgentLimits.COMPACTION_KEEP_TAIL, plan.summarizeUpToSeq + 1)
    }

    @Test
    fun `the minimum message count sits above the kept window`() {
        // If the guard ever drifts below it, a conversation with nothing in the
        // middle gets "compacted" into a summary of itself.
        assertTrue(
            "minimum (${AgentLimits.COMPACTION_MIN_MESSAGES}) must exceed head+tail " +
                "(${AgentLimits.COMPACTION_KEEP_HEADING + AgentLimits.COMPACTION_KEEP_TAIL})",
            AgentLimits.COMPACTION_MIN_MESSAGES >
                AgentLimits.COMPACTION_KEEP_HEADING + AgentLimits.COMPACTION_KEEP_TAIL,
        )
    }

    @Test
    fun `one message below the minimum is not compacted even when huge`() {
        val size = AgentLimits.COMPACTION_MIN_MESSAGES - 1
        assertFalse(ConversationCompactor.plan(transcript(size, bodyChar = 40_000)).shouldCompact)
    }

    @Test
    fun `a conversation with no middle to summarise is not compacted`() {
        val headAndTailOnly = AgentLimits.COMPACTION_KEEP_HEADING + AgentLimits.COMPACTION_KEEP_TAIL
        assertFalse(ConversationCompactor.plan(transcript(headAndTailOnly, bodyChar = 40_000)).shouldCompact)
    }

    @Test
    fun `the summary names what happened rather than paraphrasing it`() {
        val summary = ConversationCompactor.summarise(
            compacted = listOf(
                LlmMessage.User("fix the vite config"),
                LlmMessage.ToolResultMessage("c1", "read_file", "vite.config.js"),
                LlmMessage.ToolResultMessage("c2", "write_file", "vite.config.js"),
                LlmMessage.ToolResultMessage("c3", "shell", "built ok"),
            ),
            fromSeq = 3,
        )

        assertTrue(summary, summary.contains("fix the vite config"))
        assertTrue(summary, summary.contains("1 shell command"))
        assertTrue(summary, summary.contains("vite.config.js"))
        assertTrue(summary, summary.contains("compacted from 4 messages"))
    }

    @Test
    fun `a huge tool result does not dominate the summary`() {
        // A `cat` of a large file would be the very thing being compacted away.
        val summary = ConversationCompactor.summarise(
            compacted = listOf(
                LlmMessage.ToolResultMessage("c1", "shell", "x".repeat(200_000)),
            ),
            fromSeq = 0,
        )

        assertTrue("summary was ${summary.length} chars", summary.length < 2_000)
        assertFalse(summary.contains("xxxxxxxxxx"))
    }

    @Test
    fun `the summary stays bounded for a very long stretch`() {
        val summary = ConversationCompactor.summarise(transcript(500), fromSeq = 490)

        assertTrue("summary was ${summary.lines().size} lines", summary.lines().size <= 45)
    }
}

private fun LlmMessage.textOrEmpty(): String = when (this) {
    is LlmMessage.User -> text
    is LlmMessage.Assistant -> text
    is LlmMessage.ToolResultMessage -> content
}
