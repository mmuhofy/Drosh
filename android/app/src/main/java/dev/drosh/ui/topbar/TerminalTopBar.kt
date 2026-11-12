package dev.drosh.ui.topbar

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.ui.DroshIcons
import dev.drosh.theme.DroshBackground
import dev.drosh.ui.DroshDropdownMenu
import dev.drosh.ui.DroshMenuDensity
import dev.drosh.ui.DroshMenuItem
import dev.drosh.theme.LocalFontSet
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
 * The pill's fill: dark, and translucent.
 *
 * The pills used to be glass — a blurred slice of the terminal behind them plus
 * a tint. The blur is gone: it read as frosted glass on a device that could blur
 * and as a smudge on one that could not, and either way the button's silhouette
 * depended on what the terminal happened to be printing.
 *
 * The transparency stayed, though, because a row that floats over the output
 * should look like it is floating over it: the terminal shows through, faintly
 * enough that the content stays readable and the row keeps its own presence.
 *
 * Dark rather than themed: the row floats over the terminal, and the terminal is
 * dark in every theme, so the button is dark in every theme too. See
 * [PILL_CONTENT] for why its content is light.
 */
private val PILL_SURFACE = Color(0xFF2A2A2C).copy(alpha = 0.78f)

/**
 * The row's own colour for its text and its icons.
 *
 * The row floats over the terminal, and the terminal is dark in every theme
 * — a dark terminal in a light app. So the row's content is the light one
 * whatever the app's own text colour is: what is behind the row is the
 * terminal, and the terminal's foreground is what belongs on it.
 */
private val PILL_CONTENT = Color(0xFFF2F2F2)

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
 * The old edge drew two arcs of the ellipse inscribed in the node's bounds. On a
 * square pill that is the outline; on the 88x44 right-hand pair it was not -- an
 * ellipse has no straight top and bottom, so the arcs drifted off the capsule's
 * edge and got cut by the clip, which is the broken border the two right buttons
 * used to have. The pair is two circles now, but the stroke is still the shape's
 * own outline rather than an inscribed ellipse, so it cannot disagree with the
 * clip whatever shape a pill is given.
 *
 * Drawn at double width and clipped to the shape, which leaves exactly [width]
 * of it inside -- no inset arithmetic, and it works for any [Shape].
 *
 * Drawn after the content so nothing opaque inside can cover it.
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
private val PILL_SHAPE = RoundedCornerShape(percent = 50)

/**
 * The sessions button's shape: a square with soft corners.
 *
 * A circle says "one action"; a square with rounded corners says "a list of
 * things", which is what the button opens. The corners are soft enough that the
 * button still belongs to the row.
 */
private val SESSION_BUTTON_SHAPE = RoundedCornerShape(14.dp)

/**
 * A pill's width, equal to its height, so the buttons read as full circles.
 *
 * It was wider than tall — a stadium — which left the icon floating in an oval
 * with dead space at both ends. [BAR_ROW_HEIGHT] on both sides is the disc the
 * name of the thing implies.
 */
