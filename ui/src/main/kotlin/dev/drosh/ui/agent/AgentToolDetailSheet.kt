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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.LocalFontSet
import kotlin.math.roundToInt

/**
 * What a tool did, in full, as a sheet.
 *
 * ## Why a sheet and not an inline expansion
 *
 * A tool row that expands inline makes the transcript taller by however much
 * output there is — a `git diff` on a big change pushes the conversation off the
 * screen, and the row the user was reading moves out from under their thumb. A
 * sheet holds the detail somewhere else entirely and the transcript stays put.
 *
 * The same shape as the terminal history sheet, and for the same reasons: it
 * really drags, and it is one opaque surface rather than a blur over text it is
 * trying to be read through.
 */
@Composable
fun AgentToolDetailSheet(
    visible: Boolean,
    title: String,
    subtitle: String,
    body: String,
    state: ToolCallState?,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    if (!visible) return
    val closeCd = stringResource(R.string.action_close)

    Box(modifier = Modifier.fillMaxSize()) {
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

        var dragDown by remember { mutableFloatStateOf(0f) }
        val dismissAt = with(LocalDensity.current) { 160.dp.toPx() }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, dragDown.roundToInt()) }
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                // Opaque, not a blur: a sheet full of text read through a blur
                // is harder to read than one over a flat surface.
                .background(DroshSurfaceHigh)
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
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DroshText.copy(alpha = 0.2f)),
            )
            Spacer(Modifier.height(10.dp))

            ToolDetailHeader(
                title = title,
                subtitle = subtitle,
                state = state,
                onDismiss = onDismiss,
            )

            HorizontalRule()

            // The body, scrolled by the sheet rather than by the transcript.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(DroshBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = body.ifBlank { stringResource(R.string.agent_tool_no_output) },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = LocalFontSet.current.mono,
                    color = if (body.isBlank()) DroshTextMuted else DroshTextSecondary,
                )
            }

            ToolDetailFooter(
                onCopy = { onCopy(body) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ToolDetailHeader(
    title: String,
    subtitle: String,
    state: ToolCallState?,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = DroshText,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                fontFamily = LocalFontSet.current.mono,
                color = DroshTextSecondary,
                maxLines = 1,
            )
        }
        state?.let {
            Text(
                text = stringResource(it.labelRes()),
                fontSize = 11.sp,
                fontFamily = LocalFontSet.current.mono,
                color = if (it == ToolCallState.Failed || it == ToolCallState.Cancelled) {
                    DroshOutline
                } else {
                    DroshTextMuted
                },
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = stringResource(R.string.action_close),
            fontSize = 13.sp,
            color = DroshTextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun HorizontalRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(1.dp)
            .background(DroshOutline.copy(alpha = 0.5f)),
    )
}

@Composable
private fun ToolDetailFooter(
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshSurfaceHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Copy is the action this screen exists for: the output is usually
        // something the user wants to paste somewhere.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onCopy)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = DroshIcons.Copy,
                contentDescription = null,
                tint = DroshTextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.action_copy),
                fontSize = 12.5.sp,
                color = DroshTextSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.action_close),
            fontSize = 12.5.sp,
            color = DroshTextMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * The state's own words, rather than a colour the user has to read.
 *
 * A sealed interface, so the branches are its six members and there is no
 * `else` to leave a new state silently unlabelled the day one is added.
 */
private fun ToolCallState.labelRes(): Int = when (this) {
    ToolCallState.Running -> R.string.agent_tool_running
    ToolCallState.Succeeded -> R.string.agent_tool_succeeded
    ToolCallState.Failed -> R.string.agent_tool_failed
    ToolCallState.Cancelled -> R.string.agent_tool_cancelled
    ToolCallState.AwaitingApproval -> R.string.agent_tool_awaiting
    ToolCallState.Pending -> R.string.agent_tool_pending
}
