package dev.drosh.ui.agent

import android.content.Intent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
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
import dev.drosh.domain.agent.TerminalProjection
import dev.drosh.domain.agent.TokenUsage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.CollapsibleRow
import dev.drosh.ui.agent.components.DiffBlock
import dev.drosh.ui.agent.components.DroshAgentMark
import dev.drosh.ui.agent.components.FlatButton
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.MonoBlock
import dev.drosh.ui.agent.components.StatusPill
import dev.drosh.ui.agent.components.TOUCH_TARGET
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.rememberCoroutineScope
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.drosh.design.system.DroshTileSelected
import dev.drosh.domain.agent.LlmModel
import dev.drosh.ui.agent.components.AgentIconPill
import dev.drosh.ui.agent.components.AgentPill
import dev.drosh.ui.agent.components.AgentSelectableText
import dev.drosh.ui.agent.components.LocalAgentGlass
import dev.drosh.ui.agent.components.ProvideAgentGlass
import dev.drosh.ui.agent.components.agentGlassStyle
import dev.drosh.ui.agent.components.rememberAgentClipboard
import dev.drosh.ui.agent.components.rememberAgentGlass
import kotlinx.coroutines.launch
import timber.log.Timber

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
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val retrying by viewModel.retrying.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()

    val waiting = runState is AgentRunState.WaitingApproval
    val running = viewModel.isRunning

    // One glass state for the whole screen: the transcript is the blur source and
    // every floating control — pills, the overflow menu, the sheet — is an effect
    // reading it. Sharing it is what keeps this to one offscreen render.
    val glass = rememberAgentGlass()

    var menuOpen by rememberSaveable(chatId) { mutableStateOf(false) }
    var sheetOpen by rememberSaveable(chatId) { mutableStateOf(false) }
    var modelPickerOpen by rememberSaveable(chatId) { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val terminalLines = remember(messages) { TerminalProjection.project(messages) }
    val listState = rememberLazyListState()
    val clipboard = rememberAgentClipboard()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Follow the stream, but only when the user is already near the bottom.
    // Yanking the viewport while they are reading earlier output is worse than
    // missing a few tokens.
    LaunchedEffect(messages.size, waiting) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val atBottom = lastVisible >= messages.size - 3
        if (atBottom) listState.animateScrollToItem(messages.size.coerceAtLeast(0))
    }

    ProvideAgentGlass(glass) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(DroshBackground),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ChatPillRow(
                    modelLabel = providerState.selectedModelId.ifEmpty {
                        providerState.models.firstOrNull()?.id ?: "model seç"
                    },
                    running = running,
                    onBack = onBack,
                    onStop = viewModel::stop,
                    onOpenMenu = { menuOpen = true },
                    onOpenModelPicker = { modelPickerOpen = true },
                )

                providerState.error?.let { message ->
                    ErrorBanner(
                        message = message,
                        onDismiss = viewModel::dismissError,
                        onFix = onOpenSettings,
                    )
                }

                // A retry the loop is working through, not an error. Showing it as one
                // would tell the user something failed when the system is doing exactly
                // what it should.
                retrying?.let { notice -> RetryBanner(notice = notice) }

                // A run that ended badly, with the one action that helps.
                failure?.let { message ->
                    FailureCard(
                        message = message,
                        onRetry = viewModel::retry,
                        onDismiss = viewModel::dismissFailure,
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .hazeSource(glass.state),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            onAnswer = viewModel::answer,
                            onCopy = clipboard,
                            onShare = { picked ->
                                scope.launch { shareText(context, picked) }
                            },
                        )
                    }

                    if (messages.isEmpty()) {
                        item(key = "hint") { ChatEmptyHint() }
                    }
                }

                if (!usage.isEmpty) {
                    UsageStrip(usage = usage)
                }

                AgentComposer(
                    enabled = !running,
                    stopVisible = running,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                )
            }

            // ── overlays ──

            if (modelPickerOpen) {
                ModelPickerSheet(
                    models = providerState.models,
                    selectedId = providerState.selectedModelId,
                    onSelect = { id ->
                        viewModel.selectModel(id)
                        modelPickerOpen = false
                    },
                    onDismiss = { modelPickerOpen = false },
                )
            }

            if (menuOpen) {
                AgentChatMenu(
                    chatName = chat?.name ?: "Sohbet",
                    onRename = {
                        menuOpen = false
                        renaming = true
                    },
                    onShowTerminalHistory = {
                        menuOpen = false
                        sheetOpen = true
                    },
                    onDelete = {
                        menuOpen = false
                        deleting = true
                    },
                    onDismiss = { menuOpen = false },
                )
            }

            if (sheetOpen) {
                TerminalHistorySheet(
                    lines = terminalLines,
                    onDismiss = { sheetOpen = false },
                )
            }

            if (renaming) {
                RenameChatDialog(
                    initial = chat?.name.orEmpty(),
                    onDismiss = { renaming = false },
                    onConfirm = { name ->
                        renaming = false
                        viewModel.rename(name)
                    },
                )
            }

            if (deleting) {
                DeleteChatDialog(
                    name = chat?.name ?: "Sohbet",
                    onDismiss = { deleting = false },
                    onConfirm = {
                        deleting = false
                        viewModel.delete(onDeleted = onBack)
                    },
                )
            }
        }
    }
}

