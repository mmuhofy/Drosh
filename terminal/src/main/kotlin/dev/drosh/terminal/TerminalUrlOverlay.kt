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

    internal companion object {
        const val URL_SURFACE_ALPHA = 0x33
        const val URL_UNDERLINE_ALPHA = 0x99

        /**
         * The shortest gap between two URL scans.
         *
         * Long enough that a firehose costs one scan per interval instead of
         * one per chunk, short enough that a link scrolling into view is
         * underlined almost immediately.
         */
        private const val URL_SCAN_INTERVAL_MS = 120L

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
        set(value) {
            if (field === value) return
            field = value
            // Spans are rows of one particular screen; a new view is a new
            // screen, so nothing cached applies to it.
            cachedSpans = emptyList()
            cachedGeneration = -1
        }

    /**
     * Whether this layer does its job. The platform's own enabled flag —
     * drop this to false and the overlay draws nothing and resolves no taps.
     * The View stays in the tree: detaching it would also drop the touch
     * wiring, and a disabled link layer costs one early return per draw.
     */
    // (uses android.view.View.isEnabled)

    /**
     * Called when a tap resolves to a URL. Opening it — in-app browser or
     * otherwise — is the caller's business; the overlay only knows the link.
     *
     * Not named `onUrlClick` on purpose: the terminal screen that hosts this
     * overlay has a parameter by that name, and a property with the same name
     * inside the hosting `apply` block resolves to the val on the left of its
     * own assignment.
     */
    var onLinkClick: ((String) -> Unit)? = null

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

    /** One painted run: the cells of one row that one URL covers. */
    private class UrlSpan(
        val row: Int,
        val startCol: Int,
        val endCol: Int,
        val url: String,
        val rounded: Boolean,
    )

    /**
     * The spans the last scan found, and what they were found against.
     *
     * A scan reads every visible row out of the emulator as a string, joins
     * the soft-wrapped runs and runs two regex passes over the result — per
     * frame, that was the price of underlines nobody was looking at. The
     * screen rarely changes between frames, so the result is kept and
     * replayed until the screen actually changes, and even then at most once
     * per [URL_SCAN_INTERVAL_MS]: under a firehose the underline trails the
     * text by a fraction of a second, which an eye does not catch, while the
     * scan cost drops by an order of magnitude.
     */
    private var cachedSpans: List<UrlSpan> = emptyList()
    private var cachedGeneration: Int = -1
    private var cachedTopRow: Int = Int.MIN_VALUE
    private var cachedColumns: Int = -1
    private var cachedRows: Int = -1
    private var lastScanUptime: Long = 0L

    private fun readRows(view: TerminalView): RowSnapshot? {
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
        val view = terminalView ?: return null
        val snapshot = readRows(view) ?: return null
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
                if (!isEnabled) {
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
        if (!isEnabled) return

        val view = terminalView ?: return
        val emulator = view.mEmulator ?: return
        val renderer = view.mRenderer ?: return

        val columns = emulator.mColumns
        val rows = emulator.mRows
        val now = android.os.SystemClock.uptimeMillis()
        if (scanIsDue(view, columns, rows, now)) rescan(view, columns, rows, now)

        val spans = cachedSpans
        if (spans.isEmpty()) return

        val fontWidth = renderer.mFontWidth
        val fontLineSpacing = renderer.mFontLineSpacing
        val fontAscent = renderer.mFontLineSpacingAndAscent - renderer.mFontLineSpacing

        for (span in spans) {
            val x1 = span.startCol * fontWidth
            val x2 = span.endCol * fontWidth
            val baselineY = (span.row + 1) * fontLineSpacing
            // The cell already has leading above the glyphs, so the surface
            // looked padded at the top and flush at the bottom. One physical
            // pixel (the canvas is in pixels) on each side evens that out.
            val topY = (baselineY + fontAscent - urlHighlightPadding).toFloat()
            val bottomY = baselineY + urlHighlightPadding

            // Underline marks the link at rest, on every row it covers.
            canvas.drawLine(x1, bottomY, x2, bottomY, urlUnderlinePaint)

            // The surface is only for a link being held, so resting output
            // stays unmarked apart from the underline.
            if (span.url == pressedUrl) {
                if (span.rounded) {
                    canvas.drawRoundRect(x1, topY, x2, bottomY, 4f, 4f, urlBackgroundPaint)
                } else {
                    canvas.drawRect(x1, topY, x2, bottomY, urlBackgroundPaint)
                }
            }
        }
    }

    /**
     * True when the screen moved on since the last scan and enough time has
     * passed to look again.
     *
     * Both halves matter. Without the time part a firehose would rescan on
     * every chunk, which is what the cache is for; without the change part an
     * idle terminal would keep scanning a screen that has not moved.
     */
    private fun scanIsDue(view: TerminalView, columns: Int, rows: Int, now: Long): Boolean {
        val screenMoved = view.contentGeneration != cachedGeneration ||
            view.mTopRow != cachedTopRow ||
            columns != cachedColumns ||
            rows != cachedRows
        return screenMoved && now - lastScanUptime >= URL_SCAN_INTERVAL_MS
    }

    /** Re-reads the screen and remembers the spans found in it. */
    private fun rescan(view: TerminalView, columns: Int, rows: Int, now: Long) {
        cachedGeneration = view.contentGeneration
        cachedTopRow = view.mTopRow
        cachedColumns = columns
        cachedRows = rows
        lastScanUptime = now

        val snapshot = readRows(view)
        cachedSpans = if (snapshot == null) emptyList() else scanSpans(snapshot, columns)
    }

    /**
     * Finds every URL on the visible rows, as spans: the cells of each row one
     * match covers. Rows are joined per logical line first, so a URL the
     * terminal wrapped across rows is found whole and painted on each row it
     * covers. One row on its own keeps its rounded chip; a wrapped one is
     * painted square per row.
     */
    private fun scanSpans(snapshot: RowSnapshot, columns: Int): List<UrlSpan> {
        val rowTexts = snapshot.texts
        val spans = ArrayList<UrlSpan>()

        for (group in logicalLineGroups(rowTexts.size, snapshot.continues)) {
            val builder = StringBuilder()
            // One slot per row for its start offset, plus a trailing slot
            // holding the end of the last row, so segmentEnd can be read for
            // any row.
            val rowOffsets = IntArray(group.last - group.first + 2)
            for (visRow in group) {
                rowOffsets[visRow - group.first] = builder.length
                builder.append(rowTexts[visRow] ?: "")
            }
            rowOffsets[group.last - group.first + 1] = builder.length

            val logicalText = builder.toString()
            if (logicalText.isEmpty()) continue

            for (match in UrlDetector.findUrls(logicalText)) {
                for (index in group) {
                    val rowStart = rowOffsets[index - group.first]
                    val rowEnd = rowOffsets[index - group.first + 1]
                    val segmentStart = maxOf(match.start, rowStart)
                    val segmentEnd = minOf(match.end, rowEnd)
                    if (segmentStart >= segmentEnd) continue

                    val colStart = (segmentStart - rowStart).coerceIn(0, columns)
                    val colEnd = (segmentEnd - rowStart).coerceIn(0, columns)
                    if (colStart >= colEnd) continue

                    spans += UrlSpan(
                        row = index,
                        startCol = colStart,
                        endCol = colEnd,
                        url = match.url,
                        rounded = segmentStart == match.start && segmentEnd == match.end,
                    )
                }
            }
        }
        return spans
    }
}
