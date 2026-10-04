package dev.drosh.agent.tool.diff

/**
 * A line diff, written here rather than borrowed.
 *
 * ## Why not `diff` and not `java-diff-utils`
 *
 * `git diff --no-index` and a real `diff` binary both have to exist inside the
 * PRoot guest. They may not — the rootfs is minimal and what is present varies
 * by version — and shelling out to produce a preview means the agent can render
 * an approval card that it is then unable to apply, or vice versa. Producing the
 * diff on the host removes the guest from the loop entirely.
 *
 * `java-diff-utils` would work, but it needs no host library either, and the only
 * part of it we would use is the text rendering. Forty lines of hunk assembly is
 * cheaper than the dependency and than learning its API.
 *
 * ## Algorithm
 *
 * Common prefix and suffix are trimmed first, which is the cheap 90%: editing one
 * line in a thousand-line file leaves a handful of lines to reconcile. What
 * remains is matched with a longest-common-subsequence table.
 *
 * The LCS table is `n × m` integers. Past [MAX_LCS_CELLS] that is tens of
 * megabytes on a phone, so beyond the cap the whole remaining block is reported
 * as one replacement. That is a worse diff, never a wrong one, and the fallback is
 * visible in the output rather than silent.
 */
object TextDiff {

    /** Largest LCS table we will build. 4M cells is ~16 MB as an IntArray. */
    private const val MAX_LCS_CELLS = 4_000_000

    /** Lines of unchanged context kept either side of a change. */
    const val DEFAULT_CONTEXT = 3

    enum class Kind { CONTEXT, ADDED, REMOVED }

    data class Line(val kind: Kind, val text: String) {
        /** The character that introduces this line in unified output. */
        val marker: Char
            get() = when (kind) {
                Kind.CONTEXT -> ' '
                Kind.ADDED -> '+'
                Kind.REMOVED -> '-'
            }
    }

