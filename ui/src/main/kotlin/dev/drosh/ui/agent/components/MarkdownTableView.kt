package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.LocalFontSet
import kotlin.math.max

/**
 * A GFM table, drawn by us.
 *
 * The library parses tables and has no slot to draw one — see [MarkdownTables] for
 * why that is a printing bug rather than an absence. This is the slot's stand-in.
 *
 * ## Columns are measured, not counted
 *
 * Every column is as wide as its widest cell, measured rather than counted in
 * characters, and every cell is then laid out in exactly that width. Two things
 * fall out of it. A cell cannot be re-measured at a different width by whatever
 * is on the next line, so nothing drifts down the table. And a cell holding CJK
 * text, an emoji or a combining mark — none of which is one character wide — takes
 * the room it needs rather than the room a character count would guess.
 *
 * Measured rather than counted also because the font is the app's, not a grid: a
 * proportional face has no character width to count in the first place.
 *
 * ## Horizontal scroll, one line, no border
 *
 * A table is data with a shape, and a shape has a minimum width. Squeezing it into
 * the transcript's column would wrap every cell and destroy the very alignment the
 * table exists to show, so it scrolls sideways instead — the same choice the code
 * block makes, for the same reason.
 *
 * Under the header there is one line and nothing else. A full grid of borders would
 * be a second, heavier visual language in the middle of a transcript that has none;
 * the header rule says "this is the top of the data" and stops.
 *
 * Cells carry their inline markdown — `**bold**`, `` `code` ``, a link reduced to
 * its label — because a table of raw asterisks is a table nobody has to read twice.
 * A link is *not* accented here: nothing in this cell is clickable, and colouring
 * it like a link would promise one.
 */
@Composable
internal fun DroshMarkdownTable(
    table: GfmTable,
    modifier: Modifier = Modifier,
) {
    val fonts = LocalFontSet.current
    val measurer = rememberTextMeasurer()

    val bodyStyle = TextStyle(
        fontFamily = fonts.sans,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        color = DroshTextSecondary,
    )
    val headerStyle = bodyStyle.copy(color = DroshText, fontWeight = FontWeight.SemiBold)
    val codeStyle = TextStyle(
        fontFamily = fonts.mono,
        fontSize = 12.sp,
        lineHeight = 19.sp,
        color = DroshText,
    )
    val codeBackground = DroshSurfaceHigh

    val headerCells = remember(table, codeStyle, codeBackground) {
        table.header.map { markdownInline(it, codeStyle, codeBackground) }
    }
    val bodyCells = remember(table, codeStyle, codeBackground) {
        table.rows.map { row -> row.map { markdownInline(it, codeStyle, codeBackground) } }
    }

    val density = LocalDensity.current
    val columnWidths = remember(table, headerCells, bodyCells, headerStyle, bodyStyle, density) {
        val widest = IntArray(table.header.size)
        headerCells.forEachIndexed { i, cell ->
            widest[i] = measurer.measure(cell, headerStyle).size.width
        }
        bodyCells.forEach { row ->
            row.forEachIndexed { i, cell ->
                if (i < widest.size) {
                    widest[i] = max(widest[i], measurer.measure(cell, bodyStyle).size.width)
                }
            }
        }
        val gutter = with(density) { (CELL_PADDING * 2).roundToPx() }
        widest.map { with(density) { (it + gutter).toDp() } }
    }
    val tableWidth = columnWidths.fold(0.dp) { acc, width -> acc + width }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // Flat fill, no border: the table is a block of content, and the code
            // block beside it already owns the "boxed" reading.
            .background(DroshSurfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 6.dp),
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.width(tableWidth)) {
                TableRow(
                    cells = headerCells,
                    widths = columnWidths,
                    alignments = table.alignments,
                    style = headerStyle,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(DroshOutline),
                )
                table.rows.forEach { row ->
                    TableRow(
                        cells = row,
                        widths = columnWidths,
                        alignments = table.alignments,
                        style = bodyStyle,
                    )
                }
            }
        }
    }
}

