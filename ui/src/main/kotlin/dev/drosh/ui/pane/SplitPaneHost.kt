package dev.drosh.ui.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.domain.terminal.NormalizedRect
import dev.drosh.domain.terminal.PaneLayout
import dev.drosh.ui.DroshIcons
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts the terminal panes: one, two side by side, or one with the second
 * floating over it.
 *
 * Layout is computed in the host's own pixels and converted to [PaneLayout]'s
 * fractions only at the edges, so the model never needs a screen size and a
 * rotation is absorbed by the Box re-measuring rather than by a stored value
 * going stale.
 *
 * The floating pane is composed last, so it is genuinely above the primary one.
 * Z-order rather than `zIndex`, because the panes are Views inside AndroidView
 * and Compose cannot interleave draw phases it does not own.
 */
@Composable
fun SplitPaneHost(
    layout: PaneLayout,
    onSplitFractionChange: (Float) -> Unit,
    onSplitFractionCommit: () -> Unit,
    onFloatingBoundsChange: (NormalizedRect) -> Unit,
    onToggleMaximized: () -> Unit,
    modifier: Modifier = Modifier,
    floatingTitle: String = "",
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        // Nothing to lay out before the first measurement. Returning here rather
        // than dividing by zero is also why every size below is guarded: a
        // constraint of 0 arrives during the first frame of a split opening.
        if (widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints

        if (layout.isFloating) {
            primary()
            FloatingPaneWindow(
                bounds = layout.floatingBounds,
                maximized = layout.maximized,
                title = floatingTitle,
                hostWidthPx = widthPx,
                hostHeightPx = heightPx,
                onBoundsChange = onFloatingBoundsChange,
                onToggleMaximized = onToggleMaximized,
                modifier = Modifier.fillMaxSize(),
                content = secondary,
            )
            return@BoxWithConstraints
        }

        val leftPx = (layout.splitFraction * widthPx).roundToInt().coerceIn(1, (widthPx - 1).toInt())
        val rightPx = (widthPx - leftPx).roundToInt().coerceAtLeast(1)

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.size(
                    width = with(density) { leftPx.toDp() },
                    height = with(density) { heightPx.toDp() },
                ),
            ) { primary() }

            Box(
                modifier = Modifier
                    .offset { IntOffset(leftPx, 0) }
                    .size(
                        width = with(density) { rightPx.toDp() },
                        height = with(density) { heightPx.toDp() },
                    ),
            ) { secondary() }

            SplitDivider(
                onDrag = { deltaPx ->
                    // Clamped in the model, not here. PaneLayout owns the
                    // limits; a second set of them here is a second thing to
                    // keep in step when they change.
                    onSplitFractionChange((leftPx + deltaPx) / widthPx)
                },
                onDragEnd = onSplitFractionCommit,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

/** Width of the divider's touch target, which is wider than the line drawn. */
private val DIVIDER_HIT_WIDTH = 24.dp

/** The visible rule between two docked panes. */
private val DIVIDER_THICKNESS = 2.dp

/**
 * The draggable seam between two docked panes.
 *
 * The touch target is [DIVIDER_HIT_WIDTH] but only [DIVIDER_THICKNESS] is
 * drawn. A 2dp line is right to look at and wrong to grab — a finger covers far
 * more than 2dp, and a target that thin misses often enough that the split
 * reads as broken rather than as stiff.
 *
 * The line carries the accent only while it is being dragged. A permanently lit
 * divider competes with the output on either side of it, which is the one thing
 * a terminal should not do.
 */
@Composable
private fun SplitDivider(
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .width(DIVIDER_HIT_WIDTH)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        onDragEnd()
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        onDrag(delta)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(DIVIDER_THICKNESS)
                .fillMaxHeight()
                .background(
                    color = if (dragging) DroshPrimary else DroshOutline,
                    shape = RoundedCornerShape(percent = 50),
                ),
        )
    }
}

