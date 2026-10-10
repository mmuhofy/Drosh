package dev.drosh.ui.topbar

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.res.painterResource
import dev.drosh.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import dev.drosh.ui.topbar.BLUR_SUPPORTED
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.ui.DroshIcons
import kotlin.math.roundToInt
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.LocalFontSet
import dev.drosh.ui.session.SessionSwitcherViewModel

/**
 * The terminal's floating pill row.
 *
 * Two positions, and nothing else moves between them.
 *
 *  - **Collapsed** — the viewport is up in the scrollback, or a TUI owns the
 *    terminal. The app is **fullscreen**, the pills are **flush with the top of
 *    the screen**, and the grid's top padding shrinks to the band the status bar
 *    vacated.
 *  - **Expanded** — at the live edge. The status bar is back, and the pills sit
 *    below it at `statusBarH + 10dp`.
 *
 * The row is [BAR_ROW_HEIGHT] in both states. Only its offset moves: the row
 * shrinking to the status bar's height while collapsed read as a squashed control
 * that could not decide where it belonged, and fitting it inside a band was never
 * what putting it at the top of the screen required.
 *
 * The terminal's top padding moves with it, on the same tween — see
 * [rememberTerminalTopPadding]. A fixed clearance is wrong in both directions at
 * once: it wastes the status bar's height where there is no status bar, and lets
 * the first line of output go under the row where there is.
 */
private const val BAR_ROW_HEIGHT_DP = 44
private const val BAR_TOP_OFFSET_DP = 10
private const val BAR_BOTTOM_OFFSET_DP = 6

/**
 * Where the row's top edge sits once the app is fullscreen.
 *
 * Not zero. A row flush against y=0 reads as pinned to the edge of the screen
 * rather than as a control, and on a device with a cutout a few dp is the
 * difference between the row sitting in the band and sitting under the notch.
 * Small on purpose: the band is the status bar's, not the row's.
 */
private const val COLLAPSED_ROW_OFFSET_DP = 6

/**
 * How far below the row the terminal's grid starts once fullscreen.
 *
 * Almost nothing. Collapsed, the grid picks up right where the buttons end, so
 * the scrollback gets back every line the status-bar clearance was taking.
 */
private const val COLLAPSED_GRID_GAP_DP = 2

/**
 * How long the row and the terminal's padding take to travel.
 *
 * Just under the platform's own status-bar transition, so the last thing to
 * settle is the system's rather than two motions ending on top of each other.
 */
private const val CHROME_ANIMATION_MILLIS = 220

/**
 * How long they wait before starting on the way *in*.
 *
 * Because the clock has to leave first. The status bar belongs to the system
 * window and is drawn above the app, so it has to be told to hide and that takes
 * its own time; moving the pills while the clock is still fading puts two things
 * in motion in the same 40dp, which is what read as the row going back and
 * forth.
 */
private const val CHROME_COLLAPSE_DELAY_MILLIS = 70

/**
 * Over the blurred slice, so the terminal shows through as a smudge rather
 * than as glyphs.
 *
 * Deliberately light. At 0.72 this read as a grey box with a hint of terminal in
 * it, because 72% of the pill's area was a solid surface and only 28% was the
 * glass the user is supposed to be looking through. The prototype's own pill is
 * `rgba(255,255,255,0.08)` -- nearly nothing -- because its blur is doing the
 * work. Here the fill only has to lift the pill off the terminal a little.
 */
private const val PILL_SURFACE_ALPHA = 0.28f

/**
 * The tint where the platform cannot blur, so the text has to be hidden by
 * opacity instead.
 *
 * `Modifier.blur` is a no-op below API 31 -- there is no fallback, just no blur --
 * so a 28% tint there leaves the terminal's text behind the pill perfectly
 * readable. This tint does not depend on the blur for anything.
 */
private const val PILL_SURFACE_ALPHA_UNBLURRED = 0.88f