    /**
     * One contiguous run of changes with surrounding context.
     *
     * Line numbers are 1-based and follow the unified-diff convention, where an
     * empty range is anchored at the line *before* it.
     */
    data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<Line>,
    ) {
        val header: String
            get() = "@@ -${range(oldStart, oldCount)} +${range(newStart, newCount)} @@"
    }

    data class Result(
        val hunks: List<Hunk>,
        val added: Int,
        val removed: Int,
    ) {
        val isEmpty: Boolean get() = hunks.isEmpty()
    }

    /**
     * Diff two texts line by line.
     *
     * @param oldLabel file name for the `---` header; omitted when blank
     * @param newLabel file name for the `+++` header; omitted when blank
     */
    fun diff(
        old: String,
        new: String,
        oldLabel: String = "",
        newLabel: String = "",
        context: Int = DEFAULT_CONTEXT,
    ): Result {
        val oldLines = splitLines(old)
        val newLines = splitLines(new)

        val script = buildScript(oldLines, newLines)
        if (script.none { it.kind != Kind.CONTEXT }) return Result(emptyList(), 0, 0)

        val hunks = groupIntoHunks(script, context)
        return Result(
            hunks = hunks,
            added = script.count { it.kind == Kind.ADDED },
            removed = script.count { it.kind == Kind.REMOVED },
        )
    }

    /** Render as unified diff text, or an empty string when nothing changed. */
    fun unified(
        old: String,
        new: String,
        oldLabel: String = "",
        newLabel: String = "",
        context: Int = DEFAULT_CONTEXT,
    ): String {
        val result = diff(old, new, context = context)
        if (result.isEmpty) return ""

        return buildString {
            if (oldLabel.isNotBlank()) append("--- a/$oldLabel\n")
            if (newLabel.isNotBlank()) append("+++ b/$newLabel\n")
            result.hunks.forEach { hunk ->
                append(hunk.header).append('\n')
                hunk.lines.forEach { line ->
                    append(line.marker).append(line.text).append('\n')
                }
            }
        }
    }

    /** `+7 -2` style summary for the approval card. */
    fun summary(result: Result): String = "+${result.added} -${result.removed}"

    // ── internals ────────────────────────────────────────────────────────

    /**
     * Split into lines without inventing a trailing empty line.
     *
     * `"a\nb\n"` and `"a\nb"` are the same text; naive splitting would report the
     * second as differing from the first by a blank line.
     */
    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split("\n").let { lines ->
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        }

    /**
     * The edit script: every line of the old file, plus every line of the new one,
     * annotated with what happened to it.
     */
    private fun buildScript(oldLines: List<String>, newLines: List<String>): List<Line> {
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size &&
            oldLines[prefix] == newLines[prefix]
        ) {
            prefix++
        }

        var suffix = 0
        while (
            suffix < oldLines.size - prefix &&
            suffix < newLines.size - prefix &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }

        val oldMiddle = oldLines.subList(prefix, oldLines.size - suffix)
        val newMiddle = newLines.subList(prefix, newLines.size - suffix)

        val script = ArrayList<Line>(oldLines.size + newMiddle.size)
        oldLines.subList(0, prefix).forEach { script += Line(Kind.CONTEXT, it) }
        script += reconcile(oldMiddle, newMiddle)
        oldLines.subList(oldLines.size - suffix, oldLines.size).forEach { script += Line(Kind.CONTEXT, it) }

        return script
    }

    /** Longest common subsequence over the trimmed middle, or a block replace. */
    private fun reconcile(oldMiddle: List<String>, newMiddle: List<String>): List<Line> {
        if (oldMiddle.isEmpty()) return newMiddle.map { Line(Kind.ADDED, it) }
        if (newMiddle.isEmpty()) return oldMiddle.map { Line(Kind.REMOVED, it) }

        if (oldMiddle.size.toLong() * newMiddle.size.toLong() > MAX_LCS_CELLS) {
            // Too big to reconcile exactly. Replacing the whole block is a coarser
            // but correct diff, and it is obviously coarser to the reader.
            return oldMiddle.map { Line(Kind.REMOVED, it) } +
                newMiddle.map { Line(Kind.ADDED, it) }
        }

        val matches = longestCommonSubsequence(oldMiddle, newMiddle)
        val script = ArrayList<Line>(oldMiddle.size + newMiddle.size)

        var i = 0
        var j = 0
        for ((x, y) in matches) {
            while (i < x) script += Line(Kind.REMOVED, oldMiddle[i++])
            while (j < y) script += Line(Kind.ADDED, newMiddle[j++])
            script += Line(Kind.CONTEXT, oldMiddle[x])
            i++
            j++
        }
        while (i < oldMiddle.size) script += Line(Kind.REMOVED, oldMiddle[i++])
        while (j < newMiddle.size) script += Line(Kind.ADDED, newMiddle[j++])

        return script
    }

    /**
     * Indices of matching lines, in order.
     *
     * Classic bottom-up LCS table. The table is `oldSize + 1` by `newSize + 1`;
     * the guard in [reconcile] is what keeps that allocation bounded.
     */
    private fun longestCommonSubsequence(
        oldLines: List<String>,
        newLines: List<String>,
    ): List<Pair<Int, Int>> {
        val n = oldLines.size
        val m = newLines.size
        val width = m + 1
        val table = IntArray((n + 1) * width)

        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i * width + j] = if (oldLines[i] == newLines[j]) {
                    table[(i + 1) * width + (j + 1)] + 1
                } else {
                    maxOf(table[(i + 1) * width + j], table[i * width + (j + 1)])
                }
            }
        }

        val pairs = ArrayList<Pair<Int, Int>>(minOf(n, m))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (oldLines[i] == newLines[j]) {
                pairs += i to j
                i++
                j++
            } else if (table[(i + 1) * width + j] >= table[i * width + (j + 1)]) {
                i++
            } else {
                j++
            }
        }
        return pairs
    }

    /**
     * Attach context to each run of changes and drop runs separated by fewer than
     * twice the context — merging those produces one readable hunk instead of two
     * that repeat the same lines.
     */
    private fun groupIntoHunks(script: List<Line>, context: Int): List<Hunk> {
        val changedIndices = script.indices.filter { script[it].kind != Kind.CONTEXT }
        if (changedIndices.isEmpty()) return emptyList()

        // Track old/new line numbers as we walk, so every hunk can be anchored.
        val oldNumbers = IntArray(script.size)
        val newNumbers = IntArray(script.size)
        var oldLine = 1
        var newLine = 1
        for (i in script.indices) {
            oldNumbers[i] = oldLine
            newNumbers[i] = newLine
            when (script[i].kind) {
                Kind.CONTEXT -> {
                    oldLine++
                    newLine++
                }

                Kind.REMOVED -> oldLine++
                Kind.ADDED -> newLine++
            }
        }

        val hunks = mutableListOf<Hunk>()
        var cursor = 0
        while (cursor < changedIndices.size) {
            var end = cursor
            // Extend while the gap to the next change is small enough that a single
            // hunk with context covers both.
            while (end + 1 < changedIndices.size &&
                changedIndices[end + 1] - changedIndices[end] <= context * 2 + 1
            ) {
                end++
            }

            val from = maxOf(0, changedIndices[cursor] - context)
            val to = minOf(
                script.size - 1,
                changedIndices[end] + context,
            )
            val slice = script.subList(from, to + 1)

            val oldCount = slice.count { it.kind != Kind.ADDED }
            val newCount = slice.count { it.kind != Kind.REMOVED }
            hunks += Hunk(
                oldStart = if (oldCount == 0) oldNumbers[from] - 1 else oldNumbers[from],
                oldCount = oldCount,
                newStart = if (newCount == 0) newNumbers[from] - 1 else newNumbers[from],
                newCount = newCount,
                lines = slice,
            )

            cursor = end + 1
        }
        return hunks
    }

    /** `12,7` or `12` for an empty range, matching the unified-diff convention. */
    private fun range(start: Int, count: Int): String =
        if (count == 0) "$start,0" else if (count == 1) "$start" else "$start,$count"
}