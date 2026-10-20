package dev.drosh.ui.terminal

import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.engine.core.TranscriptHit

/**
 * The Rust engine's matches, read out of the session's output log.
 *
 * ## What the engine can answer
 *
 * The engine keeps a bounded copy of what the session printed — the log — and
 * searches that. The search answers with the log's own coordinates: the
 * index of the line and the column range within it.
 *
 * ## What the engine cannot answer
 *
 * A line of the log is an **output** line, not a grid row. A line the
 * terminal soft-wrapped covers several rows, and a line a program redrew with
 * a carriage return holds every redraw. So a hit has no position on the
 * screen, and there is nothing the viewport could be moved to.
 *
 * [reveal] therefore says no, and the find bar says so too: the match is
 * counted and quoted, and the screen stays where it is. Pretending otherwise
 * — moving the viewport to a row the hit does not live on — would be a lie
 * about where the match is.
 *
 * ## The quoted text
 *
 * The text of a match is the log's line, cut at the match's columns. The
 * search runs over the log's *readable* lines — the escape sequences
 * stripped — and the transcript is read the same way, so a match is always
 * quoted in the text the transcript shows.
 */
class EngineFindSource(private val session: EngineSession) : TerminalFindSource {

    override val kind: TerminalFindKind get() = TerminalFindKind.ENGINE

    /**
     * Every match of [query] in the session's output log, oldest line first.
     *
     * The match is case-insensitive, the way the app's own search is, and the
     * occurrences in a line do not overlap.
     *
     * At most [SEARCH_LIMIT] matches are reported: a log that holds more
     * matches than that has more than the limit and not exactly the limit.
     * The log holds four thousand lines, so the limit only bites on a query
     * that matches thousands of times.
     */
    override fun search(query: String): TerminalFindMatches {
        if (query.isEmpty()) return TerminalFindMatches.Engine(emptyList())
        val hits = session.searchTranscript(query, SEARCH_LIMIT)
        if (hits.isEmpty()) return TerminalFindMatches.Engine(emptyList())
        // The log is read once more for the text of the matches. The search
        // and this read are two reads of the same log, and the log moves:
        // see quoteOf for what happens when it does.
        val lines = session.transcript()
        return TerminalFindMatches.Engine(hits.map { hit -> matchOf(hit, lines, query) })
    }

    /**
     * The engine cannot be scrolled to a match, and this says so.
     *
     * There is no row to scroll to: the hit's line is a line of the output
     * log, and the grid it was drawn on has been rewritten many times since.
     */
    override fun reveal(matches: TerminalFindMatches, index: Int): Boolean = false

    /**
     * The match at [hit], with the text it covers.
     *
     * The text is empty when it cannot be read: the line the hit names is not
     * in the transcript any more — the log dropped its oldest lines between
     * the search and the read — and a quote of the wrong text is worse than
     * no quote at all.
     */
    private fun matchOf(hit: TranscriptHit, lines: List<String>, query: String): EngineTranscriptMatch {
        val text = quoteOf(lines.getOrNull(hit.line), query, hit.colStart, hit.colEnd)
        return EngineTranscriptMatch(
            line = hit.line,
            colStart = hit.colStart,
            colEnd = hit.colEnd,
            text = text,
        )
    }

    /**
     * The characters of [line] between [colStart] and [colEnd].
     *
     * The columns are character columns, not byte offsets and not UTF-16
     * units, so the line is cut by its code points: a line with an emoji or a
     * Turkish dotless i in it has the same number of characters as the engine
     * counted.
     *
     * The text is only reported when it really is the query: the engine
     * matched the folded text, and the fold is one character in and one
     * character out, so the characters at those columns fold to the query
     * exactly. When they do not, the log moved between the two reads and the
     * text is unknown.
     */
    private fun quoteOf(line: String?, query: String, colStart: Int, colEnd: Int): String {
        if (line == null) return ""
        val characters = line.codePoints().toArray()
        val start = colStart.coerceIn(0, characters.size)
        val end = colEnd.coerceIn(start, characters.size)
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
         * The log holds four thousand lines, so a query like "e" can match
         * thousands of times; the limit keeps one scan from building an
         * unbounded answer.
         */
        const val SEARCH_LIMIT = 10_000
    }
}
