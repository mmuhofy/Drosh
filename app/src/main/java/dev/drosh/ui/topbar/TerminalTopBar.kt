package dev.drosh.ui.topbar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.ui.res.painterResource
import dev.drosh.R
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshError
import kotlin.math.roundToInt
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.session.SessionSwitcherViewModel

/**
 * Modern minimalist top bar — iOS/Obsidian-style floating pills.
 *
 * The bar is driven by a single piece of state, [chromeCollapsed], and that state
 * is the prototype's:
 *
 *  - **Collapsed** — a session nobody has scrolled yet, the viewport is up in
 *    the scrollback, or a TUI has the terminal. The system status bar is hidden
 *    outright and the pills ride up into the space it left rather than sitting
 *    below it. The strip behind them is Drosh's own background — the same colour
 *    behind the terminal frame and the sidebar — so the chrome reads as the app's
 *    rather than as a bar laid over the terminal.
 *  - **Expanded** — the viewport is at the live edge. The system status bar is
 *    shown with no background of its own, so the terminal runs on behind the
 *    clock, and the pills drop below the strip to float over the output.
 *
 * The pills never disappear. Obsidian's chrome lifts away entirely and leaves a
 * dim behind it; here the pills are how you get back to search, sessions and the
 * agent, so they move rather than go. Only the status bar closes.
 *
 * Both the scrim and the row are positioned off
 * [WindowInsets.statusBarsIgnoringVisibility] rather than [WindowInsets.statusBars].
 * The bar's visibility now changes on every scroll, and `statusBars` is zero the
 * moment it hides — so measuring layout with it would collapse the whole top of
 * the screen the instant the state flipped.
 *
 * Nothing here reserves space. The terminal is full-bleed and these are overlays,
 * which is what keeps the grid from resizing — and every line of output reflowing
 * — on each crossing of the boundary.
 */
private const val BAR_ROW_HEIGHT_DP = 44
private const val BAR_TOP_OFFSET_DP = 10
private const val BAR_BOTTOM_OFFSET_DP = 6

/**
 * The floor for the collapsed row's height.
 *
 * The collapsed row is the status bar's band, so on a device with a very short
 * bar the icons would be sized to a strip that is too short to hit. This is the
 * bottom of what a finger can be expected to land on, and it is only ever
 * *above* the bar's own height, never below it — the strip is the band, not
 * something near it.
 */
private val MIN_COLLAPSED_ROW = 30.dp

/**
 * How long the row and the strip take to travel.
 *
 * Just under the platform's own status-bar transition. Ours finishing first
 * means the last thing to settle is the system's own bar, rather than two
 * motions ending on top of each other.
 */
private const val CHROME_ANIMATION_MILLIS = 220

/**
 * How long the pills wait before starting to move.
 *
 * Only when collapsing, and only because the clock has to leave first. The
 * status bar is the system window's, drawn above the app, so there is no way to
 * cover it — it has to be told to hide, and that takes its own time. Moving the
 * pills while the clock is still fading out puts two things in motion at once in
 * the same 40dp, which is what read as the bar going back and forth.
 *
 * Expanding does not wait: the pills are already where the clock is going, and
 * delaying them would just make the chrome feel like it had stalled.
 */
private const val CHROME_COLLAPSE_DELAY_MILLIS = 70

/**
 * Over the blurred slice, so the terminal shows through as a smudge rather
 * than as glyphs. This is the prototype's 0.72.
 */
private const val PILL_SURFACE_ALPHA = 0.72f

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

/** Wider than it is tall, so the ends read as a stadium and not a disc. */
private const val PILL_WIDTH_DP = 52

/**
 * 14dp at 12sp monospace leaves the output behind a button as a light and dark
 * smudge with no legible glyphs. Applied through Modifier.blur, so this is a
 * real RenderEffect on a real layer, and below API 31 the caller falls back to
 * a plain surface.
 */
private val PILL_BLUR_RADIUS = 14.dp

