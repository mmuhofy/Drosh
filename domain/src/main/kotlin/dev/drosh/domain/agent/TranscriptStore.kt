package dev.drosh.domain.agent

/**
 * Durable storage for a chat's transcript.
 *
 * ## The history the model sees
 *
 * The model really does remember. On restore, [assembleHistory] rebuilds the
 * `LlmMessage` list from the same rows the transcript renders, so "you told me
 * to use MVVM" works after a restart instead of the agent asking what it is
 * working on.
 *
 * That is why this is an interface over rows rather than a blob of formatted
 * text: the model-facing view has to be *derived* from the stored one. Two
 * separate stores would drift, and the symptom of drift is a model contradicting
 * something the user can see on their own screen.
 */
interface TranscriptStore {

    /** Every row for a chat, oldest first. */
    suspend fun load(chatId: String): List<ChatMessage>

    /**
     * Replace a chat's transcript with the user's view of it.
     *
     * Replaces rather than appends, and the distinction matters: the loop
     * already wrote the model-facing rows for this conversation, and appending a
     * second description of the same turns would restore as duplicates. Since the
     * user's view is a superset — everything the model was told plus the reasoning,
     * approvals and notices only the user saw — it is the whole transcript once
     * written.
     *
     * Until it is written, [load] returns the model-facing rows, which are enough
     * to render the conversation. That is what makes a run that is killed
     * mid-flight still leave a readable transcript behind.
     */
    suspend fun save(chatId: String, messages: List<ChatMessage>)

    /**
     * Replace a chat's whole transcript with the model-facing view of a run.
     *
     * The loop knows the conversation as `LlmMessage`s and never builds the
     * user-facing rows — reasoning, approvals and notices have no `LlmMessage`
     * equivalent and the loop would be inventing them. The UI keeps writing those
     * rows as it observes events, so the two views are written by the two that
     * actually have them, into the same ordered table.
     */
    suspend fun saveModelView(chatId: String, messages: List<LlmMessage>)

    /** Replace a chat's whole transcript — used after compaction. */
    suspend fun replaceAll(chatId: String, messages: List<ChatMessage>)

    suspend fun clear(chatId: String)

    /**
     * Build the conversation the model is given.
     *
     * Rows that are for the user alone are skipped rather than summarised into
     * the model's view: reasoning is the model's own and it does not need to read
     * it back, and an approval already reached the model as a tool result.
     */
    fun assembleHistory(messages: List<ChatMessage>): List<LlmMessage>
}

/**
 * Turns transcript rows into the conversation the model is sent.
 *
 * Kept in `:domain` as a pure function so the mapping is unit-testable and has no
 * storage dependency — it is the part that decides whether the agent actually
 * remembers, and it is worth being able to assert on directly.
 *
 * ## Tool calls belong to the assistant turn that made them
 *
 * A transcript stores an assistant row followed by its tool-call rows. Sent
 * separately that is a `tool_result` with no `tool_use` before it, which all four
 * protocols reject outright. So a turn is rebuilt as one assistant message
 * carrying its calls, then the results.
 *
 * A turn made of tool calls and no text has no assistant row at all, so one is
 * synthesized with empty text — that is what the wire expects, and it is what
 * the loop itself writes during a live run.
 *
 * ## An approval row sits between the calls
 *
 * The builder puts the approval after the call it belongs to, so two calls in
 * one turn have an approval row between them. Breaks on `User` or a new
 * `Assistant`, never on an approval.
 */
fun assembleModelHistory(messages: List<ChatMessage>): List<LlmMessage> {
    val assembled = mutableListOf<LlmMessage>()

    /** Tool calls of the turn being read, in order. */
    var pendingTools = mutableListOf<ChatMessage.ToolCall>()

    /** Text of the turn's assistant message, held until the turn is closed. */
    var pendingText: String? = null

    /** True when the turn is one the model should see an assistant turn for. */
    var turnHasAssistant = false

    /**
     * Close the turn being read.
     *
     * The assistant message is emitted here rather than when its row was read,
     * because the wire wants the text and the tool calls in the *same* message:
     * emitting it separately would produce an assistant turn with no calls
     * followed by another with no text, which is two turns the model never had.
     */
    fun flush() {
        // A call whose arguments were never stored cannot be replayed: a
        // `tool_use` with invented arguments asks the model to run something it
        // never asked for. Dropping the call and its result together leaves a
        // conversation the model can still follow.
        val replayable = pendingTools.filter { it.arguments.isNotEmpty() }

        if (turnHasAssistant || replayable.isNotEmpty()) {
            assembled += LlmMessage.Assistant(
                text = pendingText.orEmpty(),
                toolCalls = replayable.map { LlmToolCall(it.callId, it.name, it.arguments) },
            )
        }
        assembled += replayable.map {
            LlmMessage.ToolResultMessage(it.callId, it.name, it.finalOutput.orEmpty())
        }

        pendingTools.clear()
        pendingText = null
        turnHasAssistant = false
    }

    messages.forEach { message ->
        when (message) {
            is ChatMessage.User -> {
                flush()
                assembled += LlmMessage.User(message.text)
            }

            is ChatMessage.Assistant -> {
                flush()
                // A trailing empty assistant turn is what the provider sent when
                // it went straight to a tool call. Sending it back would make
                // the model repeat the empty turn — so it is dropped, and the
                // turn the calls belong to is synthesized at flush instead.
                if (message.text.isEmpty() && message.streaming) {
                    turnHasAssistant = false
                    pendingText = null
                } else {
                    turnHasAssistant = true
                    pendingText = message.text
                }
            }

            is ChatMessage.ToolCall -> {
                // Only a finished call has a result the model can read. A call
                // that was cancelled or is still running has nothing to report,
                // and omitting it leaves a dangling tool_call id in the history.
                if (message.state == ToolCallState.Succeeded && message.finalOutput != null) {
                    pendingTools += message
                }
            }

            // Reasoning, approvals and notices were never part of what the model
            // was told, so they have no place in what it is told now.
            is ChatMessage.Reasoning,
            is ChatMessage.Approval,
            is ChatMessage.Failure,
            is ChatMessage.Notice,
            -> Unit
        }
    }
    flush()
    return assembled
}
