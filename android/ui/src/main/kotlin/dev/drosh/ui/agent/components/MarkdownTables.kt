package dev.drosh.ui.agent.components

/**
 * GFM tables, lifted out of the markdown before the library sees it.
 *
 * ## Why this exists at all
 *
 * `multiplatform-markdown-renderer-m3-android` parses a table — its AST has the
 * node, and the GFM flavour is on — but `markdownComponents()` has no `table`
 * parameter, so there is no slot to put a renderer in. The result is worse than
 * unsupported: the table is not dropped, it is printed. `| a | b |` and its
 * delimiter row reach the screen as the source text, wrapped as prose, which
 * looks like a renderer that failed rather than one that declined.
 *
 * So the tables are taken out here and drawn by [DroshMarkdownTable], and
 * everything between them is handed to the library unchanged. That is the whole
 * trade: one construct is ours, and the other thirty-nine stay the library's.
 *
 * ## What counts as a table
 *
 * GFM's own rule, unchanged: a header row, a delimiter row beneath it with the
 * same number of cells, and then body rows until the block ends. The delimiter
 * row is what makes this safe to run over arbitrary prose — a paragraph with two
 * pipes in it is common, a paragraph with a matching `---|---` line under it is
 * not.
 *
 * ## Quote-aware splitting
 *
 * A `|` is a cell separator only when it is *outside* anything that quotes it. A
 * cell like `` `a | b` `` is one cell, and a row whose only pipes are inside code
 * spans is not a table at all. Two things quote a pipe in markdown:
 *
 *  - **Code spans** — a run of N backticks, closed by a run of N backticks. The
 *    run *count* matters: a single backtick inside a ``` ``` ``` span is text,
 *    so the closing run has to be matched rather than searched for.
 *  - **Backslash escapes** — `\|` is a literal pipe and never separates.
 *
 * Both are handled by [splitCells], which is why the same function finds the
 * cells of the header, the delimiter and the body: they are the same row shape,
 * and a splitter that treated them differently would be a splitter with two
 * notions of a cell.
 *
 * ## Streaming
 *
 * A table arrives a row at a time, and the cut [StableMarkdown] makes — at a
 * blank line outside a fence — can never land inside one, because a table has no
 * blank lines in it. So a table is never split across the settled and growing
 * halves of an answer, which is what lets each half be scanned on its own.
 */
internal object MarkdownTables {

    /**
     * Splits [markdown] into the runs the library should parse and the tables
     * [DroshMarkdownTable] should draw, in document order.
     *
     * Text runs keep their exact source, blank lines and all, because the library
     * re-parses them as markdown and trimming them would change how consecutive
     * blocks are separated.
     */
    fun blocks(markdown: String): List<MarkdownBlock> {
        if (markdown.isEmpty()) return emptyList()
        // Cheap and almost always true: no pipe anywhere means no table
        // anywhere, and the line scan below is skipped entirely.
        if (!markdown.contains('|')) return listOf(MarkdownBlock(markdown))

        val lines = linesOf(markdown)
        val out = ArrayList<MarkdownBlock>()
        var textStart = 0
        var openFence = ""

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            val marker = StableMarkdown.fenceMarkerOf(line.text)
            if (marker != null) {
                // A closing fence only counts when it is not followed by more of
                // the same marker, because ``` inside a ```` block is content.
                openFence = when {
                    openFence.isEmpty() -> marker
                    marker.length >= openFence.length && marker.startsWith(openFence) -> ""
                    else -> openFence
                }
            }

            if (openFence.isEmpty()) {
                val table = tableAt(markdown, lines, i)
                if (table != null) {
                    if (line.start > textStart) {
                        out += MarkdownBlock(markdown.substring(textStart, line.start))
                    }
                    out += MarkdownBlock(markdown.substring(table.start, table.end), table.table)
                    textStart = table.end
                    i = table.lastLine + 1
                    continue
                }
            }

            i++
        }

