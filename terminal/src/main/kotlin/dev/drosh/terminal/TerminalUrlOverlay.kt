// Inspired by: termux/termux-app — TerminalView rendering coordinate system
// Adapted for Drosh — dev.drosh
//
// Overlay View that paints URL links on top of a classic TerminalView. Uses
// the TerminalRenderer's font metrics (mFontWidth, mFontLineSpacing,
// mFontLineSpacingAndAscent) and TerminalView's mTopRow to align the link
// markers with terminal cells.
//
// URLs are detected across *logical* lines, not screen rows. A terminal hard
// wraps long output, so a single URL routinely spans two rows. Scanning each
// row independently matched only the truncated first half and left the
// continuation row unmarked; grouping rows via TerminalBuffer.getLineWrap()
// detects the whole URL and paints every row it covers.
//
// This is the link layer and nothing else. It split away from
// SearchHighlightOverlay, which used to carry both jobs, because the two jobs
// are different things: search is a row-local text scan, links need
// logical-line grouping and a press state, and the URL branch of the old
// class's onDraw drew with a group end that could equal the row count — an
// out-of-bounds read that crashed the app whenever a soft-wrapped line
// reached the last visible row. [TerminalUrlOverlayTest] pins that history.
// The search half lives on in SearchHighlightOverlay.
package dev.drosh.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.termux.view.TerminalView
import dev.drosh.core.DroshPalette
import dev.drosh.domain.UrlDetector