private const val PILL_WIDTH_DP = 44

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
fun rememberTerminalTopPadding(collapsed: Boolean, tuiActive: Boolean = false): Dp {
    // The terminal fills the screen under the system's own bars; the row
    // floats over the output, which is what a floating row is for.
    //
    // What the grid must not be under is the *system's* status bar, because
    // that one is opaque and the text under it cannot be read. So the clearance
    // is the status bar's height — and nothing at all when the bar is hidden,
    // which is the immersive state.
    val target = if (collapsed) 0.dp else statusBarHeight()
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
    /**
     * Whether the terminal is immersive: the system's own bars are hidden and
     * the row goes with them.
     *
     * The row's *position* follows the scroll — it sits at the top while the
     * chrome is up and drops below the status bar when it is not — but whether
     * it is there at all is the setting's business, together with the system's
     * bars: a row of pills floating over the output while the clock is still
     * on screen says there is something above the terminal, and there is not.
     */
    immersive: Boolean = false,
    /** Strip sampled from the terminal, or null when there is nothing to sample. */
    backdrop: ImageBitmap?,
    /** Where the terminal sits in root space, so a pill can find its slice. */
    terminalBounds: Rect?,
    viewModel: SessionSwitcherViewModel,
    onOpenSidebar: () -> Unit,
    onFindInOutput: () -> Unit,
    /** Scroll to the shell's previous prompt. */
    onPreviousPrompt: () -> Unit = {},
    /** Scroll to the shell's next prompt. */
    onNextPrompt: () -> Unit = {},
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * Opens the session grid.
     *
     * This slot used to open the agent screen. The agent is reachable from the
     * drawer's navigation instead, and the grid is the control the row is
     * missing: switching sessions is what the top of a terminal is for.
     */
    onOpenSessions: () -> Unit,
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
    // How many sessions the app knows about. The button says so on itself, so
    // the grid is one glance away and its size is known before it is opened.
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val sessionCount = sessions.size

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
    // Where the row is.
    //
    // Immersive — the user is reading the scrollback, or a full-screen program
    // owns the terminal — the row takes the status bar's place: the system bar
    // is gone in that state, and the row sits a few dp from the top edge.
    //
    // At the live edge the row floats: it is drawn over the terminal, below
    // the system status bar, and the terminal uses the whole screen.
    /**
     * The row's opacity.
     *
     * The immersive state is not a position: the row goes away. It fades out
     * with the same tween it travels on, so the two are one movement.
     */
    val rowAlpha by animateFloatAsState(
        targetValue = if (immersive) 0f else 1f,
        // The same timing as the row's travel, in the unit the animation wants.
        animationSpec = tween<Float>(
            durationMillis = CHROME_ANIMATION_MILLIS,
            delayMillis = if (immersive) 0 else CHROME_COLLAPSE_DELAY_MILLIS,
        ),
        label = "chromeRowAlpha",
    )

    val rowOffset by animateDpAsState(
        targetValue = if (chromeCollapsed) COLLAPSED_ROW_OFFSET else statusBarH + BAR_TOP_OFFSET,
        animationSpec = chromeTween,
        label = "chromeRowOffset",
    )

    val rowHeight = BAR_ROW_HEIGHT

    /** The icon's size, matching every lucide glyph in the row. */
    val pillIconSize = 22.dp

    /*
     * THE BUTTONS, HIDDEN
     *
     * The row can be taken out of the way: the overflow menu has an entry for
     * it, and the row's buttons go away until the screen is tapped again. What
     * is left is the terminal — which is what the row was drawn over in the
     * first place.
     */
    var buttonsHidden by remember { mutableStateOf(false) }

    Box(
        modifier = if (buttonsHidden) {
            // The whole screen, so a tap anywhere brings the row back. The bar
            // is an overlay: it is drawn over the terminal, and a tap on the
            // terminal is a tap the terminal never sees.
            modifier.fillMaxSize()
        } else {
            modifier
                .fillMaxWidth()
                .height(BAR_TOP_OFFSET + BAR_ROW_HEIGHT + BAR_BOTTOM_OFFSET)
        },
    ) {
        var moreExpanded by remember { mutableStateOf(false) }

        /*
         * THE BAND ABOVE THE ROW
         *
         * When the chrome is up — the viewport is in the scrollback, or a TUI
         * owns the terminal — the row sits below the system status bar, and
         * the band between them is Drosh's own background. Not the terminal's:
         * a terminal with a light theme would put a light band there, and the
         * status bar's clock would sit on it.
         *
         * When the chrome is collapsed the terminal reaches the top edge and
         * the band is not drawn at all — there is nothing between the status
         * bar and the output.
         */
        // No band above the row.
        //
        // The band was the app's own background between the system status bar
        // and the row, so the clock would not sit on the terminal's background.
        // But the terminal now fills the screen — the row floats over it — and
        // a band is a strip of nothing at the top of the output, which is the
        // one thing the terminal should not have.

        if (!buttonsHidden) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The row's whole travel. Collapsed that is 0 — flush with the
                // top of the screen, inside the band the status bar left. Expanded
                // it is the status bar's inset plus a gap, so the row sits below
                // the clock rather than under it.
                .offset(y = rowOffset)
                // And in the immersive state the row is *gone*, not moved: the
                // terminal is the screen, and a row of pills floating over the
                // output is the one thing that says "there is something above
                // this". It fades out with the same tween the row travels on.
                .alpha(rowAlpha)
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
                    contentDescription = stringResource(dev.drosh.ui.R.string.topbar_open_sessions),
                    height = rowHeight,
                    iconSize = pillIconSize,
                    onClick = onOpenSidebar,
                )

                // The same opaque surface and edge as the buttons.
                GlassSurface(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    shape = PILL_SHAPE,
                    modifier = Modifier.height(rowHeight),
                ) {
                    Text(
                        text = activeName ?: "Drosh",
                        color = PILL_CONTENT,
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


            // ── Right: one body, two discs ────────────────────────────────────
            // Same Row as the left group rather than a sibling Box aligned by
            // hand. Two separate parents let the clusters settle on different
            // baselines whenever their content differed in height, which is why
            // the left and right buttons looked vertically offset.
            //
            // One body, two buttons.
            //
            // The pair reads as a single control with two actions in it: the
            // surface carries the fill and the gradient edge, so the silhouette
            // is one shape rather than two that happen to touch. Inside it each
            // button is its own tap target with its own ripple, so a press is
            // never ambiguous about which of the two it meant.
            Box(
                modifier = Modifier
                    .height(rowHeight)
                    .clip(PILL_SHAPE)
                    .background(PILL_SURFACE)
                    .pillEdge(PILL_SHAPE),
            ) {
                Row(
                    modifier = Modifier.height(rowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    GlassPillButton(
                        backdrop = backdrop,
                        terminalBounds = terminalBounds,
                        contentDescription = stringResource(dev.drosh.ui.R.string.topbar_sessions),
                        width = rowHeight,
                        height = rowHeight,
                        iconSize = pillIconSize,
                        ownSurface = false,
                        content = {
                            // The sessions button is a soft-cornered square with
                            // the number of sessions in it.
                            //
                            // It used to be an eye with a count over it, and the
                            // eye was saying the same thing twice: a switcher
                            // with a number on it is a view of what is there.
                            // The square says "a set of things" and the number
                            // says how many — one sign, not two.
                            Box(
                                modifier = Modifier
                                    .size(pillIconSize)
                                    .clip(SESSION_BUTTON_SHAPE)
                                    .background(PILL_CONTENT.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (sessionCount > 0) sessionCount.toString() else "-",
                                    color = PILL_CONTENT,
                                    fontFamily = LocalFontSet.current.sans,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        },
                        onClick = onOpenSessions,
                    )

                    GlassPillButton(
                        backdrop = backdrop,
                        terminalBounds = terminalBounds,
                        icon = DroshIcons.EllipsisVertical,
                        contentDescription = stringResource(dev.drosh.ui.R.string.topbar_more_actions),
                        width = PILL_WIDTH,
                        height = rowHeight,
                        iconSize = pillIconSize,
                        ownSurface = false,
                        onClick = { moreExpanded = true },
                    )
                }
            }
        }
        }

        // A tap anywhere brings the row back. The bar is an overlay, so the
        // tap is taken while the buttons are away — which is exactly when it
        // is wanted — and given back to the terminal the moment they are.
        if (buttonsHidden) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { buttonsHidden = false },
            )
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
                sessionName = activeName,
                onFindInOutput = onFindInOutput,
                onPreviousPrompt = onPreviousPrompt,
                onNextPrompt = onNextPrompt,
                onRefresh = onRefresh,
                onToggleButtons = { buttonsHidden = !buttonsHidden },
            )
        }
    }
}