/**
 * The tint for a pill whose blur is actually rendering.
 */
private val PILL_TINT_ALPHA: Float =
    if (BLUR_SUPPORTED) PILL_SURFACE_ALPHA else PILL_SURFACE_ALPHA_UNBLURRED

/**
 * The hairline around and between pills. The prototype's `rgba(255,255,255,.14)`.
 *
 * A plain colour rather than a token: `DroshSurfaceHigh` is a composable getter
 * over `LocalDroshColors`, and a top-level `val` cannot call one -- the border
 * would be read at class-load time, before any composition existed.
 *
 * Carried on the surface it belongs to: a button drawing its own border inside a
 * group that already has one would double it to 2dp, which reads as a seam rather
 * than as an edge.
 */
private val PILL_BORDER = Color.White.copy(alpha = 0.14f)

/** Thickness of the glass edge. Visible stroke width, measured inside the shape. */
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
 * A fake liquid-glass edge: a diagonal gradient stroke that follows [shape] exactly.
 *
 * The old edge drew two arcs of the ellipse inscribed in the node's bounds. On a
 * square pill that is the outline; on the 88x44 right-hand pair it is not -- an
 * ellipse has no straight top and bottom, so the arcs drifted off the capsule's
 * edge and got cut by the clip, which is the broken border on the two right
 * buttons.
 *
 * Here the stroke is the shape's own outline, so it cannot disagree with the clip.
 * It is drawn at double width and clipped to the shape, which leaves exactly
 * [width] of it inside -- no inset arithmetic, and it works for any [Shape], the
 * half-capsules of the joined pair included.
 *
 * Drawn after the content so nothing opaque inside can cover it.
 */
private fun Modifier.liquidGlassBorder(shape: Shape, width: Dp = PILL_EDGE_WIDTH): Modifier =
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

/** The full capsule, and the two halves of the joined pair. */
private val PILL_SHAPE = RoundedCornerShape(percent = 50)
private val PILL_START_SHAPE = RoundedCornerShape(
    topStartPercent = 50, topEndPercent = 0, bottomEndPercent = 0, bottomStartPercent = 50,
)
private val PILL_END_SHAPE = RoundedCornerShape(
    topStartPercent = 0, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 0,
)

/**
 * Where a pill sits inside the sampled terminal strip.
 *
 * A plain mutable holder on purpose. This is written during layout and read
 * during draw, and routing it through Compose state makes layout invalidate
 * itself: onGloballyPositioned wrote state, which re-ran layout, which called
 * it again, and the leftmost buttons visibly climbed the screen during a
 * scroll.
 */
private class PillSlice {
    var pillBounds: Rect? = null

    fun offsetIn(terminal: Rect?): IntOffset {
        val p = pillBounds ?: return IntOffset.Zero
        if (terminal == null) return IntOffset.Zero
        return IntOffset(
            (p.left - terminal.left).roundToInt(),
            (p.top - terminal.top).roundToInt(),
        )
    }
}

/**
 * A pill's width, equal to its height, so the buttons read as full circles.
 *
 * It was wider than tall — a stadium — which left the icon floating in an oval
 * with dead space at both ends. [BAR_ROW_HEIGHT] on both sides is the disc the
 * name of the thing implies.
 */
private const val PILL_WIDTH_DP = 44

/**
 * How hard the pill's backdrop is blurred.
 *
 * 22dp was not enough: the glyphs behind a button were still legible as shapes,
 * which is the opposite of frosted glass. 34dp was not enough either. Past 44dp
 * the radius equals the pill's own height, so nothing resolves and what shows
 * through is the terminal's colour and brightness rather than its contents.
 *
 * Applied through Modifier.blur, so this is a real RenderEffect on a real layer --
 * and where BLUR_SUPPORTED is false there is no blur at all and the tint has to
 * carry the whole job.
 */
private val PILL_BLUR_RADIUS = 44.dp

