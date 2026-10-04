package dev.drosh.ui.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshWarning
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.CollapsibleRow
import dev.drosh.ui.agent.components.DiffBlock
import dev.drosh.ui.agent.components.DroshGhostMark
import dev.drosh.ui.agent.components.FlatButton
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.MonoBlock
import dev.drosh.ui.agent.components.StatusPill
import dev.drosh.ui.agent.components.TOUCH_TARGET

/**
 * One agent chat.
 *
 * The transcript is the screen. Tool calls are one collapsed line each and expand
 * on tap — a tool that printed two hundred lines must not push the conversation
 * off the screen, and the user has to be able to see what ran without scrolling.
 *
 * Approvals are inline where they are raised rather than in a dialog: the question
 * is only answerable while the diff that prompted it is on screen.
 */
@Composable
fun AgentChatScreen(
    chatId: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onChatCreated: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AgentChatViewModel = hiltViewModel(),
) {
    LaunchedEffect(chatId) {
        chatId?.let { viewModel.attach(it, onChatCreated) }
    }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val pending by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val providerState by viewModel.providerState.collectAsStateWithLifecycle()

    val waiting = runState is AgentRunState.WaitingApproval
    val running = viewModel.isRunning

    val listState = rememberLazyListState()

    // Follow the stream, but only when the user is already near the bottom.
    // Yanking the viewport while they are reading earlier output is worse than
    // missing a few tokens.
    LaunchedEffect(messages.size, waiting) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val atBottom = lastVisible >= messages.size - 3
        if (atBottom) listState.animateScrollToItem(messages.size.coerceAtLeast(0))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        ChatTopBar(
            title = chat?.name ?: "Agent",
            stepLabel = (runState as? AgentRunState.Running)
                ?.let { "çalışıyor" },
            waiting = waiting,
            onBack = onBack,
            onStop = viewModel::stop,
            onOpenSettings = onOpenSettings,
        )

        providerState.error?.let { message ->
            ErrorBanner(message = message, onDismiss = viewModel::dismissError, onFix = onOpenSettings)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageRow(message = message, onAnswer = viewModel::answer)
            }

            if (messages.isEmpty()) {
                item(key = "hint") { ChatEmptyHint() }
            }
        }

        Composer(
            enabled = !running,
            stopVisible = running,
            onSend = viewModel::send,
            onStop = viewModel::stop,
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    stepLabel: String?,
    waiting: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshSurface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                icon = DroshIcons.ArrowLeft,
                contentDescription = "Geri",
                onClick = onBack,
            )

            DroshGhostMark(
                size = 20.dp,
                tint = if (waiting) DroshWarning else DroshPrimary,
            )
            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = DroshText,
                    maxLines = 1,
                )
                if (stepLabel != null) {
                    Text(
                        text = stepLabel,
                        fontSize = 10.sp,
                        color = DroshPrimary,
                    )
                }
            }

            // A stop button while streaming, the settings entry otherwise. Showing
            // both at once crowds a 390dp bar.
            if (stepLabel != null) {
                IconAction(
                    icon = DroshIcons.Square,
                    contentDescription = "Durdur",
                    onClick = onStop,
                    tint = DroshError,
                )
            } else {
                IconAction(
                    icon = DroshIcons.Settings,
                    contentDescription = "Agent ayarları",
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshError.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = DroshText,
            modifier = Modifier.weight(1f),
            // Announced when it appears: a failed run the user cannot hear about
            // is a failed run they do not know about.
        )
        FlatButton(text = "Çöz", onClick = onFix, modifier = Modifier.width(72.dp))
        IconAction(
            icon = DroshIcons.X,
            contentDescription = "Kapat",
            onClick = onDismiss,
        )
    }
}

@Composable
private fun MessageRow(message: ChatMessage, onAnswer: (String, ApprovalDecision) -> Unit) {
    when (message) {
        is ChatMessage.User -> UserBubble(message)

        is ChatMessage.Assistant -> AssistantText(message)

        is ChatMessage.Reasoning -> ReasoningBlock(message)

        is ChatMessage.ToolCall -> ToolCallRow(message)

        is ChatMessage.Approval -> ApprovalBlock(message, onAnswer)

        is ChatMessage.Failure -> NoticeBlock(
            text = message.message,
            color = DroshError,
            description = "Hata: ${message.message}",
        )

        is ChatMessage.Notice -> NoticeBlock(
            text = message.text,
            color = DroshTextSecondary,
            description = message.text,
        )
    }
}