/**
 * The floating pane: a window dragged by its title bar, resized from its
 * bottom-right corner, and expandable to fill the host.
 *
 * Geometry stays in [PaneLayout]'s fractions and is converted here, so a saved
 * arrangement survives a rotation and a change of screen. The title bar is a
 * real drag handle rather than the whole window: a terminal has gestures of its
 * own — swipe to scroll, long-press to select — and making the entire surface
 * draggable would steal them.
 */
@Composable
private fun FloatingPaneWindow(
    bounds: NormalizedRect,
    maximized: Boolean,
    title: String,
    hostWidthPx: Float,
    hostHeightPx: Float,
    onBoundsChange: (NormalizedRect) -> Unit,
    onToggleMaximized: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current

    if (maximized) {
        // Full-bleed rather than a card at the host's edges: a maximized window
        // that still showed its own border and corners would read as a window on
        // top of a window.
        Box(modifier = modifier) {
            content()
            FloatingPaneTitleBar(
                title = title,
                onToggleMaximized = onToggleMaximized,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        return
    }

    val widthPx = (bounds.width * hostWidthPx).roundToInt().coerceAtLeast(1)
    val heightPx = (bounds.height * hostHeightPx).roundToInt().coerceAtLeast(1)
    val leftPx = (bounds.left * hostWidthPx).roundToInt()
    val topPx = (bounds.top * hostHeightPx).roundToInt()

    Box(modifier = modifier) {
        // A Box around the Column, not a Column around the Box. The resize grip
        // has to sit *over* the terminal output in the corner — that is where a
        // user looks for it, and output would otherwise swallow the tap — which
        // means it is a sibling of the content, not a row below it. Aligning it
        // inside a Column would also not compile: ColumnScope.align takes a
        // vertical only, and a corner needs both axes.
        Box(
            modifier = Modifier
                .offset { IntOffset(leftPx, topPx) }
                .size(
                    width = with(density) { widthPx.toDp() },
                    height = with(density) { heightPx.toDp() },
                )
                .clip(RoundedCornerShape(14.dp))
                .background(DroshSurface),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                FloatingPaneTitleBar(
                    title = title,
                    onToggleMaximized = onToggleMaximized,
                    onDrag = { deltaX, deltaY ->
                        onBoundsChange(
                            bounds.copy(
                                left = bounds.left + deltaX / hostWidthPx,
                                top = bounds.top + deltaY / hostHeightPx,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            }

            ResizeGrip(
                onDrag = { deltaX, deltaY ->
                    onBoundsChange(
                        bounds.copy(
                            width = bounds.width + deltaX / hostWidthPx,
                            height = bounds.height + deltaY / hostHeightPx,
                        ),
                    )
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * The draggable title bar of a floating pane.
 *
 * Carries the session name, so a window that has been moved off to one corner
 * still says what it is — otherwise a floating terminal is just a rectangle
 * someone forgot to close.
 */
@Composable
private fun FloatingPaneTitleBar(
    title: String,
    onToggleMaximized: () -> Unit,
    modifier: Modifier = Modifier,
    onDrag: ((Float, Float) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .background(DroshSurfaceHigh)
            .then(
                if (onDrag == null) Modifier else Modifier.pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            color = DroshText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        FloatingPaneButton(
            icon = DroshIcons.Maximize,
            contentDescription = "Fill the screen",
            onClick = onToggleMaximized,
        )
    }
}

/**
 * The corner grip that resizes a floating pane.
 *
 * Diagonal drag only: horizontal and vertical components are discarded unless
 * one dominates, because free two-axis resizing from a 24dp corner is easy to
 * trigger by accident when the intent was to scroll the terminal underneath.
 */
@Composable
private fun ResizeGrip(
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(GRIP_SIZE)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (abs(dragAmount.x) >= abs(dragAmount.y)) {
                        onDrag(dragAmount.x, 0f)
                    } else {
                        onDrag(0f, dragAmount.y)
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(GRIP_DOT)
                .clip(CircleShape)
                .background(DroshTextMuted),
        )
    }
}

private val GRIP_SIZE = 30.dp
private val GRIP_DOT = 8.dp

/** A circular icon button on the floating pane's chrome. */
@Composable
private fun FloatingPaneButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(DroshSurface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DroshText,
            modifier = Modifier.size(15.dp),
        )
    }
}