/**
 * The chat's control row.
 *
 * ## There is no top bar
 *
 * This replaces one. A bar with a title, a back arrow, a settings gear and a stop
 * button gave the screen a permanent 56dp of chrome that was mostly empty, put the
 * chat's name — which the user named once and almost never reads — at 14sp in the
 * most prominent position available, and offered settings that belong to the app
 * rather than to the conversation.
 *
 * What is left is what the screen actually changes: where you are, which model is
 * answering, and what else this conversation can do. Three pills on one row.
 *
 * ## The model sits where the title was
 *
 * The model is the thing a user actually varies between chats, and it is worth the
 * most prominent slot on this row. The chat's name is per-conversation and static,
 * so it moved to the overflow menu where it is still one tap away and no longer
 * spending the best position on screen.
 */
@Composable
private fun ChatPillRow(
    modelLabel: String,
    running: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenModelPicker: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentIconPill(
            icon = DroshIcons.ArrowLeft,
            contentDescription = "Geri",
            onClick = onBack,
            tint = DroshTextSecondary,
        )

        // Weighted and truncating, so an OpenRouter model id like
        // "anthropic/claude-sonnet-4-20250514" takes the space it needs and yields
        // it back rather than pushing the overflow button off the row.
        AgentPill(
            label = modelLabel,
            onClick = onOpenModelPicker,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 6.dp, end = 6.dp),
            contentDescription = "Model: $modelLabel",
            trailingChevron = true,
        )

        // Stop replaces the overflow while a run is going, so the thing that ends
        // the run is where the eye already is. Both at once crowds a 390dp row, and
        // a stop button is more urgent than a menu.
        if (running) {
            AgentIconPill(
                icon = DroshIcons.Square,
                contentDescription = "Durdur",
                onClick = onStop,
                tint = DroshError,
            )
        } else {
            AgentIconPill(
                icon = DroshIcons.EllipsisVertical,
                contentDescription = "Sohbet menüsü",
                onClick = onOpenMenu,
                tint = DroshTextSecondary,
            )
        }
    }
}

/**
 * The model picker.
 *
 * A sheet rather than a dropdown: the list is as tall as the provider's catalogue,
 * and a menu that grows past half the screen stops being a menu. The selection is
 * marked with a tick as well as a background, so it does not depend on colour.
 */