        if (markdown.length > textStart) {
            out += MarkdownBlock(markdown.substring(textStart))
        }
        return out
    }

    /** A line and where it sits in the source, newline excluded. */
    private class Line(val start: Int, val end: Int, val text: String)

    private fun linesOf(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            lines += Line(start, end, text.substring(start, end))
            if (end == text.length) break
            start = end + 1
        }
        return lines
    }

    private class FoundTable(val table: GfmTable, val start: Int, val end: Int, val lastLine: Int)

    /**
     * The table beginning at [index], or null.
     *
     * Requires a body row, not just the two header lines. A streaming answer
     * produces a header and a delimiter well before the first data row, and a
     * two-line table drawn as an empty frame is a worse thing to show than the
     * text it would have replaced.
     */
    private fun tableAt(markdown: String, lines: List<Line>, index: Int): FoundTable? {
        val headerLine = lines[index]
        val delimiterLine = lines.getOrNull(index + 1) ?: return null

        val quote = quotePrefixOf(headerLine.text)
        if (quote != quotePrefixOf(delimiterLine.text)) return null

        val header = splitCells(headerLine.text.removePrefix(quote)) ?: return null
        val delimiter = splitCells(delimiterLine.text.removePrefix(quote)) ?: return null
        if (header.isEmpty() || header.size != delimiter.size) return null

        val alignments = ArrayList<GfmAlignment>(delimiter.size)
        for (cell in delimiter) {
            alignments += alignmentOf(cell) ?: return null
        }

        val rows = ArrayList<List<String>>()
        var last = index + 1
        while (last + 1 < lines.size) {
            val rowLine = lines[last + 1]
            if (rowLine.text.isBlank()) break
            if (quotePrefixOf(rowLine.text) != quote) break
            val bare = rowLine.text.removePrefix(quote)
            if (startsAnotherBlock(bare)) break
            val cells = splitCells(bare) ?: break
            // GFM pads a short row and cuts a long one: the table has as many
            // columns as its header, whatever the body claimed.
            rows += List(header.size) { c -> cells.getOrElse(c) { "" } }
            last++
        }
        if (rows.isEmpty()) return null

        // The table owns the newline that closed its last row too, so two tables
        // written one after the other do not end up sharing it.
        val lastLine = lines[last]
        val end = if (markdown.getOrNull(lastLine.end) == '\n') lastLine.end + 1 else lastLine.end
        return FoundTable(
            table = GfmTable(header, alignments, rows),
            start = headerLine.start,
            end = end,
            lastLine = last,
        )
    }

    /**
     * The `>` markers a line carries, each with the space that follows it.
     *
     * Empty rather than null for a line that is not quoted. An unquoted table is
     * the common case, and "no markers" and "not a table line" would then be the
     * same answer — which they are not. Returned rather than stripped in place
     * because a table inside a blockquote keeps its markers in the source, and
     * only the text that follows them is split on pipes.
     */
    private fun quotePrefixOf(line: String): String {
        var i = 0
        while (i < 3 && i < line.length && line[i] == ' ') i++
        if (i >= line.length || line[i] != '>') return ""
        val prefix = StringBuilder()
        while (i < line.length && line[i] == '>') {
            prefix.append('>')
            i++
            if (i < line.length && line[i] == ' ') {
                prefix.append(' ')
                i++
            }
        }
        return prefix.toString()
    }

    /**
     * Whether a body row is really the beginning of the next block.
     *
     * GFM ends a table at a blank line or at the start of another block-level
     * construct. Without this, a list item written straight after a table would
     * be absorbed into it as a one-cell row.
     */
    private fun startsAnotherBlock(line: String): Boolean {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed[0] == '>') return true
        if (trimmed[0] == '#') {
            val hashes = trimmed.takeWhile { it == '#' }
            val rest = trimmed.substring(hashes.length)
            return hashes.length in 1..5 && (rest.isEmpty() || rest[0] == ' ')
        }
        val first = trimmed[0]
        if ((first == '-' || first == '*' || first == '+') && trimmed.getOrNull(1) == ' ') return true
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.isNotEmpty()) {
            val rest = trimmed.substring(digits.length)
            if (rest.length >= 2 && (rest[0] == '.' || rest[0] == ')') && rest[1] == ' ') return true
        }
        return false
    }

    /** `---`, `:---`, `---:` or `:---:` — or null when the cell is not one. */
    private fun alignmentOf(cell: String): GfmAlignment? {
        var body = cell.trim()
        if (body.isEmpty()) return null
        val left = body.startsWith(':')
        if (left) body = body.substring(1)
        // Both colons are peeled in that order, and the right-hand test cannot be
        // guarded on the left one: `:---:` carries both, and a guard would leave
        // its trailing colon in the body and reject the cell.
        val right = body.endsWith(':')
        if (right) body = body.substring(0, body.length - 1)
        if (body.isEmpty() || body.any { it != '-' }) return null
        return when {
            left -> GfmAlignment.CENTER
            right -> GfmAlignment.END
            else -> GfmAlignment.START
        }
    }

    /**
     * Splits a row into its cells, or null when the row has no unquoted pipe.
     *
     * Null rather than a one-cell list because the caller uses it to decide
     * whether the row is a table row at all: `` `a | b` `` yields nothing, which
     * is the signal that this is prose containing a pipe.
     */
    fun splitCells(row: String): List<String>? {
        var i = 0
        while (i < row.length && i < 3 && row[i] == ' ') i++
        // The leading pipe is optional, and belongs to the row rather than to a
        // cell: `| a | b |` is two cells, not four.
        if (i < row.length && row[i] == '|') i++
        if (i >= row.length) return null

        val cells = ArrayList<String>()
        val current = StringBuilder()
        var sawPipe = false
        var endedWithPipe = false

        while (i < row.length) {
            val c = row[i]
            when {
                // An escaped pipe is a pipe in the cell, not a separator.
                c == '\\' && i + 1 < row.length && row[i + 1] == '|' -> {
                    current.append('|')
                    i += 2
                }

                c == '`' -> {
                    val run = backtickRunAt(row, i)
                    val marker = "`".repeat(run)
                    val close = row.indexOf(marker, i + run)
                    if (close < 0) {
                        // An unclosed code span. CommonMark says the backticks are
                        // literal text, and so does this.
                        current.append(marker)
                        i += run
                    } else {
                        current.append(row, i, close + run)
                        i = close + run
                    }
                }

                c == '|' -> {
                    sawPipe = true
                    cells += current.toString()
                    current.setLength(0)
                    endedWithPipe = true
                    i++
                }

                else -> {
                    endedWithPipe = false
                    current.append(c)
                    i++
                }
            }
        }
        if (!sawPipe) return null
        // The trailing pipe is optional too, and the empty cell after it is not
        // a column.
        if (!endedWithPipe || current.isNotBlank()) cells += current.toString()
        return cells.map { it.trim() }
    }

    /** How many backticks start at [index]. */
    private fun backtickRunAt(row: String, index: Int): Int {
        var n = 0
        while (index + n < row.length && row[index + n] == '`') n++
        return n
    }
}

/** One piece of a message: either markdown for the library, or a table for us. */
internal data class MarkdownBlock(
    val source: String,
    /** Null when the library renders this piece. */
    val table: GfmTable? = null,
)

/** A parsed GFM table. Cell text is raw markdown, minus the cell separators. */
internal data class GfmTable(
    val header: List<String>,
    val alignments: List<GfmAlignment>,
    val rows: List<List<String>>,
)

/** How a column's cells sit inside their column. GFM has three, and no justify. */
internal enum class GfmAlignment { START, CENTER, END }