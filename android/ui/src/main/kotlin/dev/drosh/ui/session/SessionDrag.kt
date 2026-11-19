package dev.drosh.ui.session

// UNTESTED — verify before use: long-press + movement-threshold drag shared by
// single cards and group cards. CI compiles it; the gesture timing needs a real
// device. The hold timer and the drag are one pointer input reading in the
// Initial pass, so the card's clickable modifier cannot swallow the long press.

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/** The finger must travel this far after the hold before a drag begins. */
private val DRAG_THRESHOLD: Dp = 8.dp

/**
 * How long the finger must stay down, still, before a drag is armed.
 *
 * `awaitLongPressOrCancellation` was verified present in Compose (BOM
 * 2026.04.01) but its timeout comes from `ViewConfiguration` (~500 ms) and has
 * no custom-timeout parameter. The card needs a shorter hold, so the timer here
 * is explicit. Movement before the hold belongs to the grid's scroll, not to us.
 */
private const val LONG_PRESS_TIMEOUT_MS = 200L

/**
 * Reports the card's window-space rectangle and turns a still hold followed by
 * enough movement into [onStart]/[onDrag]/[onEnd]. [dragDelta] is the distance
 * the card should be drawn from its layout slot; it is cleared at gesture end so
 * the caller's spring settles the card back into place.
 *
 * Events are read in [PointerEventPass.Initial] and only consumed once the 8dp
 * threshold is crossed. Before that the clickable modifier still sees the raw
 * events, so a short tap remains a tap and starts no drag.
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
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                dragDelta.value = Offset.Zero

                // Hold phase. A timeout means "still down long enough"; an early
                // up or movement beyond the threshold leaves the gesture to the
                // clickable/scroll handlers untouched.
                val held = withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        if ((change.position - down.position).getDistance() >= thresholdPx) break
                    }
                    false
                } == null

                if (held) {
                    var started = false
                    var startAbsolute = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        val distance = (change.position - down.position).getDistance()
                        // Do not consume until the threshold is crossed: until
                        // then this could still be a plain press.
                        if (!started && distance < thresholdPx) continue
                        change.consume()
                        if (!started) {
                            started = true
                            startAbsolute = change.position + origin.value
                            onStart(startAbsolute)
                        }
                        dragDelta.value = change.position - down.position
                        onDrag(change.position + origin.value)
                    }
                    if (started) onEnd()
                    dragDelta.value = Offset.Zero
                }
            }
        }
}
