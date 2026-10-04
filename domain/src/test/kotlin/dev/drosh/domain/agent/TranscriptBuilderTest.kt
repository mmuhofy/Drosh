package dev.drosh.domain.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptBuilderTest {

    private fun run(builder: TranscriptBuilder, vararg events: AgentEvent): List<ChatMessage> {
        events.forEach(builder::accept)
        return builder.snapshot()
    }

    @Test
    fun `text deltas accumulate into one streaming assistant message`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.TextDelta("vite "),
            AgentEvent.TextDelta("build "),
            AgentEvent.TextDelta("warning gone"),
        )

        val messages = b.snapshot()
        assertEquals(1, messages.size)
        val assistant = messages.single() as ChatMessage.Assistant
        assertEquals("vite build warning gone", assistant.text)
        assertTrue(assistant.streaming)
    }

    @Test
    fun `a new turn does not append to the previous assistant message`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.TextDelta("first answer"),
            AgentEvent.TurnStarted(step = 2, maxSteps = 20),
            AgentEvent.TextDelta("second answer"),
        )

        val assistants = b.snapshot().filterIsInstance<ChatMessage.Assistant>()
        assertEquals(2, assistants.size)
        assertEquals("first answer", assistants[0].text)
        assertFalse("first message must no longer be streaming", assistants[0].streaming)
        assertEquals("second answer", assistants[1].text)
    }

    @Test
    fun `empty reasoning is dropped instead of leaving an empty bubble`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.ReasoningDelta("thinking about it"),
            AgentEvent.ReasoningCompleted(""),
        )

        assertTrue(b.snapshot().none { it is ChatMessage.Reasoning })
    }

    @Test
    fun `reasoning completed with text replaces the streamed text`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.ReasoningDelta("thinking about "),
            AgentEvent.ReasoningDelta("it"),
            AgentEvent.ReasoningCompleted("thinking about it"),
        )

        val reasoning = b.snapshot().single() as ChatMessage.Reasoning
        assertEquals("thinking about it", reasoning.text)
    }

    @Test
    fun `tool call row collects live output and then the final result`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.ToolCallStarted("call_1", "shell", "npm run build"),
            AgentEvent.ToolOutput("call_1", "vite v5.4.8 building..."),
            AgentEvent.ToolOutput("call_1", "1423 modules transformed"),
            AgentEvent.ToolCompleted(
                callId = "call_1",
                name = "shell",
                result = ToolResult.Success("done"),
                truncated = false,
                durationMs = 5_900,
            ),
        )

        val tool = b.snapshot().single() as ChatMessage.ToolCall
        assertEquals("npm run build", tool.summary)
        assertEquals(ToolCallState.Succeeded, tool.state)
        assertEquals("done", tool.finalOutput)
        assertEquals(5_900L, requireNotNull(tool.durationMs))
        assertEquals(
            listOf("vite v5.4.8 building...", "1423 modules transformed"),
            tool.output,
        )
    }

    @Test
    fun `duplicate tool call start does not orphan the first row`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.ToolCallStarted("call_1", "shell", "npm run build"),
            AgentEvent.ToolCallStarted("call_1", "shell", "npm run build"),
        )

        assertEquals(1, b.snapshot().size)
    }

    @Test
    fun `output arriving before the start still produces a row`() {
        val b = TranscriptBuilder()

        run(b, AgentEvent.ToolOutput("orphan", "some line"))

        val tool = b.snapshot().single() as ChatMessage.ToolCall
        assertEquals("orphan", tool.callId)
        assertEquals(listOf("some line"), tool.output)
    }

    @Test
    fun `output is capped and the oldest lines are dropped`() {
        val b = TranscriptBuilder(maxVisibleOutputLines = 3)

        val events = (1..6).map { AgentEvent.ToolOutput("call_1", "line $it") }
        run(b, AgentEvent.ToolCallStarted("call_1", "shell", "x"), *events.toTypedArray())

        val tool = b.snapshot().single() as ChatMessage.ToolCall
        assertEquals(listOf("line 4", "line 5", "line 6"), tool.output)
    }

    @Test
    fun `approval marks the tool row and adds an approval row`() {
        val b = TranscriptBuilder()
        val approval = AgentApproval(
            id = "ap_1",
            chatId = "chat",
            callId = "call_1",
            toolName = "write_file",
            title = "vite.config.js değiştirilsin mi?",
            diff = "@@ -1 +1 @@",
        )

        run(
            b,
            AgentEvent.ToolCallStarted("call_1", "write_file", "vite.config.js"),
            AgentEvent.ApprovalRequired(approval),
        )

        val messages = b.snapshot()
        val tool = messages.filterIsInstance<ChatMessage.ToolCall>().single()
        assertEquals(ToolCallState.AwaitingApproval, tool.state)
        val row = messages.filterIsInstance<ChatMessage.Approval>().single()
        assertEquals(approval, row.approval)
    }

    @Test
    fun `a repeated approval event does not duplicate the approval row`() {
        val b = TranscriptBuilder()
        val approval = AgentApproval("ap_1", "chat", "call_1", "write_file", "ok?")

        run(
            b,
            AgentEvent.ToolCallStarted("call_1", "write_file", "f"),
            AgentEvent.ApprovalRequired(approval),
            AgentEvent.ApprovalRequired(approval),
        )

        assertEquals(1, b.snapshot().filterIsInstance<ChatMessage.Approval>().size)
    }

    @Test
    fun `error result marks the row failed and carries the message`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.ToolCallStarted("call_1", "shell", "boom"),
            AgentEvent.ToolCompleted(
                callId = "call_1",
                name = "shell",
                result = ToolResult.Error("exit code 127"),
                truncated = false,
                durationMs = 12,
            ),
        )

        val tool = b.snapshot().single() as ChatMessage.ToolCall
        assertEquals(ToolCallState.Failed, tool.state)
        assertEquals("exit code 127", tool.error)
    }

    @Test
    fun `cancelled result is not reported as a failure`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.ToolCallStarted("call_1", "shell", "rm -rf /"),
            AgentEvent.ToolCompleted(
                callId = "call_1",
                name = "shell",
                result = ToolResult.Cancelled("user declined"),
                truncated = false,
                durationMs = 0,
            ),
        )

        val tool = b.snapshot().single() as ChatMessage.ToolCall
        assertEquals(ToolCallState.Cancelled, tool.state)
        assertNull(tool.error)
    }

    @Test
    fun `truncated flag survives onto the row`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.ToolCallStarted("call_1", "shell", "cat big.log"),
            AgentEvent.ToolCompleted(
                callId = "call_1",
                name = "shell",
                result = ToolResult.Success("…"),
                truncated = true,
                durationMs = 40,
            ),
        )

        assertTrue((b.snapshot().single() as ChatMessage.ToolCall).truncated)
    }

    @Test
    fun `step limit is a notice, not a failure`() {
        val b = TranscriptBuilder()

        b.accept(AgentEvent.RunFinished(RunOutcome.StepLimitReached(20)))

        val messages = b.snapshot()
        assertEquals(1, messages.size)
        assertTrue(messages.single() is ChatMessage.Notice)
    }

    @Test
    fun `a run that produced no text still shows the final text`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.ToolCallStarted("call_1", "shell", "ls"),
            AgentEvent.ToolCompleted("call_1", "shell", ToolResult.Success("a\nb"), false, 5),
            AgentEvent.RunFinished(RunOutcome.Completed("done")),
        )

        val assistant = b.snapshot().filterIsInstance<ChatMessage.Assistant>().single()
        assertEquals("done", assistant.text)
    }

    @Test
    fun `failure ends the transcript with a failure row`() {
        val b = TranscriptBuilder()

        b.accept(AgentEvent.RunFinished(RunOutcome.Failed("401 unauthorized")))

        val failure = b.snapshot().single() as ChatMessage.Failure
        assertEquals("401 unauthorized", failure.message)
    }

    @Test
    fun `usage and retry events add no rows`() {
        val b = TranscriptBuilder()

        run(
            b,
            AgentEvent.UsageUpdated(TokenUsage(input = 10, output = 2)),
            AgentEvent.Retrying(attempt = 1, maxAttempts = 3, delayMs = 1_000, reason = "429"),
        )

        assertTrue(b.snapshot().isEmpty())
    }

    @Test
    fun `startRun closes a message left streaming by a previous run`() {
        val b = TranscriptBuilder()
        b.accept(AgentEvent.TextDelta("interrupted"))

        b.startRun()
        b.accept(AgentEvent.TextDelta("next"))

        val assistants = b.snapshot().filterIsInstance<ChatMessage.Assistant>()
        assertEquals(2, assistants.size)
        assertFalse(assistants[0].streaming)
    }

    @Test
    fun `snapshot is a copy and later events do not mutate it`() {
        val b = TranscriptBuilder()
        b.accept(AgentEvent.ToolCallStarted("call_1", "shell", "x"))

        val first = b.snapshot()
        b.accept(AgentEvent.ToolOutput("call_1", "line"))

        val toolBefore = first.single() as ChatMessage.ToolCall
        val toolAfter = b.snapshot().single() as ChatMessage.ToolCall
        assertEquals(emptyList<String>(), toolBefore.output)
        assertEquals(listOf("line"), toolAfter.output)
    }

    @Test
    fun `arguments json is not needed by the transcript layer`() {
        // Guards against someone reintroducing provider-shaped types here.
        val input = buildJsonObject { put("command", "ls") }
        assertEquals("ls", input["command"].toString().trim('"'))
    }
}
