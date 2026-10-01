package dev.drosh.ui.topbar

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect as AndroidRect
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.termux.view.TerminalView
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.OutfitFontFamily
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The text selection menu, drawn by Compose.
 *
 * Replaces the platform ActionMode, whose floating toolbar is Android's own:
 * it cannot carry the app's design system and it cannot be blurred.
 *
 * ## Why the blur is sampled rather than requested
 *
 * Haze cannot do this here. It records Compose's own draw commands, and the
 * terminal is a View inside an AndroidView — not in the Compose display list at
 * all. Asking Haze for a backdrop over the terminal produces an empty
 * rectangle, which is the same failure the top bar's pills had.
 *
 * So the pixels come from the view directly, the same way the top bar samples
 * its backdrop. The terminal draws itself into a bitmap sized to the menu, and
 * Modifier.blur applies the real RenderEffect to it. The result is a genuine
 * blur of the actual output behind the menu — not a tint pretending to be one.
 *
 * Sampling is on a timer rather than per frame: view.draw walks every visible
 * row, so doing it at frame rate would be a second full terminal render on top
 * of the real one, on the main thread.
 */
@Composable
fun SelectionMenuBackdrop(
    terminalView: TerminalView?,
    bounds: AndroidRect?,
    sizePx: IntSize,
    captureMillis: Long = 90L,
): State<ImageBitmap?> = produceState<ImageBitmap?>(
    initialValue = null, terminalView, bounds, sizePx,
) {
    if (terminalView == null || bounds == null || sizePx.width <= 0 || sizePx.height <= 0) {
        value = null
        return@produceState
    }
    val src = AndroidRect(
        bounds.left + MENU_MENU_DROP_PX,
        bounds.top + MENU_MENU_DROP_PX,
        bounds.left + MENU_MENU_DROP_PX + sizePx.width,
        bounds.top + MENU_MENU_DROP_PX + sizePx.height,
    )
    while (true) {
        value = sampleRegion(terminalView, src, sizePx)
        delay(captureMillis)
    }
}

private const val MENU_MENU_DROP_PX = 0

private fun sampleRegion(view: View, src: AndroidRect, size: IntSize): ImageBitmap? {
    if (view.width <= 0 || view.height <= 0) return null
    val w = size.width
    val h = size.height
    if (w <= 0 || h <= 0) return null
    val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        .getOrNull() ?: return null
    // Clip to the region so view.draw only rasterises the part we need.
    val canvas = AndroidCanvas(bitmap)
    canvas.clipRect(0f, 0f, w.toFloat(), h.toFloat())
    canvas.translate(-src.left.toFloat(), -src.top.toFloat())
    val ok = runCatching { view.draw(canvas) }.isSuccess
    if (!ok) {
        bitmap.recycle()
        return null
    }
    return bitmap.asImageBitmap()
}

/**
 * Draws [backdrop] behind the menu, blurred, clipped to [shape].
 *
 * No tint is applied over it: the blur is the whole effect, which is what
 * makes it read as glass rather than as a coloured slab.
 */
@Composable
fun SelectionMenuSurface(
    backdrop: ImageBitmap?,
    blurRadius: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (backdrop != null) {
                    Modifier.blur(radius = blurRadius).clip(shape)
                } else {
                    Modifier
                }
            )
            .background(DroshSurfaceHigh.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
    ) {
        if (backdrop != null) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                drawImage(
                    image = backdrop,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }
        }
        content()
    }
}
private val MenuShape = RoundedCornerShape(16.dp)

/**
 * The menu row itself.
 *
 * [url] is the single URL found in the selection, or null — the Open action is
 * only added when there is one, rather than being shown disabled, because a
 * dead button on every selection trains people to ignore the row.
 */
@Composable
fun SelectionMenuRow(
    backdrop: ImageBitmap?,
    selectedText: String?,
    url: String?,
    canPaste: Boolean,
    blurRadius: Dp,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectionMenuSurface(
        backdrop = backdrop,
        blurRadius = blurRadius,
        shape = MenuShape,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MenuAction(DroshIcons.Copy, "Kopyala", onCopy)
            if (canPaste) MenuAction(DroshIcons.ClipboardPaste, "Yapıştır", onPaste)
            if (url != null) MenuAction(DroshIcons.ExternalLink, "Aç", { onOpenUrl(url) })
            MenuAction(DroshIcons.Type, "Tümü", onSelectAll)
            MenuAction(DroshIcons.Share, "Paylaş", onShare)
        }
    }
}

@Composable
private fun MenuAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MenuLabel(icon = icon, label = label)
    }
}

@Composable
private fun MenuLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = DroshText,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = label,
            color = DroshTextSecondary,
            fontFamily = OutfitFontFamily,
            fontSize = 8.5.sp,
            maxLines = 1,
        )
    }
}
