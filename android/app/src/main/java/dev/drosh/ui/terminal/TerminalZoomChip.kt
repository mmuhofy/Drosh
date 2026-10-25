// Ported from: the vendored terminal's `TerminalZoomChip` (Iris/Drosh before the
// Rust migration).
// Adapted for Drosh — dev.drosh
// Changes: package moved, and the state now carries only the size — the chip
// sits at a fixed place rather than following the fingers, because a chip under
// two fingers is a chip the fingers are covering.
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
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshText
import java.util.Locale

/**
 * The size chip shown while the terminal is being pinched.
 *
 * A pinch moves the font under the fingers, so the thing being read grows or
 * shrinks with nothing to name the number it reached. The chip is that name —
 * and the only way to find the size again after letting go.
 *
 * It fades rather than disappearing, so it does not blink on every 0.1sp step,
 * and the caller keeps it briefly after the gesture ends so the final size is
 * still readable when the fingers are gone.
 *
 * Not interactive and never focusable, so it cannot intercept a touch meant for
 * the terminal underneath.
 */
@Composable
fun TerminalZoomChip(
    textSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (textSizeSp > 0f) 1f else 0f,
        animationSpec = tween(durationMillis = if (textSizeSp > 0f) 90 else 320),
        label = "zoomChipAlpha",
    )

    if (alpha <= 0f) {
        return
    }

    Box(
        modifier = modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(999.dp))
            .background(DroshSurface)
            .border(1.dp, DroshOutline, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            // US locale explicitly: a Turkish or German locale would print
            // "14,3sp", and the terminal's own size is a number, not prose.
            text = String.format(Locale.US, "%.1fsp", textSizeSp),
            color = DroshText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
