// Inspired by: termux/termux-app — TerminalView rendering coordinate system
// Adapted for Drosh — dev.drosh
//
// Overlay View that draws search-highlight rectangles and URL link surfaces
// on top of a classic TerminalView. Uses the TerminalRenderer's font metrics
// (mFontWidth, mFontLineSpacing, mFontLineSpacingAndAscent) and TerminalView's
// mTopRow to align highlights with terminal cells.
//
// URLs are detected across *logical* lines, not screen rows. A terminal hard
// wraps long output, so a single URL routinely spans two rows. Scanning each
// row independently matched only the truncated first half and left the
// continuation row unmarked; grouping rows via TerminalBuffer.getLineWrap()
// detects the whole URL and paints every row it covers.
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

    private val urlBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#33719FFF") // DroshPrimary 20%
    }

    private val rect = Rect()

    /**
     * The visible rows, plus whether each one soft-wraps into the next. Grouping
     * on this is what lets a URL that the terminal split across rows be detected
     * and hit-tested as one link.
     */
    private class RowSnapshot(
        val texts: Array<String?>,
        val continues: BooleanArray,
    )

    private fun readRows(): RowSnapshot? {
        val view = terminalView ?: return null
        val emulator = view.mEmulator ?: return null
        val screen = emulator.getScreen()
        val rows = emulator.mRows
        val topRow = view.mTopRow

        val texts = arrayOfNulls<String>(rows)
        val continues = BooleanArray(rows)
        for (visRow in 0 until rows) {
            val externalRow = topRow + visRow
            val lineObject = try {
                screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow))
            } catch (_: IllegalArgumentException) {
                continue
            }
            texts[visRow] = String(lineObject.mText, 0, lineObject.spaceUsed)
            // Shares externalToInternalRow's bound check, so it throws in step.
            continues[visRow] = try {
                screen.getLineWrap(externalRow)
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        return RowSnapshot(texts, continues)
    }

    /**
     * The full URL covering the cell at [externalRow], [col], or null.
     *
     * Resolves against the same logical lines the highlight is painted from, so
     * tapping the continuation row of a wrapped URL opens the whole link
     * instead of the fragment the terminal happened to break it at.
     */
    fun urlAtCell(externalRow: Int, col: Int): String? {
        val snapshot = readRows() ?: return null
        val topRow = terminalView?.mTopRow ?: return null
        val texts = snapshot.texts
        val continues = snapshot.continues

        var groupStart = 0
        while (groupStart < texts.size) {
            var groupEnd = groupStart
            while (groupEnd < texts.size && continues[groupEnd] && texts[groupEnd] != null) groupEnd++
            if (groupEnd > groupStart) {
                val joined = StringBuilder()
                for (visRow in groupStart..groupEnd) joined.append(texts[visRow] ?: "")
                val logicalText = joined.toString()
                val targetRow = externalRow - topRow
                if (targetRow in groupStart..groupEnd) {
                    var rowOffset = 0
                    for (visRow in groupStart until targetRow) rowOffset += texts[visRow]?.length ?: 0
                    val offset = rowOffset + col
                    UrlDetector.findUrls(logicalText)
                        .firstOrNull { offset >= it.start && offset < it.end }
                        ?.let { return it.url }
                }
            }
            groupStart = groupEnd + 1
        }
        return null
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
        val rowContinues = snapshot.continues

        val query = searchQuery
        if (!query.isNullOrEmpty()) {
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
                    val baselineY = (visRow + 1) * fontLineSpacing
                    rect.left = (colStart * fontWidth).toInt()
                    rect.right = (colEnd * fontWidth).toInt()
                    rect.top = baselineY + fontAscent
                    rect.bottom = baselineY + fontLineSpacing
                    canvas.drawRect(rect, highlightPaint)
                    start = matchEnd
                }
            }
        }

        if (!showUrlHighlights) return

        var groupStart = 0
        while (groupStart < rows) {
            var groupEnd = groupStart
            while (groupEnd < rows && rowContinues[groupEnd] && rowTexts[groupEnd] != null) groupEnd++

            if (groupEnd > groupStart) {
                drawLogicalLineUrls(canvas, rowTexts, groupStart, groupEnd, columns, fontWidth, fontLineSpacing, fontAscent)
            }
            groupStart = groupEnd + 1
        }
    }

    /**
     * Joins rows [groupStart, groupEnd] into one logical line, detects URLs in
     * it, and paints the cells of every row the match covers. A link that stays
     * on one row keeps its rounded chip; one the terminal split across rows is
     * painted square per row.
     */
    private fun drawLogicalLineUrls(
        canvas: Canvas,
        rowTexts: Array<String?>,
        groupStart: Int,
        groupEnd: Int,
        columns: Int,
        fontWidth: Float,
        fontLineSpacing: Int,
        fontAscent: Int,
    ) {
        val builder = StringBuilder()
        // One slot per row for its start offset, plus a trailing slot holding the
        // end of the last row, so segmentEnd can be read for any row.
        val rowOffsets = IntArray(groupEnd - groupStart + 2)
        for (visRow in groupStart..groupEnd) {
            rowOffsets[visRow - groupStart] = builder.length
            builder.append(rowTexts[visRow] ?: "")
        }
        rowOffsets[groupEnd - groupStart + 1] = builder.length

        val logicalText = builder.toString()
        if (logicalText.isEmpty()) return

        for (match in UrlDetector.findUrls(logicalText)) {
            for (index in groupStart..groupEnd) {
                val rowStart = rowOffsets[index - groupStart]
                val rowEnd = rowOffsets[index - groupStart + 1]
                val segmentStart = maxOf(match.start, rowStart)
                val segmentEnd = minOf(match.end, rowEnd)
                if (segmentStart >= segmentEnd) continue

                val colStart = (segmentStart - rowStart).coerceIn(0, columns)
                val colEnd = (segmentEnd - rowStart).coerceIn(0, columns)
                if (colStart >= colEnd) continue

                val x1 = colStart * fontWidth
                val x2 = colEnd * fontWidth
                val baselineY = (index + 1) * fontLineSpacing
                val topY = (baselineY + fontAscent).toFloat()
                // The cell ends exactly on the baseline, which left the last
                // pixel of the row uncovered. One physical pixel of padding,
                // not one dp: the canvas here is in pixels.
                val bottomY = baselineY + 1f

                val isFirstRow = segmentStart == match.start
                val isLastRow = segmentEnd == match.end
                // A match wholly inside one row keeps the rounded chip. A match
                // the terminal split across rows is painted square per row; the
                // per-corner Canvas overload that would round only the outer ends
                // is not available at this platform level.
                if (isFirstRow && isLastRow) {
                    canvas.drawRoundRect(x1, topY, x2, bottomY, 4f, 4f, urlBackgroundPaint)
                } else {
                    canvas.drawRect(x1, topY, x2, bottomY, urlBackgroundPaint)
                }
            }
        }
    }
}
