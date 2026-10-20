package dev.drosh.ui.terminal

import com.termux.view.TerminalView
import dev.drosh.terminal.session.TerminalSearchResults
import dev.drosh.terminal.session.TerminalTranscriptSearch

/**
 * The vendored Termux terminal's matches, read out of its own scrollback.
 *
 * The scan is the app's own search, unchanged: the emulator's buffer is read
 * from the oldest row to the newest, and every run of cells the query covers
 * is a match. The matches are the rows of that buffer, so the terminal can be
 * scrolled to the one the user is on — which is what the find bar's arrows
 * have always done.
 */
class TermuxFindSource(private val view: TerminalView) : TerminalFindSource {

    override val kind: TerminalFindKind get() = TerminalFindKind.TERMUX

    /**
     * Every match of [query] in the view's transcript, oldest row first.
     *
     * The matching is case-insensitive, because a shell's output is not
     * written with a capitalisation convention anyone searches by.
     */
    override fun search(query: String): TerminalFindMatches =
        TerminalFindMatches.Termux(
            TerminalSearchResults(
                query = query,
                matches = TerminalTranscriptSearch().search(view, query),
            ),
        )

    /**
     * Scrolls the terminal to the row the match at [index] is on.
     *
     * The row is the emulator's own absolute row, so the terminal scrolls to
     * the match wherever the output has moved it to.
     */
    override fun reveal(matches: TerminalFindMatches, index: Int): Boolean {
        val results = (matches as? TerminalFindMatches.Termux)?.results ?: return false
        val row = results.matches.getOrNull(index)?.row ?: return false
        view.revealRow(row)
        return true
    }
}
