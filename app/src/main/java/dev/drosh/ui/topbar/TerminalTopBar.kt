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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import dev.drosh.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshError
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
 * Değişiklik notları (önceki versiyona göre):
 *  - Pill butonlar artık yarı şeffaf surface'lere sahip (önceden sadece
 *    border ile sınırlıydlar). Surface: DroshSurfaceHigh @ 65% alpha.
 *    Border kaldırıldı — terminal içeriği arkasından hafifçe görünüyor.
 *  - Boyutlar büyütüldü: buton 36/40dp → 44dp, ikon 18dp → 22dp (iOS ölçeği).
 *  - Session-name kutusu stadium (tam yuvarlak) pill'e çevrildi.
 *  - Basma anında hafif scale-down animasyonu (spring, bounce yok) —
 *    iOS tarzı dokunma geri bildirimi.
 *  - "Vibrancy" simülasyonu: gerçek backdrop blur DEĞİL (Compose'da bunun
 *    native karşılığı yok, bkz. sohbet notu). Bunun yerine yarı şeffaf
 *    surface + hafif highlight gradyanı ile "buzlu cam" hissi veriliyor.
 *    Gerçek blur için Haze kütüphanesi gerekir — ayrı bir adım.
 *  - MoreActionsDropdown: hardcoded offset kaldırıldı (anchor'a göre
 *    otomatik konumlanıyor), Divider → HorizontalDivider.
 *  - Icons now use DroshIcons ImageVector instead of painterResource XML drawables.
 *
 * Public API değişmedi: TerminalTopBar(...) imzası aynı.
 */
private const val BAR_ROW_HEIGHT_DP = 44
private const val BAR_TOP_OFFSET_DP = 10
private const val BAR_BOTTOM_OFFSET_DP = 6
/**
 * A pill: nothing but the blur of whatever is behind it.
 *
 * There is no fill and no border. An earlier version had a 72% surface and then
 * a 90% one over the top, both working around the fact that the blur was not
 * covering the button. Now that it does, a fill would only tint the smear, and
 * the ask was for the blur on its own.
 *
 * blurredEdgeTreatment is the whole fix for the square. Haze clips its captured
 * backdrop to `blurredEdgeTreatment.shape` and nothing else — `Modifier.clip`
 * is not consulted. The default is BlurredEdgeTreatment.Rectangle, which carries
 * RectangleShape, so the blur filled the button's bounding box while the tint
 * filled the rounded shape. In the four corners between the two, the terminal
 * showed through at full strength: that was the square, and that was why the
 * text was still readable at 90% opacity. Passing the pill's own shape clips
 * the blur to exactly the region the icon sits in.
 *
 * The blur samples [hazeState] rather than this node's own content, which is
 * the one thing Modifier.blur() cannot do: a RenderEffect blurs the layer it is
 * attached to and has no access to anything behind it, and behind a pill is a
 * TerminalView inside an AndroidView, so there is nothing to re-draw.
 *
 * Below API 31 Haze substitutes a translucent scrim for the blur — minSdk is
 * 26, so older devices get a flat panel and newer ones get the real effect.
 */
@Composable
private fun Modifier.pillGlass(
    hazeState: HazeState,
    shape: Shape,
    style: HazeStyle,
): Modifier = this
    .hazeEffect(state = hazeState, style = style) {
        blurredEdgeTreatment = BlurredEdgeTreatment(shape)
    }
    .clip(shape)

/** Wider than it is tall, so the ends read as a stadium and not a disc. */
private const val PILL_WIDTH_DP = 52

/**
 * 14dp at 12sp monospace leaves the output behind a button as a light and dark
 * smudge with no legible glyphs. Haze does not downscale by default
 * (HazeInputScale.Default is None, not Auto), so this is the radius that
 * actually gets applied rather than a third of it.
 */
private val PILL_BLUR_RADIUS = 14.dp

private val BAR_ROW_HEIGHT = BAR_ROW_HEIGHT_DP.dp
private val PILL_WIDTH = PILL_WIDTH_DP.dp
private val BAR_TOP_OFFSET = BAR_TOP_OFFSET_DP.dp
private val BAR_BOTTOM_OFFSET = BAR_BOTTOM_OFFSET_DP.dp