/**
 * The overflow menu.
 *
 * Two entries, and both are about the output rather than about the app's
 * arrangement: refresh it, or find in it. Everything that acts on a second
 * pane moved to the drawer, where the pane it acts on is on screen.
 *
 * The session's name is the menu's title: these actions act on that terminal, and
 * a menu that does not say which one is a menu the user has to remember the
 * context of.
 */
@Composable
private fun MoreActionsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sessionName: String?,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
    onToggleButtons: () -> Unit,
    onPreviousPrompt: () -> Unit,
    onNextPrompt: () -> Unit,
) {
    DroshDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        title = sessionName?.takeIf { it.isNotBlank() } ?: "Drosh",
        // The slim card: long and thin, so it covers as little of the terminal
        // as it can, with each entry pressing in a pill.
        density = DroshMenuDensity.Slim,
        // Further below the button than the default: the menu is a card, not a
        // tooltip hanging off the icon, and it should clear the row entirely
        // rather than crowd the button that opened it.
        offset = DpOffset(0.dp, 14.dp),
        items = buildList {
            // The split actions are gone from here. They live in the drawer,
            // beside the pair they act on — a control for a second pane that
            // sits in a menu for the first one is a control the user has to
            // reason their way to, and it was two screens' worth of entries
            // in one four-item list.
            add(DroshMenuItem(label = stringResource(dev.drosh.ui.R.string.topbar_refresh), icon = DroshIcons.RotateCw, id = "refresh"))
            add(DroshMenuItem(label = stringResource(dev.drosh.ui.R.string.topbar_find), icon = DroshIcons.Search, id = "find"))
            // The prompts: the shell reports them, and they are the only way
            // back to a command that scrolled out of the history.
            add(DroshMenuItem(label = stringResource(dev.drosh.ui.R.string.topbar_previous_prompt), icon = DroshIcons.ArrowUp, id = "prev_prompt"))
            add(DroshMenuItem(label = stringResource(dev.drosh.ui.R.string.topbar_next_prompt), icon = DroshIcons.ArrowDown, id = "next_prompt"))
            // The row's buttons, out of the way: the terminal is what the
            // user is looking at, and a tap brings them back.
            add(DroshMenuItem(label = stringResource(dev.drosh.ui.R.string.topbar_shrink_buttons), icon = DroshIcons.EyeOff, id = "shrink"))
            // Settings is gone from here. It is reachable from the drawer, and
            // an overflow entry that duplicates a drawer item gives two ways to
            // open the same screen.
        },
        onItemClick = { item ->
            onDismiss()
            when (item.id) {
                "refresh" -> onRefresh()
                "find" -> onFindInOutput()
                "prev_prompt" -> onPreviousPrompt()
                "next_prompt" -> onNextPrompt()
                "shrink" -> onToggleButtons()
            }
        },
    )
}

