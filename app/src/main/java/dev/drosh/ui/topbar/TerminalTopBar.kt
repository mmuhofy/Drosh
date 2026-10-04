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
 *    Gerçek blur TerminalBackdrop.kt'den gelir.
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

@Composable
fun TerminalTopBar(
    /** System status bar hidden outright. Follows the setting, not the scroll. */
    immersive: Boolean,
    /** Row has moved up into the band the status bar used to occupy. */
    rowInBand: Boolean,
    /** Strip sampled from the terminal, or null when there is nothing to sample. */
    backdrop: ImageBitmap?,
    /** Where the terminal sits in root space, so a pill can find its slice. */
    terminalBounds: Rect?,
    viewModel: SessionSwitcherViewModel,
    keyboardFocused: Boolean,
    onToggleKeyboard: () -> Unit,
    onOpenSidebar: () -> Unit,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
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
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    drawableRes = dev.drosh.ui.R.drawable.ic_agent_head,
                    contentDescription = "Agent",
                    // 22dp, matching every lucide glyph in this row. The mark is
                    // drawn on a 24 viewport that it fills, so it needs no
                    // correcting — unlike the old 2048 mark, which sat at 46% of its
                    // own canvas and looked half the size of its neighbours.
                    iconSize = 22.dp,
                    onClick = { onOpenAgent() },
                )

                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = DroshIcons.PanelLeft,
                    contentDescription = "Open sessions",
                    onClick = onOpenSidebar,
                )

                 val nameShape = RoundedCornerShape(percent = 50)
                 Box(
                     modifier = Modifier
                         .height(BAR_ROW_HEIGHT)
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
                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = if (keyboardFocused) DroshIcons.KeyboardOff else DroshIcons.Keyboard,
                    contentDescription = if (keyboardFocused) "Hide keyboard" else "Show keyboard",
                    onClick = onToggleKeyboard,
                )

                GlassPillButton(
                    backdrop = backdrop,
                    terminalBounds = terminalBounds,
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "More actions",
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
                onFindInOutput = { onFindInOutput(); moreExpanded = false },
                onRefresh = { onRefresh(); moreExpanded = false },
            )
        }
    }
}

@Composable
private fun MoreActionsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
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

