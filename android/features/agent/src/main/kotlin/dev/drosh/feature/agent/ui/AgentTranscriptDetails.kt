package dev.drosh.feature.agent.ui

import dev.drosh.ui.components.MOTION_MS
import dev.drosh.ui.components.glassRow
import dev.drosh.ui.components.ActionButton
import dev.drosh.ui.components.FlatButton
import dev.drosh.ui.components.IconAction
import dev.drosh.ui.components.CollapsibleRow
import android.content.Intent

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshOnPrimary
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceLow
import dev.drosh.theme.DroshSuccess
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshWarning
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.AgentTodo
import dev.drosh.domain.agent.AgentTodoStatus
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRule
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.TerminalProjection
import dev.drosh.domain.agent.TokenUsage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.DroshMenuItem
import dev.drosh.ui.DroshDropdownMenu
import dev.drosh.ui.settings.SettingsCheck
import dev.drosh.feature.agent.ui.components.DiffBlock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTilePressed
import dev.drosh.theme.DroshTrack
import dev.drosh.theme.DroshTileSelected
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.agent.LlmModel
import dev.drosh.feature.agent.ui.components.AgentIconPill
import dev.drosh.feature.agent.ui.components.AgentPill
import dev.drosh.feature.agent.ui.components.AgentSelectableText
import dev.drosh.feature.agent.ui.components.AgentToolPill
import dev.drosh.feature.agent.ui.components.DroshStatusBarVisible
import dev.drosh.feature.agent.ui.components.ProvideAgentGlass
import dev.drosh.ui.session.SessionLogSource
import dev.drosh.ui.session.SessionLogTarget
import dev.drosh.ui.components.rememberAgentClipboard
import dev.drosh.feature.agent.ui.components.rememberAgentGlass
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.drosh.feature.agent.ui.components.MarkdownText
import androidx.compose.ui.res.stringResource
import dev.drosh.ui.R
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush

/**
 * The agent's checklist.
 *
 * A list, not a card: no fill, no border, hanging off the same rule as the tool
 * output it replaces. A checklist is the same kind of thing as that output, and
 * boxing it inside a box reads as one more layer.
 *
 * ## Why progress is a count and not a bar
 *
 * `2/5` says how far along it is and how much is left, which is what someone
 * glancing at it wants. A bar says the same while hiding the total, so the reader
 * cannot tell whether five items or fifty is left.
 *
 * ## Completed items stay
 *
 * Struck through, not removed. A checklist that forgets what it finished cannot
 * answer "did it do the thing I asked", and the strike is the cheapest way to keep
 * that answer on screen.
 */

@Composable
internal fun TodoCard(
    todos: List<AgentTodo>,
    modifier: Modifier = Modifier,
) {
    val done = todos.count { it.status == AgentTodoStatus.COMPLETED }
    val completedCd = stringResource(R.string.agent_todo_completed)
    val inProgressCd = stringResource(R.string.agent_todo_in_progress)
    val pendingCd = stringResource(R.string.agent_todo_pending)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.agent_todo_count, done, todos.size),
                fontSize = 11.sp,
                fontFamily = LocalFontSet.current.mono,
                fontWeight = FontWeight.Medium,
                color = if (done == todos.size) DroshSuccess else DroshTextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(DroshOutline.copy(alpha = 0.4f)),
            )
        }

        Spacer(Modifier.height(8.dp))

        todos.forEach { todo -> TodoRow(todo = todo) }
    }
}

