package dev.drosh.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The translucent fill of the terminal's pills, matched here so the bottom bar
 * reads as the same control family as the row at the top of the terminal.
 * Dark on purpose: both rows float over a dark terminal in every theme.
 */
private val GLASS_FILL = Color(0xFF2A2A2C).copy(alpha = 0.78f)

/**
 * The hairline edge: bright at the top-left crest, nearly gone through the
 * middle, a softer catch at the bottom-right. Same stops as the terminal pills
 * so the two borders cannot drift apart.
 */
private val GLASS_EDGE_STOPS = arrayOf(
    0.00f to Color.White.copy(alpha = 0.42f),
    0.30f to Color.White.copy(alpha = 0.14f),
    0.50f to Color.White.copy(alpha = 0.09f),
    0.70f to Color.White.copy(alpha = 0.14f),
    1.00f to Color.White.copy(alpha = 0.30f),
)

/** Every glass control in the bottom bar is a capsule. */
private val GLASS_PILL_SHAPE = RoundedCornerShape(percent = 50)

/**
 * The glass border: a gradient stroke drawn on the shape's own outline at
 * double width and clipped to the shape, which leaves exactly [width] inside.
 * Drawn after the content so nothing opaque in the control can cover it.
 */
private fun Modifier.glassPillEdge(shape: Shape, width: Dp = 1.dp): Modifier =
    this
        .clip(shape)
        .drawWithCache {
            val strokePx = width.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            val brush = Brush.linearGradient(
                colorStops = GLASS_EDGE_STOPS,
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
 * The search pill, now in the bottom bar.
 *
 * It filters session cards by the user's name, the program's title or a rename
 * made in this screen. The surface is the same translucent dark fill plus a
 * hairline gradient edge as the terminal's pills — not a heavy filled field.
 */
@Composable
internal fun SessionSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focusRequester = remember { FocusRequester() }

    // UNTESTED — verify before use: press feedback is a short tween with no
    // overshoot, so the pill settles exactly where it started.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = tween(PRESS_MILLIS),
        label = "searchPillPress",
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) PRESSED_ALPHA else 1f,
        animationSpec = tween(PRESS_MILLIS),
        label = "searchPillAlpha",
    )

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                this.alpha = pressAlpha
            }
            .height(PILL_HEIGHT)
            .clip(GLASS_PILL_SHAPE)
            .background(GLASS_FILL)
            .glassPillEdge(GLASS_PILL_SHAPE)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = { focusRequester.requestFocus() },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = DroshIcons.Search,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(16.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.session_screen_search_hint),
                    color = DroshTextMuted,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = DroshText, fontSize = 13.sp),
                cursorBrush = SolidColor(DroshPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = { onQueryChange("") },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DroshIcons.X,
                    contentDescription = stringResource(R.string.session_screen_search_clear),
                    tint = DroshTextMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * The one and only `+` on the screen: a glass disc beside the search pill,
 * with the same translucent fill and hairline edge as the pill.
 */
@Composable
internal fun TransparentAddButton(onNewSession: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // UNTESTED — verify before use: same short, non-bouncing press as the pill.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = tween(PRESS_MILLIS),
        label = "addPress",
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) PRESSED_ALPHA else 1f,
        animationSpec = tween(PRESS_MILLIS),
        label = "addAlpha",
    )

    Box(
        modifier = Modifier
            .size(PILL_HEIGHT)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                this.alpha = pressAlpha
            }
            .clip(CircleShape)
            .background(GLASS_FILL)
            .glassPillEdge(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onNewSession,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DroshIcons.Plus,
            contentDescription = stringResource(R.string.session_screen_add),
            tint = DroshTextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Both controls in the bottom bar are this tall, so they align at the 48dp floor. */
private val PILL_HEIGHT = 48.dp

/** Press shrinks to 94%; the tween has no overshoot, so there is no bounce. */
private const val PRESS_SCALE = 0.94f

/** A slight dimming while pressed, on top of the scale. */
private const val PRESSED_ALPHA = 0.88f

/** Short enough to read as a tap, not an animation the user waits for. */
private const val PRESS_MILLIS = 120
