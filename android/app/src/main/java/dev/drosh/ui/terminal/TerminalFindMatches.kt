package dev.drosh.ui.terminal

import dev.drosh.terminal.session.TerminalSearchResults

/**
 * What one find query found in one terminal.
 *
 * The matches are the source's own: a Termux-backed terminal answers with
 * the rows of its buffer, an engine-backed one with the lines of its output
 * log. The two are kept apart here rather than flattened into one list,
 * because they cannot be used the same way — the Termux matches can be drawn
 * on the screen and scrolled to, and the engine's cannot.
 */
sealed interface TerminalFindMatches {

    /** How many matches the query found. */
    val count: Int

    /**
     * The matches of the vendored Termux terminal.
     *
     * The results are the ones the terminal's own search produced, so the
     * highlight overlay draws exactly the runs of cells the scan found.
     */
    data class Termux(val results: TerminalSearchResults) : TerminalFindMatches {
        override val count: Int get() = results.count
    }

    /**
     * The matches of the Rust engine.
     *
     * The hits are lines of the engine's output log, and every one of them
     * carries the text it matched: the engine's line, cut at the match's
     * columns. That text is what the bar quotes, because the engine cannot
     * put the match on the screen.
     */
    data class Engine(val hits: List<EngineTranscriptMatch>) : TerminalFindMatches {
        override val count: Int get() = hits.size
    }
}
