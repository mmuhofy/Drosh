package dev.drosh.ui.topbar

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.termux.view.TerminalView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The top bar's backdrop, sampled straight out of the terminal.
 *
 * ## Why not Haze
 *
 * Haze records Compose's own draw commands into a GraphicsLayer and blurs that.
 * The terminal is a plain `View` (TerminalView : View) inside an AndroidView,
 * drawn by the Android view system, so it is not in the Compose display list at
 * all and Haze cannot see it. That is the documented limitation: anything that
 * punches through the composition is invisible to it. This is why the blur over
 * these pills rendered as an empty rectangle however it was configured — there
 * was nothing behind it to sample.
 *
 * Haze 2's HazeInput.Backdrop reads real window pixels and would have solved
 * it, but it needs Android 17 QPR2 and therefore AGP 9.1 and compileSdk 37.
 *
 * ## Why not PixelCopy
 *
 * PixelCopy reads the composited window, so the pills are themselves inside the
 * sampled region and the capture feeds back into its own blur. Asking the view
 * to draw into a bitmap of ours has neither problem: only the terminal renders,
 * synchronously, and we choose the region.
 *
 * ## Why not per frame
 *
 * View.draw walks every visible row, so a capture per frame would be a second
 * full terminal draw on top of the real one. The backdrop is refreshed on a
 * timer instead, and not at all when the row has no terminal behind it.
 */
@Composable
fun rememberTerminalBackdrop(
    terminalView: TerminalView?,
    stripHeight: Dp,
    /** False while the row sits in the band above the terminal — nothing to sample there. */
    active: Boolean,
    /** How often to look for new content. Not how often to capture. */
    pollMillis: Long = 48L,
): State<ImageBitmap?> {
    // Read outside produceState: its block is not a composable context, and
    // Dp.toPx needs one.
    val stripPx = with(LocalDensity.current) { stripHeight.toPx() }
    return produceState<ImageBitmap?>(
        initialValue = null,
        terminalView, stripPx, active,
    ) {
        if (terminalView == null || !active) {
            value = null
            return@produceState
        }
        var seen = -1
        while (true) {
            // Only resample when the terminal has actually produced something.
            // A capture redraws the whole view, so running it on a plain timer
            // spends a second terminal render per interval on output that has
            // not moved — which is precisely the cost this is meant to avoid.
            val generation = terminalView.contentGeneration
            if (generation != seen) {
                seen = generation
                capture(terminalView, stripPx)?.let { value = it.asImageBitmap() }
            }
            delay(pollMillis)
        }
    }
}

private fun capture(view: View, stripHeightPx: Float): Bitmap? {
    if (view.width <= 0 || view.height <= 0) return null
    val h = stripHeightPx.roundToInt().coerceIn(1, view.height)
    val bitmap = runCatching { Bitmap.createBitmap(view.width, h, Bitmap.Config.ARGB_8888) }
        .getOrNull() ?: return null
    // The strip is the top of the view, so the source is translated up by
    // however much taller the view is than the strip.
    val ok = runCatching {
        view.draw(
            AndroidCanvas(bitmap).apply { translate(0f, -(view.height - h).toFloat()) }
        )
    }.isSuccess
    return if (ok) bitmap else bitmap.also { it.recycle() }
}

/**
 * One pill's slice of [backdrop], blurred and clipped to [shape].
 *
 * Modifier.blur is where the RenderEffect comes from: it blurs whatever the
 * canvas draws, which here is the terminal slice belonging to this pill.
 * BlurredEdgeTreatment carries the pill's own shape so the result is clipped to
 * it rather than to a rectangle, which is what the default Rectangle treatment
 * would have produced.
 *
 * [sourceOffset] is this pill's position inside the sampled strip, in pixels.
 */
@Composable
fun TerminalBackdropSlice(
    backdrop: ImageBitmap?,
    /** Read at draw time, not during composition — the pill's position is only final after layout. */
    sourceOffset: () -> IntOffset,
    blurRadius: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    if (backdrop == null) return
    Canvas(
        modifier
            .blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment(shape))
            .clip(shape)
    ) {
        drawImage(
            image = backdrop,
            srcOffset = sourceOffset(),
            srcSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
}
