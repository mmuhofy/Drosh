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
                    val rowOffset = texts[groupStart..targetRow].sumOf { (it ?: "").length }
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
                    val baselineY = ((visRow + 1) * fontLineSpacing).toFloat()
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
     * it, and paints the cells of every row the match covers. Each row gets at
     * most one rectangle; rows fully inside a URL are drawn square, the first
     * and last rows keep the rounded shape.
     */
    private fun drawLogicalLineUrls(
        canvas: Canvas,
        rowTexts: Array<String?>,
        groupStart: Int,
        groupEnd: Int,
        columns: Int,
        fontWidth: Int,
        fontLineSpacing: Int,
        fontAscent: Int,
    ) {
        val builder = StringBuilder()
        val rowOffsets = IntArray(groupEnd - groupStart + 1)
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

                val x1 = (colStart * fontWidth).toFloat()
                val x2 = (colEnd * fontWidth).toFloat()
                val baselineY = ((index + 1) * fontLineSpacing).toFloat()
                val top = baselineY + fontAscent

                val isFirstRow = segmentStart == match.start
                val isLastRow = segmentEnd == match.end
                if (isFirstRow && isLastRow) {
                    canvas.drawRoundRect(x1, top, x2, baselineY, 4f, 4f, urlBackgroundPaint)
                } else if (isFirstRow) {
                    canvas.drawRoundRect(x1, top, x2, baselineY, 4f, 4f, urlBackgroundPaint, 4f, 4f, 0f, 0f)
                } else if (isLastRow) {
                    canvas.drawRoundRect(x1, top, x2, baselineY, 4f, 4f, 0f, 0f, 4f, 4f)
                } else {
                    canvas.drawRect(x1, top, x2, baselineY, urlBackgroundPaint)
                }
            }
        }
    }
}
