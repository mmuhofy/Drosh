package dev.drosh.ui.agent

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshTile
import dev.drosh.design.system.DroshTileSelected
import dev.drosh.design.system.DroshTilePressed
import dev.drosh.design.system.DroshTrack
import dev.drosh.design.system.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import kotlin.math.roundToInt

/**
 * One bottom sheet for the whole agent.
 *
 * ## Why this exists
 *
 * There were four sheets and four shapes. The attach sheet had a 26dp corner and a
 * DroshSurfaceHigh body over DroshBackground; the tool detail sheet had the same
 * corner on the same surface but a rule and a footer the others did not have; the
 * search sheet had its own header; and the terminal history sheet animated a scrim
 * nothing else animated. Four sheets on one screen is four apps.
 *
 * The model picker is the reference — it is the sheet the app already agreed on:
 * a 28dp corner (Material's own expanded-sheet radius), a 38x4 grabber on the
 * track colour, a 17sp bold title 22dp in from the edge, and a drag that follows
 * the finger before it leaves. Every sheet in the agent now speaks that language,
 * and there is one place where the language is written down.
 *
 * ## Why it drags
 *
 * A grabber drawn over a sheet that cannot be dragged is a lie, and it is the one
 * affordance that says "this moves". The gesture lives on the whole panel: a drag
 * that starts on a scroller scrolls the scroller — a child gets the pointer first —
 * so the sheet is pulled down from its title row and its grabber. That is the right
 * way round for a panel whose content is a long list.
 */

/** The sheet's own corner radius: Material's expanded bottom-sheet radius. */
val SheetCorner: Dp = 28.dp

/** How far the title, the grabber and the body sit in from the edge. */
val SheetGutter: Dp = 22.dp

/** The touch target on the sheet's close button: Material's minimum. */
private val SheetCloseTarget: Dp = 44.dp

/** How far a finger has to pull the sheet down before it is let go. */
private val SheetDismissDrag: Dp = 160.dp

/**
 * The shared sheet.
 *
 * @param visible drives the rise. Callers that compose this only while it is open
 *   can leave it at the default.
 * @param heightFraction how much of the screen the panel takes.
 * @param leading a glyph beside the title, when the sheet is worth one.
 * @param trailing an action beside the close button — a copy, a share.
 * @param onBack a back arrow in the title row, for a sheet with a stage under it.
 */
@Composable
fun AgentSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    visible: Boolean = true,
    heightFraction: Float = 0.72f,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val closeCd = stringResource(R.string.action_close)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DroshSurfaceHigh.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = closeCd },
        )

        // The sheet rises from the bottom edge rather than fading in: a sheet that
        // only fades gives no cue which edge it belongs to, so the gesture that
        // dismisses it has nothing to connect to.
        val rise by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "agentSheetRise",
        )

        // What the finger is doing right now, kept apart from the open animation:
        // one offset for both is what left a sheet stuck, because the animation
        // was already at its target and the drag was read off the same number.
        var dragDown by remember { mutableFloatStateOf(0f) }
        val dismissAt = with(LocalDensity.current) { SheetDismissDrag.toPx() }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset {
                    IntOffset(0, (((1f - rise) * 420f) + dragDown).roundToInt())
                }
                .fillMaxWidth()
                .fillMaxHeight(heightFraction)
                .clip(RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner))
                // Opaque, not a blur: a sheet full of text read through a blur is
                // harder to read than one over a flat surface.
                .background(DroshTile)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragDown > dismissAt) onDismiss() else dragDown = 0f
                        },
                        onDragCancel = { dragDown = 0f },
                    ) { _, delta ->
                        // Downwards only. Upwards there is the content being
                        // scrolled, and a panel that rises when the user reaches
                        // for the top of a long list is a panel fighting the list.
                        if (delta > 0f) dragDown += delta
                        else if (dragDown > 0f) dragDown = (dragDown + delta).coerceAtLeast(0f)
                    }
                }
                .navigationBarsPadding(),
        ) {
            SheetGrabber()
            SheetHeader(
                title = title,
                subtitle = subtitle,
                icon = icon,
                leading = leading,
                trailing = trailing,
                onBack = onBack,
                onDismiss = onDismiss,
            )
            content()
        }
    }
}

