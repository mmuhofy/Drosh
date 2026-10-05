package dev.drosh.domain.agent

/**
 * Folds a stream of [AgentEvent] into the transcript the UI renders.
 *
 * ## Why this lives in `:domain`
 *
 * It is the one piece of agent logic that is pure Kotlin with no Android, no
 * coroutines and no I/O, and it is where the subtle bugs live: a tool call that
 * arrives before its `ToolCallStarted`, output that arrives after the call
 * already completed, text that continues into an assistant message which was
 * never marked as streaming. Putting it here makes it unit-testable, and
 * `:domain:test` runs on every pull request.
 *
 * ## Why it is not a pure `reduce(list, event): list`
 *
 * Streaming text arrives in many small events per second. Rebuilding the whole
 * list on each one is wasteful and, more importantly, makes the streaming
 * assistant message impossible to identify as "the one currently growing"
 * without a scan. State lives in the instance instead; [snapshot] hands out an
 * immutable copy for Compose to diff.
 *
 * Not thread-safe: one instance per chat, driven from a single coroutine.
 */
class TranscriptBuilder(
    /** Visible output lines kept per tool row before the oldest are dropped. */
    private val maxVisibleOutputLines: Int = DEFAULT_MAX_VISIBLE_OUTPUT_LINES,
) {

    private val messages = mutableListOf<ChatMessage>()
    private var seq = 0

    /** Id of the assistant message currently receiving text deltas, if any. */
    private var streamingAssistantId: String? = null

    /** Id of the reasoning message currently receiving deltas, if any. */
    private var streamingReasoningId: String? = null

    /**
     * Begin a new run.
     *
     * Closes any message left streaming by a previous run so a new answer cannot
     * be appended to it. The transcript is *not* cleared: history persists across
     * prompts within a chat.
     */
    fun startRun() {
        closeStreaming()
    }

    /**
     * Seed the transcript from durable storage.
     *
     * The builder is otherwise only ever grown by [accept], so a chat reopened
     * after the process died would start empty and the next answer would be
     * appended to nothing.
     *
     * A restored assistant message is never left streaming: whatever process was
     * writing it is gone, and a row that claims to be mid-stream would sit in the
     * transcript with a caret nothing is going to move, and would swallow the next
     * delta instead of starting a new message.
     *
     * Only valid on an empty builder. Restoring over a live conversation would
     * silently discard what is on screen.
     */
    fun restore(messages: List<ChatMessage>) {
        check(this.messages.isEmpty()) { "refusing to restore over a live transcript" }
        this.messages += messages.map { message ->
            if (message is ChatMessage.Assistant) message.copy(streaming = false) else message
        }
        streamingAssistantId = null
        streamingReasoningId = null
    }

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnStarted -> closeStreaming()

            is AgentEvent.TextDelta -> appendAssistantText(event.text)

            is AgentEvent.ReasoningDelta -> appendReasoningText(event.text)

            is AgentEvent.ReasoningCompleted -> finishReasoning(event.text)

            is AgentEvent.ToolCallStarted -> openToolCall(event)

            is AgentEvent.ToolOutput -> appendToolOutput(event.callId, event.line)

            is AgentEvent.ToolProgress -> appendToolOutput(event.callId, event.text)

            is AgentEvent.ApprovalRequired -> openApproval(event.approval)

            is AgentEvent.ToolCompleted -> completeToolCall(event)

            // Usage and retry notices are transient chrome, not transcript rows.
            is AgentEvent.UsageUpdated -> Unit
            is AgentEvent.Retrying -> Unit

            is AgentEvent.TurnCompleted -> closeStreaming()

            is AgentEvent.RunFinished -> finishRun(event.outcome)
        }
    }

    /** Immutable copy for rendering. */
    fun snapshot(): List<ChatMessage> = messages.toList()

    // ── internals ────────────────────────────────────────────────────────

    private fun nextId(): String = "m${seq++}"

    private fun closeStreaming() {
        val assistantId = streamingAssistantId
        if (assistantId != null) {
            update(assistantId) { m ->
                if (m is ChatMessage.Assistant) m.copy(streaming = false) else m
            }
            streamingAssistantId = null
        }
        streamingReasoningId = null
    }

    private fun appendAssistantText(text: String) {
        if (text.isEmpty()) return
        val id = streamingAssistantId
        if (id != null && messages.any { it.id == id }) {
            update(id) { m ->
                if (m is ChatMessage.Assistant) m.copy(text = m.text + text) else m
            }
            return
        }
        val newId = nextId()
        messages += ChatMessage.Assistant(newId, text, streaming = true)
        streamingAssistantId = newId
    }

    private fun appendReasoningText(text: String) {
        if (text.isEmpty()) return
        val id = streamingReasoningId
        if (id != null && messages.any { it.id == id }) {
            update(id) { m ->
                if (m is ChatMessage.Reasoning) m.copy(text = m.text + text) else m
            }
            return
        }
        val newId = nextId()
        messages += ChatMessage.Reasoning(newId, text)
        streamingReasoningId = newId
    }

    /**
     * Reasoning is only worth a row if there is any. Providers frequently emit a
     * completed event with empty text, which would otherwise leave an empty
     * bubble in the transcript.
     */
    private fun finishReasoning(text: String) {
        val id = streamingReasoningId
        streamingReasoningId = null
        if (id == null) {
            if (text.isNotBlank()) messages += ChatMessage.Reasoning(nextId(), text)
            return
        }
        if (text.isBlank()) {
            messages.removeAll { it.id == id }
        } else {
            update(id) { m -> if (m is ChatMessage.Reasoning) m.copy(text = text) else m }
        }
    }

    private fun openToolCall(event: AgentEvent.ToolCallStarted) {
        // A duplicate start for the same call would orphan the original row.
        if (messages.any { it is ChatMessage.ToolCall && it.callId == event.callId }) return
        messages += ChatMessage.ToolCall(
            id = nextId(),
            callId = event.callId,
            name = event.name,
            summary = event.summary,
            state = ToolCallState.Running,
        )
    }

    private fun appendToolOutput(callId: String, line: String) {
        val index = messages.indexOfFirst { it is ChatMessage.ToolCall && it.callId == callId }
        if (index < 0) {
            // Output without a start: create the row rather than dropping data.
            messages += ChatMessage.ToolCall(
                id = nextId(),
                callId = callId,
                name = "?",
                summary = "",
                state = ToolCallState.Running,
                output = listOf(line),
            )
            return
        }
        val existing = messages[index] as ChatMessage.ToolCall
        val appended = existing.output + line
        val trimmed = if (appended.size > maxVisibleOutputLines) {
            appended.takeLast(maxVisibleOutputLines)
        } else {
            appended
        }
        messages[index] = existing.copy(output = trimmed)
    }

    private fun openApproval(approval: AgentApproval) {
        messages.indexOfFirst { it is ChatMessage.ToolCall && it.callId == approval.callId }
            .takeIf { it >= 0 }
            ?.let { index ->
                messages[index] = (messages[index] as ChatMessage.ToolCall)
                    .copy(state = ToolCallState.AwaitingApproval)
            }

        if (messages.none { it is ChatMessage.Approval && it.approval.id == approval.id }) {
            messages += ChatMessage.Approval(nextId(), approval)
        }
    }

    private fun completeToolCall(event: AgentEvent.ToolCompleted) {
        val index = messages.indexOfFirst {
            it is ChatMessage.ToolCall && it.callId == event.callId
        }
        if (index < 0) {
            messages += ChatMessage.ToolCall(
                id = nextId(),
                callId = event.callId,
                name = event.name,
                summary = "",
                state = stateFor(event.result),
                finalOutput = finalText(event.result),
                truncated = event.truncated,
                durationMs = event.durationMs,
                error = (event.result as? ToolResult.Error)?.message,
            )
            return
        }
        messages[index] = (messages[index] as ChatMessage.ToolCall).copy(
            state = stateFor(event.result),
            finalOutput = finalText(event.result),
            truncated = event.truncated,
            durationMs = event.durationMs,
            error = (event.result as? ToolResult.Error)?.message,
        )
    }

    private fun stateFor(result: ToolResult): ToolCallState = when (result) {
        is ToolResult.Success -> ToolCallState.Succeeded
        is ToolResult.Error -> ToolCallState.Failed
        is ToolResult.Cancelled -> ToolCallState.Cancelled
        // The loop parks instead of completing; if one arrives anyway, showing
        // it as pending is more honest than claiming it finished.
        is ToolResult.AwaitingApproval -> ToolCallState.AwaitingApproval
    }

    private fun finalText(result: ToolResult): String? = when (result) {
        is ToolResult.Success -> result.output
        is ToolResult.Error -> result.message
        is ToolResult.Cancelled -> result.reason
        is ToolResult.AwaitingApproval -> null
    }

    private fun finishRun(outcome: RunOutcome) {
        closeStreaming()
        when (outcome) {
            is RunOutcome.Completed -> {
                // The model can finish a turn with only tool calls and no text.
                // Make sure the run does not end with an invisible result.
                if (outcome.finalText.isNotBlank() && !messages.any { it is ChatMessage.Assistant }) {
                    messages += ChatMessage.Assistant(nextId(), outcome.finalText)
                }
            }

            is RunOutcome.StepLimitReached -> messages += ChatMessage.Notice(
                nextId(),
                "Adım sınırına ulaşıldı (${outcome.steps}). Devam etmek için bir şey yaz.",
            )

            is RunOutcome.RepetitiveLoop -> messages += ChatMessage.Notice(
                nextId(),
                "Model aynı çağrıyı ${outcome.repeats} kez tekrarladı (${outcome.toolName}), " +
                    "bu yüzden durduruldu.",
            )

            is RunOutcome.Cancelled -> messages += ChatMessage.Notice(nextId(), "Durduruldu.")

            is RunOutcome.Failed -> messages += ChatMessage.Failure(nextId(), outcome.message)
        }
    }

    private inline fun update(id: String, transform: (ChatMessage) -> ChatMessage) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = transform(messages[index])
    }

    companion object {
        const val DEFAULT_MAX_VISIBLE_OUTPUT_LINES: Int = 200
    }
}
