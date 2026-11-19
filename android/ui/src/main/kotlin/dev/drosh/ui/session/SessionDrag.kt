package dev.drosh.ui.session

// UNTESTED — verify before use: long-press + movement-threshold drag shared by
// single cards and group cards. The threshold stops a held-but-still finger from
// starting a reorder; the delta is hoisted so the caller can draw the card
// under the finger and spring it home when the gesture ends.

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The finger must travel this far after the long press before a drag begins. */
private val DRAG_THRESHOLD: Dp = 8.dp

/**
 * Reports the card's window-space rectangle and turns a long press followed by
 * enough movement into [onStart]/[onDrag]/[onEnd]. [dragDelta] is the distance
 * the card should be drawn from its layout slot; it is cleared at gesture end so
 * the caller's spring settles the card back into place.
 */
@Composable
internal fun Modifier.sessionDrag(
    dragKey: Any?,
    dragDelta: MutableState<Offset>,
    onBounds: (Rect) -> Unit,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
): Modifier {
    val thresholdPx = with(LocalDensity.current) { DRAG_THRESHOLD.toPx() }
    val origin = remember { mutableStateOf(Offset.Zero) }

    return this
        .onGloballyPositioned {
            origin.value = it.boundsInRoot().topLeft
            onBounds(it.boundsInRoot())
        }
        .pointerInput(dragKey) {
            var travelled = 0f
            var started = false
            var gestureOrigin = Offset.Zero
            var startAbsolute = Offset.Zero

            detectDragGesturesAfterLongPress(
                onDragStart = {
                    travelled = 0f
                    started = false
                    // Captured before any translation is applied, so converting
                    // pointer positions stays stable for the whole gesture.
                    gestureOrigin = origin.value
                    dragDelta.value = Offset.Zero
                },
                onDrag = { change, amount ->
                    change.consume()
                    travelled += amount.getDistance()
                    val absolute = change.position + gestureOrigin
                    if (travelled >= thresholdPx) {
                        if (!started) {
                            started = true
                            startAbsolute = absolute
                            onStart(absolute)
                        }
                        dragDelta.value = absolute - startAbsolute
                        onDrag(absolute)
                    }
                },
                onDragEnd = {
                    if (started) onEnd()
                    started = false
                    dragDelta.value = Offset.Zero
                },
                onDragCancel = {
                    if (started) onEnd()
                    started = false
                    dragDelta.value = Offset.Zero
                },
            )
        }
}
