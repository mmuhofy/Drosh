package dev.drosh.ui.topbar

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    captureMillis: Long = 500L,
): State<ImageBitmap?> {
    // Read outside the effect: Dp.toPx needs a composable context and the
    // effect block is not one.
    val stripPx = with(LocalDensity.current) { stripHeight.toPx() }
    val backdrop = remember { mutableStateOf<ImageBitmap?>(null) }
    // Read in the composable body: LaunchedEffect's block is not a composable
    // context, so LocalLifecycleOwner cannot be read inside it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(terminalView, stripPx, active, lifecycle) {
        if (terminalView == null || !active) {
            backdrop.value = null
            return@LaunchedEffect
        }
        // Suspended while the app is not resumed. A capture draws the whole
        // terminal view, so running one per output chunk behind a backgrounded
        // app is main-thread work for a window nobody is looking at — and on
        // resume that backlog is exactly when the app is least able to absorb
        // it.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var seen = -1
            while (true) {
                // The generation gate alone is not a rate limit. contentGeneration
                // advances once per PTY chunk, and a download produces thousands
                // of those a second, so gating on it meant a full extra terminal
                // render for every chunk on the main thread. The interval is the
                // real limit; the generation check just avoids resampling when
                // nothing has changed.
                if (terminalView.contentGeneration != seen) {
                    seen = terminalView.contentGeneration
                    capture(terminalView, stripPx)?.let { backdrop.value = it.asImageBitmap() }
                }
                delay(captureMillis)
            }
        }
    }
    return backdrop
}

private fun capture(view: View, stripHeightPx: Float): Bitmap? {
    if (view.width <= 0 || view.height <= 0) return null
    val h = stripHeightPx.roundToInt().coerceIn(1, view.height)
    val bitmap = runCatching { Bitmap.createBitmap(view.width, h, Bitmap.Config.ARGB_8888) }
        .getOrNull() ?: return null
    // No translation. The bitmap is h tall and the view is drawn into it as-is, so
    // the view's own rows 0..h land in it and the rest falls outside — which is the
    // *top* strip the pills sit over.
    //
    // It used to translate by -(view.height - h), which lands the view's rows
    // [view.height - h, view.height) in the bitmap: the **bottom** strip. The
    // comment said top, so the mistake was invisible until the pills actually
    // floated over the terminal — and then it showed the last line of output
    // behind the first button on screen.
    val ok = runCatching { view.draw(AndroidCanvas(bitmap)) }.isSuccess
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
