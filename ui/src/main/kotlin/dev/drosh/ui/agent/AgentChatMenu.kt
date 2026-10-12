package dev.drosh.ui.agent

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.TerminalLine
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.AgentMenuDivider
import dev.drosh.ui.agent.components.AgentTerminalPane
import dev.drosh.ui.agent.components.LocalAgentGlass
import dev.drosh.ui.agent.components.agentGlassStyle
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource

/**
 * The chat's terminal history, as a bottom sheet.
 *
 * The menu that used to open this is gone: it moved to the drawer's own
 * `DroshDropdownMenu`, anchored to the overflow pill, so one screen no longer
 * carries two dropdowns. What is left is the sheet itself — the one thing here
 * that was worth keeping.
 */
/**
 * The terminal log, as a bottom sheet.
 *
 * ## Why a sheet and not a pane
 *
 * This was a tab next to the chat. Two views of the same conversation, one tap
 * apart, permanently occupying the top of the screen — including when there is
 * nothing to see in it, which is most of the time, because most turns do not run a
 * shell command.
 *
 * As a sheet the log is somewhere the user goes to look, and the chat keeps the
 * whole screen it was using. The cost is one extra tap, paid only by the people
 * who want the log.
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
                .background(Color.Black.copy(alpha = 0.5f * scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = closeCd },
        )

        val glass = LocalAgentGlass.current
        val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

        // The sheet rises from the bottom edge rather than fading in: a sheet that
        // only fades gives no cue which edge it belongs to, so the gesture that
        // dismisses it has nothing to connect to.
        //
        // Keyed on [sheetVisible] so it actually runs. `animateFloatAsState` was
        // targeting 1f from an initial of 1f, so the offset was always zero and the
        // sheet appeared instantly — the animation was written and did nothing.
        val rise by animateFloatAsState(
            targetValue = if (sheetVisible) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "terminalSheetRise",
        )


        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, ((1f - rise) * 420).roundToInt()) }
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .clip(shape)
                .then(
                    if (glass == null) {
                        Modifier.background(DroshSurfaceHigh.copy(alpha = 0.97f))
                    } else {
                        Modifier
                            .hazeEffect(glass.state, agentGlassStyle())
                            .background(DroshSurfaceHigh.copy(alpha = 0.80f))
                    }
                )
                .navigationBarsPadding(),
        ) {
            // A grabber, because the sheet is dismissed by dragging it. It is the
            // only affordance for a gesture that has no button.
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
                TerminalHistoryEmpty(Modifier.weight(1f))
            } else {
                // The log gets its own haze source so the sheet's own header blurs
                // the terminal output scrolling under it.
                AgentTerminalPane(
                    lines = lines,
                    modifier = Modifier
                        .weight(1f)
                        // Only a source when there is a state to observe; a null
                        // HazeState here would be a silent no-op at best.
                        .then(
                            if (glass != null) Modifier.hazeSource(glass.state) else Modifier
                        ),
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

@Composable
private fun TerminalHistoryEmpty(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(DroshBackground.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(R.string.agent_no_commands),
                fontSize = 13.sp,
                color = DroshTextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.agent_no_commands_hint),
                fontSize = 11.5.sp,
                color = DroshTextMuted,
                modifier = Modifier.alpha(0.9f),
            )
        }
    }
}
