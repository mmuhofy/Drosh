package dev.drosh.ui.terminal

/**
 * Where the find bar asks a terminal what a query finds.
 *
 * The app is between two terminals, and they answer in two different
 * coordinate systems:
 *
 *  - [TermuxFindSource] — the vendored Termux view keeps its scrollback in a
 *    buffer of rows, so a match is a run of cells in a row, and the terminal
 *    can be scrolled to it.
 *  - [EngineFindSource] — the Rust engine keeps a bounded copy of what the
 *    session printed, so a match is a run of characters in a line of that
 *    copy, and the terminal cannot be scrolled to it.
 *
 * The find bar asks this interface and never asks what is behind it, so the
 * two paths cannot be crossed: a Termux-backed terminal is only ever searched
 * in its rows, and an engine-backed one only ever in the lines of its output
 * log.
 *
 * ## The kind is part of the answer
 *
 * [kind] says which terminal a source speaks for, and the two implementations
 * never answer in the other's coordinates. A source is also the only thing
 * that can move the viewport onto a match, and it says no when it cannot:
 * the engine's matches have no place on the screen to be moved to.
 */
sealed interface TerminalFindSource {

    /** Which terminal this source answers for. */
    val kind: TerminalFindKind

    /**
     * Every match of [query] in the terminal, oldest first.
     *
     * The order is the order the terminal's own record is in: the rows of the
     * scrollback for the vendored terminal, the lines of the output log for
     * the engine. The first match is the furthest back in history.
     */
    fun search(query: String): TerminalFindMatches

    /**
     * Move the viewport onto the match at [index] of [matches].
     *
     * False when it cannot be done: the engine's matches are lines of its
     * output log and not rows of a grid, so there is nowhere on the screen to
     * move to. False also when [matches] are not this source's own — a
     * source only ever moves the viewport for the matches it produced.
     */
    fun reveal(matches: TerminalFindMatches, index: Int): Boolean
}
