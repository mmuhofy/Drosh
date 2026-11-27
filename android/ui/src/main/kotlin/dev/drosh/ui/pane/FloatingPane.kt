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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.domain.terminal.NormalizedRect
import dev.drosh.domain.terminal.PaneLayout
import dev.drosh.ui.DroshIcons
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun FloatingPaneWindow(
    bounds: NormalizedRect,
    edgeSnapped: Boolean,
    title: String,
    hostWidthPx: Float,
    hostHeightPx: Float,
    onBoundsChange: (NormalizedRect) -> Unit,
    onBoundsCommit: () -> Unit,
    onExpand: () -> Unit,
    onDock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current

    /**
     * A pane parked at the edge, drawn as a strip.
     *
     * The terminal is *not* drawn here. At 12% of the width it is a column of
     * clipped characters — unreadable, and still paying for a layout and a redraw
     * of two TerminalViews per frame behind it. What is drawn instead is the
     * session's name and a grip, which is what makes the strip findable: the
     * alternative was a four-pixel sliver the user had to already know about.
     *
     * Tapping brings it back at the size it had before. Not a drag: the whole
     * point is that it is somewhere a finger would rather not go.
     */
    if (edgeSnapped) {
        Box(
            modifier = modifier,
        ) {
            EdgeSnappedStrip(
                title = title,
                // From the *strip*, not from the pane it used to be.
                //
                // A parked pane is a thin bar: about 12% of one dimension and most
                // of the other, so which one is thin says which edge it went to.
                // The pane's own proportions cannot — a nearly square pane snaps to
                // a strip on either axis and reads the same way both times, which
                // put the restore arrow pointing sideways for a pane parked at the
                // top. That is the guess this very function's comment rejects.
                parkedOnSide = (
                    bounds.width * hostWidthPx
                    ) < (bounds.height * hostHeightPx),
                hostWidthPx = hostWidthPx,
                hostHeightPx = hostHeightPx,
                bounds = bounds,
                onExpand = onExpand,
                onClose = onClose,
            )
        }
        return
    }

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
    val latestOnBoundsChange by rememberUpdatedState(onBoundsChange)

    /**
     * The bounds this gesture started from, kept apart for each grip.
     *
     * Two state slots rather than one, and the reason is a bug the first version
     * had: both grips seeded `dragOrigin = bounds`, the single shared slot. So
     * after resizing, the next *move* started from the bounds captured when the
     * window was last composed — before the resize was applied — and the window
     * sprang back to its old position. Same slot, same origin, two gestures that
     * mean different things.
     *
     * Separate slots also fix the second half of it: a move used to write `left`
     * and `top` onto a copy of the full rect and send the whole thing back, so
     * its `width`/`height` were whatever the last composition said — which after
     * a resize was the pre-resize size, and the pane reset itself to full size
     * the moment you dragged it again.
     *
     * A move therefore changes **only** position and a resize **only** size, and
     * each is written onto the live value.
     */
    var moveOrigin by remember { mutableStateOf<NormalizedRect?>(null) }
    var resizeOrigin by remember { mutableStateOf<NormalizedRect?>(null) }

    // The origins have to come from the *current* bounds, and both grips detect
    // inside `pointerInput(Unit)`, which never restarts — so a plain `bounds`
    // read there is the value from whenever the window was first composed. The
    // first move would land correctly and the second would snap the window back
    // to where it started, and a resize after a move would jump it back too.
    val liveBounds = rememberUpdatedState(bounds)

    Box(modifier = modifier) {
        // A Box around the Column, not a Column around the Box. The resize grip
        // has to sit *over* the terminal output in the corner — that is where a
        // user looks for it, and output would otherwise swallow the tap — which
        // means it is a sibling of the content, not a row below it. Aligning it
        // inside a Column would also not compile: ColumnScope.align takes a
        // vertical only, and a corner needs both axes.
        // A card, not a rectangle: rounded, and casting a real shadow so it
        // reads as sitting *over* the terminal behind it rather than as a hole
        // cut in it. The shadow is drawn by the modifier, which means it has to
        // come before the clip that gives the card its shape.
        Box(
            modifier = Modifier
                .offset { IntOffset(leftPx, topPx) }
                .size(
                    width = with(density) { widthPx.toDp() },
                    height = with(density) { heightPx.toDp() },
                )
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(18.dp),
                    clip = false,
                )
                .clip(RoundedCornerShape(18.dp))
                .background(DroshSurface),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                FloatingPaneTitleBar(
                    title = title,
                    onDock = onDock,
                    onClose = onClose,
                    onDragStart = { moveOrigin = liveBounds.value },
                    onDrag = { deltaX, deltaY ->
                        val from = moveOrigin ?: return@FloatingPaneTitleBar
                        // Position only. `copy` carries the width and height this
                        // gesture started with, so a resize made in an earlier
                        // gesture survives — which is the whole point of a
                        // separate slot.
                        val moved = from.copy(
                            left = from.left + deltaX / hostWidthPx,
                            top = from.top + deltaY / hostHeightPx,
                        )
                        moveOrigin = moved
                        latestOnBoundsChange(moved)
                    },
                    onDragEnd = {
                        moveOrigin = null
                        // Without this the edge-snap threshold in the model is
                        // never consulted: `onBoundsCommit` was passed in and
                        // never called, so `withEdgeSnap` and the whole
                        // park-at-the-edge gesture were unreachable.
                        onBoundsCommit()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            }

            ResizeGrip(
                onDragStart = { resizeOrigin = liveBounds.value },
                onDrag = { deltaX, deltaY ->
                    val from = resizeOrigin ?: return@ResizeGrip
                    // Size only, and the origin is pinned to the top-left: a
                    // resize that also moved the window would make the corner
                    // grip feel like a second way to drag.
                    val moved = from.copy(
                        width = from.width + deltaX / hostWidthPx,
                        height = from.height + deltaY / hostHeightPx,
                    )
                    resizeOrigin = moved
                    latestOnBoundsChange(moved)
                },
                onDragEnd = {
                    resizeOrigin = null
                    onBoundsCommit()
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * The parked-pane strip: name, a grip, and a way back.
 *
 * Orientation follows the shape of the **strip**, which is a thin bar — about 12%
 * of one dimension and most of the other — so the thin one says which edge the
 * pane went to. The pane's own proportions before the snap cannot say it: a
 * nearly square pane reads the same either way and picked a side for a pane
 * parked at the top, which is what this function's comment said not to do.
 */
@Composable
private fun EdgeSnappedStrip(
    title: String,
    /** True when the pane is parked against the left or right edge. */
    parkedOnSide: Boolean,
    hostWidthPx: Float,
    hostHeightPx: Float,
    bounds: NormalizedRect,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = (bounds.width * hostWidthPx).roundToInt().coerceAtLeast(1)
    val heightPx = (bounds.height * hostHeightPx).roundToInt().coerceAtLeast(1)
    val leftPx = (bounds.left * hostWidthPx).roundToInt()
    val topPx = (bounds.top * hostHeightPx).roundToInt()

    val name = if (title.isBlank()) "Session" else title

    Column(
        modifier = Modifier
            .offset { IntOffset(leftPx, topPx) }
            .size(
                width = with(density) { widthPx.toDp() },
                height = with(density) { heightPx.toDp() },
            )
            .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
            .background(DroshSurfaceHigh)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExpand,
            )
            .semantics {
                contentDescription = "$name, kenara yaslandı. Dokun: geri aç"
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Rotated rather than reflowed when the strip is tall and narrow, so the
        // name reads along the edge rather than as one character per line.
        Text(
            // Both branches are the name. Rotating the label so it reads along a
            // side-parked strip was the intent; the transform was never written,
            // so what shipped was a plain centred label — which is right for a
            // top-parked strip and merely acceptable for a side one. Stated as
            // the name rather than as a branch that does nothing.
            text = name,
            color = DroshText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .clearAndSetSemantics { },
        )
        // A small arrow pointing away from the edge, toward the screen. Tapping it
        // is what un-parks the pane. It exists because the strip itself is small and
        // its only other affordance would read as "more" or "close" — a one-way
        // arrow into the screen is the one symbol that always means "bring this
        // back".
        val restoreIcon = when {
            parkedOnSide ->
                if (leftPx <= 0) DroshIcons.ArrowRight else DroshIcons.ArrowLeft
            topPx <= 0 -> DroshIcons.ArrowDown
            else -> DroshIcons.ArrowUp
        }
        FloatingPaneButton(
            icon = restoreIcon,
            contentDescription = "$name geri aç",
            onClick = onExpand,
        )
        Spacer(Modifier.height(6.dp))
        FloatingPaneButton(
            icon = DroshIcons.Maximize,
            contentDescription = "$name geri aç",
            onClick = onExpand,
        )
        Spacer(Modifier.height(6.dp))
        FloatingPaneButton(
            icon = DroshIcons.X,
            contentDescription = "$name kapat",
            onClick = onClose,
            tint = DroshError,
        )
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