/**
 * The system status bar's height, whether or not it is currently showing.
 *
 * Deliberately the visibility-agnostic inset. `statusBars` is zero the moment the
 * bar hides, and this row's state changes on scroll, so reading that one here
 * would fling the row downward exactly as it is meant to be moving up.
 *
 * One function for both the row and the terminal's padding: they are two halves
 * of the same movement and must agree about where the status bar ends.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun statusBarHeight(): Dp =
    WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()

/**
 * The terminal's top padding, in step with the row.
 *
 * The two states are not mirror images, and should not be:
 *
 *  - **Collapsed** the status bar is gone, so its height is free and the grid
 *    starts right where the row ends. Everything past that first line is output
 *    the user was not getting.
 *  - **Expanded** the status bar is back, so the grid starts below it and the row
 *    **floats over the output**. That is the point of it floating: the pills are
 *    translucent and blurred, and the terminal runs on behind them. There is no
 *    clearance behind them, so the first line is not pushed out of the way.
 *
 * Derived from the same constants the row uses rather than a second set, and on
 * the same tween, so the grid cannot end up out of step with the control.
 */
@Composable
fun rememberTerminalTopPadding(collapsed: Boolean): Dp {
    val statusBarH = statusBarHeight()
    val target = if (collapsed) {
        // The status bar is gone, so its height is free. CHROME_CLEARANCE already
        // carries the row, its offset and the gap.
        CHROME_CLEARANCE
    } else {
        // The status bar is back and the row floats over the terminal, so the only
        // thing to clear is the bar itself.
        statusBarH
    }
    return animateDpAsState(
        targetValue = target,
        animationSpec = tween<Dp>(
            durationMillis = CHROME_ANIMATION_MILLIS,
            delayMillis = if (collapsed) CHROME_COLLAPSE_DELAY_MILLIS else 0,
        ),
        label = "terminalTopPadding",
    ).value
}

private val BAR_ROW_HEIGHT = BAR_ROW_HEIGHT_DP.dp
private val PILL_WIDTH = PILL_WIDTH_DP.dp
private val BAR_TOP_OFFSET = BAR_TOP_OFFSET_DP.dp
private val BAR_BOTTOM_OFFSET = BAR_BOTTOM_OFFSET_DP.dp

/** The row's offset once fullscreen. See COLLAPSED_ROW_OFFSET_DP. */
private val COLLAPSED_ROW_OFFSET = COLLAPSED_ROW_OFFSET_DP.dp
private val COLLAPSED_GRID_GAP = COLLAPSED_GRID_GAP_DP.dp

/**
 * The terminal's top clearance once fullscreen: the row, its offset, and the gap.
 *
 * Read by `TerminalScreen` too, so the grid's inset and the row above it are one
 * number. It is a **total**, not a gap, and already contains the row's own height,
 * so nothing may add `BAR_ROW_HEIGHT` to it.
 *
 * Declared here rather than with the other dp constants because it is derived from
 * them, and top-level initialisation order is declaration order.
 */
val CHROME_CLEARANCE = COLLAPSED_ROW_OFFSET + BAR_ROW_HEIGHT + COLLAPSED_GRID_GAP

/**
 * How much of the terminal's top edge the top bar backdrop samples.
 *
 * The row's own offset plus its height, because that is where the pills sit once
 * they are floating over output. Nothing taller is reachable — a pill samples only
 * its own slice of the strip.
 */
val TOP_BAR_BACKDROP_STRIP = BAR_TOP_OFFSET + BAR_ROW_HEIGHT

