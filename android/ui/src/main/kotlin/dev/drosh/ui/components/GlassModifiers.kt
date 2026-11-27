package dev.drosh.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.drosh.theme.DroshSurfaceLow
import dev.drosh.theme.DroshSurfaceVariant

/**
 * The light on the glass edge, as (position, alpha) along the top-left -> bottom-right
 * diagonal. Same stops as the terminal top bar, so both screens carry one edge.
 *
 * Bright at the top-left crest, nearly gone through the middle, a softer second
 * catch at the bottom-right. The floor is never 0: a little rim all the way round
 * keeps the silhouette readable on a bright transcript.
 */
private val GLASS_EDGE_STOPS = arrayOf(
    0.00f to 0.42f,
    0.30f to 0.14f,
    0.50f to 0.09f,
    0.70f to 0.14f,
    1.00f to 0.30f,
)

/**
 * A fake liquid-glass edge: a diagonal gradient stroke that follows [shape].
 *
 * It strokes the shape's own outline at double width and clips to the shape, which
 * leaves exactly [width] inside -- so it cannot disagree with the clip, whatever
 * the shape is. Drawn after the content so nothing opaque inside can cover it.
 *
 * [strength] scales every stop: quiet rows use under 1, a pressed control more.
 */
fun Modifier.glassEdge(
    shape: Shape,
    width: Dp = 1.dp,
    strength: Float = 1f,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val strokePx = width.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        val brush = Brush.linearGradient(
            colorStops = Array(GLASS_EDGE_STOPS.size) { i ->
                GLASS_EDGE_STOPS[i].first to
                    Color.White.copy(alpha = (GLASS_EDGE_STOPS[i].second * strength).coerceIn(0f, 1f))
            },
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawWithContent {
            drawContent()
            if (strokePx > 0f) {
                drawOutline(outline, brush, style = Stroke(width = strokePx * 2f))
            }
        }
    }

/**
 * A list row / card surface: one flat fill, and no edge.
 *
 * For things that sit in a sheet or in the flow rather than floating over the
 * transcript: they do not need a backdrop, only to read as the same material.
 * [raised] is the lifted fill — a selected row, a choice, an input.
 *
 * No outline. Every card on a screen carrying one turns the screen into a
 * stack of boxes, and a card that reads as a box reads as a form field. The
 * edge is a control's feature and the pills keep theirs.
 *
 * Clip first, so a ripple or [clickable] after this stays inside the shape.
 */
@Composable
fun Modifier.glassRow(shape: Shape, raised: Boolean = false): Modifier {
    // DroshSurfaceVariant and DroshSurfaceLow are composable getters over the
    // theme, so this has to be @Composable too.
    val fill = if (raised) DroshSurfaceVariant else DroshSurfaceLow
    return this
        .clip(shape)
        .background(fill)
}

