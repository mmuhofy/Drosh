package dev.drosh.ui.agent.components

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
import androidx.compose.ui.draw.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.drosh.design.system.DroshPrimary
import kotlin.math.roundToInt

/** What the transcript hands up when the user has a word selected. */
data class AgentTextSelection(
    /** The selected characters, ready to paste. */
    val text: String,
    /** Character range within the source string, for painting the highlight. */
    val range: IntRange,
    /** Where the selection sits, in the composable's own coordinates. */
    val bounds: Rect,
)

/**
 * A transcript line the user can select.
 *
 * ## Why this does not use `SelectionContainer`
 *
 * `SelectionContainer` is the obvious choice and it is wrong for this screen. On a
 * long press it raises the platform's own ActionMode toolbar, and that toolbar is
 * Android's: it cannot carry this app's type, its tint, or a blur, and it sits on
 * a solid slab. `SelectionMenu.kt` in the app module replaced it for exactly this
 * reason — "it cannot carry the app's design system and it cannot be blurred".
 * Using the container here would put that same platform toolbar back on the agent
 * screens, which is the one thing the design is trying to avoid.
 *
 * So the gesture is handled here instead: long press selects the word under the
 * finger, and [AgentSelectionMenu] is placed above it.
 *
 * ## What this costs
 *
 * There are no draggable handles. A selection is chosen by long-pressing a word
 * and extended with "Tümü" in the menu, not by dragging a grabber.
 *
 * That is a real limitation and it is stated here rather than discovered later.
 * Drag handles mean reimplementing the platform's handle placement and hit
 * testing, and doing that without being able to run the app is how a screen ends
 * up with handles that do not track the text. A working word selection with an
 * honest menu is better than draggable handles that sit a few dp off the glyphs.
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
    var selection by remember(text) { mutableStateOf<AgentTextSelection?>(null) }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }

    // The menu lives in this Box rather than in the screen, so its position comes
    // from the text's own coordinates. Hoisting it to the screen would mean tracking
    // this item's position through a scrolling LazyColumn, and a menu that lags the
    // scroll by a frame points at the wrong text.
    Box(modifier = modifier) {
        Text(
            text = selection.toHighlightedString(text),
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
                            val bounds = rectForRange(result, word)
                            val picked = text.substring(word.first, word.last + 1)
                            selection = AgentTextSelection(picked, word, bounds)
                        },
                        // A plain tap ends the selection. Without this the highlight
                        // would stay on screen after the user has moved on, and the
                        // menu with it.
                        onTap = { selection = null },
                    )
                },
        )

        val picked = selection
        if (picked != null) {
            val place = remember(picked.bounds) {
                agentSelectionMenuOffset(
                    bounds = picked.bounds,
                    containerWidth = MENU_WIDTH_PX,
                    menuWidth = MENU_WIDTH_PX,
                    gapPx = MENU_GAP_PX,
                )
            }
            AgentSelectionMenu(
                selectedText = picked.text,
                onCopy = { onCopy?.invoke(picked.text) },
                onSelectAll = onSelectAll,
                onShare = { onShare?.invoke(picked.text) },
                modifier = Modifier
                    .offset { place }
                    .zIndex(1f),
            )
        }
    }
}

/**
 * The source text with the selection painted behind it.
 *
 * The highlight is a background span on the real string rather than a drawn
 * rectangle over the laid-out text: it moves with the glyphs on a font-scale
 * change or a re-wrap, which a drawn box does not.
 */
private fun AgentTextSelection?.toHighlightedString(source: String): AnnotatedString =
    if (this == null) {
        AnnotatedString(source)
    } else {
        buildAnnotatedString {
            append(source)
            addStyle(
                style = SpanStyle(background = DroshPrimary.copy(alpha = 0.32f)),
                start = range.first.coerceIn(0, source.length),
                end = (range.last + 1).coerceIn(0, source.length),
            )
        }
    }

/**
 * The word around [offset].
 *
 * Bounded by whitespace rather than by a word-breaking rule: transcript text is
 * prose and paths, and a path broken on `/` or `.` selects a fragment that means
 * nothing to paste. Whitespace gives the user the unit they were aiming at.
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

/**
 * Where the menu goes, relative to the selection and to the room available.
 *
 * Above the word when there is space above it, below otherwise — the same rule a
 * context menu follows, and the reason a selection on the first line of the
 * transcript does not push its menu off the top of the screen.
 */
fun agentSelectionMenuOffset(
    bounds: Rect,
    containerWidth: Float,
    menuWidth: Float,
    gapPx: Float,
): IntOffset {
    // Above the word when there is room, below otherwise — the same rule a context
    // menu follows, and the reason a selection on the first line does not push its
    // menu off the top of the screen.
    val above = bounds.top - gapPx
    val y = if (above > 0f) above else bounds.bottom + gapPx

    // Clamped rather than centred: the menu is wider than the word, so centring it
    // hangs off an edge for any selection near one.
    val maxX = (containerWidth - menuWidth - 8f).coerceAtLeast(8f)
    val x = (bounds.center.x - menuWidth / 2f).coerceIn(8f, maxX)

    return IntOffset(x.roundToInt(), y.roundToInt())
}

/**
 * The menu's measured width, as the placement maths needs it in pixels.
 *
 * Three labels with two icons each. Hardcoded because the labels are hardcoded
 * beside it: the moment either changes, the clamp stops matching what is drawn and
 * a selection near an edge hangs off the screen. Better a stale number than a menu
 * that positions itself by measuring, which re-measures on every text change and
 * moves under the user's finger.
 */
private const val MENU_WIDTH_PX = 208f
private const val MENU_GAP_PX = 10f
