package dev.drosh.feature.terminal

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import kotlin.math.roundToInt

/**
 * The selection's toolbar and its two grips.
 *
 * ## What belongs here
 *
 * The terminal's own selection state lives in the engine, and the engine draws
 * the highlight. This composable only draws what the engine cannot: a compact
 * control strip on the selection and a grip at each end that the user can drag.
 *
 * ## Where the numbers come from
 *
 * [bounds] is the selection's visible box in the pane's own pixels; [paneOrigin]
 * is where the pane sits on the screen. The toolbar is placed against the box
 * and clamped to the pane, so a selection at the top of the screen gets its
 * toolbar below it rather than off the screen.
 *
 * The grips use the selection's ordered endpoints, not the corners of the
 * box: a selection that starts mid-row and wraps has a top-left endpoint that
 * is not the box's left edge on its first row.
 */
@Composable
fun TerminalSelectionMenu(
    /** The selection's visible box, in the pane's own pixels. */
    bounds: Rect,
    /** Where the pane sits on the screen, in pixels. */
    paneOrigin: Offset,
    onCopy: () -> Unit,
    onSelectWord: () -> Unit,
    onSelectLine: () -> Unit,
    onSelectAll: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
    /** A grip was dragged: the point is in the pane's own pixels. */
    onExtend: (x: Float, y: Float, topLeft: Boolean) -> Unit,
    /** The grip's finger lifted. */
    onExtendEnd: () -> Unit = {},
    /** The selection's top-left endpoint, in pane pixels, or null when off screen. */
    topLeftHandle: Offset?,
    /** The selection's bottom-right endpoint, in pane pixels, or null. */
    bottomRightHandle: Offset?,
    /** The terminal's background, for contrast. */
    terminalBackground: Color = Color.Black,
    cellHeight: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleSize = 20.dp
    val handleSizePx = with(density) { handleSize.toPx() }
    val cellH = if (cellHeight > 0f) cellHeight else bounds.height().toFloat().coerceAtLeast(1f)

    // The grips sit on their own rows: the top grip at the centre of the
    // selection's first row, the bottom grip at the centre of its last.
    val startCenterY = if (topLeftHandle != null) {
        paneOrigin.y + topLeftHandle.y + cellH / 2f
    } else {
        paneOrigin.y + bounds.top + cellH / 2f
    }
    val endCenterY = if (bottomRightHandle != null) {
        paneOrigin.y + bottomRightHandle.y + cellH / 2f
    } else {
        paneOrigin.y + bounds.bottom - cellH / 2f
    }

    BoxWithConstraints(modifier = modifier) {
        val paneWidth = constraints.maxWidth.toFloat()
        val paneHeight = constraints.maxHeight.toFloat()
        val menuHeight = with(density) { MENU_HEIGHT_DP.dp.toPx() }
        val menuWidth = with(density) { menuWidthFor(ACTION_COUNT).toPx() }
        val edge = with(density) { 4.dp.toPx() }

        // Above the selection when there is room, below it otherwise. A
        // selection touching the top of the pane is the common case while the
        // keyboard is open, and a menu placed above it would be clipped to a
        // sliver of its own border.
        val selectionTop = paneOrigin.y + bounds.top
        val selectionBottom = paneOrigin.y + bounds.bottom
        val menuY = if (selectionTop - menuHeight - edge >= 0f) {
            selectionTop - menuHeight - edge
        } else {
            (selectionBottom + edge).coerceAtMost((paneHeight - menuHeight - edge).coerceAtLeast(edge))
        }
        val selectionCenterX = paneOrigin.x + (bounds.left + bounds.right) / 2f
        val menuX = (selectionCenterX - menuWidth / 2f)
            .coerceIn(edge, (paneWidth - menuWidth - edge).coerceAtLeast(edge))

        // The grips, at the selection's own endpoints. A grip whose endpoint
        // has scrolled off the screen is not drawn: it would sit on a row that
        // has nothing to do with the selection.
        topLeftHandle?.let { handle ->
            SelectionGrip(
                centerX = paneOrigin.x + handle.x,
                centerY = startCenterY,
                sizePx = handleSizePx,
                stemDown = true,
                paneOrigin = paneOrigin,
                accent = DroshPrimary,
                terminalBackground = terminalBackground,
                onDrag = { x, y -> onExtend(x, y, true) },
                onDragEnd = onExtendEnd,
            )
        }
        bottomRightHandle?.let { handle ->
            SelectionGrip(
                centerX = paneOrigin.x + handle.x,
                centerY = endCenterY,
                sizePx = handleSizePx,
                stemDown = false,
                paneOrigin = paneOrigin,
                accent = DroshPrimary,
                terminalBackground = terminalBackground,
                onDrag = { x, y -> onExtend(x, y, false) },
                onDragEnd = onExtendEnd,
            )
        }

        // The toolbar. Its own background is opaque enough to read over a
        // busy terminal, and the border keeps it separate from a same-coloured
        // line of output underneath.
        Row(
            modifier = Modifier
                .offset { IntOffset(menuX.roundToInt(), menuY.roundToInt()) }
                .clip(MenuShape)
                .background(DroshSurfaceHigh.copy(alpha = 0.96f))
                .border(1.dp, DroshOutline, MenuShape)
                .padding(horizontal = 3.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            MenuAction(DroshIcons.Copy, "Kopyala", onCopy)
            MenuAction(DroshIcons.ALargeSmall, "Kelime", onSelectWord)
            MenuAction(DroshIcons.ListChecks, "Satır", onSelectLine)
            MenuAction(DroshIcons.Square, "Tümü", onSelectAll)
            MenuAction(DroshIcons.Search, "Ara", onSearch)
            MenuAction(DroshIcons.X, "Kapat", onDismiss)
        }
    }
}

private val MenuShape = RoundedCornerShape(12.dp)

/** Width of one action cell, including the gaps around it. */
private const val ACTION_DP = 44
private const val ACTION_GAP_DP = 1
private const val MENU_EDGE_DP = 3
private const val ACTION_COUNT = 6

/** The menu row's height, used to decide whether it fits above a selection. */
private const val MENU_HEIGHT_DP = 44

/** What the toolbar will occupy for [actionCount] actions. */
private fun menuWidthFor(actionCount: Int): androidx.compose.ui.unit.Dp =
    (actionCount * ACTION_DP + (actionCount - 1).coerceAtLeast(0) * ACTION_GAP_DP +
        MENU_EDGE_DP * 2).dp

/**
 * One toolbar action: icon over a short label.
 *
 * Labels are part of the affordance on a terminal: an icon alone for "Kelime"
 * and "Satır" would be a guess, and the two actions are exactly the ones a
 * user does not expect to find in a copy toolbar.
 */
@Composable
private fun MenuAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ACTION_DP.dp, 38.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = DroshText,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                color = DroshTextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * A draggable grip at one end of the selection.
 *
 * The drawing is a small lollipop: a filled circle on a short stem, with a
 * contrasting ring so it stands out on both light and dark terminals. The
 * stem points into the selection — down for the top grip, up for the bottom
 * one — so the two grips read as the edges they belong to.
 *
 * The touch target is 44 dp even though the grip is 20 dp. A terminal is a
 * dense grid, and a grip that is only as big as its drawing is a grip the
 * user misses on the first try.
 */
@Composable
private fun SelectionGrip(
    centerX: Float,
    centerY: Float,
    sizePx: Float,
    stemDown: Boolean,
    paneOrigin: Offset,
    accent: Color,
    terminalBackground: Color,
    onDrag: (x: Float, y: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val touchPx = with(density) { 44.dp.toPx() }
    val currentCenter by rememberUpdatedState(Offset(centerX, centerY))
    val currentPaneOrigin by rememberUpdatedState(paneOrigin)

    // Contrast against the terminal's ground, so the grip keeps an edge on
    // both a light and a dark theme. The accent is Drosh's blue; the ring is
    // the only thing that has to flip.
    val darkBackground = terminalBackground.luminance() < 0.5f
    val ring = if (darkBackground) Color.White.copy(alpha = 0.9f) else Color(0xFF101820)
    val stemLength = sizePx * 0.55f
    val radius = sizePx / 2f

    Box(
        modifier = Modifier
            .offset {
                val center = currentCenter
                IntOffset(
                    (center.x - touchPx / 2f).roundToInt(),
                    (center.y - touchPx / 2f).roundToInt(),
                )
            }
            .size(44.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, _ ->
                    change.consume()
                    val center = currentCenter
                    val origin = currentPaneOrigin
                    val screenX = (center.x - touchPx / 2f) + change.position.x
                    val screenY = (center.y - touchPx / 2f) + change.position.y
                    onDrag(screenX - origin.x, screenY - origin.y)
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val circleCenterY = if (stemDown) {
                centerY - stemLength / 2f
            } else {
                centerY + stemLength / 2f
            }
            // The stem first, so the circle's ring covers its end.
            val stemLeft = centerX - sizePx * 0.11f
            val stemTop = if (stemDown) circleCenterY else centerY - stemLength / 2f
            drawRoundRect(
                color = accent,
                topLeft = Offset(stemLeft, stemTop),
                size = Size(sizePx * 0.22f, stemLength),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(sizePx * 0.11f),
            )
            drawCircle(
                color = ring,
                radius = radius + sizePx * 0.08f,
                center = Offset(centerX, circleCenterY),
            )
            drawCircle(
                color = accent,
                radius = radius,
                center = Offset(centerX, circleCenterY),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TerminalSelectionMenuPreview() {
    TerminalSelectionMenu(
        bounds = Rect(48, 120, 300, 168),
        paneOrigin = Offset(0f, 0f),
        onCopy = {},
        onSelectWord = {},
        onSelectLine = {},
        onSelectAll = {},
        onSearch = {},
        onDismiss = {},
        onExtend = { _, _, _ -> },
        topLeftHandle = Offset(48f, 120f),
        bottomRightHandle = Offset(300f, 168f),
        terminalBackground = Color(0xFF1E1E1E),
        cellHeight = 24f,
    )
}
