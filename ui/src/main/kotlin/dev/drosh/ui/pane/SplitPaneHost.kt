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
    onFloatingBoundsCommit: () -> Unit,
    onExpandEdgeSnapped: () -> Unit,
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

        // The pane is in another window, so there is nothing here to lay out for
        // it. Returning before the branches below is what stops this from
        // rendering as a docked split: `isFloating` is false for a system overlay,
        // so without this it would fall through and draw a divider against
        // nothing.
        //
        // `secondary` is not invoked, and that is the whole mechanism — it holds
        // the pane's `AndroidView`, so not composing it is what lets the overlay's
        // view be the only one for that session. Compositing an empty box instead
        // would leave a second view fighting it over the emulator.
        if (layout.isSystemOverlay) {
            primary()
            return@BoxWithConstraints
        }

        if (layout.isFloating) {
            primary()
            FloatingPaneWindow(
                bounds = layout.floatingBounds,
                edgeSnapped = layout.edgeSnapped,
                title = floatingTitle,
                hostWidthPx = widthPx,
                hostHeightPx = heightPx,
                onBoundsChange = onFloatingBoundsChange,
                onBoundsCommit = onFloatingBoundsCommit,
                onExpand = onExpandEdgeSnapped,
                onDock = onDock,
                onClose = onClosePane,
                modifier = Modifier.fillMaxSize(),
                content = secondary,
            )
            return@BoxWithConstraints
        }

        // **The single-pane case, and it has to come before everything else.**
        //
        // This branch was missing, which meant the docked path below ran for
        // *every* screen in the app, split or not: the primary terminal at 50%
        // height, a second `TerminalPaneBody` underneath painting
        // `canvas.drawColor(0xFF000000)` because its slot had no session, a
        // hairline seam between them and a draggable pill on it. The app did not
        // "sometimes start split" — it was always split, and the bottom half was
        // black.
        //
        // Not invoking `secondary` is the whole mechanism, for the same reason
        // the two branches above state: it holds the pane's `AndroidView`, so not
        // composing it is what keeps a second view from claiming a session that
        // the primary pane already has. That view was also *registered*, which is
        // why focus could end up in the empty pane and why sessions opened in the
        // bottom half — `switchTab` writes to whichever pane has focus.
        if (!layout.isSplit) {
            primary()
            return@BoxWithConstraints
        }

        // Top pane, divider, bottom pane. Stacked rather than side by side: two
        // 180dp columns of terminal are about ten characters wide, narrower than
        // most paths, while a shorter-but-full-width pane still reads.
        //
        // Both panes stay in composition for the whole drag and take their
        // heights from the seam's live position. They used to be replaced by
        // nothing at all while the divider was held, on the theory that two
        // TerminalViews re-measuring per frame juddered: the model was still
        // written every frame, nothing consumed it visually, and the panes
        // reappeared snapped to a step on release. That is a divider that does
        // not do the one thing a divider is for — the seam moved and the panes
        // did not, so there was nothing to drag. The panes now follow the finger,
        // and the seam still leads them rather than trailing the finger by a
        // frame.
        var dragging by remember { mutableStateOf(false) }

        val topPx = (layout.splitFraction * heightPx)
            .roundToInt()
            .coerceIn(1, (heightPx - 1).toInt())
        /**
         * The seam's live position in pixels, during a drag.
         *
         * Separate from [topPx] because [topPx] is what the model says and the
         * model updates through a state write: using it as the base of the next
         * delta is what made the seam lag and then jump. This one is written
         * synchronously inside the gesture, so every frame reads the position
         * the last frame produced.
         */
        var dragTopPx by remember { mutableStateOf(topPx.toFloat()) }

        /**
         * Where the seam actually is, and the heights that follow from it.
         *
         * Read from the drag while one is in progress and from the model the rest
         * of the time. [dragTopPx] is an *absolute* pixel value, so the moment the
         * host resizes for an unrelated reason — keyboard, rotation — the stored
         * drag value is off and the seam would sit somewhere the panes are not.
         * The model is the source of truth between drags; the drag slot is only
         * what the *current* drag is at.
         */
        val seamPx = if (dragging) dragTopPx else topPx.toFloat()
        val liveTopPx = seamPx.roundToInt().coerceIn(1, (heightPx - 1).toInt())
        val liveBottomPx = (heightPx - liveTopPx).roundToInt().coerceAtLeast(1)

        /**
         * The furthest down the seam may travel, in pixels.
         *
         * Paired with a floor of zero, so the seam can reach either end of the
         * host. It used to be clamped to a 96dp minimum at both ends — and 96dp
         * is more than [PaneLayout.COLLAPSE_FRACTION] of the host on every phone
         * there is, which meant the seam could never reach the threshold that
         * collapses the split. The gesture that was supposed to leave split view
         * could not be performed at all.
         *
         * [PaneLayout.withDraggedFraction] is what holds the *model* at or inside
         * the collapse band; a larger clamp here only hid it.
         *
         * A pane is allowed to pass through zero during the drag, because until
         * release it is only a size on screen and the drag has not decided
         * anything. The decision is [PaneLayout.commitDraggedFraction]'s.
         */
        val dragMaxPx = (heightPx - 1f).coerceAtLeast(1f)

        /**
         * The same three numbers, for the gesture rather than for this frame.
         *
         * `SplitDivider` detects the drag inside `pointerInput(Unit)`, which never
         * restarts, so its lambdas keep the values captured when the node was
         * first created. Reading `topPx` and `heightPx` out of the composition
         * there meant every drag after the first one seeded itself from the
         * split's original geometry and jumped on the first delta — and divided by
         * a stale host height, so the keyboard opening under the host changed
         * neither the fraction that got written nor how far the seam could travel.
         *
         * The float state objects are the fix: the gesture reads them through
         * `by`, so it sees this frame's values without the pointer input having to
         * restart.
         */
        val liveTopRow = rememberUpdatedState(topPx.toFloat())
        val liveHeightPx = rememberUpdatedState(heightPx)
        val liveDragMaxPx = rememberUpdatedState(dragMaxPx)

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .size(
                        width = with(density) { widthPx.toDp() },
                        height = with(density) { liveTopPx.toDp() },
                    ),
            ) { primary() }

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, liveTopPx) }
                    .size(
                        width = with(density) { widthPx.toDp() },
                        height = with(density) { liveBottomPx.toDp() },
                    ),
            ) { secondary() }

            /**
             * The steps, as a layer over the whole host rather than part of the
             * divider.
             *
             * They have to span the host to be useful — a tick at each possible
             * height is what tells the user where releasing will land — and the
             * divider is a 28dp band that follows the seam, so anything inside
             * it could only ever be drawn around the seam's own position.
             */
            if (dragging) {
                StepGuides(
                    stepFractions = layout.splitSteps(),
                    currentFraction = seamPx / heightPx,
                )
            }

            SplitDivider(
                /**
                 * Each delta is applied to **the current** divider position, not
                 * to the one the drag started at.
                 *
                 * The first version read `(topPx + deltaPx)`, and `topPx` is
                 * captured from the composition — so every event in the gesture
                 * computed from the same starting height and the pane only ever
                 * moved by the last delta. Worse, the sign was wrong: the gesture
                 * reported a downward drag as a positive delta and that was
                 * added to the *top* pane's height, so dragging up made the top
                 * pane taller and the seam followed the finger backwards.
                 *
                 * `dragTopPx` is the seam's live position. Clamped here rather
                 * than in the model so the model only ever sees a fraction of
                 * the host, and so a drag past either end lands exactly on the
                 * end instead of overshooting and being clamped by rounding.
                 */
                onDrag = { deltaPx ->
                    val host = liveHeightPx.value
                    val next = (dragTopPx + deltaPx).coerceIn(0f, liveDragMaxPx.value)
                    dragTopPx = next
                    if (host > 0f) onSplitFractionChange(next / host)
                },
                onDragStart = {
                    dragging = true
                    // Seeded from where the seam actually is *now*, read live rather
                    // than captured at first composition.
                    dragTopPx = liveTopRow.value
                },
                onDragEnd = {
                    dragging = false
                    onSplitFractionCommit()
                },
                onDoubleTap = onSwapPanes,
                // Straddles the seam, so the seam line stays visible on both
                // sides of the grip rather than the grip covering it.
                modifier = Modifier.offset {
                    IntOffset(
                        0,
                        seamPx.toInt() - with(density) { DIVIDER_HIT_HEIGHT.toPx() }.toInt() / 2,
                    )
                },
            )
        }
    }
}

