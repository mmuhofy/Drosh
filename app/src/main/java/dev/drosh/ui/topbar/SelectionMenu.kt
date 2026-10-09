package dev.drosh.ui.topbar

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect as AndroidRect
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import dev.drosh.design.system.LocalFontSet
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The text selection menu, drawn by Compose.
 *
 * Replaces the platform ActionMode, whose floating toolbar is Android's own:
 * it cannot carry the app's design system and it cannot be blurred.
 *
 * ## Why the keyboard toggle lives here
 *
 * It used to be a button in the top bar, and it was the only control there that was
 * not about the terminal's content — it changed how the device is being typed into,
 * from a bar that otherwise lists things about the session.
 *
 * It also competed for the row. The bar had four buttons, and the keyboard one was
 * the only one whose state was a mode rather than a destination, which is why the
 * agent button had to be pushed off the end.
 *
 * Here it is the last action in a row the user has already opened, on a selection
 * they just made — so the common case, "I selected something and now I want to type
 * instead", is one tap from one gesture rather than a hunt for a bar button.
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
    // Clamped to the view: sampling past its edge yields empty pixels, which the
    // blur then smears into the menu.
    val src = AndroidRect(
        bounds.left,
        bounds.top,
        bounds.left + sizePx.width,
        bounds.top + sizePx.height,
    ).also {
        it.left = it.left.coerceAtLeast(0)
        it.top = it.top.coerceAtLeast(0)
        it.right = it.right.coerceAtMost(terminalView.width)
        it.bottom = it.bottom.coerceAtMost(terminalView.height)
    }
    if (src.width() <= 0 || src.height() <= 0) {
        value = null
        return@produceState
    }
    while (true) {
        value = sampleRegion(terminalView, src, sizePx)
        delay(captureMillis)
    }
}

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
    Box(modifier = modifier.clip(shape)) {
        // The blur belongs to the backdrop alone. Applied to the Box it would
        // blur every child, including the labels, which is the opposite of what
        // glass is for — the surface goes soft, the text stays crisp.
        if (backdrop != null) {
            androidx.compose.foundation.Canvas(
                Modifier
                    .matchParentSize()
                    .blur(radius = blurRadius)
            ) {
                drawImage(
                    image = backdrop,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }
        }
        // A light tint so the labels hold contrast against whatever is behind
        // them; without it, text on a blurred light patch disappears.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(DroshSurfaceHigh.copy(alpha = 0.42f))
        )
        content()
    }
}
private val MenuShape = RoundedCornerShape(16.dp)

/** Width of one action cell, including the gaps around it. */
private const val MENU_ACTION_DP = 46
private const val MENU_ACTION_GAP_DP = 2
private const val MENU_EDGE_DP = 6

/**
 * The width the menu will occupy for a given number of actions.
 *
 * Measuring the menu and then sampling that measured size is circular: until
 * it has been measured there is no backdrop, and once it shrinks from five
 * actions to three the previous width is sampled. Deriving it from the action
 * count means the first frame already asks for the right region.
 */
fun menuWidthFor(actionCount: Int): Dp =
    (actionCount * MENU_ACTION_DP + (actionCount - 1).coerceAtLeast(0) * MENU_ACTION_GAP_DP +
        MENU_EDGE_DP * 2).dp

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
    /** Whether the on-screen keyboard is up, so the action can name the inverse. */
    keyboardFocused: Boolean,
    blurRadius: Dp,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onToggleKeyboard: () -> Unit,
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
            // Last, because it is the only action here that does not act on the
            // selection. The label says the inverse of what the tap will do, since
            // "Klavye" next to a keyboard that is already up reads as an invitation to
            // open it again rather than as a way to put it away.
            MenuAction(
                icon = if (keyboardFocused) DroshIcons.KeyboardOff else DroshIcons.Keyboard,
                label = if (keyboardFocused) "Kapat" else "Klavye",
                onClick = onToggleKeyboard,
            )
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
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
            fontFamily = LocalFontSet.current.sans,
            fontSize = 8.5.sp,
            maxLines = 1,
        )
    }
}