/**
 * The row's pill button.
 *
 * An opaque disc with a gradient edge. It used to be frosted glass — a blurred
 * slice of the terminal behind it — and the doc here claimed a press-scale that
 * was never implemented; the ripple is the whole of the press feedback.
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
     * The button's own content, when it is not a single icon.
     *
     * The sessions button draws an eye with a count in it, which is more than
     * an icon can say.
     */
    content: (@Composable () -> Unit)? = null,
    /**
     * False when this button sits inside a body that already carries the fill
     * and the edge.
     *
     * The two right-hand buttons are one body with two discs in it, so the fill
     * and the silhouette belong to the body: drawing them again per button would
     * stack two edges a hairline apart. The button keeps its own circular clip
     * and its own ripple either way — that is what makes a press land in one
     * circle rather than across the whole capsule.
     */
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
        ) { content(PILL_CONTENT) }
    }
}

/**
 * One pill: opaque fill, gradient edge, then content.
 *
 * Shared by the buttons and the session-name pill so they cannot drift apart.
 * Clip comes before the ripple on purpose -- a bounded ripple is a rectangle, and
 * it is the clip ahead of it that keeps it inside the shape.
 *
 * The fill is [PILL_SURFACE] rather than a blurred slice of the terminal: the
 * pills are opaque, so nothing behind them can change how they read. [backdrop]
 * and [terminalBounds] are still accepted because the row still samples the
 * terminal for its own backdrop, and a caller should not have to know which of
 * the row's pieces read it.
 *
 * [ownSurface] false: the fill and the edge belong to a parent body; only the
 * clip, the ripple and the content are drawn here.
 */
@Composable
private fun GlassSurface(
    @Suppress("UNUSED_PARAMETER") backdrop: ImageBitmap?,
    @Suppress("UNUSED_PARAMETER") terminalBounds: Rect?,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    ownSurface: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
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