@Composable
internal fun TodoRow(todo: AgentTodo) {
    val completed = todo.status == AgentTodoStatus.COMPLETED
    val active = todo.status == AgentTodoStatus.IN_PROGRESS
    val completedCd = stringResource(R.string.agent_todo_completed)
    val inProgressCd = stringResource(R.string.agent_todo_in_progress)
    val pendingCd = stringResource(R.string.agent_todo_pending)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = todo.title + ", " + when (todo.status) {
                    AgentTodoStatus.COMPLETED -> completedCd
                    AgentTodoStatus.IN_PROGRESS -> inProgressCd
                    AgentTodoStatus.PENDING -> pendingCd
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        // The mark carries the state as shape, not only as colour: an empty box, a
        // filled box and a ring are three different shapes, so the list is readable
        // without relying on hue.
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        completed -> DroshSuccess
                        // A ring rather than a fill: started is not done, and a
                        // half-done item drawn as a filled box would overstate it.
                        active -> DroshOutline.copy(alpha = 0.35f)
                        else -> Color.Transparent
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                completed -> Icon(
                    imageVector = DroshIcons.Check,
                    contentDescription = null,
                    tint = DroshOnPrimary,
                    modifier = Modifier.size(11.dp),
                )

                active -> Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(DroshPrimary),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = todo.title,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = when {
                completed -> DroshTextMuted
                active -> DroshText
                else -> DroshTextSecondary
            },
            textDecoration = if (completed) TextDecoration.LineThrough else null,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Must match `AskUserTool.NAME`. */
internal const val ASK_USER_TOOL = "ask_user"

/**
 * How a question gets answered.
 *
 * Options as rows rather than a row of buttons: a list of choices has no natural
 * count, and two columns silently drop the third. Rows also fit the length of an
 * option, which is where these fail first — buttons truncate their labels and a
 * truncated choice is a choice the user cannot read.
 */
@Composable
internal fun QuestionControls(
    options: List<String>,
    onAnswer: (ApprovalDecision) -> Unit,
) {
    val skipCd = stringResource(R.string.agent_skip_question_cd)
    if (options.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                ChoiceRow(option = option, onClick = { onAnswer(ApprovalDecision.Answer(option)) })
            }
            DismissRow(onClick = { onAnswer(ApprovalDecision.Reject("dismissed")) })
        }
        return
    }

    // No options: the answer is free text, and a button cannot collect one.
    var reply by rememberSaveable { mutableStateOf("") }
    val canSend = reply.isNotBlank()

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassRow(RoundedCornerShape(12.dp), raised = true)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (reply.isEmpty()) {
                Text(
                    text = stringResource(R.string.agent_reply_hint),
                    fontSize = 13.5.sp,
                    color = DroshTextMuted,
                )
            }
            BasicTextField(
                value = reply,
                onValueChange = { reply = it },
                maxLines = 4,
                textStyle = TextStyle(fontSize = 13.5.sp, color = DroshText),
                cursorBrush = SolidColor(DroshPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.action_skip),
                fontSize = 13.sp,
                color = DroshTextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onAnswer(ApprovalDecision.Reject("dismissed")) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = skipCd },
            )
            Spacer(Modifier.weight(1f))
            AgentPill(
                label = stringResource(R.string.agent_reply),
                onClick = { onAnswer(ApprovalDecision.Answer(reply.trim())) },
                primary = true,
                enabled = canSend,
                contentDescription = stringResource(R.string.agent_send_reply_cd),
                // In the transcript, so it is a flat fill: the glass and its edge
                // belong to the two surfaces that float over content, and a
                // borderless chip inside somebody's answer would read as chrome.
                edged = false,
            )
        }
    }
}

@Composable
internal fun ChoiceRow(option: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassRow(RoundedCornerShape(11.dp), raised = true)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .semantics { contentDescription = option },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = DroshText,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = DroshIcons.ChevronRight,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
internal fun DismissRow(onClick: () -> Unit) {
    val skipQuestionCd = stringResource(R.string.action_skip)
    Text(
        text = stringResource(R.string.action_skip),
        fontSize = 13.sp,
        color = DroshTextMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = skipQuestionCd },
    )
}

/** What the user chose, once they have. */
@Composable
internal fun DecisionRow(decision: ApprovalDecision) {
    val approvedCd = stringResource(R.string.agent_decision_approved)
    val rejectedCd = stringResource(R.string.agent_decision_rejected)
    val answeredCd = stringResource(R.string.agent_decision_answered)
    val alwaysAllowedCd = stringResource(R.string.agent_decision_always_allow)
    val (label, tint) = when (decision) {
        is ApprovalDecision.Approve -> approvedCd
        is ApprovalDecision.Reject -> rejectedCd
        is ApprovalDecision.Answer -> answeredCd
        is ApprovalDecision.AlwaysAllow -> alwaysAllowedCd
    } to when (decision) {
        is ApprovalDecision.Approve -> DroshSuccess
        is ApprovalDecision.Reject -> DroshError
        is ApprovalDecision.Answer -> DroshSuccess
        is ApprovalDecision.AlwaysAllow -> DroshSuccess
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (decision) {
                is ApprovalDecision.Reject -> DroshIcons.X
                else -> DroshIcons.Check
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
            )
            // The words matter. "onaylandı" alone does not say what the user said,
            // and for a question that text is the entire content of the answer.
            when (decision) {
                is ApprovalDecision.Reject -> if (decision.reason.isNotBlank()) {
                    Text(
                        text = decision.reason,
                        fontSize = 11.5.sp,
                        color = DroshTextMuted,
                    )
                }

                is ApprovalDecision.Answer -> Text(
                    text = decision.text,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = DroshTextSecondary,
                )

                // Nothing under the label: the rule is not something the user
                // wrote, and the label already says what happened.
                is ApprovalDecision.Approve, is ApprovalDecision.AlwaysAllow -> Unit
            }
        }
    }
}

