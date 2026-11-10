package dev.drosh.ui.terminal

import dev.drosh.terminal.session.TerminalEngine

/**
 * The matches in a session's grid.
 *
 * ## What the engine can answer
 *
 * The engine searches its own grid and answers with the grid's coordinates:
 * the row, the column and the length of the match. A match is therefore a
 * place on the screen — which is the difference between the old engine, whose
 * matches were lines of an output log and had nowhere to be moved to, and
 * this one.
 *
 * ## What the engine cannot answer
 *
 * The *text* of a match is not in the grid: a cell holds a codepoint and its
 * colours, not the characters of a line. The text is read from the session's
 * transcript — the engine's own text of what the session printed — and the
 * match's columns are where they land in it.
 *
 * When the two do not agree — the transcript has moved on since the search,
 * or the match is in a row the transcript does not have — the quote is empty
 * rather than wrong. A quote of the wrong text is worse than no quote.
 */
class EngineFindSource(private val pane: TerminalEngine) : TerminalFindSource {

    override val kind: TerminalFindKind get() = TerminalFindKind.ENGINE

    /**
     * Every match of [query] in the grid, oldest row first.
     *
     * The match is case-insensitive, the way the app's own search is.
     */
    override fun search(query: String): TerminalFindMatches {
        if (query.isEmpty()) return TerminalFindMatches.Engine(emptyList())
        val hits = pane.find(query, SEARCH_LIMIT)
        if (hits.isEmpty()) return TerminalFindMatches.Engine(emptyList())
        val lines = pane.transcript()
        return TerminalFindMatches.Engine(
            hits.map { hit -> matchOf(hit, lines, query) },
        )
    }

    /**
     * Move the viewport onto the match at [index] of [matches].
     *
     * A match is a row of the grid, so the viewport can be moved to it: the
     * row is scrolled to, the way a terminal scrolls to a search result.
     */
    override fun reveal(matches: TerminalFindMatches, index: Int): Boolean {
        val hits = (matches as? TerminalFindMatches.Engine)?.hits ?: return false
        val hit = hits.getOrNull(index) ?: return false
        pane.scrollTo(hit.line)
        return true
    }

    /**
     * The match at [hit], with the text it covers.
     *
     * The text is empty when it cannot be read: the row the hit names is not
     * in the transcript, or the characters at those columns are not the query
     * — the grid moved between the search and the read, and the text is
     * unknown.
     */
    private fun matchOf(
        hit: Triple<Int, Int, Int>,
        lines: List<String>,
        query: String,
    ): EngineTranscriptMatch {
        val (row, column, length) = hit
        val text = quoteOf(lines.getOrNull(row), query, column, length)
        return EngineTranscriptMatch(
            line = row,
            colStart = column,
            colEnd = column + length,
            text = text,
        )
    }

    /**
     * The characters of [line] between [column] and [column + length].
     *
     * The columns are character columns, not byte offsets and not UTF-16
     * units, so the line is cut by its code points: a line with an emoji or a
     * Turkish dotless i in it has the same number of characters as the engine
     * counted.
     */
    private fun quoteOf(line: String?, query: String, column: Int, length: Int): String {
        if (line == null) return ""
        val characters = line.codePoints().toArray()
        val start = column.coerceIn(0, characters.size)
        val end = (start + length).coerceIn(start, characters.size)
        if (end <= start) return ""
        val quote = String(characters, start, end - start)
        return if (folded(quote).contentEquals(folded(query))) quote else ""
    }

    /**
     * The text with the case of each character folded.
     *
     * Folded one character at a time, the way the engine folds it: a whole
     * string lowercased can change the number of characters, and a column
     * counted in the folded text would then not be a column of the line.
     */
    private fun folded(text: String): IntArray =
        text.codePoints().map { Character.toLowerCase(it) }.toArray()

    private companion object {
        /**
         * How many matches one scan reports.
         *
         * A query like "e" can match thousands of times; the limit keeps one
         * scan from building an unbounded answer.
         */
        const val SEARCH_LIMIT = 10_000
    }
}