@Composable
fun TerminalTopBar(
    /**
     * Fullscreen: the viewport is up in the scrollback, or a TUI owns the
     * terminal. The row goes to the very top of the screen, into the space the
     * status bar vacated, and the grid pads itself to match.
     */
    chromeCollapsed: Boolean,
    /** Strip sampled from the terminal, or null when there is nothing to sample. */
    backdrop: ImageBitmap?,
    /** Where the terminal sits in root space, so a pill can find its slice. */
    terminalBounds: Rect?,
    viewModel: SessionSwitcherViewModel,
    onOpenSidebar: () -> Unit,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit,
    /** True while a second pane is open. */
    isSplit: Boolean = false,
    /** True when that second pane is floating rather than docked. */
    isFloating: Boolean = false,
    onToggleFloat: () -> Unit = {},
    onCloseSplit: () -> Unit = {},
    onCycleSplit: () -> Unit = {},
    onSwapPanes: () -> Unit = {},
    isSystemOverlay: Boolean = false,
    canDrawOverlays: Boolean = true,
    onToggleSystemOverlay: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activeName by viewModel.activeName.collectAsStateWithLifecycle()

    val statusBarH = statusBarHeight()

    // One spec for the row and the terminal's padding, so they cannot be caught
    // disagreeing.
    val chromeTween = tween<Dp>(
        durationMillis = CHROME_ANIMATION_MILLIS,
        delayMillis = if (chromeCollapsed) CHROME_COLLAPSE_DELAY_MILLIS else 0,
    )

    // The row's **offset**, and nothing else. Its height is fixed at 44dp in both
    // states: the row shrinking to the status bar's height is what made it read as
    // squashed, and fitting it inside a band was never what that bought.
    //
    // Collapsed: the app goes true fullscreen (the real status bar is gone, no
    // inset reserved by the system at all), so the row sits a few dp from the top
    // of the screen, inside the band the status bar vacated. Not flush at y=0 --
    // see COLLAPSED_ROW_OFFSET.
    // Expanded: the real status bar is back, so the row sits below it.
    val rowOffset by animateDpAsState(
        targetValue = if (chromeCollapsed) COLLAPSED_ROW_OFFSET else statusBarH + BAR_TOP_OFFSET,
        animationSpec = chromeTween,
        label = "chromeRowOffset",
    )

    val rowHeight = BAR_ROW_HEIGHT

    /** The icon's size, matching every lucide glyph in the row. */
    val pillIconSize = 22.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_TOP_OFFSET + BAR_ROW_HEIGHT + BAR_BOTTOM_OFFSET),
    ) {
        var moreExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The row's whole travel. Collapsed that is 0 — flush with the
                // top of the screen, inside the band the status bar left. Expanded
                // it is the status bar's inset plus a gap, so the row sits below
                // the clock rather than under it.
                .offset(y = rowOffset)
                .height(rowHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // ── Left: sessions button + session name pill ───────────────────
            // Pinned to [rowHeight] so it cannot settle a different height from
            // the pair on the other side. Two clusters in one Row share a centre
            // only for as long as they are the same height; this makes it true by
            // construction rather than by what happens to be inside them.
            Row(
                modifier = Modifier.height(rowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = DroshIcons.PanelLeft,
                    contentDescription = "Open sessions",
                    height = rowHeight,
                    iconSize = pillIconSize,
                    onClick = onOpenSidebar,
                )

                // Same glass as the buttons: backdrop, tint and edge.
                GlassSurface(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    shape = PILL_SHAPE,
                    modifier = Modifier.height(rowHeight),
                ) {
                    Text(
                        text = activeName ?: "Drosh",
                        color = DroshText,
                        fontFamily = LocalFontSet.current.sans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))


            // ── Right: the joined pair ────────────────────────────────────────
            // Same Row as the left group rather than a sibling Box aligned by
            // hand. Two separate parents let the clusters settle on different
            // baselines whenever their content differed in height, which is why
            // the left and right buttons looked vertically offset.
            //
            // Agent and more are one surface with a hairline between them, not two
            // pills with a gap. Two floating controls a hand's width apart on the
            // same side of the screen read as two unrelated actions; touching
            // circles read as one group with two things in it, and the divider
            // says where one ends.
            Row(
                modifier = Modifier
                    .height(rowHeight)
                    .clip(PILL_SHAPE)
                    .background(DroshSurfaceHigh.copy(alpha = PILL_TINT_ALPHA))
                    .liquidGlassBorder(PILL_SHAPE),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // Each half is clipped to its half of the capsule, not to a circle:
                // two circles inside a capsule leave the backdrop, the ripple and
                // the border disagreeing about where the group's edge is.
                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    drawableRes = dev.drosh.ui.R.drawable.ic_agent_head,
                    contentDescription = "Agent",
                    width = PILL_WIDTH,
                    height = rowHeight,
                    iconSize = pillIconSize,
                    ownSurface = false,
                    shape = PILL_START_SHAPE,
                    onClick = { onOpenAgent() },
                )

                // The seam: a hairline that fades at both ends instead of a hard bar.
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(rowHeight - 12.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.18f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )

                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "More actions",
                    width = PILL_WIDTH,
                    height = rowHeight,
                    iconSize = pillIconSize,
                    ownSurface = false,
                    shape = PILL_END_SHAPE,
                    onClick = { moreExpanded = true },
                )
            }
        }

        // Material's DropdownMenu anchors to the position of the composable it is
        // called on. This one sat directly in the full-width bar Box, so it
        // opened at the far left. A zero-width box aligned to the end puts the
        // anchor under the overflow button on the right without the menu
        // inheriting a width from its parent.
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            MoreActionsDropdown(
                expanded = moreExpanded,
                onDismiss = { moreExpanded = false },
                onFindInOutput = onFindInOutput,
                onRefresh = onRefresh,
                isSplit = isSplit,
                isFloating = isFloating,
                onToggleFloat = onToggleFloat,
                onCloseSplit = onCloseSplit,
                onCycleSplit = onCycleSplit,
                onSwapPanes = onSwapPanes,
                isSystemOverlay = isSystemOverlay,
                canDrawOverlays = canDrawOverlays,
                onToggleSystemOverlay = onToggleSystemOverlay,
            )
        }
    }
}

