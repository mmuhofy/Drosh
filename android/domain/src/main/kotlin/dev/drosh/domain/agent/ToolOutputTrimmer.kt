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
 * ## Why head *and* tail
 *
 * The useful parts of command output sit at opposite ends — the invocation and
 * the summary at the top, the error at the bottom. A plain `take(n)` throws the
 * error away, which is the one line the model most needs. So the character and
 * line budgets are split between the two ends and the total is guaranteed to
 * stay within `maxChars`, marker included.
 *
 * ## Why slices are aligned to line boundaries
 *
 * Cutting mid-line produces output the model reads as a truncated file rather
 * than as a clipped log. Alignment is skipped when the text has no newline at
 * all, so a single enormous line still yields a bounded result instead of
 * collapsing to nothing.
 */
object ToolOutputTrimmer {

    data class Trimmed(val text: String, val truncated: Boolean)

    fun trim(
        output: String,
        maxChars: Int = AgentLimits.TOOL_OUTPUT_MAX_CHARS,
        maxLines: Int = AgentLimits.TOOL_OUTPUT_MAX_LINES,
    ): Trimmed {
        val tooLong = output.length > maxChars
        val tooManyLines = countLines(output) > maxLines
        if (!tooLong && !tooManyLines) return Trimmed(output, truncated = false)

        val marker = truncationMarker(maxChars, maxLines)
        // Reserve room for the marker and for the two newlines joining the three
        // pieces, so the result is bounded by maxChars and not merely near it.
        val budget = (maxChars - marker.length - 2).coerceAtLeast(MIN_BUDGET)
        val headBudget = budget / 2
        val tailBudget = budget - headBudget
        val headLineCap = maxLines / 2
        val tailLineCap = maxLines - headLineCap

        val head = takeHead(output, headBudget, headLineCap, cut = tooLong)
        val tail = takeTail(output, tailBudget, tailLineCap, cut = tooLong)

        val text = buildString {
            append(head)
            if (head.isNotEmpty() && !head.endsWith("\n")) append('\n')
            append(marker)
            if (tail.isNotEmpty() && !tail.startsWith("\n")) append('\n')
            append(tail)
        }.trimEnd('\n')

        return Trimmed(text, truncated = true)
    }

    /** Tells the model output was removed so it knows to re-read if it needs it. */
    fun truncationMarker(maxChars: Int, maxLines: Int): String =
        "… [output truncated: $maxLines line / $maxChars char limit]"

    private fun takeHead(text: String, budget: Int, maxLines: Int, cut: Boolean): String {
        var slice = if (cut && text.length > budget) text.substring(0, budget) else text
        if (cut && slice.length < text.length) {
            val nl = slice.lastIndexOf('\n')
            if (nl > 0) slice = slice.substring(0, nl)
        }
        val lines = slice.lines()
        // Head keeps the *first* lines: the invocation is at the top.
        if (lines.size > maxLines) slice = lines.take(maxLines).joinToString("\n")
        return slice
    }

    private fun takeTail(text: String, budget: Int, maxLines: Int, cut: Boolean): String {
        var slice = if (cut && text.length > budget) {
            text.substring(text.length - budget)
        } else {
            text
        }
        if (cut && slice.length < text.length) {
            val nl = slice.indexOf('\n')
            if (nl in 0 until slice.length - 1) slice = slice.substring(nl + 1)
        }
        val lines = slice.lines()
        // Tail keeps the *last* lines: the error is at the bottom.
        if (lines.size > maxLines) slice = lines.takeLast(maxLines).joinToString("\n")
        return slice
    }

    private fun countLines(text: String): Int {
        var count = 1
        for (c in text) {
            if (c == '\n') {
                count++
                if (count > AgentLimits.TOOL_OUTPUT_MAX_LINES * 4) return count
            }
        }
        return count
    }

    /** Below this there is nothing worth splitting between head and tail. */
    private const val MIN_BUDGET = 64
}
