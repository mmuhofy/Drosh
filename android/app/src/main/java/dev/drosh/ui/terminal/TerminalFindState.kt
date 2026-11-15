package dev.drosh.ui.terminal

// Terminal search results removed — engine only.

/**
 * What one find query found in the terminal the user is looking at.
 *
 * The matches are the source's own, and the [kind] says whose they are: rows
 * of a Termux buffer, or lines of the engine's output log. The bar needs to
 * know, because the two cannot be used the same way — the Termux matches are
 * drawn on the screen and can be scrolled to, and the engine's are counted
 * and quoted and nothing more.
 */
data class TerminalFindState(
    /** The query these matches are the answers to. */
    val query: String,
    /** Which terminal the matches came from. */
    val kind: TerminalFindKind,
    /** The matches themselves. */
    val matches: TerminalFindMatches = TerminalFindMatches.Engine(emptyList()),
    /** Which of the matches the user is on; -1 when there are none. */
    val activeIndex: Int = -1,
    /** Override for the match count when the engine search reports it directly. */
    val matchCountOverride: Int? = null,
    /** Override for the active match's quoted text. */
    val activeTextOverride: String? = null,
    /** Viewport row of the active match, or -1 when there is none. */
    val activeRow: Int = -1,
    /** Viewport column of the active match. */
    val activeColumn: Int = 0,
    /** Viewport length of the active match. */
    val activeLength: Int = 0,
) {

    /** How many matches the query found. */
    val count: Int get() = matchCountOverride ?: matches.count

    /** The text of the match the user is on. */
    val activeText: String?
        get() = activeTextOverride ?: (matches as? TerminalFindMatches.Engine)?.hits?.getOrNull(activeIndex)?.text

    /**
     * The Termux results, with the active match applied.
     *
     * Null on the engine path: the engine's matches are lines of its output
     * log, and the highlight overlay draws runs of cells in the rows of a
     * Termux buffer. Nothing is drawn for an engine match — there is no cell
     * of it on the screen.
     */
    // Termux results removed — engine only.

}