/** One line of the table: every cell in its column's width, no exceptions. */
@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    widths: List<Dp>,
    alignments: List<GfmAlignment>,
    style: TextStyle,
) {
    // heightIn rather than height: the row grows with the user's font scale, and
    // every row in the table shares one text style, so they all grow together.
    Row(modifier = Modifier.heightIn(min = MIN_ROW_HEIGHT)) {
        widths.forEachIndexed { column, width ->
            val alignment = alignments.getOrElse(column) { GfmAlignment.START }
            Box(
                modifier = Modifier
                    .width(width)
                    .padding(horizontal = CELL_PADDING),
                // The column's alignment is a property of the column, so it
                // positions the cell rather than the text inside it: that keeps
                // every cell the same width whatever its content.
                contentAlignment = when (alignment) {
                    GfmAlignment.START -> Alignment.CenterStart
                    GfmAlignment.CENTER -> Alignment.Center
                    GfmAlignment.END -> Alignment.CenterEnd
                },
            ) {
                Text(
                    text = cells.getOrElse(column) { AnnotatedString("") },
                    style = style,
                    textAlign = when (alignment) {
                        GfmAlignment.START -> TextAlign.Start
                        GfmAlignment.CENTER -> TextAlign.Center
                        GfmAlignment.END -> TextAlign.End
                    },
                    maxLines = 1,
                    softWrap = false,
                    // The column is sized to fit, so this should never be
                    // reached. Ellipsis rather than clip because a clipped cell
                    // loses its last character with no sign it did.
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A cell's inline markdown, as styled text.
 *
 * Four constructs, because those are the four a model puts in a table cell: bold,
 * italic, code and a link. Everything else is left as the character it is — a
 * table cell is not a document, and a parser that tried to be one would have to
 * be right about every construct to be right about any of them.
 *
 * Unbalanced markers are literal text rather than an error, which is the same rule
 * [MarkdownTables.splitCells] follows on pipes: half-written markdown streams in
 * constantly, and dropping the characters would show the reader something the
 * source does not contain.
 */
private fun markdownInline(
    raw: String,
    codeStyle: TextStyle,
    codeBackground: Color,
): AnnotatedString {
    if (raw.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        val pending = StringBuilder()

        fun flush() {
            if (pending.isNotEmpty()) {
                append(pending)
                pending.setLength(0)
            }
        }

        fun styled(style: SpanStyle, text: String) {
            flush()
            pushStyle(style)
            append(text)
            pop()
        }

        val codeSpan = SpanStyle(
            color = codeStyle.color,
            fontFamily = codeStyle.fontFamily,
            fontSize = codeStyle.fontSize,
            background = codeBackground,
        )
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    pending.append(raw[i + 1])
                    i += 2
                }

                c == '`' -> {
                    var run = 0
                    while (i + run < raw.length && raw[i + run] == '`') run++
                    val marker = "`".repeat(run)
                    val close = raw.indexOf(marker, i + run)
                    if (close < 0) {
                        pending.append(marker)
                        i += run
                    } else {
                        styled(codeSpan, raw.substring(i + run, close))
                        i = close + run
                    }
                }

                (c == '*' || c == '_') && raw.getOrNull(i + 1) == c -> {
                    val marker = "$c$c"
                    val close = raw.indexOf(marker, i + 2)
                    if (close < 0) {
                        pending.append(marker)
                        i += 2
                    } else {
                        styled(SpanStyle(fontWeight = FontWeight.Bold), raw.substring(i + 2, close))
                        i = close + 2
                    }
                }

                c == '*' || c == '_' -> {
                    val close = raw.indexOf(c, i + 1)
                    if (close <= i + 1) {
                        pending.append(c)
                        i++
                    } else {
                        styled(SpanStyle(fontStyle = FontStyle.Italic), raw.substring(i + 1, close))
                        i = close + 1
                    }
                }

                c == '[' -> {
                    val link = linkLabelAt(raw, i)
                    if (link == null) {
                        pending.append(c)
                        i++
                    } else {
                        pending.append(link)
                        i = linkEndAt(raw, i)
                    }
                }

                else -> {
                    pending.append(c)
                    i++
                }
            }
        }
        flush()
    }
}

/** The text a `[label](target)` shows, or null when this is not a link. */
private fun linkLabelAt(raw: String, start: Int): String? {
    val labelEnd = raw.indexOf(']', start + 1)
    if (labelEnd < 0) return null
    if (raw.getOrNull(labelEnd + 1) != '(') return null
    val targetEnd = raw.indexOf(')', labelEnd + 2)
    if (targetEnd < 0) return null
    val label = raw.substring(start + 1, labelEnd)
    return label.ifBlank { raw.substring(labelEnd + 2, targetEnd) }
}

/** Just past the `)` of the link starting at [start]. */
private fun linkEndAt(raw: String, start: Int): Int {
    val labelEnd = raw.indexOf(']', start + 1)
    if (labelEnd < 0) return start + 1
    val targetEnd = raw.indexOf(')', labelEnd + 2)
    return if (targetEnd < 0) start + 1 else targetEnd + 1
}

/** Air either side of a cell's text, inside its column. */
private val CELL_PADDING = 7.dp

/** A row is one line of text tall, whatever the tallest cell in it needs. */
private val MIN_ROW_HEIGHT = 19.dp