package dev.drosh.ui.terminal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import java.util.Locale

/**
 * The size chip shown while the terminal is being pinched.
 *
 * A pinch moves the font under the fingers, which means the thing being read
 * grows or shrinks with no visible control — the gesture is invisible by the
 * time the user looks up. The chip is that feedback: it names the number the
 * gesture has reached, which is also the only way to find the size again after
 * letting go.
 *
 * It fades rather than disappearing so it does not blink on every 0.1sp step,
 * and the caller keeps it alive briefly after the gesture ends (see
 * [ZoomChipState]) so the size is still readable when the fingers are gone.
 *
 * Not interactive and never focusable, so it cannot intercept a touch meant for
 * the terminal underneath: Compose hit-testing only consumes where a gesture
 * modifier sits, and this has none.
 */
@Composable
fun TerminalZoomChip(
    state: ZoomChipState,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (state.visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (state.visible) 90 else 320),
        label = "zoomChipAlpha",
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(999.dp))
            .background(DroshSurface)
            .border(1.dp, DroshOutline, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            // US locale explicitly: this chip sits over a terminal, and a Turkish or
            // German locale would print "14,3sp" — and in a locale where the
            // separator is a comma, "14,3" reads as fourteen-point-three to a
            // Turkish user and as fourteen comma three to a German one, while
            // the value it reports is neither. The terminal's own number is
            // the sp setting, not prose, so it keeps one format everywhere.
            text = String.format(Locale.US, "%.1fsp", state.textSizeSp),
            color = DroshText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * What the chip shows, and whether it is showing.
 *
 * Held as one value rather than three so a per-frame update is a single
 * snapshot: two fingers move faster than a frame, and a chip that took the
 * size from one state and the position from another would lag behind the
 * gesture in a way that reads as a rendering bug.
 */
data class ZoomChipState(
    val textSizeSp: Float,
    val focusX: Float,
    val focusY: Float,
    val visible: Boolean,
) {
    companion object {
        val Hidden = ZoomChipState(
            textSizeSp = 0f,
            focusX = 0f,
            focusY = 0f,
            visible = false,
        )
    }
}