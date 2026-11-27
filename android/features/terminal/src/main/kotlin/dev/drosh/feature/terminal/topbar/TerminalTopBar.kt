package dev.drosh.feature.terminal.topbar

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.res.painterResource
import dev.drosh.feature.terminal.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    // The grid's clearance follows the row.
    //
    // Collapsed — the viewport is up in the scrollback, or a TUI owns the
    // terminal — the row has taken the status bar's place at the top, and the
    // grid pads itself to match so the first lines of the output are not under
    // it. Expanded the row sits below the system's status bar, and the grid
    // starts below that.
    val target = if (collapsed) CHROME_CLEARANCE else statusBarHeight()
    return animateDpAsState(
        targetValue = target,
        animationSpec = tween<Dp>(
            durationMillis = CHROME_ANIMATION_MILLIS,
            delayMillis = if (collapsed) CHROME_COLLAPSE_DELAY_MILLIS else 0,
        ),
        label = "terminalTopPadding",
    ).value
}

internal val BAR_ROW_HEIGHT = BAR_ROW_HEIGHT_DP.dp
internal val PILL_WIDTH = PILL_WIDTH_DP.dp

/**
 * The session switcher's shape and width band.
 *
 * A soft-cornered rectangle rather than a capsule: the control holds a *name*,
 * and a name is not a button glyph. The band keeps a short name from looking
 * like a dot and a long one from taking the row from the overflow menu.
 */
