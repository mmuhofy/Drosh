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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshWarning
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.AgentTodo
import dev.drosh.domain.agent.AgentTodoStatus
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.TerminalProjection
import dev.drosh.domain.agent.TokenUsage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.ui.settings.SettingsCheck
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.CollapsibleRow
import dev.drosh.ui.agent.components.DiffBlock
import dev.drosh.ui.agent.components.FlatButton
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.StatusPill
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import dev.drosh.design.system.DroshTile
import dev.drosh.design.system.DroshTilePressed
import dev.drosh.design.system.DroshTrack
import dev.drosh.design.system.DroshTileSelected
import dev.drosh.design.system.LocalFontSet
import dev.drosh.domain.agent.LlmModel
import dev.drosh.ui.agent.components.AgentIconPill
import dev.drosh.ui.agent.components.AgentPill
import dev.drosh.ui.agent.components.AgentSelectableText
import dev.drosh.ui.agent.components.DroshStatusBarVisible
import dev.drosh.ui.agent.components.LocalAgentGlass
import dev.drosh.ui.agent.components.glassRow
import dev.drosh.ui.agent.components.glassSurface
import dev.drosh.ui.agent.components.ProvideAgentGlass
import dev.drosh.ui.agent.components.rememberAgentClipboard
import dev.drosh.ui.agent.components.rememberAgentGlass
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.drosh.ui.agent.components.MOTION_MS
import dev.drosh.ui.agent.components.MarkdownText
import androidx.compose.ui.res.stringResource
import dev.drosh.ui.R
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.delay

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
    // The terminal hides the status bar outright and never restores it, so
    // arriving from there would inherit a hidden one.
    DroshStatusBarVisible()

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
    // Observed as state, not read as a getter: a plain read happens once during
    // composition, and the run ending changes nothing else, so the stop button
    // would stay on screen under a finished transcript.
    val running by viewModel.running.collectAsStateWithLifecycle()

    // One glass state for the whole screen: the transcript is the blur source and
    // every floating control — pills, the overflow menu, the sheet — is an effect
    // reading it. Sharing it is what keeps this to one offscreen render.
    val glass = rememberAgentGlass()

    var sheetOpen by rememberSaveable(chatId) { mutableStateOf(false) }
    // The composer's text, held here so a bubble's "Edit" can put a message
    // the user already sent back into it. The composer used to own its own
    // draft, which meant nothing outside it could hand it anything.
    var composerDraft by rememberSaveable(chatId) { mutableStateOf("") }
    // The tool whose full output is on the sheet, or null. A detail sheet rather
    // than an inline expansion: an expanded git diff pushes the conversation off
    // the screen, and the row the user was reading moves out from under their
    // thumb.
    var inspecting by remember { mutableStateOf<ChatMessage.ToolCall?>(null) }
    // Bumped when the keyboard should come up after the draft is filled.
    var composerFocusRequest by remember { mutableIntStateOf(0) }
    var modelPickerOpen by rememberSaveable(chatId) { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val terminalLines = remember(messages) { TerminalProjection.project(messages) }
    val listState = rememberLazyListState()

    // Recomputed only when the transcript changes; grouping walks the whole list.
    val groupedTranscript = remember(messages) { groupTranscript(messages) }
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
                        providerState.models.firstOrNull()?.id ?: stringResource(R.string.agent_select_model)
                    },
                    onBack = onBack,
                    onOpenModelPicker = { modelPickerOpen = true },
                    menuItems = listOf(
                        DroshMenuItem(
                            label = stringResource(R.string.agent_rename),
                            id = "rename",
                            icon = DroshIcons.Pencil,
                        ),
                        DroshMenuItem(
                            label = stringResource(R.string.agent_terminal_history),
                            id = "history",
                            icon = DroshIcons.Terminal,
                        ),
                        DroshMenuItem(
                            label = stringResource(R.string.agent_delete_session),
                            id = "delete",
                            icon = DroshIcons.Trash2,
                        ),
                    ),
                    onMenuItemClick = { item ->
                        when (item.id) {
                            "rename" -> renaming = true
                            "history" -> sheetOpen = true
                            "delete" -> deleting = true
                        }
                    },
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
                    items(groupedTranscript, key = { rowKey(it) }) { row ->
                        when (row) {
                            is TranscriptRow.Single -> MessageRow(
                                message = row.message,
                                onAnswer = viewModel::answer,
                                onCopy = clipboard,
                                onShare = { picked ->
                                    scope.launch { shareText(context, picked) }
                                },
                                onRetry = viewModel::retry,
                                onEdit = { draft ->
                                    composerDraft = draft
                                    composerFocusRequest += 1
                                },
                                onInspectTool = { tool -> inspecting = tool },
                            )

                            is TranscriptRow.ToolGroup -> Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                row.tools.forEach { tool ->
                                    ToolCallRow(tool)
                                }
                            }
                        }
                    }

                    if (messages.isEmpty()) {
                        item(key = "hint") { ChatEmptyHint() }
                    }
                }

                if (!usage.isEmpty) {
                    UsageStrip(usage = usage)
                }

                AgentComposer(
                    running = running,
                    draft = composerDraft,
                    onDraftChange = { composerDraft = it },
                    onSend = { text ->
                        composerDraft = ""
                        viewModel.send(text)
                    },
                    onStop = viewModel::stop,
                    focusRequest = composerFocusRequest,
                )
            }

            // ── overlays ──

            if (modelPickerOpen) {
                ModelPickerSheet(
                    models = providerState.models,
                    selectedId = providerState.selectedModelId,
                    loading = providerState.loadingModels,
                    onSelect = { id ->
                        viewModel.selectModel(id)
                        modelPickerOpen = false
                    },
                    onOpenSettings = onOpenSettings,
                    onDismiss = { modelPickerOpen = false },
                )
            }



            inspecting?.let { tool ->
                AgentToolDetailSheet(
                    visible = true,
                    title = toolVerbs(tool.name).first,
                    subtitle = tool.summary,
                    body = tool.finalOutput ?: tool.output.joinToString("\n"),
                    state = tool.state,
                    onDismiss = { inspecting = null },
                    onCopy = clipboard,
                )
            }

            if (sheetOpen) {
                TerminalHistorySheet(
                    lines = terminalLines,
                    sheetVisible = sheetOpen,
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
                    name = chat?.name ?: stringResource(R.string.agent_chat),
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
    onBack: () -> Unit,
    onOpenModelPicker: () -> Unit,
    menuItems: List<DroshMenuItem>,
    onMenuItemClick: (DroshMenuItem) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentIconPill(
            icon = DroshIcons.ArrowLeft,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
            tint = DroshTextSecondary,
        )

        // Fills the space between the back arrow and the overflow button, label held
        // at the left.
        //
        // `weight(1f, fill = false)` was the bug: free to be narrower than its share,
        // so a short model id left the overflow button sitting beside it instead of at
        // the edge. Filling the share pins the button right whatever the label says,
        // and a long OpenRouter id truncates rather than pushing the button off.
        AgentPill(
            label = modelLabel,
            onClick = onOpenModelPicker,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp, end = 6.dp),
            contentDescription = stringResource(R.string.agent_model_cd, modelLabel),
            trailingChevron = true,
            contentAlignment = Alignment.CenterStart,
        )

        // Always the overflow button. It used to become a stop button while a run was
        // going, which put the urgent action where the eye already was — and made the
        // one control whose position the user had just learned move without warning.
        // Stop lives in the composer instead, next to the text that is generating.
        // Two controls in one row that swap identity is a bar you have to re-read every
        // time a run starts.
        //
        // The menu is the drawer's own: the same DroshDropdownMenu the terminal's
        // overflow opens, anchored to the pill it belongs to. It used to be a
        // full-screen scrim with a surface of its own, which made one screen carry
        // two dropdowns.
        Box {
            AgentIconPill(
                icon = DroshIcons.EllipsisVertical,
                contentDescription = stringResource(R.string.agent_chat_menu_cd),
                onClick = { menuOpen = true },
                tint = DroshTextSecondary,
            )
            DroshDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                items = menuItems,
                onItemClick = onMenuItemClick,
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
/**
 * The model picker, as the same sheet the language picker is.
 *
 * One bottom-sheet shape for the whole app: the same grabber, the same
 * capsule search, the same row with a circle of the model's initials and
 * the same check. The model sheet used to be its own shape — a scrim, its
 * own surface, its own row — which made two pickers on two screens look
 * like two apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    models: List<LlmModel>,
    selectedId: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val matches = remember(query, models) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) models
        else models.filter { model ->
            model.id.lowercase().contains(q) ||
                (model.label?.lowercase()?.contains(q) == true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DroshTile,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(DroshTrack),
            )
        },
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.agent_model),
                color = DroshText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp),
            )

            // The same capsule the language sheet searches through.
            Row(
                modifier = Modifier
                    .padding(horizontal = 22.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(DroshTilePressed)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    imageVector = DroshIcons.Search,
                    contentDescription = null,
                    tint = DroshTextMuted,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_search_models),
                            color = DroshTextMuted,
                            fontSize = 14.5.sp,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = DroshText, fontSize = 14.5.sp),
                        cursorBrush = SolidColor(DroshPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            when {
                loading -> {
                    Text(
                        text = stringResource(R.string.agent_models_loading),
                        color = DroshTextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(22.dp),
                    )
                }
                matches.isEmpty() -> {
                    // The way out of an empty picker: the keys live in settings,
                    // and a sheet that can only be dismissed is a dead end.
                    Text(
                        text = stringResource(R.string.agent_no_models),
                        color = DroshTextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.agent_open_settings), color = DroshPrimary)
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(matches, key = { it.id }) { model ->
                            ModelOptionRow(
                                model = model,
                                selected = model.id == selectedId,
                                onClick = { onSelect(model.id) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ModelOptionRow(
    model: LlmModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) DroshTileSelected else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The model's initials, in the language sheet's own circle.
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (selected) DroshPrimary else DroshTilePressed),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = modelInitials(model),
                color = if (selected) DroshTile else DroshTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.label ?: model.id,
                color = if (selected) DroshPrimary else DroshText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.id,
                color = DroshTextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) SettingsCheck()
    }
}

/** Two letters from the model's label, or its id when there is no label. */
private fun modelInitials(model: LlmModel): String {
    val words = (model.label ?: model.id).split(' ', '-', '_', ':', '/').filter { it.isNotBlank() }
    val initials = words.take(2).map { it.first().uppercase() }.joinToString("")
    return initials.ifEmpty { model.id.take(2).uppercase() }
}

@Composable
private fun AgentComposer(
    running: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    /** Incremented by whoever wants the keyboard raised after filling the draft. */
    focusRequest: Int = 0,
) {
    val sendCd = stringResource(R.string.agent_send)
    val stopCd = stringResource(R.string.agent_stop)
    // The draft is the screen's, not the composer's: a bubble's "Edit" puts a
    // message the user already sent back into the box, and a composer that owns
    // its own text cannot be handed anything.
    val text = draft
    val enabled = !running
    val canSend = text.isNotBlank() && enabled

    // A focus requester the screen drives. Editing a sent message means the
    // keyboard should come up — the user is mid-thought, and a filled box with
    // the keyboard down is a box they have to tap before they can type in.
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // The same liquid glass as the pills, at a larger radius. One surface for
        // the whole app's controls, so the composer and the pill row read as the
        // same material rather than as two things that happen to be on the same
        // screen.
        //
        // It floats, and it is the one surface on this screen that does: a real
        // shadow beneath it, so it reads as sitting over the transcript rather
        // than as a bar the transcript stops at. The border is drawn here and
        // nowhere else outside the pills — an outline is what says "this is a
        // control", and the composer is the one card-sized control on screen.
        val glass = LocalAgentGlass.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
                .glassSurface(
                    glass,
                    pressed = false,
                    shape = RoundedCornerShape(20.dp),
                    edged = true,
                )
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Column {
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = if (enabled) {
                                stringResource(R.string.agent_composer_hint)
                            } else {
                                stringResource(R.string.agent_composer_busy)
                            },
                            fontSize = 14.5.sp,
                            color = DroshTextMuted,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onDraftChange,
                        enabled = enabled,
                        singleLine = false,
                        maxLines = 6,
                        textStyle = TextStyle(fontSize = 14.5.sp, color = DroshText),
                        cursorBrush = SolidColor(DroshPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 36dp drawn inside a 48dp box, to sit with the 40dp pills above.
                    // At 30dp the primary action on the screen was smaller than the
                    // icons in the row above it, which read as a deliberate demotion.
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        // Stop replaces send, here rather than in the top row: it acts
                        // on the thing happening right now, so it belongs beside the
                        // text that is generating.
                        if (running) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(DroshError)
                                    .clickable(onClick = onStop)
                                    .semantics { contentDescription = stopCd },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = DroshIcons.Square,
                                    contentDescription = null,
                                    tint = DroshOnPrimary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (canSend) DroshPrimary else DroshSurfaceHigh)
                                    .clickable(enabled = canSend) {
                                        val sent = text
                                        text = ""
                                        onSend(sent)
                                    }
                                    .semantics { contentDescription = sendCd },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = DroshIcons.Send,
                                    contentDescription = null,
                                    tint = if (canSend) DroshOnPrimary else DroshTextMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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
        FlatButton(
            text = stringResource(R.string.agent_fix),
            onClick = onFix,
            modifier = Modifier.width(72.dp),
        )
        IconAction(
            icon = DroshIcons.X,
            contentDescription = stringResource(R.string.action_close),
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
private fun UserBubble(
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
private fun AssistantText(
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
private fun AssistantAction(
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
private fun ThinkingDots(modifier: Modifier = Modifier) {
    val thinkingCd = stringResource(R.string.agent_thinking_cd)
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }

    val transition = rememberInfiniteTransition(label = "thinkingSweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "thinkingSweepProgress",
    )

    Text(
        text = stringResource(R.string.agent_thinking_secs, elapsed),
        fontSize = 11.5.sp,
        fontFamily = LocalFontSet.current.mono,
        color = DroshPrimary,
        modifier = modifier
            .semantics { contentDescription = thinkingCd }
            .sweepHighlight(progress),
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
private fun ReasoningBlock(message: ChatMessage.Reasoning) {
    val stopThinkingCd = stringResource(R.string.agent_stop_thinking)
    var open by rememberSaveable(message.id) { mutableStateOf(false) }

    // Elapsed, counted rather than measured, for the same reason as the tool rows.
    var elapsed by remember(message.id) { mutableStateOf(0) }
    val running = message.text.isEmpty() || message.text.endsWith("…")
    LaunchedEffect(message.id, running) {
        if (running) {
            elapsed = 0
            while (true) {
                delay(1000)
                elapsed++
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "reasoningSweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "reasoningSweepProgress",
    )

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
                modifier = if (running) Modifier.sweepHighlight(progress) else Modifier,
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
 * One tool call: a line, not a card.
 *
 * ## Why there is no box
 *
 * These were `DroshSurfaceLow` rounded containers, one per call, stacked down the
 * transcript. On a turn that ran five tools that is five grey slabs between the user
 * and the answer, and the conversation stopped reading as a conversation — it read
 * as a log with prose in it.
 *
 * A card says "this is a separate thing". A tool call is not a separate thing; it is
 * a sentence the agent did, sitting in the middle of its reply. So it is a line: a
 * status glyph, the tool name, the command or path, and the outcome, on the same
 * baseline as the text around it. Nothing is boxed.
 *
 * ## What replaces the card as an anchor
 *
 * The expanded body hangs off a 2dp vertical rule at 20dp, indented from the glyph.
 * That rule is what groups the output with the line that produced it — the job the
 * card's background was doing, without the second surface.
 *
 * ## A running call opens itself
 *
 * Whether a command is working is the most common reason to watch this screen, so a
 * running call expands without a tap. Waiting to see whether `npm install` is alive
 * is the thing people are actually here for.
 */
/**
 * One tool call: a single line.
 *
 * ## The verb is the status
 *
 * The tool name is replaced by a verb, and the verb's tense is the status. While
 * running it is present tense — `Running`, `Reading`, `Writing` — and when the call
 * finishes it becomes past tense: `Ran`, `Read`, `Wrote`. The user does not have to
 * look for a separate indicator to learn whether something is still going; the word
 * itself says so, the way "is running" and "ran" do in a sentence.
 *
 * Colour carries the outcome on top of that: a failure is red whether or not the
 * reader parses the verb.
 *
 * ## One line, no card
 *
 * Icon, verb, the command or path, the elapsed time while running, and a chevron.
 * Nothing is boxed. The output, when the row is opened, hangs off a 1dp hairline —
 * the grouping cue a card's background used to provide, at a weight that reads as a
 * rule rather than as a container.
 *
 * ## The sweep
 *
 * While running, a highlight travels left to right across the verb. Same reasoning
 * as the thinking label: a pulse says "happening", a sweep says "happening and
 * moving". It stops the instant the call finishes, which is the signal that the
 * result has arrived.
 */
@Composable
private fun ToolCallRow(
    message: ChatMessage.ToolCall,
    onInspect: () -> Unit,
) {
    val copyCd = stringResource(R.string.agent_copy_output_cd)
    val running = message.state == ToolCallState.Running

    // The row is a summary and nothing more: tapping it opens the detail sheet
    // rather than expanding a wall of output into the middle of the transcript.
    // An expanded `git diff` on a large change pushes the conversation off the
    // screen and moves the row the user was reading out from under their thumb.

    // Elapsed seconds, counted rather than measured. A timer that reads a start
    // timestamp drifts with frame timing; incrementing once a second is exact to the
    // second, which is the only resolution this is shown at.
    var elapsed by remember(message.id) { mutableStateOf(0) }
    LaunchedEffect(message.id, running) {
        if (running) {
            elapsed = 0
            while (true) {
                delay(1000)
                elapsed++
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "toolSweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "toolSweepProgress",
    )


    val clipboard = rememberAgentClipboard()
    val (present, past) = toolVerbs(message.name)
    val verb = if (running) present else past

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onInspect)
                .padding(vertical = 1.dp)
                .semantics { contentDescription = toolRowDescription(message) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(message.name)
            Spacer(Modifier.width(7.dp))

            // Verb and summary are one string, not two Texts.
            //
            // Two Texts cannot be selected together: a long press lands on whichever
            // one is under the finger and the selection stops at its edge, so
            // selecting "npm test" out of "Ran npm test" means dragging off the verb
            // and into the summary. One Text is one selectable run, and the styled
            // span is what keeps the two halves looking different.
            SelectionContainer {
                Text(
                    text = toolRowText(
                        verb = verb,
                        summary = message.summary,
                        verbColor = verbColor(message.state),
                        summaryColor = DroshTextSecondary,
                        // While running, the gradient sweep paints the verb rather
                        // than the whole row: the summary is already the dim half and
                        // sweeping it too would make the command harder to read than
                        // the word saying it is running.
                        sweeping = running,
                        progress = progress,
                    ),
                    fontSize = 12.sp,
                    fontFamily = LocalFontSet.current.mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            Spacer(Modifier.weight(1f))

            // Only while running. Once the call is done the duration is a fact about
            // the past and belongs with the output, not in the row.
            if (running) {
                Text(
                    text = "${elapsed}s",
                    fontSize = 11.sp,
                    fontFamily = LocalFontSet.current.mono,
                    color = DroshPrimary,
                )
            }

            Icon(
                imageVector = DroshIcons.ChevronRight,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier
                    .size(12.dp),
            )
        }

        // The checklist is the tool's *result* rather than its output text, so it
        // stays on the transcript: it is the one thing a tool produces that is
        // itself a thing to look at. Everything else - the raw output, the copy
        // action, the clipped marker - is on the detail sheet now.
        if (message.todos.isNotEmpty()) {
            TodoCard(
                todos = message.todos,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Seconds, not milliseconds.
 *
 * "18420 ms" is a number with no scale attached; the reader has to divide it by a
 * thousand in their head to learn that it was slow. Sub-second runs keep one decimal
 * because 0.2s and 1s are different experiences.
 */
private fun formatToolDuration(ms: Long): String =
    if (ms < 1000) "${(ms / 10.0).let { String.format("%.1f", it) }} sn" else "${ms / 1000} sn"

/** Spoken form of a row, for a screen reader rather than the eye. */
private fun toolRowDescription(message: ChatMessage.ToolCall): String = buildString {
    append(message.name)
    if (message.summary.isNotEmpty()) append(", ${message.summary}")
    append(
        when (message.state) {
            ToolCallState.Running -> ", çalışıyor"
            ToolCallState.Succeeded -> ", başarılı"
            ToolCallState.Failed -> ", başarısız"
            ToolCallState.Cancelled -> ", iptal edildi"
            else -> ""
        }
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
private fun ApprovalBlock(
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
private fun TodoCard(
    todos: List<AgentTodo>,
    modifier: Modifier = Modifier,
) {
    val done = todos.count { it.status == AgentTodoStatus.COMPLETED }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$done/${todos.size}",
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
private fun TodoRow(todo: AgentTodo) {
    val completed = todo.status == AgentTodoStatus.COMPLETED
    val active = todo.status == AgentTodoStatus.IN_PROGRESS

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = todo.title + when (todo.status) {
                    AgentTodoStatus.COMPLETED -> ", tamamlandı"
                    AgentTodoStatus.IN_PROGRESS -> ", sürüyor"
                    AgentTodoStatus.PENDING -> ", bekliyor"
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
private const val ASK_USER_TOOL = "ask_user"

/**
 * How a question gets answered.
 *
 * Options as rows rather than a row of buttons: a list of choices has no natural
 * count, and two columns silently drop the third. Rows also fit the length of an
 * option, which is where these fail first — buttons truncate their labels and a
 * truncated choice is a choice the user cannot read.
 */
@Composable
private fun QuestionControls(
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
                label = "Yanıtla",
                onClick = { onAnswer(ApprovalDecision.Answer(reply.trim())) },
                primary = true,
                enabled = canSend,
                contentDescription = stringResource(R.string.agent_send_reply_cd),
            )
        }
    }
}

@Composable
private fun ChoiceRow(option: String, onClick: () -> Unit) {
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
private fun DismissRow(onClick: () -> Unit) {
    Text(
        text = "Atla",
        fontSize = 13.sp,
        color = DroshTextMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "Soruyu atla" },
    )
}

/** What the user chose, once they have. */
@Composable
private fun DecisionRow(decision: ApprovalDecision) {
    val (label, tint) = when (decision) {
        is ApprovalDecision.Approve -> "onaylandı" to DroshSuccess
        is ApprovalDecision.Reject -> "reddedildi" to DroshError
        is ApprovalDecision.Answer -> "yanıtlandı" to DroshSuccess
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

                is ApprovalDecision.Approve -> Unit
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
private fun Modifier.sweepHighlight(progress: Float): Modifier = this.drawWithContent {
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

/**
 * The verb for a tool, in present and past tense.
 *
 * Present while the call runs, past once it finishes — the tense is the status, so
 * the row does not need a separate indicator to say whether something is still going.
 *
 * `write_file` is `Wrote` rather than `Edited` because the model creates as often as
 * it modifies, and a verb that only fits one of the two would be wrong half the time.
 */
private fun toolVerbs(name: String): Pair<String, String> = when (name) {
    "shell" -> "Running" to "Ran"
    "read_file" -> "Reading" to "Read"
    "write_file" -> "Writing" to "Wrote"
    "grep" -> "Searching" to "Searched"
    "move_file" -> "Moving" to "Moved"
    "ask_user" -> "Asking" to "Asked"
    "update_todo" -> "Updating" to "Updated"
    "web_search" -> "Searching" to "Searched"
    else -> "Working" to "Done"
}

/**
 * The verb's colour, which carries the outcome the tense does not.
 *
 * A failure is red whether or not the reader parses "Ran" — the verb says it
 * finished, the colour says how.
 */
@Composable
private fun verbColor(state: ToolCallState): Color = when (state) {
    ToolCallState.Running -> DroshPrimary
    ToolCallState.Succeeded -> DroshText
    ToolCallState.Failed -> DroshError
    ToolCallState.Cancelled -> DroshTextMuted
    ToolCallState.AwaitingApproval -> DroshWarning
    ToolCallState.Pending -> DroshTextMuted
}

/**
 * Consecutive tool calls, grouped for layout.
 *
 * A turn that runs five tools is not five things the user reads between; it is one
 * block. With 18dp between every message those five rows were as tall as five
 * paragraphs of prose, and the list stopped reading as a list.
 *
 * So runs of tool calls collapse into one list item with 2dp between rows, and
 * everything else keeps the 18dp that separates one thought from the next. The
 * grouping is computed here rather than in the transcript because it is a property
 * of *what is on screen*, not of what the agent did.
 */
/**
 * A stable key for a grouped row.
 *
 * A group is keyed by its first member rather than its size: two adjacent runs
 * that happen to hold the same number of tools are still different rows, and a
 * key built from the count alone would make Compose reuse the wrong one.
 */
private fun rowKey(row: TranscriptRow): String = when (row) {
    is TranscriptRow.Single -> "m_${row.message.id}"
    is TranscriptRow.ToolGroup -> "g_${row.tools.first().id}"
}

private sealed interface TranscriptRow {
    /** One or more adjacent tool calls, rendered as a tight group. */
    data class ToolGroup(val tools: List<ChatMessage.ToolCall>) : TranscriptRow

    /** Anything else: user, assistant, reasoning, approval, notice. */
    data class Single(val message: ChatMessage) : TranscriptRow
}

private fun groupTranscript(messages: List<ChatMessage>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    var pendingTools = mutableListOf<ChatMessage.ToolCall>()

    fun flushTools() {
        if (pendingTools.isEmpty()) return
        rows += TranscriptRow.ToolGroup(pendingTools.toList())
        pendingTools = mutableListOf()
    }

    messages.forEach { message ->
        if (message is ChatMessage.ToolCall) {
            pendingTools += message
        } else {
            flushTools()
            rows += TranscriptRow.Single(message)
        }
    }
    flushTools()
    return rows
}

/**
 * The verb and the summary as one selectable string.
 *
 * One `Text` rather than two, so a long press selects across both halves instead of
 * stopping at whichever one the finger landed in. The visual difference between
 * "Ran" and the command is carried by colour and weight on spans, so nothing is lost
 * by merging them.
 *
 * The gap is a non-breaking space rather than layout padding because padding is not
 * part of the selected text — a selection that ended at the verb would exclude the
 * space and paste "Ran" where "Ran npm test" was meant.
 */
@Composable
private fun toolRowText(
    verb: String,
    summary: String,
    verbColor: Color,
    summaryColor: Color,
    sweeping: Boolean,
    progress: Float,
): AnnotatedString = buildAnnotatedString {
    // The sweep is a gradient across the verb's own width, so it needs the verb's
    // length in characters to place its band — which is why this cannot stay a
    // plain colour while running. A `SpanStyle` takes a brush *or* a colour, not
    // both, so the two states are separate rather than one style with a null field.
    if (sweeping) {
        withStyle(
            SpanStyle(
                brush = sweepingBrush(progress, verb.length),
                fontWeight = FontWeight.Medium,
            ),
        ) {
            append(verb)
        }
    } else {
        withStyle(
            SpanStyle(color = verbColor, fontWeight = FontWeight.Medium),
        ) {
            append(verb)
        }
    }
    if (summary.isNotEmpty()) {
        append(" ")
        withStyle(SpanStyle(color = summaryColor)) { append(summary) }
    }
}

/**
 * A band of [DroshPrimary] travelling across the verb, as a gradient brush.
 *
 * The span's own colour is the destination and the band's edges fade to the verb's
 * resting colour, so the sweep arrives and leaves without a hard edge. Laid out in
 * character counts rather than pixels because a `SpanStyle` gradient is measured
 * against the annotated string's text — which is why the summary is not swept: it
 * would have to share the same coordinate space, and the band is sized to the verb.
 */
@Composable
private fun sweepingBrush(progress: Float, length: Int): Brush {
    val band = (length * 0.4f).coerceAtLeast(1f)
    val x = (length + band) * progress - band
    return Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            (x / length).coerceIn(0f, 1f) to Color.Transparent,
            ((x + band) / length).coerceIn(0f, 1f) to DroshPrimary,
            ((x + band * 2) / length).coerceIn(0f, 1f) to Color.Transparent,
            1f to Color.Transparent,
        ),
    )
}
