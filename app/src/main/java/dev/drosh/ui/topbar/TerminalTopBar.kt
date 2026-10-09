package dev.drosh.ui.topbar

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.session.SessionSwitcherViewModel

/**
 * The terminal's floating pill row.
 *
 * ## There is no band
 *
 * Nothing is painted behind this row. No scrim, no gradient, no dimming, no
 * separate surface of its own — and no strip reserved for it either. The system
 * status bar is genuinely hidden, so what is behind the pills is the terminal's
 * own background, and the row has simply moved into the space the bar vacated.
 * This is displacement, not layering.
 *
 * Three earlier attempts each failed the same way: by treating the top of the
 * terminal as something that needed painting. A fill in the app's colour read as
 * a separate surface with an edge; a near-black fill read as a black bar; and
 * shrinking the row to fit the status bar's height read as a squashed control
 * caught mid-transition. The colour above the grid is the grid's own colour, so
 * there is no join to see.
 *
 * ## Two positions, one row
 *
 *  - **Collapsed** — at the prompt, on a session that has just opened, or with a
 *    TUI in control. The system bars are hidden and the row sits flush with the
 *    top of the screen, which *is* the space the status bar left.
 *  - **Expanded** — anywhere in the scrollback. The status bar is back and the row
 *    sits just below it.
 *
 * The row is [PILL_ROW_HEIGHT] in both states. Only its offset moves: the row
 * that shrank to the status bar's height while collapsed is what read as
 * squashed, and fitting it inside a band was never what that bought.
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

/** The row's height, in both states. */
val PILL_ROW_HEIGHT = 44.dp

/**
 * How far below the status bar the row sits once the status bar is back.
 *
 * Not zero. A row flush against the system bar has the clock sitting on its
 * shadow, and the shadow is the thing that separates a floating control from
 * the wallpaper behind it.
 */
private val PILL_TOP_GAP = 4.dp

/**
 * The constant gap between the pills and the first line of output.
 *
 * One number, read by the row and by the terminal's padding alike, because two
 * constants that have to be kept in step by hand are two that will not be. It
 * does not change with the chrome state: what changes is how much is above it,
 * and that is the row's offset, not the clearance.
 */
val CHROME_CLEARANCE = 4.dp

/** Wider than it is tall, so the ends read as a stadium and not a disc. */
private val PILL_WIDTH = 52.dp

/** The icon's size, matching every lucide glyph in the row. */
private val PILL_ICON_SIZE = 22.dp

// ── The glass recipe ─────────────────────────────────────────────────────────
//
// The prototype's `.pill`, term for term. Nothing here is opaque: the terminal's
// own background has to be visible through the pill, or it is a button rather
// than a control floating over a terminal.

/** `rgba(255,255,255,0.08)`. */
private val PILL_FILL = Color.White.copy(alpha = 0.08f)

/** `border: 1px solid rgba(255,255,255,0.14)`. */
private val PILL_BORDER = Color.White.copy(alpha = 0.14f)

/** The top half of `::before`: `rgba(255,255,255,0.14)` fading to nothing. */
private val PILL_SPECULAR = Color.White.copy(alpha = 0.14f)

/** `0 8px 20px rgba(0,0,0,0.35)`. */
private val PILL_SHADOW_SPOT = Color.Black.copy(alpha = 0.35f)

/**
 * One animation for the row's offset and the terminal's padding.
 *
 * They are two halves of one movement — the pills move down, the grid moves down
 * to keep clear of them — and two tweens that merely happen to share a duration
 * will drift apart the moment either is restarted.
 */
private fun chromeTween(collapsed: Boolean): AnimationSpec<Dp> = tween(
    durationMillis = CHROME_ANIMATION_MILLIS,
    delayMillis = if (collapsed) CHROME_COLLAPSE_DELAY_MILLIS else 0,
)

/**
 * The system status bar's height, whether or not it is currently showing.
 *
 * Deliberately the visibility-agnostic inset. `statusBars` is zero the moment
 * the bar hides, and this row's state changes on scroll, so reading that one
 * here would fling the row downward exactly as it is meant to be moving up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun statusBarHeight(): Dp =
    WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()

/**
 * Where the top edge of the pill row sits.
 *
 * Collapsed it is zero — flush with the top of the screen, inside the band the
 * status bar vacated. Expanded it is below the status bar, which is the whole
 * reason the bar came back.
 */
@Composable
private fun rememberPillRowOffset(collapsed: Boolean): Dp {
    val target = if (collapsed) 0.dp else statusBarHeight() + PILL_TOP_GAP
    return animateDpAsState(
        targetValue = target,
        animationSpec = chromeTween(collapsed),
        label = "pillRowOffset",
    ).value
}

/**
 * The terminal's top padding, in step with the row.
 *
 * It moves with the chrome because it has to: the pills sit in the space it
 * leaves, so a fixed padding either wastes a status bar's height at the prompt
 * or lets the first line of output go under the row in the scrollback. The
 * prototype had it fixed at 46px and that is what put the buttons on top of the
 * first line the moment the status bar came back.
 *
 * Collapsed it is the status bar's own height, which is also what the vacated
 * band used to measure — so the grid lands in the same place whether the status
 * bar is being shown or hidden, and the pills move *relative to the grid* rather
 * than the grid jumping to meet them.
 *
 * Expanded it clears the status bar, the row's own offset and the row itself.
 */