private val BAR_ROW_HEIGHT = BAR_ROW_HEIGHT_DP.dp
private val PILL_WIDTH = PILL_WIDTH_DP.dp
private val BAR_TOP_OFFSET = BAR_TOP_OFFSET_DP.dp
private val BAR_BOTTOM_OFFSET = BAR_BOTTOM_OFFSET_DP.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalTopBar(
    /**
     * The viewport is up in the scrollback, or a TUI owns the terminal.
     *
     * One flag for both halves of the state on purpose. "Hide the status bar" and
     * "tuck the pills into the space it left" are the same decision, and driving
     * them from two booleans is how they end up disagreeing for a frame on every
     * crossing.
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

    // Deliberately the visibility-agnostic inset. `statusBars` reports zero
    // while the bar is hidden, and this bar's state changes on scroll, so
    // reading it here would collapse the strip's height and fling the row upward
    // at the exact moment the row is supposed to move upward on purpose.
    val statusBarH = WindowInsets.statusBarsIgnoringVisibility
        .asPaddingValues()
        .calculateTopPadding()

    // One animated height for the row, and one animated offset, and one animated
    // strip — all from the same trigger and the same spec, so they cannot be
    // caught disagreeing on a frame.
    val chromeTween = tween<Dp>(
        durationMillis = CHROME_ANIMATION_MILLIS,
        delayMillis = if (chromeCollapsed) CHROME_COLLAPSE_DELAY_MILLIS else 0,
    )

    // Collapsed, the row is the status bar's band and nothing else.
    //
    // The prototype's `.status-bar { height: 34px }` and
    // `.pill-row.top-state { top: 4px; height: 34px }` are the same height: the
    // pills *are* the band, filling the space the clock left rather than sitting
    // under a strip of their own. Sized to the row instead — a fixed 44dp with a
    // strip tall enough to hold it — what you get is a row that hangs below the
    // band, and the band plus the overhang reads as two things rather than one.
    //
    // Floored at MIN_COLLAPSED_ROW so a short status bar cannot leave the icons
    // below a usable touch target.
    val collapsedRowHeight = statusBarH.coerceAtLeast(MIN_COLLAPSED_ROW)

    val rowHeight by animateDpAsState(
        targetValue = if (chromeCollapsed) collapsedRowHeight else BAR_ROW_HEIGHT,
        animationSpec = chromeTween,
        label = "chromeRowHeight",
    )

    val rowOffset by animateDpAsState(
        // Expanded the row sits clear of the band; collapsed it is flush with the
        // top of it, which is the whole difference the prototype draws.
        targetValue = if (chromeCollapsed) 0.dp else statusBarH + BAR_TOP_OFFSET,
        animationSpec = chromeTween,
        label = "chromeRowOffset",
    )

    /**
     * The icon, sized to the row it is in.
     *
     * A 22dp glyph inside a 30dp band leaves no breathing room and reads as
     * cropped, so it follows the row with a little air on each side. Clamped to
     * the row's own height so it can never be the thing that overflows.
     */
    val pillIconSize = (rowHeight - 12.dp).coerceIn(16.dp, 22.dp)

    // Exactly the band, and nothing below it. A strip taller than this is the
    // separate band the pills are supposed to have replaced.
    val stripHeight by animateDpAsState(
        targetValue = if (chromeCollapsed) collapsedRowHeight else 0.dp,
        animationSpec = chromeTween,
        label = "collapsedStripHeight",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarH + BAR_TOP_OFFSET + BAR_ROW_HEIGHT + BAR_BOTTOM_OFFSET),
    ) {
        // Drosh's background, not the terminal's, and not a scrim over it.
        //
        // The terminal background is a user setting and defaults to true black,
        // so either of those put a *terminal-coloured* or *nearly-black* surface
        // at the top of the screen that belongs to nothing: it read as a foreign
        // bar rather than as this app holding its own chrome. The strip is the
        // app's own colour, the same one behind the terminal frame and the
        // sidebar, so the pills sit in Drosh rather than on top of it.
        //
        // A sibling of the row rather than the row's background, because the row
        // moves inside the band when the state changes and a background set on
        // the row would travel with it, leaving the strip empty behind it.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(stripHeight)
                .background(DroshBackground),
        )

        var moreExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Offset first, then pad. The offset moves the whole row, and the
                // padding positions the row inside it, so the pair reads as one
                // target position rather than as two offsets that have to be kept
                // in step by hand.
                .offset(y = rowOffset)
                .height(rowHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // ── Left: sessions button + session name pill ───────────────────
            Row(
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
                         .background(DroshSurfaceHigh.copy(alpha = PILL_SURFACE_ALPHA)),
                     contentAlignment = Alignment.Center,
                 ) {
                    Text(
                        text = activeName ?: "Drosh",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                 }
            }

            Spacer(Modifier.weight(1f))


            // ── Right: pill buttons ────────────────────────────────────────
            // Same Row as the left group rather than a sibling Box aligned by
            // hand. Two separate parents let the clusters settle on different
            // baselines whenever their content differed in height, which is why
            // the left and right buttons looked vertically offset.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The agent button, where the keyboard toggle used to be.
                //
                // It was on the left, first in the row, which made it the first thing
                // under the thumb on a right-handed grip and put the least used
                // control in the most reachable slot. On the right it sits with the
                // other secondary actions, and the left cluster is left for what the
                // screen is actually about: the sessions and which one is open.
                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    drawableRes = dev.drosh.ui.R.drawable.ic_agent_head,
                    contentDescription = "Agent",
                    height = rowHeight,
                    // The mark is drawn on a 24 viewport that it fills, so it needs
                    // no correcting — unlike the old 2048 mark, which sat at 46% of
                    // its own canvas and looked half the size of its neighbours.
                    // It does follow the row, though: 22dp in a 30dp band reads as
                    // cropped.
                    iconSize = pillIconSize,
                    onClick = { onOpenAgent() },
                )

                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "More actions",
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
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pillButtonScale",
    )

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .onGloballyPositioned { slice.pillBounds = it.boundsInRoot() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        onClick()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Blurred terminal first, then the tint over it, then the icon. The
        // order is the prototype's: the tint sits on top of the backdrop, so
        // putting it on the Box as a background would hide the blur entirely.
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
                .background(DroshSurfaceHigh.copy(alpha = PILL_SURFACE_ALPHA)),
        )
        Box(
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) { content(DroshText) }
    }
}