private val SESSION_SWITCHER_SHAPE = RoundedCornerShape(14.dp)
private val SESSION_SWITCHER_MIN_WIDTH = 96.dp
private val SESSION_SWITCHER_MAX_WIDTH = 210.dp
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
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    // The switcher's list: the sessions that are actually open, newest first.
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val openSessions = remember(sessions) {
        sessions
            .filter { it.state != dev.drosh.domain.session.SessionState.Closed }
            .sortedByDescending { it.lastUsedAtMs }
    }
    var sessionMenuExpanded by remember { mutableStateOf(false) }

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
                .height(rowHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // ── Left: sessions button ──────────────────────────────────────
            // Pinned to [rowHeight] so it cannot settle a different height from
            // the pair on the other side. Two clusters in one Row share a centre
            // only for as long as they are the same height; this makes it true by
            // construction rather than by what happens to be inside them.
            //
            // The name is gone from the row. It was a second switcher beside a
            // button that already opens the grid, and two doors to the same
            // list made the row read as three controls where two are enough.
            // The name still titles the overflow menu, so the row does say
            // which terminal is on screen.
            // ── Left: the drawer, then the session switcher ────────────────
            //
            // The switcher is the active session's name with a chevron, not a
            // count in a square: the row should say *which* terminal is on
            // screen, and the list behind the name is the same list the square
            // opened. A count that has to be decoded is not a name.
            Row(
                modifier = Modifier.height(rowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = DroshIcons.PanelLeft,
                    contentDescription = stringResource(R.string.topbar_open_sessions),
                    height = rowHeight,
                    iconSize = pillIconSize,
                    onClick = onOpenSidebar,
                )

                Box {
                    GlassSurface(
                        backdrop = backdrop,
                        terminalBounds = terminalBounds,
                        shape = SESSION_SWITCHER_SHAPE,
                        modifier = Modifier
                            .height(rowHeight)
                            .widthIn(min = SESSION_SWITCHER_MIN_WIDTH, max = SESSION_SWITCHER_MAX_WIDTH),
                        onClick = { sessionMenuExpanded = true },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = activeName ?: stringResource(R.string.topbar_sessions),
                                color = PILL_CONTENT,
                                fontFamily = LocalFontSet.current.sans,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                imageVector = DroshIcons.ChevronDown,
                                contentDescription = null,
                                tint = PILL_CONTENT.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    DroshDropdownMenu(
                        expanded = sessionMenuExpanded,
                        onDismissRequest = { sessionMenuExpanded = false },
                        title = activeName.orEmpty(),
                        offset = DpOffset(0.dp, 14.dp),
                        density = DroshMenuDensity.Slim,
                        items = buildList {
                            openSessions.forEach { session ->
                                add(
                                    DroshMenuItem(
                                        id = "session:${session.id}",
                                        label = if (session.id == activeId) {
                                            "✓  ${session.name}"
                                        } else {
                                            session.name
                                        },
                                        icon = DroshIcons.SquareTerminal,
                                    ),
                                )
                            }
                            add(
                                DroshMenuItem(
                                    id = MENU_ALL_SESSIONS,
                                    label = stringResource(R.string.topbar_all_sessions),
                                    icon = DroshIcons.SessionGrid,
                                    dividerBefore = openSessions.isNotEmpty(),
                                ),
                            )
                        },
                        onItemClick = { item ->
                            sessionMenuExpanded = false
                            when {
                                item.id == MENU_ALL_SESSIONS -> onOpenSessions()
                                item.id.startsWith("session:") ->
                                    viewModel.activate(item.id.removePrefix("session:"))
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Right: the overflow circle only ────────────────────────────
            // The session square that used to sit here is gone: two doors to
            // one list, side by side, was the row repeating itself.
            Row(
                modifier = Modifier.height(rowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = stringResource(R.string.topbar_more_actions),
                    width = PILL_WIDTH,
                    height = rowHeight,
                    iconSize = pillIconSize,
                    onClick = { moreExpanded = true },
                )
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
 * context of. An empty name means no title — the same rule the session card's
 * menu uses. This deliberately asks for the default compact card: the menu must
 * have the same corners, item shapes, colours and ripple as the card menu, and
 * only DroshDropdownMenu decides what those are.
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
        title = sessionName.orEmpty(),
        // No density or colour overrides: the session card's menu and this one
        // are the same card, so they ship the same shape and press feedback.
        // Further below the button than the default: the menu is a card, not a
        // tooltip hanging off the icon, and it should clear the row entirely
        // rather than crowd the button that opened it.
        offset = DpOffset(0.dp, 14.dp),
        // The slim card: the row's menu floats over output the user is reading,
        // so it takes as little of it as it can while a finger still gets a
        // full-height target. It is also the density the row's button was
        // designed for — the compact card sat there as a wide block of surface.
        density = DroshMenuDensity.Slim,
        items = buildList {
            // Three sections, in the order the actions are reached for.
            //
            // ── Finding what is on screen ─────────────────────────────────
            // The split actions are gone from here. They live in the drawer,
            // beside the pair they act on — a control for a second pane that
            // sits in a menu for the first one is a control the user has to
            // reason their way to, and it was two screens' worth of entries
            // in one four-item list.
            add(DroshMenuItem(label = stringResource(R.string.topbar_find), icon = DroshIcons.Search, id = MENU_FIND))
            add(DroshMenuItem(label = stringResource(R.string.topbar_refresh), icon = DroshIcons.RotateCw, id = MENU_REFRESH))
            // ── Moving through the history ────────────────────────────────
            // The prompts: the shell reports them, and they are the only way
            // back to a command that scrolled out of the history. A rule
            // above them keeps "jump to a prompt" distinct from "look at
            // what is here", which are different jobs on the same menu.
            add(DroshMenuItem(label = stringResource(R.string.topbar_previous_prompt), icon = DroshIcons.ArrowUp, id = MENU_PREVIOUS_PROMPT, dividerBefore = true))
            add(DroshMenuItem(label = stringResource(R.string.topbar_next_prompt), icon = DroshIcons.ArrowDown, id = MENU_NEXT_PROMPT))
            // ── The row itself ────────────────────────────────────────────
            // The row's buttons, out of the way: the terminal is what the
            // user is looking at, and a tap brings them back. The rule above
            // it separates the one control that acts on the chrome from the
            // ones that act on the output.
            add(DroshMenuItem(label = stringResource(R.string.topbar_shrink_buttons), icon = DroshIcons.EyeOff, id = MENU_SHRINK, dividerBefore = true))
            // Settings is gone from here. It is reachable from the drawer, and
            // an overflow entry that duplicates a drawer item gives two ways to
            // open the same screen.
        },
        onItemClick = { item ->
            onDismiss()
            when (item.id) {
                MENU_REFRESH -> onRefresh()
                MENU_FIND -> onFindInOutput()
                MENU_PREVIOUS_PROMPT -> onPreviousPrompt()
                MENU_NEXT_PROMPT -> onNextPrompt()
                MENU_SHRINK -> onToggleButtons()
            }
        },
    )
}

/** Stable routing ids, matching the card menu's habit of never dispatching on a label. */
private const val MENU_REFRESH = "refresh"
private const val MENU_FIND = "find"
private const val MENU_PREVIOUS_PROMPT = "prev_prompt"
private const val MENU_NEXT_PROMPT = "next_prompt"
private const val MENU_SHRINK = "shrink"
private const val MENU_ALL_SESSIONS = "all_sessions"