@Composable
fun rememberTerminalTopPadding(collapsed: Boolean): Dp {
    val statusBarH = statusBarHeight()
    val target = if (collapsed) {
        // At least the row's own height, on a device whose status bar is shorter
        // than the buttons that are replacing it.
        maxOf(statusBarH, PILL_ROW_HEIGHT + CHROME_CLEARANCE)
    } else {
        statusBarH + PILL_TOP_GAP + PILL_ROW_HEIGHT + CHROME_CLEARANCE
    }
    return animateDpAsState(
        targetValue = target,
        animationSpec = chromeTween(collapsed),
        label = "terminalTopPadding",
    ).value
}

@Composable
fun TerminalTopBar(
    /**
     * The system bars are hidden and the row has moved up into the space they
     * left. See the file comment for what that means and when.
     */
    chromeCollapsed: Boolean,
    viewModel: SessionSwitcherViewModel,
    onOpenSidebar: () -> Unit,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
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
    val rowOffset = rememberPillRowOffset(chromeCollapsed)
    var moreExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Tall enough to hold the row in its expanded position, and no
            // taller: this is an overlay and must not clip its own menu.
            .height(statusBarHeight() + PILL_TOP_GAP + PILL_ROW_HEIGHT + CHROME_CLEARANCE),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The row's whole travel, and nothing else.
                .offset(y = rowOffset)
                .height(PILL_ROW_HEIGHT)
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
                    icon = DroshIcons.PanelLeft,
                    contentDescription = "Open sessions",
                    width = PILL_WIDTH,
                    onClick = onOpenSidebar,
                )

                Box(
                    modifier = Modifier
                        .height(PILL_ROW_HEIGHT)
                        .glassSurface(),
                    contentAlignment = Alignment.Center,
                ) {
                    GlassSpecularHighlight()
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
                // It was on the left, first in the row, which made it the first
                // thing under the thumb on a right-handed grip and put the least
                // used control in the most reachable slot. On the right it sits
                // with the other secondary actions, and the left cluster is left
                // for what the screen is actually about: the sessions and which
                // one is open.
                GlassPillButton(
                    drawableRes = dev.drosh.ui.R.drawable.ic_agent_head,
                    contentDescription = "Agent",
                    width = PILL_WIDTH,
                    onClick = { onOpenAgent() },
                )

                GlassPillButton(
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "More actions",
                    width = PILL_WIDTH,
                    onClick = { moreExpanded = true },
                )
            }
        }

        // Material's DropdownMenu anchors to the position of the composable it is
        // called on. A zero-width box aligned to the end puts the anchor under
        // the overflow button on the right without the menu inheriting a width
        // from its parent.
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
 * A pill's surface, drawn in the prototype's order: shadow, translucent fill,
 * hairline border.
 *
 * `clip = false` on the shadow is deliberate — clipping it would crop the shadow
 * to the pill, which is the same as having no shadow. The ambient term is
 * dropped rather than dimmed, because Android's ambient shadow is a grey haze
 * that reads as dirt on a surface this dark.
 */
private fun Modifier.glassSurface(shape: Shape = CircleShape): Modifier = this
    .shadow(
        elevation = 8.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Transparent,
        spotColor = PILL_SHADOW_SPOT,
    )
    .clip(shape)
    .background(PILL_FILL)
    .border(1.dp, PILL_BORDER, shape)

/**
 * The prototype's `.pill::before`.
 *
 * A highlight over the top half, fading downward — the reflection a curved
 * surface catches from a light above it. Drawn before the icon so the glyph
 * sits on top of its own highlight, which is what `z-index: 1` did in the CSS.
 *
 * A [BoxScope] extension, because the pill's `contentAlignment` is `Center` for
 * the icon and the highlight has to opt out of it. Without that it would land in
 * the middle of the pill, which is where its absence would be least noticed and
 * most wrong.
 */
@Composable
private fun BoxScope.GlassSpecularHighlight() {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .background(
                Brush.verticalGradient(listOf(PILL_SPECULAR, Color.Transparent)),
            ),
    )
}

@Composable
private fun GlassPillButton(
    drawableRes: Int? = null,
    icon: ImageVector? = null,
    contentDescription: String,
    width: Dp = PILL_WIDTH,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    // Spring, no bounce: this is a button on a terminal, and an elastic return
    // reads as the control disagreeing with the tap.
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
            .height(PILL_ROW_HEIGHT)
            .glassSurface()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
        GlassSpecularHighlight()
        Box(modifier = Modifier.size(PILL_ICON_SIZE), contentAlignment = Alignment.Center) {
            when {
                drawableRes != null -> Icon(
                    painter = painterResource(drawableRes),
                    contentDescription = null,
                    tint = DroshText,
                    modifier = Modifier.fillMaxSize(),
                )

                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = DroshText,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}