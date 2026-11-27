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

@Composable
internal fun MessageRow(
    message: ChatMessage,
    onAnswer: (String, ApprovalDecision) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    /** Edits what the user sent: the draft comes back for another go. */
    onEdit: (String) -> Unit,
    /** Opens a tool's full output on the detail sheet. */
    onInspectTool: (ChatMessage.ToolCall) -> Unit,
) {
    when (message) {
        is ChatMessage.User -> UserBubble(
            message,
            onCopy = onCopy,
            onShare = onShare,
            onEdit = onEdit,
        )

        is ChatMessage.Assistant -> AssistantText(
            message,
            onCopy = onCopy,
            onShare = onShare,
            onRetry = onRetry,
        )

        is ChatMessage.Reasoning -> ReasoningBlock(message)

        is ChatMessage.ToolCall -> ToolCallRow(message, onInspect = { onInspectTool(message) })

        is ChatMessage.Approval -> ApprovalBlock(message, onAnswer)

        is ChatMessage.Failure -> NoticeBlock(
            text = message.message,
            color = DroshError,
            description = stringResource(R.string.agent_error, message.message),
        )

        is ChatMessage.Notice -> NoticeBlock(
            text = message.text,
            color = DroshTextSecondary,
            description = message.text,
        )
    }
}

@Composable
internal fun UserBubble(
    message: ChatMessage.User,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    // A long press opens the menu; a drag inside the text still makes a
    // selection, because the text's own recogniser owns that gesture. The menu
    // is the row of options — edit, copy, select — and the selection is what
    // you do *after* choosing "select".
    var menuOpen by remember { mutableStateOf(false) }
    // The menu's answer, handed down to the text's own recogniser.
    var selectAllRequested by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                // A flat surface with one border, the same material as the row
                // of pills above and the composer below. A tinted blob with no
                // edge was a third kind of thing on a screen that now speaks
                // with one voice.
                .glassRow(
                    shape = RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomEnd = 4.dp,
                        bottomStart = 14.dp,
                    ),
                    raised = true,
                )
                // The press is the bubble's, not the text's: the text's own
                // recogniser makes a selection, and this one asks what to do
                // with the whole message. Two recognisers on one gesture would
                // race, so the bubble takes the press and hands the selection
                // to the text only once the menu has been answered.
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            AgentSelectableText(
                text = message.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = DroshText,
                onCopy = onCopy,
                onShare = onShare,
                selectAllRequested = selectAllRequested,
                onSelectAllConsumed = { selectAllRequested = false },
            )
        }

        // The menu is anchored to the bubble, which is the only position the
        // user has any reason to expect: the menu appears over the thing that
        // was pressed, not over the screen and not over the list.
        DroshDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            items = listOf(
                DroshMenuItem(
                    label = stringResource(R.string.agent_edit_message),
                    id = "edit",
                    icon = DroshIcons.Pencil,
                ),
                DroshMenuItem(
                    label = stringResource(R.string.action_copy),
                    id = "copy",
                    icon = DroshIcons.Copy,
                ),
                DroshMenuItem(
                    label = stringResource(R.string.agent_select_text),
                    id = "select",
                    icon = DroshIcons.Type,
                ),
            ),
            onItemClick = { item ->
                menuOpen = false
                when (item.id) {
                    "edit" -> onEdit(message.text)
                    "copy" -> onCopy(message.text)
                    // The text's own selection: the bubble hands the request to
                    // the text it wraps, which is the only place a selection
                    // can be made.
                    "select" -> selectAllRequested = true
                }
            },
        )
    }
}

@Composable
internal fun AssistantText(
    message: ChatMessage.Assistant,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownText(
            markdown = message.text,
            onCopy = onCopy,
            // Announced as it grows, so a screen-reader user hears the answer arrive
            // rather than having to poll.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        // Three acts on the answer, in three small icons under it.
        //
        // They were behind a long press, inside one text selection model, and
        // the answer they act on is the thing the user just read — so they sit
        // where the reading finished rather than where a menu has to be fished
        // out of the text. Share and copy are the two that only make sense on a
        // finished message, so a streaming answer shows neither.
        if (!message.streaming && message.text.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AssistantAction(DroshIcons.Copy, R.string.action_copy, onClick = { onCopy(message.text) })
                AssistantAction(DroshIcons.Share, R.string.action_share, onClick = { onShare(message.text) })
                AssistantAction(DroshIcons.RotateCcw, R.string.agent_retry, onClick = onRetry)
            }
        }

        if (message.streaming) {
            Spacer(Modifier.height(4.dp))
            ThinkingDots()
        }
    }
}

