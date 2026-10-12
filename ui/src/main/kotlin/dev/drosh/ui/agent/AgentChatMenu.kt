package dev.drosh.ui.agent

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.TerminalLine
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.AgentTerminalPane
import kotlin.math.roundToInt

/**
 * The chat's terminal history, as a bottom sheet.
 *
 * The menu that used to open this is gone: it moved to the drawer's own
 * `DroshDropdownMenu`, anchored to the overflow pill, so one screen no longer
 * carries two dropdowns. What is left is the sheet itself — the one thing here
 * that was worth keeping.
 *
 * ## Why it can be dragged down
 *
 * A grabber drawn under a sheet that cannot be dragged is a lie: it is the one
 * affordance that says "this moves", and a sheet that ignores the gesture
 * teaches the user not to trust its own controls. The grabber and the drag are
 * one mechanism now, and the sheet follows the finger before it leaves.
 *
 * ## Why there is no blur
 *
 * It used to blur whatever was behind it, which on a scrolling transcript
 * meant a second render of everything the user was reading at a moment when
 * the device could least afford it — and a sheet full of text over blurred
 * text is harder to read than one over a flat surface. One opaque surface,
 * one thing to look at.
 */
@Composable
fun TerminalHistorySheet(
    lines: List<TerminalLine>,
    sheetVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeCd = stringResource(R.string.action_close)
    Box(modifier = modifier.fillMaxSize()) {
        val scrim by animateFloatAsState(
            targetValue = if (sheetVisible) 1f else 0f,
            animationSpec = tween(160),
            label = "terminalSheetScrim",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DroshSurfaceHigh.copy(alpha = 0.5f * scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = closeCd },
        )

        val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

        // The sheet rises from the bottom edge rather than fading in: a sheet that
        // only fades gives no cue which edge it belongs to, so the gesture that
        // dismisses it has nothing to connect to.
        val rise by animateFloatAsState(
            targetValue = if (sheetVisible) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "terminalSheetRise",
        )

        // How far the finger has pulled the sheet down, past its resting place.
        // Separate from [rise] because the two are different things: one is the
        // open animation, the other is what the user is doing right now. A single
        // offset for both is what left the sheet stuck — the animation targeted
        // 1f from an initial 1f and the drag was read from the same number.
        var dragDown by remember { mutableFloatStateOf(0f) }
        val dismissAt = 160f

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset {
                    IntOffset(0, (((1f - rise) * 420) + dragDown).roundToInt())
                }
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .clip(shape)
                // Opaque, not translucent and blurred. One surface for a screen
                // that is all text.
                .background(DroshSurfaceHigh)
                // The grabber is the drag, and the whole sheet follows the
                // finger: a sheet that only moves when you hold one specific
                // band of it is a sheet most people never discover they can
                // move.
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragDown > dismissAt) onDismiss() else dragDown = 0f
                        },
                        onDragCancel = { dragDown = 0f },
                    ) { _, delta ->
                        if (delta > 0f) dragDown += delta
                        else if (dragDown > 0f) dragDown = (dragDown + delta).coerceAtLeast(0f)
                    }
                }
                .navigationBarsPadding(),
        ) {
            // A grabber, because the sheet is dismissed by dragging it — and now
            // it really is.
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DroshText.copy(alpha = 0.2f)),
            )
            Spacer(Modifier.height(8.dp))

            TerminalHistoryHeader(onDismiss = onDismiss)

            if (lines.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.agent_no_commands_yet),
                    fontSize = 12.sp,
                    color = DroshTextMuted,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            } else {
                // The commands are cards: one per command, with the output under
                // it. A flat surface, because that is what every other card on
                // this screen is.
                AgentTerminalPane(
                    lines = lines,
                    modifier = Modifier
                        .weight(1f)
                        .background(DroshBackground),
                )
            }
        }
    }
}

@Composable
private fun TerminalHistoryHeader(onDismiss: () -> Unit) {
    val closeHistoryCd = stringResource(R.string.agent_close_history)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = DroshIcons.SquareTerminal,
            contentDescription = null,
            tint = DroshTextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = stringResource(R.string.agent_terminal_history),
            fontSize = 15.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = DroshText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.action_close),
            fontSize = 13.sp,
            color = DroshTextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics { contentDescription = closeHistoryCd },
        )
    }
}
