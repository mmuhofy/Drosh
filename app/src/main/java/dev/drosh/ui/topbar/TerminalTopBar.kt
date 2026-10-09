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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.painterResource
import dev.drosh.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * `Modifier.blur` is a no-op below API 31 — there is no fallback, just no blur —
 * so a 28% tint there leaves the terminal's text behind the pill perfectly
 * readable. This is the tint that does not depend on the blur for anything.
 */
private const val PILL_SURFACE_ALPHA_UNBLURRED = 0.88f

/**
 * The tint for a pill whose blur is actually rendering.
 *
 * Read once rather than per pill: [BLUR_SUPPORTED] is a platform constant, and a
 * pill has no business re-deciding it.
 */
private val PILL_TINT_ALPHA: Float =
    if (BLUR_SUPPORTED) PILL_SURFACE_ALPHA else PILL_SURFACE_ALPHA_UNBLURRED

/**
 * How hard the pill's backdrop is blurred.
 *
 * 22dp was not enough: the glyphs behind a button were still legible as shapes,
 * which is the opposite of frosted glass. 34dp was not enough either. Past 44dp
 * the radius is as large as the pill itself, so nothing resolves and what shows
 * through is the terminal's colour and brightness rather than its contents.
 *
 * Applied through Modifier.blur, so this is a real RenderEffect on a real layer --
 * and where [BLUR_SUPPORTED] is false there is no blur at all and the tint has to
 * carry the whole job.
 */
private val PILL_BLUR_RADIUS = 44.dp

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

/**
 * How thick the glass edge's stroke is.
 *
 * A hairline was too fine to read as light at all: at 0.75dp on a 3x display it
 * was a couple of pixels, and two arcs that thin are easy to miss entirely -- which
 * is also what made them hard to place by eye. 1.5dp reads as a highlight rather
 * than as an artefact of rendering.
 */
private val PILL_EDGE_WIDTH = 1.5.dp

/**
 * A stroke along two arcs only: the top-left and the bottom-right.
 *
 * This is the shape light makes on a curved surface -- it catches the crest on one
 * side and grazes the trough on the other, and a stroke all the way round is what
 * makes a drawn box look drawn. Compose's angles start at 3 o'clock and run
 * clockwise, so those two arcs are 180..270 and 0..90.
 *
 * Drawn through [drawWithContent], not `drawBehind`, because on a Row the content
 * is drawn after the modifier chain and an edge laid down first ends up underneath
 * it -- invisible against anything opaque drawn inside.
 */
private fun Modifier.liquidGlassEdge(strokeWidth: Dp, color: Color = PILL_BORDER): Modifier =
    this.drawWithContent {
        drawContent()
        val stroke = strokeWidth.toPx()
        if (stroke <= 0f || size.minDimension <= stroke) return@drawWithContent
        // Inset by half the stroke, or the outer half of it falls outside the
        // bounds and the edge reads as thinner than asked for.
        val inset = stroke / 2f
        val arc = Size(size.width - stroke, size.height - stroke)
        // Centred on the two diagonals rather than starting on the axes, so the
        // stroke sits *in* the corner instead of straddling it.
        val topLeft = Offset(inset, inset)
        listOf(180f, 0f).forEach { startAngle ->
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(width = stroke),
            )
        }
    }

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

                 val nameShape = RoundedCornerShape(percent = 50)
                 Box(
                     modifier = Modifier
                         .height(rowHeight)
                         .clip(nameShape)
                         .background(DroshSurfaceHigh.copy(alpha = PILL_TINT_ALPHA)),
                     contentAlignment = Alignment.Center,
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
            // The group carries the shared edge and nothing else. Each button
            // draws its own tint over its own backdrop, so the pair and the lone
            // button on the left are the same glass rather than one being a
            // different recipe -- and where there is no backdrop at all, the two
            // circles' tints and the divider's sit side by side at the same
            // strength, which still reads as one surface.
            Row(
                modifier = Modifier
                    .height(rowHeight)
                    .clip(CircleShape)
                    .liquidGlassEdge(PILL_EDGE_WIDTH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    drawableRes = dev.drosh.ui.R.drawable.ic_agent_head,
                    contentDescription = "Agent",
                    width = PILL_WIDTH,
                    height = rowHeight,
                    // The mark is drawn on a 24 viewport that it fills, so it needs
                    // no correcting — unlike the old 2048 mark, which sat at 46% of
                    // its own canvas and looked half the size of its neighbours.
                    iconSize = pillIconSize,
                    onClick = { onOpenAgent() },
                )

                // The seam. A hairline rather than a gap, so the pair still reads
                // as one control.
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(rowHeight - 12.dp)
                        .background(DroshSurfaceHigh.copy(alpha = PILL_TINT_ALPHA)),
                )

                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "More actions",
                    width = PILL_WIDTH,
                    height = rowHeight,
                    iconSize = pillIconSize,
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
) {
    GlassPillBody(
        contentDescription, onClick, width, height, iconSize, backdrop, terminalBounds,
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
    content: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    // Deliberately a plain holder, not Compose state. Writing state from
    // onGloballyPositioned invalidates layout, which re-runs the callback,
    // which writes again — and the two leftmost buttons visibly climbed the
    // screen during a scroll. The offset is only needed at draw time, so it is
    // read there instead.
    val slice = remember { PillSlice() }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .onGloballyPositioned { slice.pillBounds = it.boundsInRoot() }
            // Compose's own press indication, not a scale on the icon.
            //
            // The icon used to shrink to 0.88 on press, which read as the glyph
            // being squashed rather than as the control being touched -- and it
            // told the thumb where the icon was but not where the button was.
            // A bounded ripple growing from the touch point answers for the whole
            // control, and clipping it to the shape keeps it inside the pill.
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Blurred terminal first, then the tint over it, then the icon. The
        // order is the prototype's: the tint sits on top of the backdrop, so
        // putting it on the Box as a background would hide the blur entirely.
        //
        // Only while row is an overlay on the terminal: collapsed, the row sits in
        // the band above the grid and `backdrop` is null, so the slice draws
        // nothing and the pill is its own surface.
        TerminalBackdropSlice(
            backdrop = backdrop,
            sourceOffset = { slice.offsetIn(terminalBounds) },
            blurRadius = PILL_BLUR_RADIUS,
            shape = shape,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(DroshSurfaceHigh.copy(alpha = PILL_TINT_ALPHA)),
        )
        Box(
            modifier = Modifier.size(iconSize),
            contentAlignment = Alignment.Center,
        ) { content(DroshText) }
    }
}