/**
 * One of the three acts under an answer.
 *
 * Quiet by design: 16dp glyph, no fill, no border. The row is an afterthought
 * to the message above it, and a set of button-shaped buttons there would
 * compete with the text for the eye.
 */
@Composable
internal fun AssistantAction(
    icon: ImageVector,
    contentDescriptionRes: Int,
    onClick: () -> Unit,
) {
    val cd = stringResource(contentDescriptionRes)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * The "still working" indicator.
 *
 * Three dots fading in sequence rather than all pulsing together. Synchronised
 * pulsing reads as one blob that breathes; a stagger reads as three separate
 * events, which is what it is — the agent is doing several things, not one thing
 * that is merely dim.
 *
 * No card, no border. It sits on the assistant's own baseline next to the label, so
 * the answer visibly grows out of the same place instead of appearing under a
 * spinner-shaped widget.
 */
/**
 * The "still working" indicator.
 *
 * One line: `Thinking 12s`. No dots, no card, no separate widget — the label is the
 * whole thing, and it carries the elapsed time so the user can see how long the
 * model has been at it without looking for a timer.
 *
 * The sweep is a highlight travelling left to right across the text, not a pulse.
 * A pulse says "something is happening"; a sweep says "something is happening and it
 * is moving", which is closer to what a model doing several things in a row actually
 * looks like. It is clipped to the glyphs, so it brightens the text rather than
 * drawing a bar over it.
 */
@Composable
internal fun ThinkingDots(modifier: Modifier = Modifier) {
    val thinkingCd = stringResource(R.string.agent_thinking_cd)
    val startedAt = rememberRunningStart(true)
    val elapsed = elapsedSince(startedAt)

    Text(
        text = stringResource(R.string.agent_thinking_secs, elapsed),
        fontSize = 11.5.sp,
        fontFamily = LocalFontSet.current.mono,
        color = DroshPrimary,
        modifier = modifier
            .semantics { contentDescription = thinkingCd }
            .sweepHighlight(sweepPosition()),
    )
}

/**
 * The agent's reasoning, as a line of text.
 *
 * ## Not a card
 *
 * This was a `CollapsibleRow` — a `DroshSurfaceLow` container with a radius, which
 * put a grey box around every moment the model thought. Reasoning is the most
 * frequent non-answer row in a transcript, so a box there meant most of the screen
 * was boxes.
 *
 * Now it is the same shape as a tool row: verb, elapsed time while running, chevron.
 * "düşünüyor" is past-tense English for the same reason the tool rows are: the verb
 * is the status, so there is no separate indicator to look for.
 *
 * Closed by default. Reasoning is worth reading when it is surprising and noise when
 * it is not, and the user asked for the log; opening it should be their call.
 */
@Composable
internal fun ReasoningBlock(message: ChatMessage.Reasoning) {
    val stopThinkingCd = stringResource(R.string.agent_stop_thinking)
    var open by rememberSaveable(message.id) { mutableStateOf(false) }

    // Elapsed and sweep from the screen's shared motion, for the same reason as
    // the tool rows and with the same payoff: a reasoning block that has finished
    // no longer wakes once a second and no longer recomposes at frame rate.
    val running = message.text.isEmpty() || message.text.endsWith("…")
    val startedAt = rememberRunningStart(running)
    val elapsed = if (running) elapsedSince(startedAt) else 0

    val chevronRotation by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = tween(MOTION_MS),
        label = "reasoningChevronRotation",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 1.dp)
                .semantics { contentDescription = stopThinkingCd },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = DroshIcons.Info,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))

            Text(
                text = if (running) stringResource(R.string.agent_reasoning_secs, elapsed) else stringResource(R.string.agent_reasoned),
                fontSize = 11.5.sp,
                fontFamily = LocalFontSet.current.mono,
                color = if (running) DroshPrimary else DroshTextMuted,
                modifier = if (running) {
                    Modifier.sweepHighlight(sweepPosition())
                } else {
                    Modifier
                },
            )

            Spacer(Modifier.weight(1f))

            Icon(
                imageVector = DroshIcons.ChevronRight,
                contentDescription = stringResource(if (open) R.string.action_collapse else R.string.action_expand),
                tint = DroshTextMuted,
                modifier = Modifier
                    .size(12.dp)
                    .rotate(chevronRotation),
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(MOTION_MS)) + expandVertically(tween(MOTION_MS)),
            exit = fadeOut(tween(MOTION_MS)) + shrinkVertically(tween(MOTION_MS)),
        ) {
            Row(modifier = Modifier.padding(top = 1.dp)) {
                // A hairline, not a card: the grouping cue the container used to
                // provide, at a weight that reads as a rule.
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .width(1.dp)
                        .heightIn(min = 12.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(DroshOutline.copy(alpha = 0.4f)),
                )
                Text(
                    text = message.text,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = LocalFontSet.current.mono,
                    color = DroshTextMuted,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp, top = 1.dp, bottom = 3.dp),
                )
            }
        }
    }
}

