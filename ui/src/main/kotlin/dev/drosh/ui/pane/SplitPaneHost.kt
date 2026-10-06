package dev.drosh.ui.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberUpdatedState
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
import dev.drosh.design.system.DroshError
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
    onDock: () -> Unit,
    onClosePane: () -> Unit,
    onSwapPanes: () -> Unit,
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
                title = floatingTitle,
                hostWidthPx = widthPx,
                hostHeightPx = heightPx,
                onBoundsChange = onFloatingBoundsChange,
                onDock = onDock,
                onClose = onClosePane,
                modifier = Modifier.fillMaxSize(),
                content = secondary,
            )
            return@BoxWithConstraints
        }

        // Top pane, divider, bottom pane. Stacked rather than side by side: two
        // 180dp columns of terminal are about ten characters wide, narrower than
        // most paths, while a shorter-but-full-width pane still reads.
        //
        // The two panes are replaced by nothing at all while the divider is
        // held. That is the single change that makes the drag feel like moving
        // something: a TerminalView is a real View doing a real relayout on every
        // height change, and two of them re-measuring per frame while a finger is
        // on the seam is what produced the juddering. The divider follows the
        // finger alone, and the panes come back on release.
        var dragging by remember { mutableStateOf(false) }

        val topPx = (layout.splitFraction * heightPx)
            .roundToInt()
            .coerceIn(1, (heightPx - 1).toInt())
        val bottomPx = (heightPx - topPx).roundToInt().coerceAtLeast(1)

        Box(modifier = Modifier.fillMaxSize()) {
            // Out of composition while dragging rather than made transparent: an
            // invisible View still lays out, still redraws, and still costs the
            // frame the drag needs.
            if (!dragging) {
                Box(
                    modifier = Modifier
                        .size(
                            width = with(density) { widthPx.toDp() },
                            height = with(density) { topPx.toDp() },
                        ),
                ) { primary() }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, topPx) }
                        .size(
                            width = with(density) { widthPx.toDp() },
                            height = with(density) { bottomPx.toDp() },
                        ),
                ) { secondary() }
            }

            SplitDivider(
                // Clamped in the model, in two different ways: freely while
                // dragging, snapped to a step on release.
                onDrag = { deltaPx -> onSplitFractionChange((topPx + deltaPx) / heightPx) },
                onDragStart = { dragging = true },
                onDragEnd = {
                    dragging = false
                    onSplitFractionCommit()
                },
                onDoubleTap = onSwapPanes,
                // Straddles the seam, so the seam line stays visible on both
                // sides of the grip rather than the grip covering it.
                modifier = Modifier.offset {
                    IntOffset(0, topPx - with(density) { DIVIDER_HIT_HEIGHT.toPx() }.toInt() / 2)
                },
            )
        }
    }
}

/**
 * The seam between two docked panes, dragging vertically.
 *
 * Not a rule with a groove in it. A straight 2dp line across a terminal reads
 * as a seam in the output rather than as a control, so the grip is a short
 * rounded pill that sits *on* the seam and nothing else moves with it: the
 * line stays hairline, the pill grows out of it.
 *
 * The pill is [DIVIDER_HIT_HEIGHT] tall to grab but only [PILL_HEIGHT] tall to
 * see. A finger covers far more than 8dp and a target that thin misses often
 * enough that the split feels broken.
 *
 * It lights with the accent only while held. A permanently lit pill competes
 * with the output on either side of it, which is the one thing a terminal
 * should not do.
 *
 * Two gestures, on purpose, and neither conflicts with the other:
 *  - **drag** moves the seam, freely, and snaps to a step on release
 *  - **double tap** swaps the two sessions, which is the only way to change
 *    which is on top — the divider sets heights, not order
 *
 * They do not collide because the drag needs a move and the tap needs two
 * stationary presses, and Compose's own double-tap detector claims the second
 * one before a drag could start from it.
 */
@Composable
private fun SplitDivider(
    onDrag: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }

    // Only while held, so it fades rather than snapping — a control that pops
    // between states reads as a glitch in the output.
    val pillWidth by animateDpAsState(
        targetValue = if (dragging) PILL_HELD_WIDTH else PILL_WIDTH,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "dividerPillWidth",
    )
    val pillColor by animateColorAsState(
        targetValue = if (dragging) DroshPrimary else DroshOutline,
        animationSpec = tween(durationMillis = 140),
        label = "dividerPillColor",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DIVIDER_HIT_HEIGHT)
            // Tap first, drag second: detectTapGestures installs a
            // double-tap detector that has to see the second press before any
            // drag can start from it. Reversing the order means the drag claims
            // the pointer on the first movement and the tap never completes.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        onDragStart()
                    },
                    onDragEnd = {
                        dragging = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        onDragEnd()
                    },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        onDrag(delta)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // The hairline. Full width so the seam reads as a seam, but only a
        // shade above the surface so it does not read as a rule drawn through
        // the output.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SEAM_THICKNESS)
                .background(DroshOutline.copy(alpha = 0.55f)),
        )

        // The grip itself, floating on the seam.
        Box(
            modifier = Modifier
                .width(pillWidth)
                .height(PILL_HEIGHT)
                .clip(RoundedCornerShape(percent = 50))
                .background(pillColor),
        )
    }
}

