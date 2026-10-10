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
// tap resolution. What remains here is only what search needs: one query, one
// pass, one rectangle per match per row.
package dev.drosh.terminal

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
    }

    var terminalView: TerminalView? = null
    var searchQuery: String? = null

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SEARCH_HIGHLIGHT_COLOR
    }

    private val rect = Rect()

    /** The visible rows as text. Rows the emulator could not resolve stay null. */
    private class RowSnapshot(
        val texts: Array<String?>,
    )

    private fun readRows(): RowSnapshot? {
        val view = terminalView ?: return null
        val emulator = view.mEmulator ?: return null
        val screen = emulator.getScreen()
        val rows = emulator.mRows
        val topRow = view.mTopRow

        val texts = arrayOfNulls<String>(rows)
        for (visRow in 0 until rows) {
            val externalRow = topRow + visRow
            val lineObject = try {
                screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow))
            } catch (_: IllegalArgumentException) {
                continue
            }
            texts[visRow] = String(lineObject.mText, 0, lineObject.spaceUsed)
        }
        return RowSnapshot(texts)
    }

    fun updateQuery(query: String?) {
        searchQuery = query
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val view = terminalView ?: return
        val emulator = view.mEmulator ?: return
        val renderer = view.mRenderer ?: return

        val columns = emulator.mColumns
        val rows = emulator.mRows

        val fontWidth = renderer.mFontWidth
        val fontLineSpacing = renderer.mFontLineSpacing
        val fontAscent = renderer.mFontLineSpacingAndAscent - renderer.mFontLineSpacing

        val snapshot = readRows() ?: return
        val rowTexts = snapshot.texts

        val query = searchQuery
        if (query.isNullOrEmpty()) return

        val lowerQuery = query.lowercase()
        for (visRow in 0 until rows) {
            val text = rowTexts[visRow] ?: continue
            if (text.isEmpty()) continue
            val lowerText = text.lowercase()
            var start = 0
            while (true) {
                val idx = lowerText.indexOf(lowerQuery, start)
                if (idx == -1) break
                val matchEnd = idx + query.length
                val colStart = idx.coerceAtMost(columns - 1)
                val colEnd = matchEnd.coerceAtMost(columns)
                if (colStart >= colEnd) break
                val baselineY = (visRow + 1) * fontLineSpacing
                rect.left = (colStart * fontWidth).toInt()
                rect.right = (colEnd * fontWidth).toInt()
                rect.top = baselineY + fontAscent
                rect.bottom = rect.top + fontLineSpacing
                canvas.drawRect(rect, highlightPaint)
                start = matchEnd
            }
        }
    }
}
