package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the agent actually remembers is decided entirely here, so it is worth
 * asserting on the mapping directly rather than through a running loop.
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
    fun `a succeeded tool call becomes the model's tool result`() {
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "run it"),
                ChatMessage.Assistant("a1", ""),
                ChatMessage.ToolCall(
                    id = "m3",
                    callId = "call_1",
                    name = "shell",
                    summary = "npm test",
                    state = ToolCallState.Succeeded,
                    finalOutput = "exit 0",
                ),
            ),
        )

        assertEquals(
            LlmMessage.ToolResultMessage("call_1", "shell", "exit 0"),
            history.last(),
        )
    }

    @Test
    fun `a failed tool call is omitted rather than sent as a result`() {
        // A tool_call id with no matching result is a protocol error the provider
        // rejects outright, and a failure is something the model learns nothing
        // useful from being re-sent.
        val history = assembleModelHistory(
            listOf(
                ChatMessage.User("u1", "run it"),
                ChatMessage.ToolCall(
                    id = "m2",
                    callId = "call_1",
                    name = "shell",
                    summary = "boom",
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
        val history = assembleModelHistory(
            listOf(ChatMessage.Assistant("m1", "", streaming = false)),
        )

        assertEquals(1, history.size)
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

    private fun transcript(size: Int, bodyChar: Int = 2_000): List<ChatMessage> =
        (0 until size).map { index ->
            when (index % 3) {
                0 -> ChatMessage.User("m$index", "q${"_".repeat(bodyChar)}")
                1 -> ChatMessage.Assistant("m$index", "a${"_".repeat(bodyChar)}")
                else -> ChatMessage.ToolCall(
                    id = "m$index",
                    callId = "call_$index",
                    name = "shell",
                    summary = "cmd $index",
                    state = ToolCallState.Succeeded,
                    finalOutput = "out${"_".repeat(bodyChar)}",
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
        assertTrue(
            "should leave the recent turns alone, cut at ${plan.summarizeUpToSeq}",
            plan.summarizeUpToSeq > 0,
        )
    }

    @Test
    fun `the cut leaves exactly the configured tail`() {
        val messages = transcript(60)
        val plan = ConversationCompactor.plan(messages)

        assertEquals(messages.size - AgentLimits.COMPACTION_KEEP_TAIL, plan.summarizeUpToSeq + 1)
    }

    @Test
    fun `a conversation too short to split is not compacted`() {
        // Plenty of characters, but no room to keep a tail and still summarise
        // something. Compacting here would replace the whole conversation with a
        // summary of itself.
        val plan = ConversationCompactor.plan(
            transcript(size = AgentLimits.COMPACTION_KEEP_HEADING + AgentLimits.COMPACTION_KEEP_TAIL + 2, bodyChar = 40_000),
        )

        assertFalse(plan.shouldCompact)
    }

    @Test
    fun `the summary names what happened rather than paraphrasing it`() {
        val summary = ConversationCompactor.summarise(
            compacted = listOf(
                ChatMessage.User("m1", "fix the vite config"),
                ChatMessage.ToolCall(
                    id = "m2",
                    callId = "c1",
                    name = "read_file",
                    summary = "vite.config.js",
                    state = ToolCallState.Succeeded,
                    finalOutput = "…",
                ),
                ChatMessage.ToolCall(
                    id = "m3",
                    callId = "c2",
                    name = "write_file",
                    summary = "vite.config.js",
                    state = ToolCallState.Succeeded,
                    finalOutput = "…",
                ),
                ChatMessage.ToolCall(
                    id = "m4",
                    callId = "c3",
                    name = "shell",
                    summary = "npm run build",
                    state = ToolCallState.Succeeded,
                    finalOutput = "…",
                ),
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
                ChatMessage.ToolCall(
                    id = "m1",
                    callId = "c1",
                    name = "shell",
                    summary = "cat big.log",
                    state = ToolCallState.Succeeded,
                    finalOutput = "x".repeat(200_000),
                ),
            ),
            fromSeq = 0,
        )

        assertTrue("summary was ${summary.length} chars", summary.length < 2_000)
        assertFalse(summary.contains("xxxxxxxxxx"))
    }

    @Test
    fun `the summary stays bounded for a very long stretch`() {
        val summary = ConversationCompactor.summarise(transcript(500), fromSeq = 490)

        assertTrue("summary was ${summary.length} lines", summary.lines().size <= 60)
    }
}

private fun LlmMessage.textOrEmpty(): String = when (this) {
    is LlmMessage.User -> text
    is LlmMessage.Assistant -> text
    is LlmMessage.ToolResultMessage -> content
}
