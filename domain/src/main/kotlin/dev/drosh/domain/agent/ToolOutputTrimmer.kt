package dev.drosh.domain.agent

/**
 * Clips tool output before it enters the conversation history.
 *
 * Tool results are the one part of the context an agent cannot ration itself —
 * the model asks for `cat somefile` and gets whatever the file contains. On a
 * phone the failure mode is not immediate: a 200 KB result is accepted, the
 * request succeeds, and the run dies several turns later when the context
 * window fills, with nothing in the transcript pointing at the cause.
 *
 * Head *and* tail are kept because the useful parts of command output are
 * usually at opposite ends — the invocation and the summary at the top, the
 * error at the bottom. A plain `take(n)` throws away the error.
 */
object ToolOutputTrimmer {

    data class Trimmed(val text: String, val truncated: Boolean)

    fun trim(
        output: String,
        maxChars: Int = AgentLimits.TOOL_OUTPUT_MAX_CHARS,
        maxLines: Int = AgentLimits.TOOL_OUTPUT_MAX_LINES,
    ): Trimmed {
        if (output.length <= maxChars && lineCountWithin(output, maxLines)) {
            return Trimmed(output, truncated = false)
        }

        val head = output.take(AgentLimits.TOOL_OUTPUT_HEAD_CHARS)
        val tail = if (output.length > AgentLimits.TOOL_OUTPUT_TAIL_CHARS) {
            output.takeLast(AgentLimits.TOOL_OUTPUT_TAIL_CHARS)
        } else {
            ""
        }

        val headLines = head.lines().takeLast(maxLines / 2)
        val tailLines = if (tail.isEmpty()) emptyList() else tail.lines().take(maxLines - maxLines / 2)

        val body = (headLines + AgentLimits.TRUNCATION_MARKER.trim('\n') + tailLines)
            .joinToString("\n")

        // The marker is longer than the slices it replaced in pathological cases
        // (a single 100k-char line); fall back to a hard tail cut rather than
        // returning something larger than the input.
        val text = if (body.length > maxChars) body.takeLast(maxChars) else body
        return Trimmed(text, truncated = true)
    }

    private fun lineCountWithin(output: String, maxLines: Int): Boolean {
        var count = 1
        for (c in output) {
            if (c == '\n') {
                count++
                if (count > maxLines) return false
            }
        }
        return true
    }
}