/**
 * The overflow menu.
 *
 * The split entries only appear once a second pane exists. Offering "Float
 * window" before that would be an action on a pane that is not there, and the
 * labels have to swap — "Float" and "Dock" describe the same gesture from
 * opposite ends, and a menu that says "Float" while the pane is already
 * floating is worse than no menu.
 */
@Composable
private fun MoreActionsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
    isSplit: Boolean,
    isFloating: Boolean,
    onToggleFloat: () -> Unit,
    onCloseSplit: () -> Unit,
    onCycleSplit: () -> Unit,
    onSwapPanes: () -> Unit,
    isSystemOverlay: Boolean,
    canDrawOverlays: Boolean,
    onToggleSystemOverlay: () -> Unit,
) {
    DroshDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        items = buildList {
            if (isSplit) {
                // While the pane is in the overlay, the only entry that makes sense
                // is the one that brings it back. Float and dock describe geometry
                // inside this app's window, and neither means anything for a window
                // this activity does not own — so offering them would describe a
                // gesture that does nothing.
                if (isSystemOverlay) {
                    add(
                        DroshMenuItem(
                            label = "Bring pane back",
                            icon = DroshIcons.Square,
                        ),
                    )
                } else {
                    add(
                        DroshMenuItem(
                            label = if (isFloating) "Dock pane" else "Float window",
                            icon = DroshIcons.Square,
                        ),
                    )
                    // Hidden without the permission rather than disabled: a greyed
                    // entry would still be a tap, and the reason it cannot work is a
                    // settings screen the user has to be sent to anyway.
                    if (canDrawOverlays) {
                        add(
                            DroshMenuItem(
                                label = "Float over other apps",
                                icon = DroshIcons.PanelBottom,
                            ),
                        )
                    }
                }
                add(
                    DroshMenuItem(
                        label = "Move divider",
                        icon = DroshIcons.RotateCcw,
                    ),
                )
                add(
                    DroshMenuItem(
                        label = "Swap panes",
                        icon = DroshIcons.ArrowUpDown,
                    ),
                )
                add(
                    DroshMenuItem(
                        label = "Close second pane",
                        icon = DroshIcons.X,
                    ),
                )
            }
            add(DroshMenuItem(label = "Refresh terminal", icon = DroshIcons.RotateCw))
            add(DroshMenuItem(label = "Find in output", icon = DroshIcons.Search))
            // Settings is gone from here. It is reachable from the drawer, and
            // an overflow entry that duplicates a drawer item gives two ways to
            // open the same screen.
        },
        onItemClick = { item ->
            onDismiss()
            when (item.label) {
                "Refresh terminal" -> onRefresh()
                "Find in output" -> onFindInOutput()
                "Float window", "Dock pane" -> onToggleFloat()
                "Float over other apps", "Bring pane back" -> onToggleSystemOverlay()
                "Move divider" -> onCycleSplit()
                "Swap panes" -> onSwapPanes()
                "Close second pane" -> onCloseSplit()
            }
        },
    )
}