/** Height of the divider's touch target, far taller than the pill drawn. */
private val DIVIDER_HIT_HEIGHT = 28.dp

/** The pill's resting size, and its size while held. */
private val PILL_WIDTH = 40.dp
private val PILL_HELD_WIDTH = 72.dp
private val PILL_HEIGHT = 4.dp

/** The seam itself: hairline, because it is not the thing being grabbed. */
private val SEAM_THICKNESS = 1.dp
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
    title: String,
    hostWidthPx: Float,
    hostHeightPx: Float,
    onBoundsChange: (NormalizedRect) -> Unit,
    onDock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current

    val widthPx = (bounds.width * hostWidthPx).roundToInt().coerceAtLeast(1)
    val heightPx = (bounds.height * hostHeightPx).roundToInt().coerceAtLeast(1)
    val leftPx = (bounds.left * hostWidthPx).roundToInt()
    val topPx = (bounds.top * hostHeightPx).roundToInt()

    /**
     * The bounds the current gesture started from.
     *
     * The bug this exists to fix: `pointerInput(Unit)` does not restart when its
     * captured values change, so a `pointerInput(Unit) { detectDragGestures {
     * onDrag(bounds.copy(left = bounds.left + dx)) } }` applies every delta to
     * the bounds as they were when the finger went down. Each event recomputed
     * from the same stale base, so the window advanced by the *last* delta only
     * and sprang back when the drag ended — which reads as the pane shuddering
     * in place rather than moving.
     *
     * Accumulating from a remembered start point instead makes each delta
     * additive on the previous one, which is what a drag means. The
     * `rememberUpdatedState` keeps the callback itself fresh without restarting
     * the gesture.
     */
    val liveBounds by rememberUpdatedState(bounds)
    val latestOnBoundsChange by rememberUpdatedState(onBoundsChange)

    var dragOrigin by remember { mutableStateOf<NormalizedRect?>(null) }

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
                    onDock = onDock,
                    onClose = onClose,
                    onDragStart = { dragOrigin = bounds },
                    onDrag = { deltaX, deltaY ->
                        val from = dragOrigin ?: return@FloatingPaneTitleBar
                        val moved = from.copy(
                            left = from.left + deltaX / hostWidthPx,
                            top = from.top + deltaY / hostHeightPx,
                        )
                        dragOrigin = moved
                        latestOnBoundsChange(moved)
                    },
                    onDragEnd = { dragOrigin = null },
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            }

            ResizeGrip(
                onDragStart = { dragOrigin = bounds },
                onDrag = { deltaX, deltaY ->
                    val from = dragOrigin ?: return@ResizeGrip
                    val moved = from.copy(
                        width = from.width + deltaX / hostWidthPx,
                        height = from.height + deltaY / hostHeightPx,
                    )
                    dragOrigin = moved
                    latestOnBoundsChange(moved)
                },
                onDragEnd = { dragOrigin = null },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * The chrome of a floating pane: its title, a dock button and a close button.
 *
 * ## Why there is no maximise button
 *
 * There was one, and it expanded the window to fill the host — which is a mode
 * with no way out that means anything. Once full-bleed the window *is* the
 * pane, so the button that shrank it again was the only control left, and a
 * user who wanted the terminal back had to find it.
 *
 * Both buttons now do something that ends in a normal terminal: **dock** makes
 * the window a proper lower pane with a divider, and **close** drops it and
 * leaves the single pane underneath. Neither is a mode.
 *
 * The title carries the session name, so a window moved off to one corner still
 * says what it is — otherwise a floating terminal is a rectangle someone forgot
 * to close.
 */
@Composable
private fun FloatingPaneTitleBar(
    title: String,
    onDock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onDrag: ((Float, Float) -> Unit)? = null,
    onDragEnd: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .height(FLOATING_CHROME_HEIGHT)
            .background(DroshSurfaceHigh)
            .then(
                if (onDrag == null) Modifier else Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        },
                    )
                },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            icon = DroshIcons.PanelBottom,
            contentDescription = "Alt pane'e sabitle",
            onClick = onDock,
        )

        FloatingPaneButton(
            icon = DroshIcons.X,
            contentDescription = "Bu session'i kapat",
            onClick = onClose,
            tint = DroshError,
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
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(GRIP_SIZE)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, dragAmount ->
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
    tint: androidx.compose.ui.graphics.Color = DroshText,
) {
    Box(
        modifier = Modifier
            .size(FLOATING_BUTTON_SIZE)
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
            tint = tint,
            modifier = Modifier.size(FLOATING_BUTTON_ICON),
        )
    }
}

/** The floating pane's title bar: tall enough to grab, short enough to ignore. */
private val FLOATING_CHROME_HEIGHT = 40.dp

private val FLOATING_BUTTON_SIZE = 28.dp
private val FLOATING_BUTTON_ICON = 15.dp