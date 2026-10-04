package dev.drosh.agent.runtime

import dev.drosh.agent.provider.ProviderRegistry
import dev.drosh.agent.tool.ToolRegistry
import dev.drosh.domain.agent.AgentApproval
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.AgentLimits
import dev.drosh.domain.agent.AgentRequest
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.RunOutcome
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private val adapter = ScriptedAdapter()


    private fun loop(
        repository: FakeProviderRepository = FakeProviderRepository(),
        vararg tools: Tool,
    ) = AgentLoop(
        providers = repository,
        registry = ProviderRegistry(setOf(adapter)),
        toolRegistry = ToolRegistry(tools.toSet()),
    )

    private fun request(
        chatId: String = "chat-1",
        prompt: String = "fix the build",
        directory: String = "/home/zsh",
    ) = AgentRequest(
        chatId = chatId,
        providerId = "openrouter",
        modelId = "anthropic/claude-sonnet-4",
        prompt = prompt,
        workingDirectory = directory,
    )

    /**
     * A run collected in the background.
     *
     * Approving or cancelling a parked run needs the collector still alive, so
     * these tests cannot use `toList()` — it would block on a loop that is waiting
     * for the very answer the test is about to send.
     *
     * Events go into a [Channel] rather than a list the test polls. A `yield()`
     * spin is a busy-wait on a `StandardTestDispatcher`, which does not reliably
     * hand the slot to the background job and is not thread-safe against it; a
     * channel receive is a real suspension point and carries the value across
     * safely.
     */
    private class LiveRun(
        scope: CoroutineScope,
        loop: AgentLoop,
        request: AgentRequest,
    ) {
        private val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        private val collected = mutableListOf<AgentEvent>()
        private val job = scope.launch {
            try {
                loop.send(request).collect { event ->
                    collected += event
                    channel.send(event)
                }
            } finally {
                // awaitToolCompletion waits on receiveCatching, which only returns
                // once the channel is closed. A cancelled run may never emit a
                // ToolCompleted, so the close is what turns "waiting for an event
                // that will never come" into a normal return.
                channel.close()
            }
        }

        /**
     * Suspend until the run parks on an approval and hand back the request.
         */
        suspend fun awaitApproval(): AgentApproval {
            while (true) {
                when (val event = channel.receive()) {
                    is AgentEvent.ApprovalRequired -> return event.approval
                    else -> Unit
                }
            }
        }

        /**
         * Suspend until the currently running tool reports a terminal result.
         *
         * Ends immediately if cancellation tore the run down before the tool could
         * finish — there is no event coming, and that is a legitimate outcome for
         * a cancelled run.
         */
        suspend fun awaitToolCompletion() {
            while (true) {
                val event = channel.receiveCatching().getOrNull() ?: return
                if (event is AgentEvent.ToolCompleted) return
            }
        }

        suspend fun finish() = job.join()

        fun outcome(): RunOutcome = collected.last().outcome()
    }

    // ── the happy path ────────────────────────────────────────────────────

    @Test
    fun `a turn with no tool calls completes and returns the text`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        adapter.script += listOf(answerTurn("All good."))

        val events = loop.send(request()).toList()

        assertEquals("All good.", (events.last().outcome() as RunOutcome.Completed).finalText)
    }

    @Test
    fun `a tool call runs and its result is fed back to the model`() = runTest {
        val shell = FakeTool("shell") { _, _ -> ToolResult.Success("exit 0") }
        val loop = loop(tools = arrayOf(shell))
        adapter.script += listOf(toolTurn("shell", args("command" to "npm run build")))
        adapter.script += listOf(answerTurn("Build passes now."))

        loop.send(request()).toList()

        assertEquals(1, shell.calls.size)
        assertEquals("npm run build", shell.calls.single().stringOf("command"))

        // The follow-up request must carry the tool result, or the model is blind.
        val result = adapter.requests[1].toolResults().single()
        assertEquals("exit 0", result.content)
    }

    @Test
    fun `history accumulates across turns in the same chat`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        adapter.script += listOf(answerTurn("first"))
        loop.send(request(prompt = "one")).toList()

        adapter.script += listOf(answerTurn("second"))
        loop.send(request(prompt = "two")).toList()

        val history = adapter.requests[1].describeHistory()
        assertTrue("expected both prompts: $history", history.contains("user: one"))
        assertTrue("expected both prompts: $history", history.contains("user: two"))
        assertTrue("expected the first answer: $history", history.contains("assistant: first"))
    }

    @Test
    fun `the system prompt and every tool schema are sent`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell"), FakeTool("read_file")))
        adapter.script += listOf(answerTurn("ok"))

        loop.send(request()).toList()

        val sent = adapter.requests.single()
        assertNotNull(sent.systemPrompt)
        assertTrue(
            "the prompt should say the environment is real",
            sent.systemPrompt!!.contains("PRoot"),
        )
        assertEquals(listOf("read_file", "shell"), sent.tools.map { it.name }.sorted())
    }

    // ── limits and repetition ─────────────────────────────────────────────

    @Test
    fun `the run stops cleanly once the step budget is spent`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        // Every call differs, so the repetition guard stays out of the way and the
        // step budget is what stops it.
        repeat(AgentLimits.MAX_STEPS + 3) { index ->
            adapter.script += listOf(toolTurn("shell", args("command" to "echo $index")))
        }

        val events = loop.send(request()).toList()

        assertEquals(
            RunOutcome.StepLimitReached(AgentLimits.MAX_STEPS),
            events.last().outcome(),
        )
        assertEquals(AgentLimits.MAX_STEPS, adapter.requests.size)
    }

    @Test
    fun `repeating the identical call stops the run early`() = runTest {
        val shell = FakeTool("shell")
        val loop = loop(tools = arrayOf(shell))
        repeat(AgentLimits.MAX_STEPS + 3) {
            adapter.script += listOf(repeatingToolTurn("shell", args("command" to "false")))
        }

        val events = loop.send(request()).toList()

        assertEquals(
            RunOutcome.RepetitiveLoop("shell", AgentLimits.REPEATED_TOOL_CALL_LIMIT),
            events.last().outcome(),
        )
        assertTrue(
            "the repetition guard should fire well before the budget; made ${adapter.requests.size} requests",
            adapter.requests.size < AgentLimits.MAX_STEPS,
        )
        assertTrue("the tool really was called repeatedly", shell.calls.size >= 3)
    }

    @Test
    fun `the same call in non-consecutive turns is allowed`() = runTest {
        val shell = FakeTool("shell")
        val loop = loop(tools = arrayOf(shell, FakeTool("read_file")))

        // A middle turn calling a *different* tool, so "ls" repeats at steps 1 and
        // 3 without being consecutive. A plain text turn in the middle would end
        // the run before the second call ever happened.
        adapter.script += listOf(toolTurn("shell", args("command" to "ls")))
        adapter.script += listOf(toolTurn("read_file", args("command" to "cat a")))
        adapter.script += listOf(toolTurn("shell", args("command" to "ls")))
        adapter.script += listOf(answerTurn("looked again"))

        val events = loop.send(request()).toList()

        assertEquals("looked again", (events.last().outcome() as RunOutcome.Completed).finalText)
        assertEquals(2, shell.calls.size)
    }

    // ── provider failures and retries ─────────────────────────────────────

    @Test
    fun `a retryable failure is retried and can succeed`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        adapter.script += listOf(failureTurn("429 rate limited", retryable = true))
        adapter.script += listOf(answerTurn("Recovered."))

        val events = loop.send(request()).toList()

        assertEquals("Recovered.", (events.last().outcome() as RunOutcome.Completed).finalText)
        assertEquals(2, adapter.requests.size)
        val retry = events.filterIsInstance<AgentEvent.Retrying>().single()
        assertEquals(1, retry.attempt)
        assertTrue(retry.reason.contains("429"))
    }

    @Test
    fun `retries are bounded and the run then fails`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        repeat(AgentLimits.MAX_PROVIDER_RETRIES + 3) {
            adapter.script += listOf(failureTurn("503 unavailable", retryable = true))
        }

        val events = loop.send(request()).toList()

        assertEquals(RunOutcome.Failed("503 unavailable"), events.last().outcome())
        assertEquals(
            "one initial attempt plus the allowed retries",
            AgentLimits.MAX_PROVIDER_RETRIES,
            adapter.requests.size,
        )
    }

    @Test
    fun `a non-retryable failure is not retried`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        adapter.script += listOf(failureTurn("401 unauthorized", retryable = false))

        val events = loop.send(request()).toList()

        assertEquals(RunOutcome.Failed("401 unauthorized"), events.last().outcome())
        assertEquals(1, adapter.requests.size)
        assertTrue(events.none { it is AgentEvent.Retrying })
    }

    @Test
    fun `a turn that already streamed text is not retried`() = runTest {
        // Retrying would append the same text to the transcript twice, and the user
        // has already read it. The partial answer is kept instead.
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        adapter.script += listOf(textThenFailureTurn("I was going to say th", "connection reset"))
        adapter.script += listOf(answerTurn("never sent"))

        val events = loop.send(request()).toList()

        assertEquals(1, adapter.requests.size)
        assertEquals(RunOutcome.Failed("connection reset"), events.last().outcome())
        assertTrue(
            "the partial text should still be visible",
            events.filterIsInstance<AgentEvent.TextDelta>().single().text.isNotEmpty(),
        )
    }

    // ── tools ─────────────────────────────────────────────────────────────

    @Test
    fun `an unknown tool name is reported to the model instead of ending the run`() = runTest {
        val shell = FakeTool("shell")
        val loop = loop(tools = arrayOf(shell))
        adapter.script += listOf(toolTurn("delete_everything", args("command" to "rm -rf /")))
        adapter.script += listOf(answerTurn("Sorry, wrong tool."))

        val events = loop.send(request()).toList()

        assertEquals("Sorry, wrong tool.", (events.last().outcome() as RunOutcome.Completed).finalText)
        val result = adapter.requests[1].toolResults().single().content
        assertTrue("should name what was asked for: $result", result.contains("delete_everything"))
        assertTrue("should list what it could have called: $result", result.contains("shell"))
    }

    @Test
    fun `a tool that throws does not end the run`() = runTest {
        val loop = loop(tools = arrayOf(ExplodingTool(), FakeTool("shell")))
        adapter.script += listOf(toolTurn("boom", args("command" to "x")))
        adapter.script += listOf(answerTurn("Moved on."))

        val events = loop.send(request()).toList()

        assertEquals("Moved on.", (events.last().outcome() as RunOutcome.Completed).finalText)
        assertTrue(adapter.requests[1].toolResults().single().content.contains("tool exploded"))
    }

    @Test
    fun `a tool called with no arguments still completes`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("list_sessions")))
        adapter.script += listOf(toolTurn("list_sessions", JsonObject(emptyMap())))
        adapter.script += listOf(answerTurn("Two sessions."))

        val events = loop.send(request()).toList()

        assertEquals("Two sessions.", (events.last().outcome() as RunOutcome.Completed).finalText)
    }

    @Test
    fun `tool output lines are streamed as they arrive`() = runTest {
        val loop = loop(tools = arrayOf(StreamingTool()))
        adapter.script += listOf(toolTurn("shell", args("command" to "npm run build")))
        adapter.script += listOf(answerTurn("Built."))

        val events = loop.send(request()).toList()

        val lines = events.filterIsInstance<AgentEvent.ToolOutput>().map { it.line }
        assertEquals(listOf("vite v5.4.8 building...", "1423 modules transformed"), lines)
    }

    @Test
    fun `a huge tool result is clipped before it reaches the model`() = runTest {
        val huge = "x".repeat(AgentLimits.TOOL_OUTPUT_MAX_CHARS * 4)
        val loop = loop(tools = arrayOf(FakeTool("shell") { _, _ -> ToolResult.Success(huge) }))
        adapter.script += listOf(toolTurn("shell", args("command" to "cat big.log")))
        adapter.script += listOf(answerTurn("Truncated."))

        val events = loop.send(request()).toList()

        val sent = adapter.requests[1].toolResults().single().content
        assertTrue(
            "history should be clipped, was ${sent.length}",
            sent.length <= AgentLimits.TOOL_OUTPUT_MAX_CHARS,
        )
        assertTrue("the marker should be present: ${sent.take(80)}", sent.contains("output truncated"))

        // And the transcript says so, rather than showing a short result that reads
        // like the whole thing.
        assertTrue(events.filterIsInstance<AgentEvent.ToolCompleted>().single().truncated)
    }

    @Test
    fun `a tool call carries a one line summary for the collapsed row`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        adapter.script += listOf(toolTurn("shell", args("command" to "npm run build")))
        adapter.script += listOf(answerTurn("done"))

        val events = loop.send(request()).toList()

        val started = events.filterIsInstance<AgentEvent.ToolCallStarted>().single()
        assertEquals("npm run build", started.summary)
    }

    // ── approval ──────────────────────────────────────────────────────────

    @Test
    fun `an approved tool runs and the run continues`() = runTest {
        val writer = ApprovingTool()
        val loop = loop(tools = arrayOf(writer))
        adapter.script += listOf(toolTurn("write_file", args("command" to "write", "path" to "a.tsx")))
        adapter.script += listOf(answerTurn("Written."))

        val run = LiveRun(this, loop, request())
        val approval = run.awaitApproval()

        assertEquals("overwrite vite.config.js?", approval.title)
        assertNotNull(approval.diff)
        assertTrue(approval.diff!!.contains("@@"))

        assertTrue(loop.answerApproval(approval.id, ApprovalDecision.Approve))
        run.finish()

        assertEquals(1, writer.executions)
        assertEquals(ApprovalDecision.Approve, writer.decision)
        assertEquals("Written.", (run.outcome() as RunOutcome.Completed).finalText)
    }

    @Test
    fun `a rejected tool does not run and the reason reaches the model`() = runTest {
        val writer = ApprovingTool()
        val loop = loop(tools = arrayOf(writer))
        adapter.script += listOf(toolTurn("write_file", args("command" to "write", "path" to "a.tsx")))
        adapter.script += listOf(answerTurn("Leaving it then."))

        val run = LiveRun(this, loop, request())
        val approval = run.awaitApproval()
        loop.answerApproval(approval.id, ApprovalDecision.Reject("that would break the build"))
        run.finish()

        assertEquals(0, writer.executions)
        val result = adapter.requests[1].toolResults().single().content
        assertTrue(
            "the reason should reach the model: $result",
            result.contains("break the build"),
        )
    }

    @Test
    fun `answering an unknown approval id returns false`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))

        assertFalse(loop.answerApproval("ap_does_not_exist", ApprovalDecision.Approve))
    }

    @Test
    fun `ask_user feeds the answer back as the tool result`() = runTest {
        val asker = AskingTool()
        val loop = loop(tools = arrayOf(asker))
        adapter.script += listOf(toolTurn("ask_user", args("command" to "which strategy?")))
        adapter.script += listOf(answerTurn("Going manual."))

        val run = LiveRun(this, loop, request())
        val approval = run.awaitApproval()

        assertEquals(listOf("automatic", "manual"), approval.options)
        loop.answerApproval(approval.id, ApprovalDecision.Answer("manual"))
        run.finish()

        assertEquals("manual", adapter.requests[1].toolResults().single().content)
    }

    @Test
    fun `state reports waiting while a run is parked on approval`() = runTest {
        val loop = loop(tools = arrayOf(ApprovingTool()))
        adapter.script += listOf(toolTurn("write_file", args("command" to "w", "path" to "a")))
        adapter.script += listOf(answerTurn("Done."))

        val run = LiveRun(this, loop, request())
        val approval = run.awaitApproval()

        // Only meaningful once the run has actually parked.
        assertTrue(
            "expected WaitingApproval, saw ${loop.state.value}",
            loop.state.value is AgentRunState.WaitingApproval,
        )
        val waiting = loop.state.value as AgentRunState.WaitingApproval
        assertEquals(setOf("chat-1"), waiting.chatIds)

        loop.answerApproval(approval.id, ApprovalDecision.Approve)
        run.finish()
        assertEquals(AgentRunState.Idle, loop.state.value)
    }

    @Test
    fun `cancelling a parked run releases the tool instead of stranding it`() = runTest {
        val writer = ApprovingTool()
        val loop = loop(tools = arrayOf(writer))
        adapter.script += listOf(toolTurn("write_file", args("command" to "w", "path" to "a")))

        val run = LiveRun(this, loop, request())
        run.awaitApproval()

        loop.cancel("chat-1")

        // cancel() resumes the parked tool with a rejection; let that unwind before
        // asserting. The channel receive suspends until the run emits its next
        // event, which happens once the tool has returned.
        run.awaitToolCompletion()

        // The tool resumed with a rejection rather than staying suspended for the
        // life of the process.
        assertNotNull("the parked tool was never released", writer.decision)
        assertEquals("run cancelled", (writer.decision as? ApprovalDecision.Reject)?.reason)
        assertEquals(0, writer.executions)

        // Join last: runTest fails the test if a child is still running at the end,
        // and joining a cancelled coroutine returns normally. Joining earlier raced
        // the tool's own unwinding, which is why the wait moved above.
        run.finish()
    }

    // ── configuration and concurrency ─────────────────────────────────────

    @Test
    fun `a missing api key fails with something actionable`() = runTest {
        val loop = loop(repository = FakeProviderRepository(key = null), tools = arrayOf(FakeTool("shell")))

        val events = loop.send(request()).toList()
        val failed = events.last().outcome() as RunOutcome.Failed

        assertTrue(failed.message.contains("OpenRouter"))
        assertTrue("should say how to fix it: ${failed.message}", failed.message.contains("Settings"))
        assertTrue("no request should have been sent", adapter.requests.isEmpty())
    }

    @Test
    fun `an unknown provider fails before any request`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))

        val events = loop.send(request().copy(providerId = "nope")).toList()

        assertEquals(RunOutcome.Failed("Unknown provider 'nope'"), events.last().outcome())
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun `with no tools registered the run refuses rather than pretending to work`() = runTest {
        val loop = loop(tools = arrayOf())

        val events = loop.send(request()).toList()

        assertTrue(
            "should explain the missing tools: ${events.last().outcome()}",
            (events.last().outcome() as RunOutcome.Failed).message.contains("No tools"),
        )
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun `two chats run at once without mixing histories`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        adapter.script += listOf(answerTurn("answer for one"))
        adapter.script += listOf(answerTurn("answer for two"))

        val one = async { loop.send(request(chatId = "one", prompt = "question one")).toList() }
        val two = async { loop.send(request(chatId = "two", prompt = "question two")).toList() }
        val eventsOne = one.await()
        val eventsTwo = two.await()

        val textsOne = eventsOne.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        val textsTwo = eventsTwo.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }

        assertEquals("answer for one", textsOne)
        assertEquals("answer for two", textsTwo)

        // Each request carries its own chat's prompt and never the other's.
        val forOne = adapter.requests.first { it.userTexts().contains("question one") }
        val forTwo = adapter.requests.first { it.userTexts().contains("question two") }
        assertFalse(forOne.userTexts().contains("question two"))
        assertFalse(forTwo.userTexts().contains("question one"))
    }

    @Test
    fun `a second run on a busy chat fails fast`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slowTool = FakeTool("shell") { _, _ ->
            gate.await()
            ToolResult.Success("done")
        }
        val loop = loop(tools = arrayOf(slowTool))
        adapter.script += listOf(toolTurn("shell", args("command" to "sleep")))
        adapter.script += listOf(answerTurn("done"))

        val first = launch { loop.send(request()).toList() }
        var spins = 0
        while (slowTool.calls.isEmpty() && spins++ < 10_000) yield()
        assertTrue("the first run never reached the tool", slowTool.calls.isNotEmpty())

        val error = runCatching { loop.send(request()).toList() }.exceptionOrNull()

        assertTrue(
            "expected a refusal, got $error",
            error is IllegalStateException && error.message!!.contains("already has a run"),
        )

        gate.complete(Unit)
        first.join()
    }

    @Test
    fun `state returns to idle when the last run finishes`() = runTest {
        val loop = loop(tools = arrayOf(FakeTool("shell")))
        adapter.script += listOf(answerTurn("done"))

        loop.send(request()).toList()

        assertEquals(AgentRunState.Idle, loop.state.value)
    }
}

// ── assertion helpers ─────────────────────────────────────────────────────

private fun AgentEvent.outcome(): RunOutcome = (this as AgentEvent.RunFinished).outcome

private fun LlmRequest.toolResults(): List<LlmMessage.ToolResultMessage> =
    messages.filterIsInstance<LlmMessage.ToolResultMessage>()

private fun LlmRequest.userTexts(): List<String> =
    messages.filterIsInstance<LlmMessage.User>().map { it.text }

private fun JsonObject.stringOf(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