@Composable
internal fun NoticeBlock(text: String, color: Color, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = color,
        )
    }
}

@Composable
internal fun ChatEmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // No agent mark here. The icon belongs to the terminal screen's agent
        // button and nowhere else: a face that means "agent" on every agent screen
        // is decoration, and the same glyph on a terminal button is what tells the
        // user that button opens the agent rather than a shell.
        Text(
            text = stringResource(R.string.agent_composer_hint),
            fontSize = 15.sp,
            color = DroshText,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.agent_composer_example),
            fontSize = 12.sp,
            color = DroshTextMuted,
        )
    }
}

/**
 * The input.
 *
 * Kept at the bottom and above the keyboard. The send button becomes a stop button
 * while a run is going, because that is the only control that matters at that
 * moment and it is already within thumb reach.
 */
/**
 * Hands text to the system share sheet.
 *
 * Suspends rather than fires and forgets because `startActivity` from a
 * non-activity context has to be a coroutine with a real scope — a fire-and-forget
 * share throws `ActivityNotFoundException` on some devices with no chooser
 * installed, and swallowing that would leave the user tapping a dead menu item.
 */
internal suspend fun shareText(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, null)
    runCatching {
        context.startActivity(chooser, null)
    }.onFailure { error ->
        // No handler for ACTION_SEND. The text is still on the clipboard path the
        // Copy action offers, so this is a degraded outcome rather than a lost one.
        Timber.w(error, "share unavailable")
    }
}

// ── run-level banners ────────────────────────────────────────────────────

/**
 * A provider failure the loop is retrying.
 *
 * Distinct from [FailureCard] on purpose: nothing has failed yet, the system is
 * waiting and trying again. Rendering it as an error would teach the user that
 * every rate limit is a dead end.
 */
@Composable
internal fun RetryBanner(notice: AgentChatViewModel.RetryNotice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshWarning.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(DroshWarning),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            // The attempt count is shown because "waiting" with no progress reads
            // as a hang, and the backoff between retries is up to fifteen seconds.
            text = stringResource(R.string.agent_retry_notice, notice.attempt, notice.maxAttempts),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = DroshWarning,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A run that ended badly, with the one action that helps.
 *
 * The message comes from the provider, so it says what went wrong; the button
 * re-sends the prompt that started the run. A failure with no way forward is a
 * dead end, and "tekrar dene" is the only forward.
 */
@Composable
internal fun FailureCard(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshError.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.agent_run_failed),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = DroshError,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = message,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = DroshTextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                icon = DroshIcons.X,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        }
    }
}

/**
 * Token usage for the run so far.
 *
 * In the transcript's own scroll area rather than pinned above the composer, so
 * it scrolls away with the conversation. A permanently pinned counter takes
 * vertical space from a phone screen all run to report something that only
 * matters at the end.
 */
@Composable
internal fun UsageStrip(usage: TokenUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.agent_usage_tokens,
                usage.output ?: 0,
                usage.input ?: 0,
            ),
            fontSize = 10.sp,
            fontFamily = LocalFontSet.current.mono,
            color = DroshTextMuted,
        )
    }
}

/**
 * A highlight travelling left to right across whatever it is applied to.
 *
 * Clipped to the content by `SrcAtop`, so it brightens the glyphs rather than
 * drawing a bar over them — the difference between a shimmer and a highlight
 * sweep. The band is 40% of the width and fades at both edges, so it reads as
 * something passing over the text rather than as a colour change.
 */
internal fun Modifier.sweepHighlight(progress: Float): Modifier = this.drawWithContent {
    drawContent()
    val band = size.width * 0.4f
    val x = (size.width + band) * progress - band
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.18f),
                Color.Transparent,
            ),
            start = Offset(x, 0f),
            end = Offset(x + band, size.height),
        ),
        blendMode = BlendMode.SrcAtop,
    )
}