/**
 * iOS-style glass pill button.
 *
 * Gerçek backdrop blur uygulamaz (Compose'da native karşılığı yok).
 * Bunun yerine yarı şeffaf surface + hafif highlight gradyanı ile
 * "buzlu cam" hissi simüle edilir. Basılınca hafif scale-down (spring,
 * bounce yok) ile dokunma geri bildirimi verir.
 */
@Composable
private fun GlassPillButton(
    backdrop: ImageBitmap?,
    terminalBounds: Rect?,
    drawableRes: Int? = null,
    icon: ImageVector? = null,
    contentDescription: String,
    onClick: () -> Unit,
    width: Dp = PILL_WIDTH,
    height: Dp = BAR_ROW_HEIGHT,
    iconSize: Dp = 22.dp,
    /**
     * False when this button sits inside a group that already carries the surface.
     *
     * The backdrop and the icon are the button's own; the fill, the border and the
     * press-scale are the group's, so several buttons in one surface do not each
     * redraw a box inside it.
     */
    ownSurface: Boolean = true,
    /** The button's clip. A half-capsule when it is one half of a joined pair. */
    shape: RoundedCornerShape = PILL_SHAPE,
) {
    GlassPillBody(
        contentDescription, onClick, width, height, iconSize, backdrop, terminalBounds, ownSurface, shape,
    ) { tint ->
        when {
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
    content: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
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
        ) { content(DroshText) }
    }
}

/**
 * One piece of glass: blurred terminal slice, tint, gradient edge, then content.
 *
 * Shared by the buttons and the session-name pill so they cannot drift apart.
 * Clip comes before the ripple on purpose -- a bounded ripple is a rectangle, and
 * it is the clip ahead of it that keeps it inside the shape.
 *
 * [ownSurface] false: the tint and edge belong to a parent group; only the
 * backdrop slice and the content are drawn here.
 */
@Composable
private fun GlassSurface(
    backdrop: ImageBitmap?,
    terminalBounds: Rect?,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    ownSurface: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // Plain holder, not state: see PillSlice.
    val slice = remember { PillSlice() }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(shape)
            .onGloballyPositioned { slice.pillBounds = it.boundsInRoot() }
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
        TerminalBackdropSlice(
            backdrop = backdrop,
            sourceOffset = { slice.offsetIn(terminalBounds) },
            blurRadius = PILL_BLUR_RADIUS,
            shape = shape,
            modifier = Modifier.matchParentSize(),
        )
        if (ownSurface) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(DroshSurfaceHigh.copy(alpha = PILL_TINT_ALPHA))
                    .liquidGlassBorder(shape),
            )
        }
        content()
    }
}
