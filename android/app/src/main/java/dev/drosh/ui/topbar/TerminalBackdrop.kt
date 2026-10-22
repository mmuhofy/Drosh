package dev.drosh.ui.topbar

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.drosh.ui.terminal.TerminalAppearanceSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The top bar's backdrop, sampled straight out of the terminal.
 *
 * ## Where the sample comes from
 *
 * The terminal is drawn by whichever renderer the pane holds, and the two can
 * only be sampled in different ways: the Termux view draws into itself, so
 * its pixels can be read back, while the engine draws through OpenGL and can
 * only report the colours of its cells. The chrome does not know which is
 * which — it asks a [TerminalAppearanceSource] for a strip and blurs what
 * comes back. What a strip is worth differs between the two, and each source
 * says so where it is written.
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
    source: TerminalAppearanceSource?,
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

    LaunchedEffect(source, stripPx, active, lifecycle) {
        if (source == null || !active) {
            backdrop.value = null
            return@LaunchedEffect
        }
        // Suspended while the app is not resumed. A capture draws the whole
        // terminal view, so running one per output chunk behind a backgrounded
        // app is main-thread work for a window nobody is looking at — and on
        // resume that backlog is exactly when the app is least able to absorb
        // it.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var seen = NO_TOKEN
            while (true) {
                // The content gate alone is not a rate limit. A download
                // produces thousands of output chunks a second, so gating on it
                // meant a full extra terminal render for every chunk on the
                // main thread. The interval is the real limit; the gate just
                // avoids resampling when nothing has changed.
                //
                // The token is compared here rather than inside the source, so
                // that starting the loop again samples again: the terminal may
                // have said everything it had to say while the loop was not
                // running.
                val token = source.contentToken
                if (token != seen) {
                    seen = token
                    source.captureStrip(stripPx.roundToInt())?.let { backdrop.value = it.asImageBitmap() }
                }
                delay(captureMillis)
            }
        }
    }
    return backdrop
}

/** The token of a terminal nobody has looked at yet. */
private const val NO_TOKEN = -1

/**
 * Whether the platform can carry a real blur at all.
 *
 * `Modifier.blur` needs a RenderEffect, which is API 31. Below it the modifier is
 * a no-op rather than a fallback, so a pill built on the assumption of a blur
 * would show the terminal's text at full strength. Anything reading this picks a
 * tint that does not depend on the blur to hide the text.
 */
val BLUR_SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * One pill's slice of [backdrop], blurred and clipped to [shape].
 *
 * Modifier.blur is where the RenderEffect comes from: it blurs whatever the
 * canvas draws, which here is the terminal slice belonging to this pill. The clip
 * comes after, so the shape is applied to the blurred result rather than the blur
 * being told the shape up front.
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
        // No `edgeTreatment`. The shape treatment clips the blur to the shape
        // *before* it spreads, which on a radius larger than the node leaves
        // almost nothing spread at all — and a canvas this small with a radius
        // this large is exactly that case. The default rectangle treatment blurs
        // the whole node, and [clip] then keeps the pill.
        //
        // `blur` is not applied where it is unsupported: RenderEffect needs API
        // 31, and where there is no blur the pill's own tint carries the job of
        // hiding the text — see PILL_SURFACE_ALPHA_UNBLURRED.
        if (blurRadius > 0.dp && BLUR_SUPPORTED) {
            Modifier.blur(radius = blurRadius)
        } else {
            Modifier
        }.then(modifier)
            .clip(shape)
    ) {
        // The picture is magnified before it is blurred.
        //
        // A blur of a 1:1 crop is an average: at a radius as large as the
        // pill, every pixel in it becomes the average colour of the terminal
        // behind it, and what shows through is a colour rather than a blur. A
        // magnified picture keeps its shapes — the text is still there, but it
        // is shapes rather than letters, and the blur finishes the job.
        //
        // The source region is therefore half the pill's size and is drawn
        // over the whole pill, so what the blur works on is a picture twice
        // the size of the glass it fills.
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        val sourceWidth = (width / MAGNIFICATION).coerceAtLeast(1)
        val sourceHeight = (height / MAGNIFICATION).coerceAtLeast(1)
        val source = sourceOffset()

        // The region has to be inside the sampled strip, or the picture is
        // partly nothing and the blur spreads that nothing over the pill.
        val left = (source.x + (width - sourceWidth) / 2).coerceIn(0, (backdrop.width - sourceWidth).coerceAtLeast(0))
        val top = (source.y + (height - sourceHeight) / 2).coerceIn(0, (backdrop.height - sourceHeight).coerceAtLeast(0))

        drawImage(
            image = backdrop,
            // Centred on the pill: the smaller region is the middle of what
            // the pill covers, so what is behind the glass is what is behind
            // the glass.
            srcOffset = IntOffset(left, top),
            srcSize = IntSize(sourceWidth, sourceHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height),
        )
    }
}

/**
 * How much larger the picture behind a pill is drawn than the glass it fills.
 *
 * Three is enough: the blur's radius in the source's own pixels falls to a
 * third, so the same radius spreads over a picture with three times the
 * detail. What comes out is the terminal's shapes rather than the terminal's
 * text — the letters are gone, the blur is not.
 */
private const val MAGNIFICATION = 3
