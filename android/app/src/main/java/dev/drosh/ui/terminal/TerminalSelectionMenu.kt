package dev.drosh.ui.terminal

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextSecondary

/**
 * The selection's menu and its two handles.
 *
 * ## What it is
 *
 * A selection the user cannot see is a selection they cannot act on, and a
 * selection they cannot adjust is a selection they have to start over. So the
 * selection brings a small menu — copy, paste, select all — and a handle at
 * each end that can be dragged.
 *
 * ## Where the numbers come from
 *
 * The engine owns the selection: it knows which cells it covers, and it marks
 * them in the frame. The rectangle the menu is placed on is those cells, and
 * the handle's drag is the engine's own gesture — a press at the end that
 * stays and a drag to the end that moves.
 */
@Composable
fun TerminalSelectionMenu(
    /** The selection's rectangle, in the pane's own pixels. */
    bounds: Rect,
    /** Where the pane sits on the screen, in pixels. */
    paneOrigin: Offset,
    hasClipboardText: Boolean,
    url: String?,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onOpenUrl: (String) -> Unit,
    /** A handle was dragged: the pane's own pixels, and which end moved. */
    onExtend: (x: Float, y: Float, fromEnd: Boolean) -> Unit,
    /** The terminal's background, so the handles can contrast with it. */
    terminalBackground: Color = Color.Black,
    modifier: Modifier = Modifier,
) {
    // Sized in dp and converted to pixels at draw time, so the handle keeps its
    // size whatever the screen's density. 18 dp reads as a grip, not a dot.
    val handleSize = 18.dp
    val density = LocalDensity.current
    val handleSizePx = with(density) { handleSize.toPx() }

    // The handle hangs just below the selection, so it does not cover the text
    // it is anchored to. The two grips sit at the selection's bottom corners:
    // the left one moves the start of the selection, the right one the end.
    val handleY = paneOrigin.y + bounds.bottom.toFloat() + handleSizePx / 2f

    Box(modifier = modifier) {
        // The handles, at the selection's ends. `fromEnd` is true for the end
        // that the right grip moves: the left grip moves the start.
        Handle(
            centerX = paneOrigin.x + bounds.left.toFloat(),
            centerY = handleY,
            size = handleSize,
            paneOrigin = paneOrigin,
            terminalBackground = terminalBackground,
            onDrag = { px, py -> onExtend(px, py, false) },
        )
        Handle(
            centerX = paneOrigin.x + bounds.right.toFloat(),
            centerY = handleY,
            size = handleSize,
            paneOrigin = paneOrigin,
            terminalBackground = terminalBackground,
            onDrag = { px, py -> onExtend(px, py, true) },
        )

        // The menu, above the selection when there is room for it.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = with(density) { (paneOrigin.x + bounds.left).toDp() },
                    top = with(density) { (paneOrigin.y + bounds.top - 44f).toDp() },
                )
                .clip(RoundedCornerShape(12.dp))
                .background(DroshSurface)
                .border(1.dp, DroshOutline, RoundedCornerShape(12.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MenuAction("Kopyala") { onCopy() }
            if (hasClipboardText) {
                MenuAction("Yapıştır") { onPaste() }
            }
            MenuAction("Tümü") { onSelectAll() }
            if (url != null) {
                MenuAction("Bağlantı", DroshPrimary) { onOpenUrl(url) }
            }
        }
    }
}

/**
 * A grab handle.
 *
 * The handle is a rounded grip that sits just below the selection's edge. Its
 * fill flips with the terminal's background so it stays readable on both light
 * and dark terminals, and it casts a small shadow so it lifts off the output
 * behind it.
 *
 * The drag is reported in the pane's own pixels: the handle lives on the
 * screen, and the selection lives in the pane, so the screen position is taken
 * back to the pane's before the gesture is told about it.
 */
@Composable
private fun Handle(
    centerX: Float,
    centerY: Float,
    size: androidx.compose.ui.unit.Dp,
    paneOrigin: Offset,
    terminalBackground: Color,
    onDrag: (x: Float, y: Float) -> Unit,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }

    // Contrast against whatever the terminal is drawn on: a light handle on a
    // dark terminal, a dark handle on a light one. The outline is the other way
    // round, so the grip keeps an edge in both cases.
    val darkBackground = terminalBackground.luminance() < 0.5f
    val handleFill = if (darkBackground) Color.White else Color(0xFF1F1F1F)
    val handleStroke = if (darkBackground) Color.Black.copy(alpha = 0.55f)
    else Color.White.copy(alpha = 0.75f)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (centerX - sizePx / 2f).roundToInt(),
                    (centerY - sizePx / 2f).roundToInt(),
                )
            }
            .size(size)
            .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(handleFill)
            .border(1.dp, handleStroke, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    // The handle's top-left corner is where the composable was
                    // placed, so the finger's screen position is that corner plus
                    // the gesture's local position. Taking the pane's origin off
                    // turns it into the pane's own pixels, which is what the
                    // engine expects.
                    val screenX = (centerX - sizePx / 2f) + change.position.x
                    val screenY = (centerY - sizePx / 2f) + change.position.y
                    onDrag(screenX - paneOrigin.x, screenY - paneOrigin.y)
                }
            },
    ) {
        // A small inner dot so the grip reads as a handle rather than a plain
        // circle. Drawn in the contrasting colour.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size((size.value * 0.35f).dp)
                .clip(CircleShape)
                .background(handleStroke),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TerminalSelectionMenuPreview() {
    TerminalSelectionMenu(
        bounds = Rect(40, 60, 220, 84),
        paneOrigin = Offset(0f, 0f),
        hasClipboardText = true,
        url = "https://example.com",
        onCopy = {},
        onPaste = {},
        onSelectAll = {},
        onOpenUrl = {},
        onExtend = { _, _, _ -> },
        terminalBackground = Color(0xFF1E1E1E),
    )
}

/** One action in the selection's menu. */
@Composable
private fun MenuAction(label: String, color: Color = DroshText, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
