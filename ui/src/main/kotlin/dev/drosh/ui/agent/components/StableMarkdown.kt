package dev.drosh.ui.agent.components

/**
 * Splits streaming markdown into a part that is settled and a part that is still growing.
 *
 * A model streams an answer a token at a time, and the chat re-renders on each one. If
 * the whole message is rendered as one document then every token re-parses the whole
 * message, including the part that has not changed for twenty seconds — and the cost
 * of that grows with the length of the answer, so the worst case is exactly when the
 * user is watching.
 *
 * Cutting at the last safely-closed block boundary avoids that: the settled prefix
 * becomes a new `String`, and Compose skips a composable whose argument did not change,
 * so only the tail is recomposed. The two halves are still valid markdown separately,
 * which is what makes this safe — see [split] for the cases where it is not.
 */
internal object StableMarkdown {

    fun split(markdown: String): Split {
        if (markdown.isEmpty()) return Split("", "")
        val cut = lastSafeCut(markdown)
        if (cut <= 0) return Split("", markdown)
        return Split(markdown.substring(0, cut), markdown.substring(cut))
    }

    /**
     * Where the settled prefix ends, or 0 if nothing is settled yet.
     *
     * A cut is only allowed after a blank line that is not inside a fenced block. Three
     * things can make a blank line unsafe, and each has bitten a real answer:
     *
     *  - Inside a fence, a blank line is just part of the code. Cutting there would split
     *    one block into two, and the library would close the first and open the second.
     *  - The blank line may be the *start* of the text, which means the first block has
     *    not started.
     *  - It may be the last character, which means nothing follows it yet.
     *
     * This is a line scan rather than a parse because the answer is by definition
     * incomplete: there is no "final" text to parse once and keep. Re-parsing to find
     * the boundary would cost the very thing this class exists to avoid.
     */
    private fun lastSafeCut(text: String): Int {
        var insideFence = false
        var fenceMarker = ""
        var cut = 0
        var lineStart = 0

        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)

            val marker = fenceMarkerOf(line)
            if (marker != null) {
                if (!insideFence) {
                    insideFence = true
                    fenceMarker = marker
                } else if (marker.length >= fenceMarker.length && marker.take(fenceMarker.length) == fenceMarker) {
                    // A closing fence only counts when it is not followed by more of the
                    // same marker, because ``` inside a ```` block is content.
                    insideFence = false
                    fenceMarker = ""
                }
            }

            if (!insideFence && line.isBlank()) {
                val afterBlankLine = lineEnd + 1
                // Needs a character after the blank line, otherwise there is no tail to
                // separate and the whole message is one still-growing block.
                if (afterBlankLine in 1 until text.length) cut = afterBlankLine
            }

            if (lineEnd == text.length) break
            lineStart = lineEnd + 1
        }

        return cut
    }

    /**
     * The fence this line opens or closes, or null if it is not a fence line.
     *
     * Up to three leading spaces are allowed, which is what CommonMark specifies and
     * what a model indenting a nested fence actually produces. Anything deeper is
     * content inside a block, not a fence.
     */
    private fun fenceMarkerOf(line: String): String? {
        var i = 0
        while (i < line.length && i < 3 && line[i] == ' ') i++
        if (i >= line.length) return null
        val marker = line[i]
        if (marker != '`' && marker != '~') return null
        var j = i
        while (j < line.length && line[j] == marker) j++
        // A backtick fence's info string may not itself contain a backtick, or the line
        // is inline code rather than a fence.
        if (marker == '`' && line.substring(j).contains('`')) return null
        return line.substring(i, j)
    }

    data class Split(val stable: String, val tail: String)
}