@Composable
fun TerminalTopBar(
    hazeState: HazeState,
    /** System status bar hidden outright. Follows the setting, not the scroll. */
    immersive: Boolean,
    /** Row has moved up into the band the status bar used to occupy. */
    rowInBand: Boolean,
    viewModel: SessionSwitcherViewModel,
    isFullscreen: Boolean,
    keyboardFocused: Boolean,
    onToggleKeyboard: () -> Unit,
    onOpenSidebar: () -> Unit,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeName by viewModel.activeName.collectAsStateWithLifecycle()

    val statusBarNow = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Captured once, deliberately. Hiding the system status bar drops this
    // inset to zero on Android 15+, so reading it per frame would collapse the
    // offset at the exact moment immersive mode turned on and the buttons
    // would never move. The first composition runs before the hide, so this is
    // the real height.
    val statusBarH = remember { statusBarNow }

    // Built once and shared by every pill. HazeStyle is immutable, so a fresh
    // instance each recomposition would only hand the effect a new object for
    // no reason.
    val hazeStyle = remember { HazeStyle.Unspecified.copy(blurRadius = PILL_BLUR_RADIUS) }

    // The system status bar is hidden whenever immersive mode is on, so when
    // the user scrolls back into the scrollback the row moves up into the band
    // it left rather than sitting under it. Nothing is drawn over the band —
    // the row simply goes there.
    val rowOffset by animateDpAsState(
        // The Box already pads by statusBarH + BAR_TOP_OFFSET, so the row's
        // origin is below the band. Reaching the band means undoing both, not
        // just the inset — which is why subtracting only statusBarH left the
        // buttons sitting 10dp down instead of moving at all.
        targetValue = if (rowInBand) -(statusBarH + BAR_TOP_OFFSET) else 0.dp,
        animationSpec = tween(durationMillis = 280),
        label = "immersiveRowOffset",
    )


    // The bar reserves a band of height and draws nothing in it. The buttons
    // sit a little below the status bar and a little above the first terminal
    // row, so the bar never lands on top of a line of output — in the HTML
    // prototype the terminal's first row starts under the band, and that gap is
    // what makes the opening line readable.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarH + BAR_TOP_OFFSET + BAR_ROW_HEIGHT + BAR_BOTTOM_OFFSET)
            .padding(top = statusBarH + BAR_TOP_OFFSET),
    ) {
        var moreExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = rowOffset)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // ── Left: pill icon button + session name pill ──────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassPillButton(
                    hazeState = hazeState,
                    hazeStyle = hazeStyle,
                    icon = DroshIcons.PanelLeft,
                    contentDescription = "Open sessions",
                    onClick = onOpenSidebar,
                )

                 Box(
                     modifier = Modifier
                         .clip(RoundedCornerShape(percent = 50))
                         .pillGlass(hazeState, RoundedCornerShape(percent = 50), hazeStyle)
                         .padding(horizontal = 16.dp, vertical = 10.dp),
                 ) {
                    Text(
                        text = activeName ?: "Drosh",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                    )
                }
            }
        }

        // ── Right: two pill buttons ────────────────────────────────────────
        Box(
            Modifier
                .wrapContentSize()
                .align(Alignment.CenterEnd)
                .padding(horizontal = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassPillButton(
                    hazeState = hazeState,
                    hazeStyle = hazeStyle,
                    drawableRes = R.drawable.ic_agent_mark,
                    contentDescription = "AI Agent",
                    // Same 22dp as every lucide glyph beside it. It was
                    // briefly set to 24dp on the theory that the mark needed
                    // extra room for its detail, but the real reason it looked
                    // small was the vector itself: the artwork fills only 46%
                    // of its 2048 viewport, so at any iconSize the visible
                    // mark was about half the size of its neighbours. The
                    // drawable now scales the artwork to fill the box, and at
                    // the same 22dp it matches the rest of the row.
                    iconSize = 22.dp,
                    onClick = { onOpenAgent() },
                )

                GlassPillButton(
                    hazeState = hazeState,
                    hazeStyle = hazeStyle,
                    icon = if (keyboardFocused) DroshIcons.KeyboardOff else DroshIcons.Keyboard,
                    contentDescription = if (keyboardFocused) "Hide keyboard" else "Show keyboard",
                    onClick = onToggleKeyboard,
                )

                GlassPillButton(
                    hazeState = hazeState,
                    hazeStyle = hazeStyle,
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "More actions",
                    onClick = { moreExpanded = true },
                )
            }

            MoreActionsDropdown(
                expanded = moreExpanded,
                onDismiss = { moreExpanded = false },
                isFullscreen = isFullscreen,
                onFindInOutput = { onFindInOutput(); moreExpanded = false },
                onRefresh = { onRefresh(); moreExpanded = false },
                onToggleFullscreen = { onToggleFullscreen(); moreExpanded = false },
            )
        }
    }
}

@Composable
private fun MoreActionsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isFullscreen: Boolean,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    DroshDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        items = listOf(
            DroshMenuItem(
                label = "Refresh terminal",
                icon = DroshIcons.RotateCw,
            ),
            DroshMenuItem(
                label = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                icon = if (isFullscreen) DroshIcons.Minimize else DroshIcons.Maximize,
            ),
            DroshMenuItem(
                label = "Find in output",
                icon = DroshIcons.Search,
            ),
            // Settings is gone from here. It is reachable from the drawer, and
            // an overflow entry that duplicates a drawer item gives two ways to
            // open the same screen.
        ),
        onItemClick = { item ->
            when (item.label) {
                "Refresh terminal" -> onRefresh()
                "Exit fullscreen", "Enter fullscreen" -> onToggleFullscreen()
                "Find in output" -> onFindInOutput()
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
    hazeState: HazeState,
    drawableRes: Int? = null,
    icon: ImageVector? = null,
    contentDescription: String,
    onClick: () -> Unit,
    width: Dp = PILL_WIDTH,
    height: Dp = BAR_ROW_HEIGHT,
    iconSize: Dp = 22.dp,
    hazeStyle: HazeStyle = remember { HazeStyle.Unspecified.copy(blurRadius = PILL_BLUR_RADIUS) },
) {
    GlassPillBody(contentDescription, onClick, width, height, iconSize, hazeState, hazeStyle) { tint ->
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
    hazeState: HazeState? = null,
    hazeStyle: HazeStyle? = null,
    content: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
) {
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
            .then(
                if (hazeState != null && hazeStyle != null) {
                    Modifier.pillGlass(hazeState, RoundedCornerShape(percent = 50), hazeStyle)
                } else {
                    Modifier.clip(RoundedCornerShape(percent = 50))
                }
            )
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
