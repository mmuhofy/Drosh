package com.termux.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/**
 * Renderer of a [TerminalEmulator] into a [Canvas].
 *
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
class TerminalRenderer(
    @JvmField val mTextSize: Int,
    @JvmField val mTypeface: Typeface
) {
    private val mTextPaint = Paint()

    /** The width of a single mono spaced character obtained by [Paint.measureText] on a single 'X'. */
    @JvmField
    val mFontWidth: Float

    /** The [Paint.getFontSpacing]. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    @JvmField
    val mFontLineSpacing: Int

    /** The [Paint.ascent]. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private val mFontAscent: Int

    /** Selection corner radius as a fraction of a line, so it scales with font size. */
    private const val SELECTION_CORNER_FRACTION = 0.34f

    /** The [mFontLineSpacing] + [mFontAscent]. */
    @JvmField
    val mFontLineSpacingAndAscent: Int

    private val asciiMeasures = FloatArray(127)

    /** Backs the text selection block; colour comes from the palette each frame. */
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }


    init {
        mTextPaint.typeface = mTypeface
        mTextPaint.isAntiAlias = true
        mTextPaint.textSize = mTextSize.toFloat()

        mFontLineSpacing = Math.ceil(mTextPaint.fontSpacing.toDouble()).toInt()
        mFontAscent = Math.ceil(mTextPaint.ascent().toDouble()).toInt()
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent
        mFontWidth = mTextPaint.measureText("X")

        val sb = StringBuilder(" ")
        for (i in asciiMeasures.indices) {
            sb.setCharAt(0, i.toChar())
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1)
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    fun render(
        mEmulator: TerminalEmulator, canvas: Canvas, topRow: Int,
        selectionY1: Int, selectionY2: Int, selectionX1: Int, selectionX2: Int,
        /** Sub-line scroll offset in pixels; the whole grid is shifted by it. */
        scrollOffsetPx: Float = 0f,
    ) {
        val reverseVideo = mEmulator.isReverseVideo()
        // A fractional offset pulls the whole grid by the offset, so whichever
        // edge it moves towards loses a strip at the opposite edge and needs one
        // extra row on the side it came from.
        //
        // Which side that is depends on the sign, and getting it wrong is what
        // left a gap at the top when scrolling out of the live edge: the row
        // being revealed was drawn, but the row *above* it — the one filling the
        // gap — was not. Reaching a full line then snapped the grid up by one
        // line, which read as a stutter at the limit.
        val partial = scrollOffsetPx != 0f
        val extraAbove = scrollOffsetPx > 0f
        var firstRow = if (partial) if (extraAbove) topRow - 2 else topRow - 1 else topRow
        var endRow = if (partial) topRow + mEmulator.mRows + 1 else topRow + mEmulator.mRows
        // The transcript is a ring buffer and activeTranscriptRows shrinks as
        // it trims, so a row that was in range when the drag started can be out
        // of range by the time it is drawn. externalToInternalRow throws on
        // anything past either end, which is a hard crash in onDraw.
        val buffer = mEmulator.getScreen()
        val minRow = -buffer.activeTranscriptRows
        if (firstRow < minRow) firstRow = minRow
        if (endRow > buffer.mScreenRows) endRow = buffer.mScreenRows
        if (endRow < firstRow) endRow = firstRow
        val columns = mEmulator.mColumns
        val cursorCol = mEmulator.getCursorCol()
        val cursorRow = mEmulator.getCursorRow()
        val cursorVisible = mEmulator.shouldCursorBeVisible()
        val screen = mEmulator.getScreen()
        val palette = mEmulator.mColors.mCurrentColors
        val cursorShape = mEmulator.getCursorStyle()

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC)

        // The offset moves the grid once, here. heightOffset must not include
        // it as well, or the shift is applied twice.
        if (scrollOffsetPx != 0f) canvas.translate(0f, scrollOffsetPx)

        // Worked out from firstRow rather than special-cased on partial, so any
        // number of extra rows stays correct: row firstRow + k is drawn at
        // base + (k + 1) * spacing, and row topRow has to land on the position
        // it occupies when nothing is offset.
        val baseOffset = mFontLineSpacingAndAscent - (topRow - firstRow) * mFontLineSpacing
        var heightOffset = baseOffset.toFloat()

        // The selection is one soft-edged block behind the text rather than a
        // hard rectangle per run. Only the block's outer corners are rounded —
        // rounding every row would notch the background at each boundary.
        val selFirstRow = selectionY1
        val selLastRow = minOf(selectionY2, mEmulator.mRows - 1)
        if (selFirstRow >= 0 && selLastRow >= selFirstRow) {
            val radius = mFontLineSpacing * SELECTION_CORNER_FRACTION
            val cell = mFontWidth
            val selPath = Path()
            for (row in selFirstRow..selLastRow) {
                val left = (if (row == selFirstRow) selectionX1 else 0) * cell
                val right = (if (row == selLastRow) selectionX2 else mEmulator.mColumns) * cell
                val top = baseOffset + (row - firstRow + 1) * mFontLineSpacing
                selPath.addSelectionRow(
                    left, top, right, top + mFontLineSpacing,
                    topLeft = if (row == selFirstRow) radius else 0f,
                    topRight = if (row == selFirstRow) radius else 0f,
                    bottomRight = if (row == selLastRow) radius else 0f,
                    bottomLeft = if (row == selLastRow) radius else 0f,
                )
            }
            selectionPaint.color = palette[TextStyle.COLOR_INDEX_SELECTION_BACKGROUND]
            canvas.drawPath(selPath, selectionPaint)
        }

        for (row in firstRow until endRow) {
            heightOffset += mFontLineSpacing

            val cursorX = if (row == cursorRow && cursorVisible) cursorCol else -1
            var selx1 = -1
            var selx2 = -1
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1
                selx2 = if (row == selectionY2) selectionX2 else mEmulator.mColumns
            }

            val lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            val line = lineObject.mText
            val charsUsedInLine = lineObject.spaceUsed

            var lastRunStyle: Long = 0
            var lastRunInsideCursor = false
            var lastRunInsideSelection = false
            var lastRunStartColumn = -1
            var lastRunStartIndex = 0
            var lastRunFontWidthMismatch = false
            var currentCharIndex = 0
            var measuredWidthForRun = 0f

            var column = 0
            while (column < columns) {
                val charAtIndex = line[currentCharIndex]
                val charIsHighsurrogate = Character.isHighSurrogate(charAtIndex)
                val charsForCodePoint = if (charIsHighsurrogate) 2 else 1
                val codePoint = if (charIsHighsurrogate) Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) else charAtIndex.code
                val codePointWcWidth = WcWidth.width(codePoint)
                val insideCursor = cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1)
                val insideSelection = column >= selx1 && column <= selx2
                val style = lineObject.getStyle(column)

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                val measuredCodePointWidth = if (codePoint < asciiMeasures.size) asciiMeasures[codePoint] else mTextPaint.measureText(
                    line as CharArray,
                    currentCharIndex, charsForCodePoint
                )
                val fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        val columnWidthSinceLastRun = column - lastRunStartColumn
                        val charsSinceLastRun = currentCharIndex - lastRunStartIndex
                        val cursorColor = if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
                        var invertCursorTextColor = false
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true
                        }
                        drawTextRun(
                            canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor, lastRunInsideSelection
                        )
                    }
                    measuredWidthForRun = 0f
                    lastRunStyle = style
                    lastRunInsideCursor = insideCursor
                    lastRunInsideSelection = insideSelection
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    lastRunFontWidthMismatch = fontWidthMismatch
                }
                measuredWidthForRun += measuredCodePointWidth
                column += codePointWcWidth
                currentCharIndex += charsForCodePoint
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex).compareTo(0) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += if (Character.isHighSurrogate(line[currentCharIndex])) 2 else 1
                }
            }

            val columnWidthSinceLastRun = columns - lastRunStartColumn
            val charsSinceLastRun = currentCharIndex - lastRunStartIndex
            val cursorColor = if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
            var invertCursorTextColor = false
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true
            }
            drawTextRun(
                canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor, lastRunInsideSelection
            )
        }
    }

    @Suppress("NewApi")
    private fun drawTextRun(
        canvas: Canvas, text: CharArray, palette: IntArray, y: Float, startColumn: Int, runWidthColumns: Int,
        startCharIndex: Int, runWidthChars: Int, mes: Float, cursor: Int, cursorStyle: Int,
        textStyle: Long, reverseVideo: Boolean,
        /**
         * True when the run is inside the text selection. Kept separate from
         * reverseVideo because the two invert for different reasons: reverse
         * video should swap fore and back, selection should paint its own pair.
         * Folding them into one boolean is why a selection on a dark scheme
         * looked like whatever the inverse video happened to resolve to.
         */
        selection: Boolean = false,
    ) {
        var foreColor = TextStyle.decodeForeColor(textStyle)
        val effect = TextStyle.decodeEffect(textStyle)
        var backColor = TextStyle.decodeBackColor(textStyle)
        val bold = (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0
        val underline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
        val italic = (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0
        val strikeThrough = (effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0
        val dim = (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0

        if ((foreColor and 0xff000000.toInt()) != 0xff000000.toInt()) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8
            foreColor = palette[foreColor]
        }

        if ((backColor and 0xff000000.toInt()) != 0xff000000.toInt()) {
            backColor = palette[backColor]
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        val reverseVideoHere = reverseVideo xor ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)
        if (reverseVideoHere) {
            if (selection) {
                foreColor = palette[TextStyle.COLOR_INDEX_SELECTION_FOREGROUND]
                // Left as the terminal background on purpose: it is the guard
                // below for whether a run paints its own rectangle. The
                // selection's background is already the soft-edged block drawn
                // behind the grid, and a per-run rect on top of it would undo
                // the corners.
                backColor = palette[TextStyle.COLOR_INDEX_BACKGROUND]
            } else {
                val tmp = foreColor
                foreColor = backColor
                backColor = tmp
            }
        }

        var left = startColumn * mFontWidth
        var right = left + runWidthColumns * mFontWidth

        var mesScaled = mes / mFontWidth
        var savedMatrix = false
        if (Math.abs(mesScaled - runWidthColumns) > 0.01) {
            canvas.save()
            canvas.scale(runWidthColumns / mesScaled, 1f)
            left *= mesScaled / runWidthColumns
            right *= mesScaled / runWidthColumns
            savedMatrix = true
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.color = backColor
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint)
        }

        if (cursor != 0) {
            mTextPaint.color = cursor
            var cursorHeight = (mFontLineSpacingAndAscent - mFontAscent).toFloat()
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4f
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4f
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint)
        }

        if ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            var actualForeColor = foreColor
            if (dim) {
                var red = 0xFF and (foreColor shr 16)
                var green = 0xFF and (foreColor shr 8)
                var blue = 0xFF and foreColor
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3
                green = green * 2 / 3
                blue = blue * 2 / 3
                actualForeColor = 0xFF000000.toInt() + (red shl 16) + (green shl 8) + blue
            }

            mTextPaint.isFakeBoldText = bold
            mTextPaint.isUnderlineText = underline
            mTextPaint.textSkewX = if (italic) -0.35f else 0f
            mTextPaint.isStrikeThruText = strikeThrough
            mTextPaint.color = actualForeColor

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint)
        }

        if (savedMatrix) canvas.restore()
    }

    fun getFontWidth(): Float {
        return mFontWidth
    }

    fun getFontLineSpacing(): Int {
        return mFontLineSpacing
    }
}

/**
 * Appends a rectangle with independently rounded corners.
 *
 * Canvas.drawRoundRect rounds all four or none, and the selection needs the
 * block's outer corners rounded while the joins between rows stay square —
 * otherwise every row boundary picks up a notch of background.
 */
private fun Path.addSelectionRow(
    left: Float, top: Float, right: Float, bottom: Float,
    topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
) {
    moveTo(left + topLeft, top)
    lineTo(right - topRight, top)
    if (topRight > 0f) arcTo(RectF(right - topRight, top, right, top + topRight), 0f, 90f, false)
    else lineTo(right, top)
    lineTo(right, bottom - bottomRight)
    if (bottomRight > 0f) arcTo(RectF(right - bottomRight, bottom - bottomRight, right, bottom), 90f, 90f, false)
    else lineTo(right, bottom)
    lineTo(left + bottomLeft, bottom)
    if (bottomLeft > 0f) arcTo(RectF(left, bottom - bottomLeft, left + bottomLeft, bottom), 180f, 90f, false)
    else lineTo(left, bottom)
    lineTo(left, top + topLeft)
    if (topLeft > 0f) arcTo(RectF(left, top, left + topLeft, top + topLeft), 270f, 90f, false)
    close()
}