/**
 * A tool call, as one pill.
 *
 * There is no tool text in the transcript. The pill says the verb, the colour of
 * the verb says how that went, and tapping it opens the detail sheet — the same
 * sheet for every tool, since the sheet is where the output lives and the output
 * is the part that must not be on screen.
 *
 * What the tool actually produced — the raw output, the diff, the copy action —
 * goes on the sheet with it. What stays here is the checklist, because a
 * checklist is the agent's plan rather than a transcript of what a command
 * printed.
 */
@Composable
internal fun ToolCallRow(
    message: ChatMessage.ToolCall,
    onInspect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AgentToolPill(message = message, onClick = onInspect)

        if (message.todos.isNotEmpty()) {
            TodoCard(
                todos = message.todos,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * A question, or an approval.
 *
 * ## The two are not the same control
 *
 * They share the loop's primitive — suspend, present, take an answer — but they are
 * different things to be asked, and the old card treated them as one:
 *
 *  - **an approval** (`write_file`) is yes or no about something already decided.
 *    Two buttons is the whole answer.
 *  - **a question** (`ask_user`) is the agent blocked on something only the user
 *    knows. Answering it needs a way to *type*, and the old card's "Yanıtla" button
 *    sent `Approve`, which reaches the model as "the user acknowledged the
 *    question" — so the free-text path the tool documents was unreachable from the
 *    screen.
 *
 * With options, the old card also rendered `options.first()` and
 * `options.getOrElse(1) { "Onayla" }` — two buttons out of however many were
 * offered, inventing an "Onayla" when only one existed. A three-way question showed
 * two of its options and a button that was never one of them.
 *
 * So: every option as its own row, and a real text field when there are none.
 *
 * ## Why this one keeps a card
 *
 * Unlike a tool row, this is a question addressed to the user with the run parked
 * on the answer. It is a distinct object in the transcript rather than a line of it,
 * and the answer controls need to read as a set you act on rather than as prose.
 */
@Composable
internal fun ApprovalBlock(
    message: ChatMessage.Approval,
    onAnswer: (String, ApprovalDecision) -> Unit,
) {
    val approval = message.approval
    // ask_user asks; write_file proposes. Distinguished by the tool that raised it
    // rather than by a new flag on the approval, since the name is already here and
    // is what the loop actually branched on.
    val isQuestion = approval.toolName == ASK_USER_TOOL

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassRow(RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isQuestion) DroshIcons.ListChecks else DroshIcons.Pencil,
                contentDescription = null,
                tint = if (isQuestion) DroshPrimary else DroshWarning,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isQuestion) stringResource(R.string.agent_question) else stringResource(R.string.agent_approval),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.9.sp,
                color = if (isQuestion) DroshPrimary else DroshWarning,
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = approval.title,
            fontSize = 14.5.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = DroshText,
        )

        approval.body?.let {
            Spacer(Modifier.height(5.dp))
            Text(
                text = it,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = DroshTextSecondary,
            )
        }

        approval.diff?.let {
            Spacer(Modifier.height(10.dp))
            DiffBlock(text = it)
        }

        Spacer(Modifier.height(12.dp))

        val decided = message.decision
        if (decided != null) {
            DecisionRow(decision = decided)
            return@Column
        }

        if (isQuestion) {
            QuestionControls(
                options = approval.options,
                onAnswer = { onAnswer(approval.id, it) },
            )
        } else if (approval.toolName == TerminalProjection.SHELL_TOOL) {
            // A shell command gets the third answer: run it, and do not ask
            // again about this shape of command. It belongs here and nowhere
            // else — there is nothing to "always" about writing one file.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlatButton(
                    text = stringResource(R.string.action_reject),
                    onClick = { onAnswer(approval.id, ApprovalDecision.Reject("declined")) },
                    modifier = Modifier.weight(1f),
                )
                FlatButton(
                    text = stringResource(R.string.agent_always_allow),
                    onClick = {
                        onAnswer(
                            approval.id,
                            ApprovalDecision.AlwaysAllow(
                                ApprovalRule.forCommand(approval.title),
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = stringResource(R.string.action_apply),
                    onClick = { onAnswer(approval.id, ApprovalDecision.Approve) },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlatButton(
                    text = stringResource(R.string.action_reject),
                    onClick = { onAnswer(approval.id, ApprovalDecision.Reject("declined")) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = stringResource(R.string.action_apply),
                    onClick = { onAnswer(approval.id, ApprovalDecision.Approve) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