@Composable
private fun UserBubble(message: ChatMessage.User) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomEnd = 4.dp,
                        bottomStart = 14.dp,
                    ),
                )
                .background(DroshPrimary.copy(alpha = 0.13f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = message.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = DroshText,
            )
        }
    }
}

@Composable
private fun AssistantText(message: ChatMessage.Assistant) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "drosh",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = DroshPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = message.text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = DroshText,
            // Announced as it grows, so a screen-reader user hears the answer
            // arrive rather than having to poll.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (message.streaming) {
            Spacer(Modifier.height(4.dp))
            ThinkingDots()
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinking-alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "düşünüyor" },
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .padding(end = 3.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(DroshTextMuted.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun ReasoningBlock(message: ChatMessage.Reasoning) {
    var open by rememberSaveable(message.id) { mutableStateOf(false) }
    CollapsibleRow(
        expanded = open,
        onToggle = { open = !open },
        summary = {
            Text(
                text = if (open) "düşünüyor" else "düşündü",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = DroshTextMuted,
            )
        },
        content = { MonoBlock(text = message.text) },
    )
}

/**
 * One tool call.
 *
 * Collapsed to a single line by default. The summary is the tool's own
 * one-liner — `npm run build`, `src/App.ktx` — rather than its argument JSON,
 * because the point of the collapsed row is "what did it just do", not "what were
 * the exact arguments".
 */
@Composable
private fun ToolCallRow(message: ChatMessage.ToolCall) {
    // A running tool shows its output without a tap: waiting to see whether a
    // command is working is the single most common reason to watch this screen.
    var open by rememberSaveable(message.id) { mutableStateOf(message.state == ToolCallState.Running) }
    val running = message.state == ToolCallState.Running

    LaunchedEffect(running) {
        if (running) open = true
    }

    CollapsibleRow(
        expanded = open,
        onToggle = { open = !open },
        summary = {
            ToolIcon(message.name)
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.name,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = DroshText,
            )
            if (message.summary.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = message.summary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DroshTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Pushes the status pill to the far end. weight only once, or the row
            // divides the slack between two spacers and the pill sits mid-row.
            Spacer(Modifier.weight(1f))
            ToolStatus(message)
        },
        content = {
            Column {
                // Read once into a local: finalOutput is a public property from
                // another module, so Kotlin cannot smart-cast it across the null
                // check even though it is a val on a data class.
                val finalOutput = message.finalOutput
                val liveOutput = message.output
                val body = when {
                    liveOutput.isNotEmpty() -> liveOutput.takeLast(MAX_LIVE_LINES).joinToString("\n")
                    finalOutput != null -> finalOutput
                    else -> null
                }
                if (body != null) {
                    MonoBlock(
                        text = body,
                        modifier = Modifier.padding(horizontal = 10.dp, top = 2.dp),
                    )
                }

                if (message.truncated) {
                    Text(
                        text = "çıktı kırpıldı — tam hâli context'e kısıtlı olarak gitti",
                        fontSize = 10.sp,
                        color = DroshWarning,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                message.durationMs?.let { duration ->
                    Text(
                        text = "${duration} ms",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DroshTextMuted,
                        modifier = Modifier.padding(horizontal = 10.dp, bottom = 8.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun ToolIcon(name: String) {
    val icon = when (name) {
        "shell" -> DroshIcons.Terminal
        "read_file" -> DroshIcons.Code
        "write_file" -> DroshIcons.Pencil
        "ask_user" -> DroshIcons.CircleHelp
        "update_todo" -> DroshIcons.ListChecks
        else -> DroshIcons.SquareTerminal
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = DroshTextMuted,
        modifier = Modifier.size(15.dp),
    )
}

@Composable
private fun ToolStatus(message: ChatMessage.ToolCall) {
    when (message.state) {
        ToolCallState.Running -> StatusPill("çalışıyor", DroshPrimary)
        ToolCallState.AwaitingApproval -> StatusPill("onay", DroshWarning)
        ToolCallState.Succeeded -> StatusPill("✓", DroshSuccess)
        ToolCallState.Failed -> StatusPill("hata", DroshError)
        ToolCallState.Cancelled -> StatusPill("iptal", DroshTextMuted)
        ToolCallState.Pending -> StatusPill("bekliyor", DroshTextMuted)
    }
}

/**
 * An approval, inline with the transcript.
 *
 * Not a dialog. The question is only answerable while the diff that prompted it
 * is visible, and a dialog hides the thing being agreed to — which is precisely
 * the mistake this flow exists to prevent.
 */
@Composable
private fun ApprovalBlock(
    message: ChatMessage.Approval,
    onAnswer: (String, ApprovalDecision) -> Unit,
) {
    val approval = message.approval
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceLow)
            .padding(12.dp),
    ) {
        Text(
            text = approval.title,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            color = DroshText,
        )

        approval.body?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = DroshTextSecondary,
            )
        }

        approval.diff?.let {
            Spacer(Modifier.height(8.dp))
            DiffBlock(text = it)
        }

        Spacer(Modifier.height(10.dp))

        val decided = message.decision
        if (decided != null) {
            Text(
                text = when (decided) {
                    is ApprovalDecision.Approve -> "onaylandı"
                    is ApprovalDecision.Reject -> "reddedildi: ${decided.reason}"
                    is ApprovalDecision.Answer -> "yanıt: ${decided.text}"
                },
                fontSize = 11.sp,
                color = DroshTextMuted,
            )
            return@Column
        }

        // Two decisions, because the pair matters: an offer is a choice between
        // what the tool proposed and something else. With no options the tool is
        // asking a question, and the free-text path needs its own affordance —
        // pretending a question can be answered with a button is how a model ends
        // up guessing at something only the user knows.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (approval.options.isEmpty()) {
                FlatButton(
                    text = "Geç",
                    onClick = { onAnswer(approval.id, ApprovalDecision.Reject("dismissed")) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = "Yanıtla",
                    onClick = { onAnswer(approval.id, ApprovalDecision.Approve) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                FlatButton(
                    text = approval.options.first(),
                    onClick = {
                        onAnswer(approval.id, ApprovalDecision.Answer(approval.options.first()))
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = approval.options.getOrElse(1) { "Onayla" },
                    onClick = {
                        onAnswer(approval.id, ApprovalDecision.Answer(approval.options.getOrElse(1) { "onay" }))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NoticeBlock(text: String, color: Color, description: String) {
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
private fun ChatEmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DroshGhostMark(size = 40.dp, tint = DroshOutline)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Ne yapmamı istiyorsun?",
            fontSize = 15.sp,
            color = DroshText,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Örnek: \"bu projedeki testleri koş ve hataları düzelt\"",
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
@Composable
private fun Composer(
    enabled: Boolean,
    stopVisible: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshSurface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TOUCH_TARGET)
                .clip(RoundedCornerShape(12.dp))
                .background(DroshSurfaceVariant)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    text = if (enabled) "Agent'a bir şey sor" else "çalışıyor…",
                    fontSize = 14.sp,
                    color = DroshTextMuted,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                singleLine = false,
                maxLines = 4,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = DroshText,
                ),
                cursorBrush = SolidColor(DroshPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.width(8.dp))

        if (stopVisible) {
            Box(
                modifier = Modifier
                    .size(TOUCH_TARGET)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DroshSurfaceHigh)
                    .clickable(onClick = onStop)
                    .semantics { contentDescription = "Durdur" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DroshIcons.Square,
                    contentDescription = null,
                    tint = DroshError,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(TOUCH_TARGET)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (text.isBlank()) DroshSurfaceHigh else DroshPrimary)
                    .clickable(enabled = text.isNotBlank() && enabled) {
                        val sent = text
                        text = ""
                        onSend(sent)
                    }
                    .semantics { contentDescription = "Gönder" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DroshIcons.Send,
                    contentDescription = null,
                    tint = if (text.isBlank()) DroshTextMuted else DroshOnPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Enough live lines to follow a build; the rest arrives as you scroll back. */
private const val MAX_LIVE_LINES = 60
