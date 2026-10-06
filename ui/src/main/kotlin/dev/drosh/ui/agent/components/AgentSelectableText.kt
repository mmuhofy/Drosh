package dev.drosh.ui.agent.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.zIndex
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import kotlin.math.abs
import kotlin.math.roundToInt

/** One end of the selection, so a drag knows which one it is moving. */
private enum class Grabber { START, END }

/**
 * A transcript line the user can select, extend by dragging, and act on.
 *
 * ## Why not `SelectionContainer`
 *
 * On a long press it raises the platform's own ActionMode toolbar, and that toolbar
 * is Android's: it cannot carry this app's type, its tint, or a blur, and it sits
 * on a solid slab. `SelectionMenu.kt` in the app module replaced it for exactly
 * this reason. Using the container here would put that toolbar back on the agent
 * screens, which is the one thing this design is avoiding.
 *
 * So the gesture is handled here: long press selects the word under the finger, and
 * either end of the selection can then be dragged to grow or shrink it.
 *
 * ## Why the grabbers are drawn rather than borrowed
 *
 * Compose ships handle *placement* inside the container, but no way to place the
 * handles yourself and put a custom menu above them. Handing back the container's
 * own toolbar was the alternative, and that is the Android toolbar again.
 *
 * These are drawn from the same [TextLayoutResult] the text itself is laid out
 * with, so they land on the glyphs rather than near them: the start handle hangs off
 * the bottom-left of the first character's box and the end handle off the
 * bottom-right of the last one's. Measuring against the layout result rather than
 * approximating from the line height is what stops them drifting a few dp above the
 * text at large font scales.
 *
 * ## Where a handle may be dropped
 *
 * Anywhere inside the text. Dragging past the end clamps to the last character, so
 * the selection cannot be inverted into an invalid range or silently reset — the
 * two failure modes a naive `coerceIn` on the pair would produce.
 */
@Composable
fun AgentSelectableText(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    onCopy: ((String) -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
) {
    var range by remember(text) { mutableStateOf<IntRange?>(null) }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    // Which end the finger is holding. Null until a long press picks one, and null
    // again on release, so a tap elsewhere cannot extend a selection the user has
    // finished adjusting.
    var held by remember { mutableStateOf<Grabber?>(null) }

    val clipboard = LocalClipboardManager.current

    // The menu lives here rather than in the screen, so its position comes from the
    // text's own coordinates. Hoisting it would mean tracking this item through a
    // scrolling LazyColumn, and a menu that lags the scroll points at the wrong text.
    Box(modifier = modifier) {
        val current = layout

        Text(
            text = range.toHighlightedString(text),
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            onTextLayout = { result -> layout = result },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(text) {
                    detectTapGestures(
                        onLongPress = { position ->
                            val result = layout ?: return@detectTapGestures
                            val offset = result.getOffsetForPosition(position)
                            val word = wordRangeAt(text, offset)
                            range = word
                            // Grab whichever end is nearer, so the finger continues
                            // moving the end it is already on rather than the far one
                            // jumping across the word.
                            held = nearestGrabber(result, word, position)
                        },
                        // A plain tap ends the selection. Without this the highlight
                        // and the menu would stay after the user has moved on.
                        onTap = {
                            range = null
                            held = null
                        },
                    )
                }
                .pointerInput(text) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            // A drag that begins without a long press first — the
                            // container's own long press sets `held`, so this only
                            // runs when it did not, which means there is nothing to
                            // move.
                            if (held == null) return@detectDragGesturesAfterLongPress
                        },
                        onDrag = { change, _ ->
                            val result = layout ?: return@detectDragGesturesAfterLongPress
                            val grabber = held ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            val existing = range ?: return@detectDragGesturesAfterLongPress

                            val offset = result.getOffsetForPosition(change.position)
                            range = when (grabber) {
                                Grabber.START -> minOf(existing.first, offset)..existing.last
                                Grabber.END -> existing.first..maxOf(existing.last, offset)
                            }
                        },
                        onDragEnd = { held = null },
                        onDragCancel = { held = null },
                    )
                },
        )

        val selected = range
        if (selected != null && current != null) {
            val bounds = rectForRange(current, selected)

            // Above the word when there is room, below otherwise — the same rule a
            // context menu follows, and why a selection on the first line does not
            // push its menu off the top of the screen.
            val place = remember(bounds) {
                agentSelectionMenuOffset(
                    bounds = bounds,
                    containerWidth = MENU_WIDTH_PX,
                    menuWidth = MENU_WIDTH_PX,
                    gapPx = MENU_GAP_PX,
                )
            }

            SelectionGrabbers(
                bounds = bounds,
                startColor = if (held == Grabber.START) DroshPrimary else DroshText,
                endColor = if (held == Grabber.END) DroshPrimary else DroshText,
                modifier = Modifier.fillMaxWidth(),
            )

            AgentSelectionMenu(
                visible = true,
                selectedText = text.substring(
                    selected.first.coerceIn(0, text.length),
                    (selected.last + 1).coerceIn(0, text.length),
                ),
                onCopy = { picked ->
                    clipboard.setText(AnnotatedString(picked))
                    onCopy?.invoke(picked)
                },
                onSelectAll = {
                    onSelectAll?.invoke()
                    range = 0..(text.length - 1).coerceAtLeast(0)
                },
                onShare = { picked -> onShare?.invoke(picked) },
                modifier = Modifier
                    .offset { place }
                    .zIndex(1f),
            )
        }
    }
}

