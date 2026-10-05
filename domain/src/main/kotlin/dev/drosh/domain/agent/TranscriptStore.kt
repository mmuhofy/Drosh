package dev.drosh.domain.agent

/**
 * Durable storage for a chat's transcript.
 *
 * ## The history the model sees
 *
 * Option (a) — the model really does remember. On restore, [assembleHistory]
 * rebuilds the `LlmMessage` list from the same rows the transcript renders, so
 * "you told me to use MVVM" works after a restart instead of the agent asking
 * what it is working on.
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
     * Append or update rows.
     *
     * Rows carry their own stable id, so re-saving a message that has streamed
     * further replaces it rather than duplicating it.
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
 */
fun assembleModelHistory(messages: List<ChatMessage>): List<LlmMessage> =
    messages.mapNotNull { message ->
        when (message) {
            is ChatMessage.User ->
                LlmMessage.User(message.text)

            is ChatMessage.Assistant -> {
                // A trailing empty assistant turn is what the provider sent when it
                // went straight to a tool call. Sending it back would make the model
                // repeat the empty turn.
                if (message.text.isEmpty() && message.streaming) {
                    null
                } else {
                    LlmMessage.Assistant(message.text)
                }
            }

            is ChatMessage.ToolCall -> {
                // Only a finished call has a result the model can read. A call that
                // was cancelled or is still running has nothing to report, and
                // omitting it leaves a dangling tool_call id in the history.
                val result = message.finalOutput
                if (message.state != ToolCallState.Succeeded || result == null) {
                    null
                } else {
                    LlmMessage.ToolResultMessage(
                        callId = message.callId,
                        name = message.name,
                        content = result,
                    )
                }
            }

            // Reasoning, approvals and notices were never part of what the model
            // was told, so they have no place in what it is told now.
            is ChatMessage.Reasoning,
            is ChatMessage.Approval,
            is ChatMessage.Failure,
            is ChatMessage.Notice,
            -> null
        }
    }