/**
 * Faint rules up the screen marking where the divider can land, shown while it
 * is held.
 *
 * ## Why they exist
 *
 * The divider snaps to five positions. Without a mark for each, every release is
 * a guess: the user drags, lets go, and the seam is somewhere they did not aim
 * for. That reads as the control jumping on its own, and it is the reason
 * "the divider has no steps" felt true even though the code had five.
 *
 * The nearest line to the seam brightens as the finger approaches it, so
 * "release and it goes *here*" is legible before the finger lifts — which is the
 * whole point of snapping rather than landing anywhere.
 *
 * Only while held. Four permanent rules across a terminal is exactly the thing
 * a terminal should not look like.
 */
@Composable
private fun StepGuides(
    stepFractions: List<Float>,
    currentFraction: Float,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val hostHeight = constraints.maxHeight
        if (hostHeight <= 0) return@BoxWithConstraints

        val nearest = stepFractions.minByOrNull { abs(it - currentFraction) }

        stepFractions.forEach { fraction ->
            val y = (fraction * hostHeight).roundToInt()
            val isNearest = fraction == nearest
            val distance = abs(fraction - currentFraction)
            val strength = (1f - distance / STEP_FADE_RANGE).coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isNearest) STEP_MARKER_ACTIVE else STEP_MARKER_THICK)
                    .background(
                        (if (isNearest) DroshPrimary else DroshOutline).copy(
                            alpha = if (isNearest) STEP_MARKER_ACTIVE_ALPHA
                            else STEP_MARKER_ALPHA * strength,
                        ),
                    )
                    .offset { IntOffset(0, y) },
            )
        }
    }
}

/** How close the seam has to be for a step's line to be at full strength. */
private const val STEP_FADE_RANGE = 0.12f

private val STEP_MARKER_THICK = 1.dp
private val STEP_MARKER_ACTIVE = 2.dp
private const val STEP_MARKER_ALPHA = 0.5f
private const val STEP_MARKER_ACTIVE_ALPHA = 0.9f

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
                vertical = bounds.width <= bounds.height,
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
 * Orientation follows which edge the pane went to, decided by which dimension
 * the snap shrank — a left or right snap narrows it, a top or bottom snap
 * shortens it. Guessing from the aspect ratio would be wrong for a pane that was
 * nearly square to begin with.
 */
@Composable
private fun EdgeSnappedStrip(
    title: String,
    vertical: Boolean,
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
            text = if (vertical) name else name,
            color = DroshText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (vertical) TextAlign.Center else TextAlign.Start,
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
            vertical -> if (leftPx <= 0) DroshIcons.ArrowRight else DroshIcons.ArrowLeft
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