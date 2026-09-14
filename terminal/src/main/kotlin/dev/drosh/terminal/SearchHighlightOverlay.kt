// Inspired by: termux/termux-app — TerminalView rendering coordinate system
// Adapted for Drosh — dev.drosh
//
// Overlay View that draws search-highlight rectangles AND URL underlines on top
// of a classic TerminalView. Uses the TerminalRenderer's font metrics
// (mFontWidth, mFontLineSpacing, mFontLineSpacingAndAscent) and TerminalView's
// mTopRow to align highlights with terminal cells.
package dev.drosh.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.termux.view.TerminalView
import dev.drosh.domain.UrlDetector

class SearchHighlightOverlay(
    context: android.content.Context,
) : View(context, null) {

    var terminalView: TerminalView? = null
    var searchQuery: String? = null
    var showUrlHighlights: Boolean = true

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#803B82F6")
    }

    private val urlUnderlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.parseColor("#FF3B82F6")
        strokeWidth = 2f
    }

    private val rect = Rect()

    fun updateQuery(query: String?) {
        searchQuery = query
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val view = terminalView ?: return
        val emulator = view.mEmulator ?: return
        val renderer = view.mRenderer ?: return

        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val rows = emulator.mRows
        val topRow = view.mTopRow

        val fontWidth = renderer.mFontWidth
        val fontLineSpacing = renderer.mFontLineSpacing
        val fontAscent = renderer.mFontLineSpacingAndAscent - renderer.mFontLineSpacing

        val query = searchQuery
        val lowerQuery = query?.lowercase()

        for (visRow in 0 until rows) {
            val externalRow = topRow + visRow
            val internalRow = screen.externalToInternalRow(externalRow)

            val lineObject = screen.allocateFullLineIfNecessary(internalRow)
            val text = String(lineObject.mText, 0, lineObject.spaceUsed)
            if (text.isEmpty()) continue

            val lowerText = text.lowercase()
            var charIdx = 0

            if (!lowerQuery.isNullOrEmpty()) {
                var start = 0
                while (true) {
                    val idx = lowerText.indexOf(lowerQuery, start)
                    if (idx == -1) break
                    val matchEnd = idx + query.length
                    val colStart = idx.coerceAtMost(columns - 1)
                    val colEnd = matchEnd.coerceAtMost(columns)
                    rect.left = (colStart * fontWidth).toInt()
                    rect.right = (colEnd * fontWidth).toInt()
                    rect.top = (visRow + 1) * fontLineSpacing + fontAscent
                    rect.bottom = rect.top + fontLineSpacing
                    canvas.drawRect(rect, highlightPaint)
                    start = matchEnd
                }
            }

            if (showUrlHighlights) {
                for (match in UrlDetector.findUrls(text)) {
                    val colStart = match.start.coerceAtMost(columns - 1)
                    val colEnd = match.end.coerceAtMost(columns)
                    val x1 = (colStart * fontWidth).toFloat()
                    val x2 = (colEnd * fontWidth).toFloat()
                    val baselineY = ((visRow + 1) * fontLineSpacing).toFloat()
                    canvas.drawLine(x1, baselineY, x2, baselineY, urlUnderlinePaint)
                }
            }
        }
    }
}
