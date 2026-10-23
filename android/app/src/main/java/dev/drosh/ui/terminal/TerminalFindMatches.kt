package dev.drosh.ui.terminal

/**
 * What one find query found in one terminal.
 *
 * The matches are the source's own: the Rust engine answers with the lines
 * of its output log. The Termux path has been removed.
 */
sealed interface TerminalFindMatches {

    /** How many matches the query found. */
    val count: Int

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