@Composable
private fun ModelPickerSheet(
    models: List<LlmModel>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = "Kapat" },
        )

        val glass = LocalAgentGlass.current
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .then(
                    if (glass == null) {
                        Modifier.background(DroshSurface)
                    } else {
                        Modifier
                            .hazeEffect(glass.state, agentGlassStyle())
                            .background(DroshSurface.copy(alpha = 0.9f))
                    }
                )
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DroshText.copy(alpha = 0.2f)),
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = "Model",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = DroshText,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(models, key = { it.id }) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TOUCH_TARGET)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (model.id == selectedId) {
                                    DroshTileSelected
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onSelect(model.id) }
                            .semantics {
                                contentDescription = model.id +
                                    if (model.id == selectedId) ", seçili" else ""
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.label ?: model.id,
                                fontSize = 14.sp,
                                color = if (model.id == selectedId) DroshPrimary else DroshText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = model.id,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = DroshTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (model.id == selectedId) {
                            Icon(
                                imageVector = DroshIcons.Check,
                                contentDescription = null,
                                tint = DroshPrimary,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The composer.
 *
 * One surface: the text field above, the controls in a row inside its lower edge.
 * A separate send button outside the field was tried and read as two objects —
 * on a 390dp screen the field ends up about 300dp wide and the button claims 44dp
 * of prime right-thumb space for one action.
 *
 * The send control is the only filled thing down here, and it fills only when there
 * is something to send. A permanently filled button is a claim about what the next
 * tap will do, and it should not make that claim when there is nothing typed.
 */
@Composable
private fun AgentComposer(
    enabled: Boolean,
    stopVisible: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank() && enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DroshSurfaceVariant)
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Column {
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = if (enabled) "Agent'a bir şey sor…" else "çalışıyor…",
                            fontSize = 14.5.sp,
                            color = DroshTextMuted,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = enabled,
                        singleLine = false,
                        maxLines = 6,
                        textStyle = TextStyle(fontSize = 14.5.sp, color = DroshText),
                        cursorBrush = SolidColor(DroshPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ComposerMiniAction(
                        icon = DroshIcons.Search,
                        contentDescription = "Araçlar",
                        onClick = { /* Tool affordance lands with the tool list. */ },
                    )
                    ComposerMiniAction(
                        icon = DroshIcons.ListChecks,
                        contentDescription = "Görevler",
                        onClick = { /* Todo affordance lands with the TodoCard. */ },
                    )

                    Spacer(Modifier.weight(1f))

                    if (stopVisible) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(DroshError)
                                .clickable(onClick = onStop)
                                .semantics { contentDescription = "Durdur" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = DroshIcons.Square,
                                contentDescription = null,
                                tint = DroshOnPrimary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(if (canSend) DroshPrimary else DroshSurfaceHigh)
                                .clickable(enabled = canSend) {
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
                                tint = if (canSend) DroshOnPrimary else DroshTextMuted,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A small control inside the composer.
 *
 * 30dp of visible circle in a 44dp box. It sits inside a surface that already
 * says "tap here", so it does not need to look like an independent button — but the
 * hit area is still 44dp, because the eye being convinced does not make a 30dp
 * circle easier to hit.
 */
@Composable
private fun ComposerMiniAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(DroshSurfaceHigh.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DroshTextSecondary,
                modifier = Modifier.size(15.dp),
            )
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
private fun MessageRow(
    message: ChatMessage,
    onAnswer: (String, ApprovalDecision) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    when (message) {
        is ChatMessage.User -> UserBubble(message, onCopy = onCopy, onShare = onShare)

        is ChatMessage.Assistant -> AssistantText(message, onCopy = onCopy, onShare = onShare)

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
private fun UserBubble(
    message: ChatMessage.User,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
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
            AgentSelectableText(
                text = message.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = DroshText,
                onCopy = onCopy,
                onShare = onShare,
            )
        }
    }
}

@Composable
private fun AssistantText(
    message: ChatMessage.Assistant,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "drosh",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = DroshPrimary,
        )
        Spacer(Modifier.height(2.dp))
        AgentSelectableText(
            text = message.text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = DroshText,
            onCopy = onCopy,
            onShare = onShare,
            // Announced as it grows, so a screen-reader user hears the answer arrive
            // rather than having to poll.
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
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, end = 10.dp),
                    )
                }

                if (message.truncated) {
                    Text(
                        text = "çıktı kırpıldı — tam hâli context'e kısıtlı olarak gitti",
                        fontSize = 10.sp,
                        color = DroshWarning,
                        modifier = Modifier.padding(start = 10.dp, top = 4.dp, end = 10.dp),
                    )
                }

                message.durationMs?.let { duration ->
                    Text(
                        text = "${duration} ms",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DroshTextMuted,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
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
        "ask_user" -> DroshIcons.Info
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
        DroshAgentMark(size = 40.dp, tint = DroshOutline)
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
/**
 * Hands text to the system share sheet.
 *
 * Suspends rather than fires and forgets because `startActivity` from a
 * non-activity context has to be a coroutine with a real scope — a fire-and-forget
 * share throws `ActivityNotFoundException` on some devices with no chooser
 * installed, and swallowing that would leave the user tapping a dead menu item.
 */
private suspend fun shareText(context: android.content.Context, text: String) {
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

/** Enough live lines to follow a build; the rest arrives as you scroll back. */
private const val MAX_LIVE_LINES = 60

// ── run-level banners ────────────────────────────────────────────────────

/**
 * A provider failure the loop is retrying.
 *
 * Distinct from [FailureCard] on purpose: nothing has failed yet, the system is
 * waiting and trying again. Rendering it as an error would teach the user that
 * every rate limit is a dead end.
 */
@Composable
private fun RetryBanner(notice: AgentChatViewModel.RetryNotice) {
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
            text = "Bağlantı hatası, ${notice.attempt}/${notice.maxAttempts} denendi — tekrar deneniyor…",
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
private fun FailureCard(
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
            text = "Çalıştırma başarısız",
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
                text = "Tekrar dene",
                onClick = onRetry,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                icon = DroshIcons.X,
                contentDescription = "Kapat",
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
private fun UsageStrip(usage: TokenUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${usage.output ?: 0} çıktı · ${usage.input ?: 0} girdi token",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = DroshTextMuted,
        )
    }
}
