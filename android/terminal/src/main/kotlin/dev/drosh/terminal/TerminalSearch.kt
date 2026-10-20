package dev.drosh.terminal.session

import com.termux.terminal.TerminalBuffer
import com.termux.view.TerminalView

/**
 * One match: the cells of one row the query covers.
 *
 * The row is **absolute**, in the emulator's external coordinate system
 * (`-activeTranscriptRows .. mScreenRows-1`), never the viewport's. A match
 * keyed to the viewport is only right for as long as the viewport stands
 * still: scrolling moves the text and leaves the highlight behind, and a
 * keyed-to-nothing highlight is worse than none. Anchored to the row itself,
 * the same answer is right for as long as the buffer holds that row.
 *
 * Columns are the match's span in that row's cells, clamped into
 * `0..mColumns` — a match the break at the right edge of the grid can only
 * be drawn as far as the grid goes.
 */
data class TerminalSearchMatch(
    val row: Int,
    val colStart: Int,
    val colEnd: Int,
)

/**
 * What one query found across one terminal's whole scrollback.
 *
 * Oldest first, which is the order the transcript is read in and the order
 * next/previous walk it in: the first match is the furthest back in history
 * and the last is the newest line on screen. [activeIndex] is the match the
 * user is on, or -1 when there is nothing to be on.
 */
data class TerminalSearchResults(
    val query: String,
    val matches: List<TerminalSearchMatch>,
    /** Which of [matches] is the current one. -1 when there are none. */
    val activeIndex: Int = -1,
) {
    val count: Int get() = matches.size

    val activeMatch: TerminalSearchMatch? get() = matches.getOrNull(activeIndex)

    companion object {
        val EMPTY = TerminalSearchResults("", emptyList(), -1)
    }
}

/**
 * Reads a terminal's whole scrollback and finds every match of a query in it.
 *
 * The transcript, not the screen: a match that only reports what is currently
 * visible is a match count that changes as you scroll, and a next button that
 * cannot reach anything above the top row. Both are the difference between
 * search and a filter.
 *
 * Reads every row once, longest-lived first, so one pass answers both the
 * count and the highlight. No caching on purpose: the buffer moves under the
 * reader on every chunk of output, and a scan of a few thousand rows of text
 * is a scan of a few thousand rows of text — cheap enough to do again rather
 * than tricky enough to get wrong.
 */
class TerminalTranscriptSearch {

    /**
     * Every match of [query] in [view]'s transcript, oldest row first.
     *
     * Matching is case-insensitive, because a shell's output is not written
     * with a capitalisation convention anyone searches by.
     */
    fun search(view: TerminalView, query: String): List<TerminalSearchMatch> {
        if (query.isEmpty()) return emptyList()
        val emulator = view.mEmulator ?: return emptyList()
        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val transcriptRows = screen.activeTranscriptRows
        if (columns <= 0) return emptyList()

        // The whole buffer, transcript and screen both: the transcript alone
        // cannot answer "is there a match on the line I am typing at". Read
        // once into a list rather than streamed, so the matching below is one
        // pure function over plain text — and so it can be tested without an
        // emulator, which is the only way a rule like "oldest row first" ever
        // gets pinned.
        val rows = ArrayList<String?>(transcriptRows + emulator.mRows)
        for (externalRow in -transcriptRows until emulator.mRows) {
            rows += rowText(screen, externalRow)
        }

        return matchTranscript(rows, query, baseRow = -transcriptRows, columns = columns)
    }

    /**
     * The row's text, or null when the emulator cannot resolve it.
     *
     * `allocateFullLineIfNecessary` is what the renderer reads too, so a row
     * that has been scrolled into the circular buffer but never fully
     * materialised still answers with the text it has rather than throwing.
     */
    private fun rowText(screen: TerminalBuffer, externalRow: Int): String? {
        val lineObject = try {
            screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return String(lineObject.mText, 0, lineObject.spaceUsed)
    }
}


/**
 * Every match of [query] across [rows], oldest first.
 *
 * Pure, and deliberately so: this is where the answer to "where is my match"
 * is decided, and the rules in it — row order, column clamping, one match per
 * run of cells — are the ones a terminal's grid makes non-obvious. Keeping it
 * free of the emulator is what lets those rules be tested at all.
 *
 * [baseRow] is the external row [rows]`[0]` answers for, which is
 * `-activeTranscriptRows` for a whole-buffer scan: a match reported against
 * row 0 when it lives a scrollback back is a highlight drawn on somebody
 * else's text.
 *
 * A row that answers null is skipped, and that is not the same as a row with
 * no match in it: a null row is one the buffer could not resolve, so its text
 * is unknown, while an empty one is a blank line.
 */
internal fun matchTranscript(
    rows: List<String?>,
    query: String,
    baseRow: Int,
    columns: Int,
): List<TerminalSearchMatch> {
    if (query.isEmpty() || columns <= 0) return emptyList()
    val lowerQuery = query.lowercase()
    val matches = ArrayList<TerminalSearchMatch>()

    rows.forEachIndexed { index, text ->
        if (text.isNullOrEmpty()) return@forEachIndexed
        val lowerText = text.lowercase()
        var from = 0
        while (true) {
            val at = lowerText.indexOf(lowerQuery, from)
            if (at < 0) break
            val colStart = at.coerceAtMost(columns - 1)
            val colEnd = (at + lowerQuery.length).coerceAtMost(columns)
            // A match clipped to nothing by a short row is not a match: the
            // rectangle would be zero wide, and a zero-wide rectangle is not
            // a highlight, it is a rounding error.
            if (colStart < colEnd) {
                matches += TerminalSearchMatch(
                    row = baseRow + index,
                    colStart = colStart,
                    colEnd = colEnd,
                )
            }
            // Past the match just found, so the two halves of "aa" in "aaa"
            // are one match and not two overlapping ones.
            from = at + lowerQuery.length
        }
    }
    return matches
}
