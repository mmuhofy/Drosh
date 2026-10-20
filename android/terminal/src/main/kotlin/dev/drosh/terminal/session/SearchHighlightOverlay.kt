// Inspired by: termux/termux-app — TerminalView rendering coordinate system
// Adapted for Drosh — dev.drosh
//
// Overlay View that draws search-highlight rectangles on top of a classic
// TerminalView. Uses the TerminalRenderer's font metrics (mFontWidth,
// mFontLineSpacing, mFontLineSpacingAndAscent) and TerminalView's mTopRow to
// align highlights with terminal cells.
//
// This is the search layer and nothing else. URL links used to be painted
// here too, until the two jobs proved to be different things: search is a
// row-local text scan, links need logical-line grouping across soft-wrapped
// rows plus a press state, and their combination in one onDraw produced an
// out-of-bounds read that crashed the app. Links now live in
// [TerminalUrlOverlay], which owns the grouping, the press feedback and the
// tap resolution. What remains here is only what search needs: one list of
// matches, one pass, one rectangle per match per row.
//
// The matches come from [TerminalTranscriptSearch] rather than being found
// here. They are keyed to absolute rows, so this view's only job is to say
// where each row is on screen right now — which is also what makes the
// highlight stay on its text when the terminal scrolls: a match is a row, and
// a row moves with the content.
package dev.drosh.terminal.session

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.termux.view.TerminalView

class SearchHighlightOverlay(
    context: android.content.Context,
) : View(context, null) {

    private companion object {
        /** Search matches keep their own blue; it is not the accent. */
        const val SEARCH_HIGHLIGHT_COLOR = 0x803B82F6.toInt()

        /**
         * The match the user is on, drawn over the others.
         *
         * The only difference between the two is opacity, and that is
         * deliberate: the terminal has already painted every cell in the
         * running program's own colours, so a highlight that hides the text
         * under it is a highlight that hides the reason it is there. One
         * stronger wash says "this one" and nothing else.
         */
        const val ACTIVE_HIGHLIGHT_COLOR = 0xCC3B82F6.toInt()
    }

    var terminalView: TerminalView? = null
        set(value) {
            if (field === value) return
            field = value
            // Matches are rows of one particular screen; a new view is a new
            // screen, so nothing about the old one applies to it.
            matches = emptyList()
            activeIndex = -1
        }

    /**
     * The query the current [matches] were found for, or null for none.
     *
     * Kept so the caller can hand the overlay the query before the scan that
     * resolves it has finished: with a query set and no matches yet, nothing
     * is drawn, which is an honest state. A stale query with the previous
     * terminal's matches is not a state this can be in — the view setter
     * clears both.
     */
    var searchQuery: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Every match of [searchQuery] across the terminal's whole scrollback,
     * oldest first, each keyed to its absolute row.
     */
    var matches: List<TerminalSearchMatch> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Which of [matches] the user is on, or -1. */
    var activeIndex: Int = -1
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * The session [matches] were found in, so a new one is noticed.
     *
     * A pane does not get a new View when its session changes — the same View
     * is handed a different emulator — so nothing else would say that every
     * row on file belongs to a buffer that no longer exists. Checked on the
     * draw pass, which is the one place that runs after every change: a
     * highlight left over from the previous session would paint itself on
     * whatever now lives at that row.
     *
     * The query is kept, because the bar is still showing it and the user has
     * not taken it back; only what it found is dropped.
     */
    private var matchesSession: com.termux.terminal.TerminalSession? = null

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SEARCH_HIGHLIGHT_COLOR
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ACTIVE_HIGHLIGHT_COLOR
    }

    private val rect = Rect()

    /**
     * Sets the query, clearing what the last one found.
     *
     * A new query means the old matches are the old query's answers, and a
     * count or a highlight that referred to them would be a lie for as long
     * as the new scan took to arrive.
     */
    fun updateQuery(query: String?) {
        searchQuery = query
        matches = emptyList()
        activeIndex = -1
    }

    /** Sets the matches, and which of them the user is on. */
    fun updateMatches(results: TerminalSearchResults) {
        searchQuery = results.query
        matchesSession = terminalView?.mTermSession
        matches = results.matches
        activeIndex = results.activeIndex
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val query = searchQuery
        if (query.isNullOrEmpty()) return

        val view = terminalView ?: return
        val emulator = view.mEmulator ?: return
        val renderer = view.mRenderer ?: return

        // A new session in the same view: what is on file belongs to a buffer
        // that is gone, so it goes. The scan that re-runs for the new session
        // puts the real answer here a moment later.
        if (view.mTermSession !== matchesSession) {
            matchesSession = view.mTermSession
            matches = emptyList()
            activeIndex = -1
        }

        val rows = emulator.mRows
        val topRow = view.mTopRow

        val fontWidth = renderer.mFontWidth
        val fontLineSpacing = renderer.mFontLineSpacing
        val fontAscent = renderer.mFontLineSpacingAndAscent - renderer.mFontLineSpacing

        val activeRow = matches.getOrNull(activeIndex)?.row

        for (match in matches) {
            // Where this match's own row is on screen now. Absolute in, so
            // this subtraction is the whole story: the same match is drawn on
            // the row it belongs to however far the viewport has travelled,
            // and a row that has scrolled off is not drawn at all.
            val visRow = match.row - topRow
            if (visRow < 0 || visRow >= rows) continue

            val baselineY = (visRow + 1) * fontLineSpacing
            rect.left = (match.colStart * fontWidth).toInt()
            rect.right = (match.colEnd * fontWidth).toInt()
            rect.top = baselineY + fontAscent
            rect.bottom = rect.top + fontLineSpacing
            canvas.drawRect(rect, if (match.row == activeRow) activePaint else highlightPaint)
        }
    }
}