class TerminalUrlOverlay(
    context: android.content.Context,
) : View(context, null) {

    private companion object {
        const val URL_SURFACE_ALPHA = 0x33
        const val URL_UNDERLINE_ALPHA = 0x99

        /**
         * Groups visible rows [0, rowCount) into logical lines, each as the
         * inclusive range of rows it covers.
         *
         * `continues[i]` says row *i* soft-wraps into row *i+1*. A logical
         * line is therefore a run of rows that keep continuing, and it ends at
         * the first row that does not — or at the bottom of the screen, when
         * the run wrapped past the last visible row.
         *
         * The bottom case is the one that matters. The grouping loop cannot
         * step past `rowCount`, so its cursor lands *on* `rowCount` rather
         * than on the last row, and a caller that walks the group inclusively
         * from that cursor reads one row beyond the array. The old code did
         * exactly that and crashed; clamping to `rowCount - 1` here is the
         * fix, made once instead of at every call site. Never returns a range
         * containing an index outside `[0, rowCount)`.
         */
        internal fun logicalLineGroups(rowCount: Int, continues: BooleanArray): List<IntRange> {
            if (rowCount <= 0) return emptyList()
            val groups = ArrayList<IntRange>()
            var start = 0
            while (start < rowCount) {
                var end = start
                while (end < rowCount && end < continues.size && continues[end]) end++
                // `end` is the first row that does not continue — the last row
                // of the logical line — or rowCount when the run reached the
                // bottom. Either way the last addressable row of the line.
                val last = minOf(end, rowCount - 1)
                groups += IntRange(start, last)
                // `last + 1`, not `end + 1`: at the bottom of the screen they
                // differ, and this must end the loop rather than exceed it.
                start = last + 1
            }
            return groups
        }
    }

    /** An accent tint at [alpha] out of 255, for `Paint.color`. */
    private fun accentAt(alpha: Int): Int =
        (alpha shl 24) or (DroshPalette.PRIMARY.toInt() and 0x00FFFFFF)

    /** Physical pixels of bleed added above and below a URL highlight. */
    private val urlHighlightPadding =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            1f,
            context.resources.displayMetrics,
        )

    var terminalView: TerminalView? = null

    /**
     * When false the overlay draws nothing and resolves no taps. The View
     * stays in the tree: detaching it would also drop the touch wiring, and a
     * disabled link layer costs one early return per draw.
     */
    var enabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * Called when a tap resolves to a URL. Opening it — in-app browser or
     * otherwise — is the caller's business; the overlay only knows the link.
     */
    var onUrlClick: ((String) -> Unit)? = null

    /**
     * The link currently held down, and the only one that gets a filled surface.
     *
     * The terminal has already painted each cell in whatever colour the running
     * program chose, and a canvas overlay cannot recolour it, so an underline is
     * what marks a link at rest. The surface is held back for the press, which
     * matches how the block engine renders links: accent text with an
     * underline, plus a surface while pressed.
     */
    var pressedUrl: String? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val urlBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentAt(URL_SURFACE_ALPHA)
    }

    /**
     * The resting-state link marker. The terminal has already painted each cell
     * in the running program's own colour and an overlay cannot recolour it, so
     * an underline is the only way to say "this run of text is a link" without
     * hiding the output. The surface is reserved for a held link.
     */
    private val urlUnderlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentAt(URL_UNDERLINE_ALPHA)
        strokeWidth = 2f
    }

    /** The visible rows, plus whether each one soft-wraps into the next. */
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
        val view = terminalView ?: return null
        val topRow = view.mTopRow
        val texts = snapshot.texts
        if (texts.isEmpty()) return null

        val targetRow = externalRow - topRow
        for (group in logicalLineGroups(texts.size, snapshot.continues)) {
            if (targetRow !in group) continue
            val joined = StringBuilder()
            for (visRow in group) joined.append(texts[visRow] ?: "")
            val logicalText = joined.toString()
            var rowOffset = 0
            for (visRow in group.first until targetRow) rowOffset += texts[visRow]?.length ?: 0
            val offset = rowOffset + col
            return UrlDetector.findUrls(logicalText)
                .firstOrNull { offset >= it.start && offset < it.end }
                ?.url
        }
        return null
    }

    /**
     * Tracks which link a finger is holding. Returns false so the terminal
     * still receives the event and keeps its own touch handling.
     */
    fun onTerminalTouch(event: MotionEvent): Boolean {
        val view = terminalView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!enabled) {
                    pressedUrl = null
                    return false
                }
                val cell = view.getColumnAndRow(event, false) ?: return false
                val col = cell[0]
                val row = cell[1]
                if (col < 0 || row < 0) {
                    pressedUrl = null
                    return false
                }
                pressedUrl = urlAtCell(view.mTopRow + row, col)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressedUrl = null
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabled) return

        val view = terminalView ?: return
        val emulator = view.mEmulator ?: return
        val renderer = view.mRenderer ?: return

        val snapshot = readRows() ?: return
        val rowTexts = snapshot.texts
        val columns = emulator.mColumns

        val fontWidth = renderer.mFontWidth
        val fontLineSpacing = renderer.mFontLineSpacing
        val fontAscent = renderer.mFontLineSpacingAndAscent - renderer.mFontLineSpacing

        for (group in logicalLineGroups(rowTexts.size, snapshot.continues)) {
            drawLogicalLineUrls(canvas, rowTexts, group, columns, fontWidth, fontLineSpacing, fontAscent)
        }
    }

    /**
     * Joins the rows of [group] — one logical line, every index inside the row
     * array by construction — detects URLs in it, and paints the cells of every
     * row the match covers. A link that stays on one row keeps its rounded
     * chip; one the terminal split across rows is painted square per row.
     */
    private fun drawLogicalLineUrls(
        canvas: Canvas,
        rowTexts: Array<String?>,
        group: IntRange,
        columns: Int,
        fontWidth: Float,
        fontLineSpacing: Int,
        fontAscent: Int,
    ) {
        val builder = StringBuilder()
        // One slot per row for its start offset, plus a trailing slot holding
        // the end of the last row, so segmentEnd can be read for any row.
        val rowOffsets = IntArray(group.last - group.first + 2)
        for (visRow in group) {
            rowOffsets[visRow - group.first] = builder.length
            builder.append(rowTexts[visRow] ?: "")
        }
        rowOffsets[group.last - group.first + 1] = builder.length

        val logicalText = builder.toString()
        if (logicalText.isEmpty()) return

        for (match in UrlDetector.findUrls(logicalText)) {
            val isPressed = match.url == pressedUrl
            for (index in group) {
                val rowStart = rowOffsets[index - group.first]
                val rowEnd = rowOffsets[index - group.first + 1]
                val segmentStart = maxOf(match.start, rowStart)
                val segmentEnd = minOf(match.end, rowEnd)
                if (segmentStart >= segmentEnd) continue

                val colStart = (segmentStart - rowStart).coerceIn(0, columns)
                val colEnd = (segmentEnd - rowStart).coerceIn(0, columns)
                if (colStart >= colEnd) continue

                val x1 = colStart * fontWidth
                val x2 = colEnd * fontWidth
                val baselineY = (index + 1) * fontLineSpacing
                // The cell already has leading above the glyphs, so the surface
                // looked padded at the top and flush at the bottom. One physical
                // pixel (the canvas is in pixels) on each side evens that out.
                val topY = (baselineY + fontAscent - urlHighlightPadding).toFloat()
                val bottomY = baselineY + urlHighlightPadding

                val isFirstRow = segmentStart == match.start
                val isLastRow = segmentEnd == match.end

                // Underline marks the link at rest, on every row it covers.
                canvas.drawLine(x1, bottomY, x2, bottomY, urlUnderlinePaint)

                // The surface is only for a link being held, so resting output
                // stays unmarked apart from the underline.
                if (isPressed) {
                    if (isFirstRow && isLastRow) {
                        canvas.drawRoundRect(x1, topY, x2, bottomY, 4f, 4f, urlBackgroundPaint)
                    } else {
                        canvas.drawRect(x1, topY, x2, bottomY, urlBackgroundPaint)
                    }
                }
            }
        }
    }
}
