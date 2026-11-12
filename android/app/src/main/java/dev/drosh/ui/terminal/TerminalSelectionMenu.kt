package dev.drosh.ui.terminal

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
    modifier: Modifier = Modifier,
) {
    val handleSize = 20.dp

    Box(modifier = modifier) {
        // The handles, at the selection's ends.
        Handle(
            x = paneOrigin.x + bounds.left.toFloat(),
            y = paneOrigin.y + bounds.bottom.toFloat(),
            size = handleSize,
            paneOrigin = paneOrigin,
            onDrag = { px, py -> onExtend(px, py, fromEnd = true) },
        )
        Handle(
            x = paneOrigin.x + bounds.right.toFloat(),
            y = paneOrigin.y + bounds.bottom.toFloat(),
            size = handleSize,
            paneOrigin = paneOrigin,
            onDrag = { px, py -> onExtend(px, py, fromEnd = false) },
        )

        // The menu, above the selection when there is room for it.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = (paneOrigin.x + bounds.left).dp,
                    top = (paneOrigin.y + bounds.top - 44).dp,
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
 * The drag is the pane's own pixels: the handle is on the screen, and the
 * selection lives in the pane, so the screen's position is taken back to the
 * pane's before the gesture is told about it.
 */
@Composable
private fun Handle(
    x: Float,
    y: Float,
    size: androidx.compose.ui.unit.Dp,
    paneOrigin: Offset,
    onDrag: (x: Float, y: Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(start = (x - size.value / 2).dp, top = (y - size.value / 2).dp)
            .size(size)
            .clip(CircleShape)
            .background(DroshPrimary)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    // The handle is on the screen; the selection lives in the
                    // pane, so the screen's position is taken back to the
                    // pane's before the gesture is told about it.
                    onDrag(
                        change.position.x + paneOrigin.x,
                        change.position.y + paneOrigin.y,
                    )
                }
            },
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
