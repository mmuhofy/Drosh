package dev.drosh.domain.agent

/**
 * Decides when a conversation has grown too long, and what to drop.
 *
 * ## Why this exists
 *
 * Once history is durable it never stops growing. Every turn appends, and the
 * cost is not the storage — it is that the whole conversation is resent to the
 * provider each turn. A chat twenty turns deep can carry more text than the
 * context window allows, and the failure arrives as an opaque
 * `context_length_exceeded` from the provider with nothing in the transcript
 * pointing at the cause.
 *
 * Cline calls this `generateAssistantMessageWithOverflowRecovery`: detect the
 * overflow, compact, retry. Compaction is the part that has to happen *before*
 * the provider refuses, or every long conversation ends at the same wall.
 *
 * ## What is kept
 *
 * The first exchange, because "we started by fixing the build parser" is the
 * frame everything else hangs on, and the last few turns, because that is what
 * the model is actively working on. The middle becomes a summary.
 *
 * The user is never shown this. It is an implementation detail of what the model
 * is sent, and a visible "this conversation was compacted" line would invite the
 * question of what was lost, which has no useful answer.
 */
object ConversationCompactor {

    data class Plan(
        /** True when compaction should run before the next request. */
        val shouldCompact: Boolean,
        /** Rows to keep verbatim, newest side of the conversation. */
        val keepFromSeq: Int,
        /** Rows older than this get summarised. */
        val summarizeUpToSeq: Int,
        /** Approximate characters in the older half, for the budget estimate. */
        val olderChars: Int,
    )

    fun plan(
        messages: List<LlmMessage>,
        maxChars: Int = AgentLimits.COMPACTION_THRESHOLD_CHARS,
    ): Plan {
        val none = Plan(false, 0, -1, 0)
        if (messages.size < AgentLimits.COMPACTION_MIN_MESSAGES) return none

        val charCount = messages.sumOf { it.approximateChars() }
        if (charCount <= maxChars) return none

        val headCount = AgentLimits.COMPACTION_KEEP_HEADING
        val tailCount = AgentLimits.COMPACTION_KEEP_TAIL
        if (messages.size <= headCount + tailCount) return none

        val cutIndex = messages.size - tailCount
        return Plan(
            shouldCompact = true,
            keepFromSeq = cutIndex,
            summarizeUpToSeq = cutIndex - 1,
            olderChars = messages.subList(0, cutIndex).sumOf { it.approximateChars() },
        )
    }

    /**
     * Build the summary that replaces the compacted stretch.
     *
     * Structured lines rather than prose: the model reads it back as instructions,
     * and a list of "we did X, we found Y" survives the next summarisation better
     * than a paragraph that blends them together.
     */
    fun summarise(compacted: List<LlmMessage>, fromSeq: Int): String {
        val lines = mutableListOf<String>()
        val filesTouched = linkedSetOf<String>()
        var commandsRun = 0

        compacted.take(MAX_SUMMARY_LINES).forEach { message ->
            when (message) {
                is LlmMessage.User -> lines += "- user asked: ${message.text.firstLine()}"

                is LlmMessage.Assistant ->
                    if (message.text.isNotBlank()) {
                        lines += "- assistant: ${message.text.firstLine()}"
                    }

                // A tool result arrives here as text, not as a call with arguments,
                // so the tool's name is what identifies what was touched.
                is LlmMessage.ToolResultMessage -> when (message.name) {
                    "shell" -> commandsRun++
                    "write_file", "read_file" -> filesTouched += message.content.firstLine()
                    else -> Unit
                }
            }
        }

        return buildList {
            add(
                "Earlier in this conversation (compacted from ${compacted.size} " +
                    "messages, before turn $fromSeq):",
            )
            if (commandsRun > 0) add("$commandsRun shell command(s) were run")
            if (filesTouched.isNotEmpty()) {
                add("files involved: ${filesTouched.take(MAX_FILES_LISTED).joinToString(", ")}")
            }
            addAll(lines)
        }.joinToString("\n")
    }

    /**
     * A character estimate, deliberately not a token count.
     *
     * Nothing counts tokens accurately without the provider's tokenizer, and a
     * guessed count used as a hard limit is worse than a conservative character
     * budget.
     */
    private fun LlmMessage.approximateChars(): Int = when (this) {
        is LlmMessage.User -> text.length
        is LlmMessage.Assistant -> text.length + toolCalls.size * TOOL_CALL_CHARS
        is LlmMessage.ToolResultMessage -> content.length
    } + PER_MESSAGE_OVERHEAD

    private fun String.firstLine(): String {
        val line = lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= LINE_LIMIT) line else line.take(LINE_LIMIT) + "..."
    }

    private const val LINE_LIMIT = 160
    private const val MAX_SUMMARY_LINES = 40
    private const val MAX_FILES_LISTED = 12
    private const val PER_MESSAGE_OVERHEAD = 24
    private const val TOOL_CALL_CHARS = 200
}
