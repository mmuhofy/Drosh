package dev.drosh.feature.terminal

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.snap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshWarning
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import kotlin.math.roundToInt

/**
 * The selection's toolbar and its two grips.
 *
 * ## What belongs here
 *
 * The terminal's own selection state lives in the engine, and the engine draws
 * the highlight. This composable only draws what the engine cannot: a compact
 * control strip on the selection and a grip at each end that the user can drag.
 *
 * ## Where the numbers come from
 *
 * [bounds] is the selection's visible box in the pane's own pixels; [paneOrigin]
 * is where the pane sits on the screen. The toolbar is placed against the box
 * and clamped to the pane, so a selection at the top of the screen gets its
 * toolbar below it rather than off the screen.
 *
 * The grips use the selection's ordered endpoints, not the corners of the
 * box: a selection that starts mid-row and wraps has a top-left endpoint that
 * is not the box's left edge on its first row.
 *
 * ## Motion
 *
 * Everything here follows the selection instead of teleporting to it, but it
 * never *travels* across the pane: a toolbar that changes sides has been
 * re-placed, not moved, and animating that move is the jump the user saw while
 * dragging a grip. Small moves — a row as the selection grows, a column while
 * the finger drags — glide; a side change snaps.
 */
@Composable
fun TerminalSelectionOverlay(
    /** The selection's visible box, in the pane's own pixels. */
    bounds: Rect,
    /** Where the pane sits on the screen, in pixels. */
    paneOrigin: Offset,
    onCopy: () -> Unit,
    onSelectWord: () -> Unit,
    onSelectLine: () -> Unit,
    onSelectAll: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
    /** A grip was dragged: the point is in the pane's own pixels. */
    onExtend: (x: Float, y: Float, topLeft: Boolean) -> Unit,
    /** The grip's finger lifted. */
    onExtendEnd: () -> Unit = {},
    /** The selection's top-left endpoint, in pane pixels, or null when off screen. */
    topLeftHandle: Offset?,
    /** The selection's bottom-right endpoint, in pane pixels, or null. */
    bottomRightHandle: Offset?,
    /** The terminal's background, for contrast. */
    terminalBackground: Color = Color.Black,
    cellHeight: Float = 0f,
    /** Put the selection on the clipboard and paste the clipboard into the session. */
    onPaste: () -> Unit = {},
    /** Hand the selection to the platform's share sheet. */
    onShare: () -> Unit = {},
    /** Type the selection into the focused session and run it. */
    onRun: () -> Unit = {},
    /** Open a new session that runs the selection. */
    onRunInNewSession: () -> Unit = {},
    /** Open the selection as a link. */
    onOpenInBrowser: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleSize = 22.dp
    val handleSizePx = with(density) { handleSize.toPx() }
    val cellH = if (cellHeight > 0f) cellHeight else bounds.height().toFloat().coerceAtLeast(1f)

    // The grips sit on their own rows: the top grip at the centre of the
    // selection's first row, the bottom grip at the centre of its last.
    val startCenterY = if (topLeftHandle != null) {
        paneOrigin.y + topLeftHandle.y + cellH / 2f
    } else {
        paneOrigin.y + bounds.top + cellH / 2f
    }
    val endCenterY = if (bottomRightHandle != null) {
        paneOrigin.y + bottomRightHandle.y + cellH / 2f
    } else {
        paneOrigin.y + bounds.bottom - cellH / 2f
    }

    // Where each grip's rule sits: the edge of the selection, not the middle of
    // its row. The start's rule is the line above the first selected row, the
    // end's the line below the last one — drawn there, the two ends read as the
    // selection's own edges and the rows in between are left to the highlight.
    val startLineY = if (topLeftHandle != null) {
        paneOrigin.y + topLeftHandle.y
    } else {
        paneOrigin.y + bounds.top
    }
    val endLineY = if (bottomRightHandle != null) {
        paneOrigin.y + bottomRightHandle.y + cellH
    } else {
        paneOrigin.y + bounds.bottom
    }

    // The toolbar's second row. Closed by default: six actions on a selection
    // covered the text the user had just chosen, and the ones that matter for
    // a terminal — run, open — were lost among copy and select-all.
    var expanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val paneWidth = constraints.maxWidth.toFloat()
        val paneHeight = constraints.maxHeight.toFloat()
        val edge = with(density) { MENU_EDGE_DP.dp.toPx() }

        val menuWidth = with(density) { MENU_WIDTH_DP.dp.toPx() }
        // The surface's own height, measured from what it draws: the padding at
        // both ends, the first row, and — when open — the divider and the two
        // rows under it. A menu that guessed short was placed as though it
        // fitted above a selection it then covered.
        val menuHeight = with(density) {
            (
                2 * MENU_PAD_DP + ACTION_HEIGHT_DP +
                    if (expanded) {
                        EXTRA_DIVIDER_DP + EXTRA_GAP_DP + 2 * ACTION_HEIGHT_DP
                    } else {
                        0
                    }
                ).dp.toPx()
        }

        val selectionTop = paneOrigin.y + bounds.top
        val selectionBottom = paneOrigin.y + bounds.bottom
        val fitsAbove = selectionTop - menuHeight - edge >= 0f
        val fitsBelow = selectionBottom + menuHeight + edge <= paneHeight
        val selectionCenterX = paneOrigin.x + (bounds.left + bounds.right) / 2f
        val menuX = (selectionCenterX - menuWidth / 2f)
            .coerceIn(edge, (paneWidth - menuWidth - edge).coerceAtLeast(edge))

        // Which side the toolbar hangs on, decided with a bias towards where it
        // already is. Without the bias, a selection whose top crosses the
        // toolbar's height flipped it from above to below on every row of a
        // drag — and the animation that carried it across looked like the menu
        // shooting to the top of the screen and back.
        var below by remember { mutableStateOf(false) }
        val preferBelow = when {
            below && fitsBelow -> true
            !below && fitsAbove -> false
            fitsBelow -> true
            fitsAbove -> false
            // Neither side has room: the one with more of the pane goes.
            else -> selectionTop < paneHeight / 2f
        }
        val sideChanged = preferBelow != below
        LaunchedEffect(preferBelow) { below = preferBelow }

        // The edge the toolbar is anchored by stays put: its bottom when it
        // hangs above the selection, its top when it hangs below. Expanding it
        // therefore grows away from the text rather than pushing the toolbar
        // over it.
        val targetTop = if (preferBelow) {
            (selectionBottom + edge).coerceAtMost((paneHeight - menuHeight - edge).coerceAtLeast(edge))
        } else {
            (selectionTop - menuHeight - edge).coerceAtLeast(edge)
        }

        val animatedMenu = smoothOffset(
            target = Offset(menuX, targetTop),
            snap = sideChanged,
        )

        // The grips, at the selection's own endpoints. A grip whose endpoint
        // has scrolled off the screen is not drawn: it would sit on a row that
        // has nothing to do with the selection.
        //
        // Each grip has a stable identity. Without one, a grip that appeared
        // while the other was hidden took its sibling's animation state and
        // slid into place from the other end's position — the "handle flies to
        // the top and comes back" the drag used to show.
        key("selection-grip-start") {
            topLeftHandle?.let { handle ->
                SelectionGrip(
                    centerX = paneOrigin.x + handle.x,
                    centerY = startCenterY,
                    lineY = startLineY,
                    // The knob hangs off the end of the selection: above the
                    // start's rule, below the end's, so it never covers text
                    // the user has just chosen.
                    outward = -1f,
                    sizePx = handleSizePx,
                    cellHeightPx = cellH,
                    paneOrigin = paneOrigin,
                    accent = DroshWarning,
                    onDrag = { x, y -> onExtend(x, y, true) },
                    onDragEnd = onExtendEnd,
                )
            }
        }
        key("selection-grip-end") {
            bottomRightHandle?.let { handle ->
                SelectionGrip(
                    centerX = paneOrigin.x + handle.x,
                    centerY = endCenterY,
                    lineY = endLineY,
                    outward = 1f,
                    sizePx = handleSizePx,
                    cellHeightPx = cellH,
                    paneOrigin = paneOrigin,
                    accent = DroshWarning,
                    onDrag = { x, y -> onExtend(x, y, false) },
                    onDragEnd = onExtendEnd,
                )
            }
        }

        // The toolbar. Glass rather than a flat chip: a translucent surface, a
        // hairline of light along its top edge and a shadow underneath it, so
        // it reads as a pane of glass over the output instead of as another
        // rectangle of colour pasted on it.
        Column(
            modifier = Modifier
                .offset {
                    IntOffset(
                        animatedMenu.x.roundToInt(),
                        animatedMenu.y.roundToInt(),
                    )
                }
                .width(MENU_WIDTH_DP.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = MenuShape,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .clip(MenuShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            DroshSurfaceHigh.copy(alpha = 0.96f),
                            DroshSurfaceHigh.copy(alpha = 0.88f),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.03f),
                        ),
                    ),
                    shape = MenuShape,
                )
                .padding(horizontal = MENU_PAD_DP.dp, vertical = MENU_PAD_DP.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp)) {
                MenuAction(DroshIcons.Copy, stringResource(R.string.selection_copy), onCopy)
                MenuAction(
                    DroshIcons.ClipboardPaste,
                    stringResource(R.string.selection_paste),
                    onPaste,
                )
                MoreAction(
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                )
                MenuAction(DroshIcons.X, stringResource(R.string.selection_dismiss), onDismiss)
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Box(
                        Modifier
                            .padding(top = MENU_PAD_DP.dp, bottom = EXTRA_GAP_DP.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.07f)),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp)) {
                        MenuAction(
                            DroshIcons.ALargeSmall,
                            stringResource(R.string.selection_select_word),
                            onSelectWord,
                        )
                        MenuAction(
                            DroshIcons.ListChecks,
                            stringResource(R.string.selection_select_line),
                            onSelectLine,
                        )
                        MenuAction(
                            DroshIcons.Square,
                            stringResource(R.string.selection_select_all),
                            onSelectAll,
                        )
                        MenuAction(
                            DroshIcons.Search,
                            stringResource(R.string.selection_search),
                            onSearch,
                        )
                    }
                    Spacer(Modifier.height(EXTRA_GAP_DP.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp)) {
                        MenuAction(
                            DroshIcons.Share,
                            stringResource(R.string.selection_share),
                            onShare,
                        )
                        MenuAction(
                            DroshIcons.Play,
                            stringResource(R.string.selection_run_here),
                            onRun,
                        )
                        MenuAction(
                            DroshIcons.SquarePlus,
                            stringResource(R.string.selection_run_new),
                            onRunInNewSession,
                        )
                        MenuAction(
                            DroshIcons.Globe,
                            stringResource(R.string.selection_open_browser),
                            onOpenInBrowser,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Follow [target] on a spring, or land on it at once.
 *
 * [snap] is for a change that is a re-placement rather than a move: animating
 * that is the toolbar travelling across the pane, which reads as a glitch.
 */
@Composable
private fun smoothOffset(target: Offset, snap: Boolean): Offset {
    val animated = remember { Animatable(target, Offset.VectorConverter) }
    LaunchedEffect(target, snap) {
        if (snap) {
            animated.snapTo(target)
        } else {
            animated.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }
    return animated.value
}

private val MenuShape = RoundedCornerShape(18.dp)

/** One action cell. Four fit a phone's width with the menu's own padding. */
private const val ACTION_DP = 44
private const val ACTION_GAP_DP = 2
private const val ACTION_HEIGHT_DP = 44
private const val MENU_PAD_DP = 4
private const val MENU_EDGE_DP = 8

/** The gap between the toolbar's two rows, and between its rows and divider. */
private const val EXTRA_GAP_DP = 2

/** The divider between the toolbar's first row and the panel above it. */
private const val EXTRA_DIVIDER_DP = 7

/** The toolbar's width: four action cells plus the gaps between them. */
private const val MENU_WIDTH_DP = 4 * ACTION_DP + 3 * ACTION_GAP_DP + 2 * MENU_PAD_DP

/**
 * One toolbar action: icon over a short label.
 *
 * Labels are part of the affordance on a terminal: an icon alone for word and
 * line selection would be a guess, and the two actions are exactly the ones a
 * user does not expect to find in a copy toolbar.
 */
@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = DroshText,
) {
    Box(
        modifier = Modifier
            .size(ACTION_DP.dp, ACTION_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = label,
                color = DroshTextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The "more" button: the toolbar's door to the actions that do not fit.
 *
 * It is a normal action cell with a rotating chevron, so it reads as "this row
 * has a second half" rather than as a menu button bolted onto a toolbar.
 */
@Composable
private fun MoreAction(expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "selectionMoreRotation",
    )
    Box(
        modifier = Modifier
            .size(ACTION_DP.dp, ACTION_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (expanded) {
                    DroshPrimary.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = DroshIcons.ChevronDown,
                contentDescription = stringResource(R.string.selection_more),
                tint = if (expanded) DroshPrimary else DroshText,
                modifier = Modifier
                    .size(17.dp)
                    .rotate(rotation),
            )
            Text(
                text = stringResource(R.string.selection_more),
                color = if (expanded) DroshPrimary else DroshTextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A draggable grip at one end of the selection: a rule and a knob under it.
 *
 * The rule is the selection's edge — the line the first row starts on, or the
 * line the last row ends on — and it is the part that says *where* the
 * selection begins or ends, spanning the end of the text it holds. The knob is
 * the part that says *this is grabbable*: a small disc hanging off the rule, on
 * the outside of the selection so it never covers the text that was just
 * chosen.
 *
 * The two shapes are what makes a terminal's selection read without a halo or a
 * stem: a bare dot on the grid looked like a stray glyph, and a ring around it
 * read as a second, dimmer handle behind the real one. A rule plus a knob is a
 * caret's ends — the shape the user already understands.
 *
 * A press grows the knob: it is the confirmation that the finger found the
 * grip, given before the drag has moved anything.
 *
 * ## Dragging
 *
 * A drag is measured from where the finger went down, never from where the disc
 * is drawn. The disc follows the selection on a spring, so its drawn position
 * lags a frame or two behind the endpoint; adding each frame's delta to that
 * lagging position fed the animation back into its own input, and the grip
 * wandered off to other rows under a steady finger. A base captured at
 * [detectDragGestures]'s start plus the accumulated delta is monotonic: the
 * finger moves one cell, the endpoint moves one cell.
 *
 * The touch target is 48 dp and stays centred on the endpoint, even though the
 * drawing is smaller. A terminal is a dense grid, and a grip that is only as
 * big as its drawing is a grip the user misses on the first try.
 */
@Composable
private fun SelectionGrip(
    centerX: Float,
    centerY: Float,
    /** The selection edge this grip holds, in pane pixels. */
    lineY: Float,
    /** -1 to hang the knob above the rule, +1 to hang it below. */
    outward: Float,
    sizePx: Float,
    /** One row's height, so the box reaches past the edge the rule sits on. */
    cellHeightPx: Float,
    paneOrigin: Offset,
    accent: Color,
    onDrag: (x: Float, y: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val touchPx = with(density) { 48.dp.toPx() }
    // Tall enough for the rule and the knob it carries on the outside: the box
    // is centred on the row, and the edge is half a row from there. A box that
    // was only 48dp square clipped the knob on a large font.
    val boxHeightPx = touchPx + cellHeightPx
    val boxHeightDp = with(density) { boxHeightPx.toDp() }
    val currentCenter by rememberUpdatedState(Offset(centerX, centerY))
    val haptics = LocalHapticFeedback.current

    var pressed by remember { mutableStateOf(false) }

    // The press is a spring with a little bounce: a grip is a physical thing
    // under the finger, and a linear tween reads as a tooltip.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 1.32f else 1f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "selectionGripPress",
    )

    // The knob is smaller than the old disc — it has a rule to belong to now —
    // and the rule is longer than the knob is wide, so the pair spans the end of
    // the row it marks.
    val knobRadius = sizePx * 0.30f
    val ruleHalfWidth = sizePx * 0.55f
    val ruleThickness = with(density) { 2.5.dp.toPx() }
    val knobGap = with(density) { 3.dp.toPx() }

    // The grip follows the selection, it does not teleport to it — but under a
    // finger it must not lag: the spring is exactly what made a drag feel like
    // it was slipping away, so a pressed grip tracks its endpoint directly.
    val animatedX by animateFloatAsState(
        targetValue = centerX,
        animationSpec = if (pressed) {
            snap()
        } else {
            spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
        },
        label = "selectionGripX",
    )
    val animatedY by animateFloatAsState(
        targetValue = centerY,
        animationSpec = if (pressed) {
            snap()
        } else {
            spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
        },
        label = "selectionGripY",
    )
    // The rule follows the edge rather than the row's centre, so it moves with
    // the selection exactly like the knob and never lags a row behind it.
    val animatedLineY by animateFloatAsState(
        targetValue = lineY,
        animationSpec = if (pressed) {
            snap()
        } else {
            spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
        },
        label = "selectionGripLineY",
    )

    // Where the finger went down, in the pane's pixels, and how far it has
    // moved since. Both are only written while a drag is in flight.
    var dragBase by remember { mutableStateOf(Offset.Zero) }
    var dragTotal by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (animatedX - touchPx / 2f).roundToInt(),
                    (animatedY - boxHeightPx / 2f).roundToInt(),
                )
            }
            .size(width = 48.dp, height = boxHeightDp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        pressed = true
                        dragBase = currentCenter - paneOrigin
                        dragTotal = Offset.Zero
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        pressed = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        pressed = false
                        onDragEnd()
                    },
                ) { change, dragAmount ->
                    change.consume()
                    dragTotal += dragAmount
                    onDrag(dragBase.x + dragTotal.x, dragBase.y + dragTotal.y)
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val centerXInBox = size.width / 2f
            // The rule's place inside this box: the box is centred on the row
            // (the drag's own reference), and the edge is half a row away.
            val ruleY = size.height / 2f + (animatedLineY - animatedY)
            drawLine(
                color = accent,
                start = Offset(centerXInBox - ruleHalfWidth, ruleY),
                end = Offset(centerXInBox + ruleHalfWidth, ruleY),
                strokeWidth = ruleThickness,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = accent,
                radius = knobRadius * pressScale,
                center = Offset(
                    centerXInBox,
                    ruleY + outward * (knobGap + knobRadius * pressScale),
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TerminalSelectionOverlayPreview() {
    TerminalSelectionOverlay(
        bounds = Rect(48, 120, 300, 168),
        paneOrigin = Offset(0f, 0f),
        onCopy = {},
        onSelectWord = {},
        onSelectLine = {},
        onSelectAll = {},
        onSearch = {},
        onDismiss = {},
        onExtend = { _, _, _ -> },
        topLeftHandle = Offset(48f, 120f),
        bottomRightHandle = Offset(300f, 168f),
        terminalBackground = Color(0xFF1E1E1E),
        cellHeight = 24f,
    )
}
