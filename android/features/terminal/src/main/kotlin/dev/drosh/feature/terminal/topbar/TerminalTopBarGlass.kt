package dev.drosh.feature.terminal.topbar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect

/**
 * The row's own colour for its text and its icons.
 *
 * The row floats over the terminal, and the terminal is dark in every theme
 * — a dark terminal in a light app. So the row's content is the light one
 * whatever the app's own text colour is: what is behind the row is the
 * terminal, and the terminal's foreground is what belongs on it.
 */
internal val PILL_CONTENT = Color(0xFFD6D7DC)

/**
 * The pill's opaque ground.
 *
 * Dark rather than themed: the row floats over the terminal, and the terminal is
 * dark in every theme, so the button is dark in every theme too. See
 * [PILL_CONTENT] for why its content is light.
 */
internal val PILL_SURFACE = Color(0xFF2A2A2C).copy(alpha = 0.78f)

/** Thickness of the pill's edge. Visible stroke width, measured inside the shape. */
private val PILL_EDGE_WIDTH = 1.dp

/**
 * The edge's light, along the top-left -> bottom-right diagonal.
 *
 * Bright at the top-left crest, nearly gone through the middle, a softer second
 * catch at the bottom-right. The floor is never 0: a little rim all the way round
 * keeps the silhouette readable on a bright terminal, which a pure two-corner
 * highlight loses.
 */
private val PILL_EDGE_STOPS = arrayOf(
    0.00f to Color.White.copy(alpha = 0.42f),
    0.30f to Color.White.copy(alpha = 0.14f),
    0.50f to Color.White.copy(alpha = 0.09f),
    0.70f to Color.White.copy(alpha = 0.14f),
    1.00f to Color.White.copy(alpha = 0.30f),
)

/**
 * The pill's edge: a diagonal gradient stroke that follows [shape] exactly.
 *
 * Drawn at double width and clipped to the shape, which leaves exactly [width]
 * of it inside -- no inset arithmetic, and it works for any [Shape].
 */
private fun Modifier.pillEdge(shape: Shape, width: Dp = PILL_EDGE_WIDTH): Modifier =
    this
        .clip(shape)
        .drawWithCache {
            val strokePx = width.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            val brush = Brush.linearGradient(
                colorStops = PILL_EDGE_STOPS,
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

/** The full capsule. Every pill in the row is one of these. */
internal val PILL_SHAPE = RoundedCornerShape(percent = 50)

/**
 * The sessions button's shape: a square with soft corners.
 *
 * A circle says "one action"; a square with rounded corners says "a list of
 * things", which is what the button opens.
 */
internal val SESSION_BUTTON_SHAPE = RoundedCornerShape(14.dp)

/** How far a pill shrinks while it is held, and how long the shrink takes. */
private const val PILL_PRESSED_SCALE = 0.92f
private const val PILL_PRESS_ANIMATION_MILLIS = 200

/**
 * The row's pill button.
 *
 * An opaque shape with a gradient edge and a short scale-down while held. Both
 * the shrink and the ripple come from [GlassSurface]'s interaction source, so
 * every button in the row answers a press the same way.
 */
@Composable
internal fun GlassPillButton(
    backdrop: ImageBitmap?,
    terminalBounds: Rect?,
    drawableRes: Int? = null,
    icon: ImageVector? = null,
    contentDescription: String,
    onClick: () -> Unit,
    width: Dp = PILL_WIDTH,
    height: Dp = BAR_ROW_HEIGHT,
    iconSize: Dp = 22.dp,
    /** The button's own content, when it is not a single icon. */
    content: (@Composable () -> Unit)? = null,
    /** False when this button sits inside a body that already carries the fill and the edge. */
    ownSurface: Boolean = true,
    /** The button's clip. A full circle for every pill in the row. */
    shape: RoundedCornerShape = PILL_SHAPE,
) {
    GlassPillBody(
        contentDescription, onClick, width, height, iconSize, backdrop, terminalBounds, ownSurface, shape,
    ) { tint ->
        when {
            content != null -> content()

            drawableRes != null -> Icon(
                painter = painterResource(drawableRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )

            icon != null -> Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun GlassPillBody(
    contentDescription: String,
    onClick: () -> Unit,
    width: Dp = PILL_WIDTH,
    height: Dp = BAR_ROW_HEIGHT,
    iconSize: Dp = 22.dp,
    backdrop: ImageBitmap?,
    terminalBounds: Rect?,
    ownSurface: Boolean,
    shape: RoundedCornerShape,
    content: @Composable (Color) -> Unit,
) {
    GlassSurface(
        backdrop = backdrop,
        terminalBounds = terminalBounds,
        shape = shape,
        modifier = Modifier.width(width).height(height),
        ownSurface = ownSurface,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.size(iconSize),
            contentAlignment = Alignment.Center,
        ) { content(PILL_CONTENT) }
    }
}

/**
 * One pill: opaque fill, gradient edge, then content.
 *
 * Shared by the buttons and the session-name pill so they cannot drift apart.
 * Clip comes before the ripple on purpose -- a bounded ripple is a rectangle, and
 * it is the clip ahead of it that keeps it inside the shape.
 */
@Composable
internal fun GlassSurface(
    @Suppress("UNUSED_PARAMETER") backdrop: ImageBitmap?,
    @Suppress("UNUSED_PARAMETER") terminalBounds: Rect?,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    ownSurface: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PILL_PRESSED_SCALE else 1f,
        animationSpec = tween(PILL_PRESS_ANIMATION_MILLIS),
        label = "pillPressScale",
    )

    Box(
        modifier = modifier
            .graphicsLayer(scaleX = pressScale, scaleY = pressScale)
            .clip(shape)
            .then(if (ownSurface) Modifier.background(PILL_SURFACE) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (ownSurface) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pillEdge(shape),
            )
        }
        content()
    }
}