/**
 * The handle.
 *
 * 38x4, fully rounded, on the track colour — the same three numbers the model
 * picker uses. Not decoration: it is the top of the drag target, and it is what
 * tells the user the sheet can be let go of.
 */
@Composable
private fun SheetGrabber() {
    Box(
        modifier = Modifier
            .padding(top = 12.dp)
            .align(Alignment.CenterHorizontally)
            .width(38.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(DroshTrack),
    )
}

/**
 * The title row: a glyph, two lines of words, and a close button.
 *
 * The close button is an icon and never a word. A text button in the corner of a
 * sheet spends the width of a word on the one action that is already spelled out
 * by the drag, and it moves depending on the language. An icon does not.
 */
@Composable
private fun SheetHeader(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SheetGutter, end = 10.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(6.dp))
        }
        onBack?.let {
            SheetIconButton(
                icon = DroshIcons.ArrowLeft,
                contentDescription = stringResource(R.string.action_back),
                onClick = it,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DroshTextSecondary,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DroshText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            it()
            Spacer(Modifier.width(4.dp))
        }
        SheetCloseButton(onDismiss = onDismiss)
    }
}

/**
 * The close button: 44dp of target around a 20dp glyph.
 *
 * The box is the target and the icon is the affordance. A 20dp icon on its own is
 * below Material's 48dp minimum and unreliable on a phone, so the press area is
 * drawn larger than the thing being pressed.
 */
@Composable
fun SheetCloseButton(onDismiss: () -> Unit, modifier: Modifier = Modifier) =
    SheetIconButton(
        icon = DroshIcons.X,
        contentDescription = stringResource(R.string.action_close),
        onClick = onDismiss,
        modifier = modifier,
    )

/** Any icon inside a 44dp target on the sheet's title row. */
@Composable
fun SheetIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = DroshTextSecondary,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(SheetCloseTarget)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * An all-caps group label, the way every section header in the app is drawn.
 *
 * Upper case and 0.9sp of tracking, because a label that reads as a sentence
 * competes with the rows under it and a label that reads as a caption does not.
 */
@Composable
fun SheetSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        color = DroshTextMuted,
        modifier = modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
    )
}

/**
 * One choice in a sheet.
 *
 * The model picker's row, kept: a 14dp tile that fills when it is the choice, a
 * leading glyph, two lines of words, and a tick that says what is selected
 * without relying on colour alone.
 */
@Composable
fun SheetOptionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    monospaceSubtitle: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) DroshTileSelected else DroshTile)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(DroshTilePressed),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) DroshTextSecondary else DroshTextMuted,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) DroshText else DroshTextMuted,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    fontFamily = if (monospaceSubtitle) LocalFontSet.current.mono else null,
                    color = DroshTextMuted,
                )
            }
        }
        trailing?.invoke()
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = DroshIcons.Check,
                contentDescription = stringResource(R.string.agent_sheet_selected),
                tint = DroshText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A capsule behind a glyph, for a row that leads with one. */
@Composable
fun SheetGlyphTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(DroshTilePressed),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DroshTextSecondary,
            modifier = Modifier.size(17.dp),
        )
    }
}

/** A sentence in the middle of an empty sheet. */
@Composable
fun SheetEmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = DroshTextMuted,
            fontSize = 14.sp,
        )
    }
}

/**
 * A sheet body: the scrollable column between the header and the foot.
 *
 * Every sheet's content sits in this, so the gutter and the scroll are one number
 * rather than one per sheet.
 */
@Composable
fun SheetBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        content = content,
    )
}