/** Text with the selection painted behind it. */
@Composable
private fun IntRange?.toHighlightedString(source: String): AnnotatedString =
    if (this == null) {
        AnnotatedString(source)
    } else {
        buildAnnotatedString {
            append(source)
            addStyle(
                style = SpanStyle(background = DroshPrimary.copy(alpha = 0.32f)),
                start = first.coerceIn(0, source.length),
                end = (last + 1).coerceIn(0, source.length),
            )
        }
    }

/**
 * The two grabbers at the ends of a selection.
 *
 * Drawn below the line of the character they belong to, the way a platform handle
 * hangs off the text rather than sitting on it — a handle centred on the glyph
 * covers the thing being selected.
 */
@Composable
private fun SelectionGrabbers(
    bounds: Rect,
    startColor: Color,
    endColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val r = 7.dp.toPx()
        // Start hangs off the left edge, end off the right, both below the baseline.
        drawCircle(
            color = startColor,
            radius = r,
            center = Offset(bounds.left, bounds.bottom + r * 0.6f),
        )
        drawCircle(
            color = endColor,
            radius = r,
            center = Offset(bounds.right, bounds.bottom + r * 0.6f),
        )
    }
}

/**
 * Which end the finger at [position] is closest to.
 *
 * Decided once, on the long press. Comparing x alone is not enough on a wrapped
 * line where the end handle sits above the start one.
 */
private fun nearestGrabber(
    layout: TextLayoutResult,
    range: IntRange,
    position: Offset,
): Grabber {
    val start = layout.getBoundingBox(range.first).center
    val end = layout.getBoundingBox(range.last).center
    val startDistance = abs(position.x - start.x) + abs(position.y - start.y)
    val endDistance = abs(position.x - end.x) + abs(position.y - end.y)
    return if (startDistance <= endDistance) Grabber.START else Grabber.END
}

/**
 * The word around [offset].
 *
 * Bounded by whitespace rather than by a word-breaking rule: transcript text is prose
 * and paths, and a path broken on `/` or `.` selects a fragment that means nothing
 * to paste. Whitespace gives the unit the user was aiming at.
 */
private fun wordRangeAt(text: String, offset: Int): IntRange {
    if (text.isEmpty()) return 0..0
    val safe = offset.coerceIn(0, text.length - 1)

    var start = safe
    while (start > 0 && !text[start - 1].isWhitespace()) start--

    var end = safe
    while (end < text.length - 1 && !text[end + 1].isWhitespace()) end++

    return start..end
}

/** The on-screen box for a character range, clamped to the laid-out text. */
private fun rectForRange(layout: TextLayoutResult, range: IntRange): Rect {
    val start = range.first.coerceIn(0, layout.layoutInput.text.length)
    val end = (range.last + 1).coerceIn(start, layout.layoutInput.text.length)
    return runCatching {
        val a = layout.getBoundingBox(start)
        val b = layout.getBoundingBox(end)
        Rect(
            left = minOf(a.left, b.left),
            top = minOf(a.top, b.top),
            right = maxOf(a.right, b.right),
            bottom = maxOf(a.bottom, b.bottom),
        )
    }.getOrElse { Rect(Offset.Zero, Offset.Zero) }
}

/** Where the menu goes, relative to the selection and to the room available. */
fun agentSelectionMenuOffset(
    bounds: Rect,
    containerWidth: Float,
    menuWidth: Float,
    gapPx: Float,
): IntOffset {
    val above = bounds.top - gapPx
    val y = if (above > 0f) above else bounds.bottom + gapPx

    // Clamped rather than centred: the menu is wider than the word, so centring it
    // hangs off an edge for any selection near one.
    val maxX = (containerWidth - menuWidth - 8f).coerceAtLeast(8f)
    val x = (bounds.center.x - menuWidth / 2f).coerceIn(8f, maxX)

    return IntOffset(x.roundToInt(), y.roundToInt())
}

/**
 * The menu's width in pixels, for the placement maths.
 *
 * Hardcoded because the labels beside it are hardcoded: when either changes the
 * clamp stops matching what is drawn and a selection near an edge hangs off the
 * screen. Better a stale number than a menu that measures itself, which
 * re-measures on every text change and moves under the user's finger.
 */
private const val MENU_WIDTH_PX = 208f
private const val MENU_GAP_PX = 10f
