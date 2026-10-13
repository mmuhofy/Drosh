package dev.drosh.ui.agent

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
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
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
import dev.drosh.domain.agent.ApprovalRule
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.TerminalProjection
import dev.drosh.domain.agent.TokenUsage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.ui.settings.SettingsCheck
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.DiffBlock
import dev.drosh.ui.agent.components.FlatButton
import dev.drosh.ui.agent.components.IconAction
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
import dev.drosh.ui.agent.components.AgentToolPill
import dev.drosh.ui.agent.components.DroshStatusBarVisible
import dev.drosh.ui.agent.components.glassRow
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

    // The composer's `+` sheet, and the attachments it produced. Held here rather
    // than in the sheet because the chips have to outlive it: the sheet closes on
    // the pick, and what it picked is still on the prompt afterwards.
    var attachSheetOpen by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }

    // The search whose results are on the sheet, or null when none is open.
    var inspectingSearch by remember { mutableStateOf<SearchOutcome?>(null) }

    // The URL waiting on the leave-app confirmation. One dialog for both the
    // sources section and the search sheet, so the question is asked once, in one
    // wording, by the same code.
    var linkToOpen by remember { mutableStateOf<String?>(null) }

    val terminalLines = remember(messages) { TerminalProjection.project(messages) }
    val listState = rememberLazyListState()

    // Recomputed only when the transcript changes; grouping walks the whole list.
    val groupedTranscript = remember(messages) { groupTranscript(messages) }

    // The same walk for the same reason: collecting sources re-reads every
    // search output in the conversation, which is not a per-frame operation.
    val sources = remember(messages) { collectSources(messages) }

    val clipboard = rememberAgentClipboard()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The chat screen is the one place the composer floats at the bottom of the
    // display with nothing under it, so it is the one place that asks the window
    // for a navigation-bar-free bottom edge.
    ChatNavBarHidden()

    // The two toggles the sheet owns. A separate ViewModel because they are
    // settings about the tools rather than about this conversation.
    //
    // Keyed on the chat: `hiltViewModel()` defaults to the nearest ViewModelStore,
    // which on this screen belongs to the navigation entry rather than to the
    // chat. Without the key, opening a second chat would reuse the first chat's
    // store — and its Compose-backed saved state — which is the exact bug the
    // composer's `rememberSaveable(chatId)` calls above exist to avoid.
    val attachSettings: AgentAttachSettingsViewModel = hiltViewModel(key = chatId ?: "new")
    val webSearchOn by attachSettings.webSearch.collectAsStateWithLifecycle()
    val savedEffort by attachSettings.effort.collectAsStateWithLifecycle()
    val effortLevels by attachSettings.efforts.collectAsStateWithLifecycle()

    // Which effort values exist depends on the model in hand, so the list is
    // reloaded when the model changes rather than held for the screen's life.
    LaunchedEffect(providerState.provider?.id, providerState.selectedModelId) {
        attachSettings.loadEfforts(
            providerState.provider?.id,
            providerState.selectedModelId.ifEmpty {
                providerState.models.firstOrNull()?.id.orEmpty()
            },
        )
    }

    // The last command this chat ran, for the sheet's session-log row. Hoisted
    // out of the sheet's own branch so the slot exists whether it is open or not.
    val sessionLog = remember(messages) { lastSessionLog(messages) }

    /**
     * Whether anything on screen is counting seconds.
     *
     * The screen's clock only runs while this is true. A transcript nobody is
     * waiting on has no elapsed time to show, and a ticker that keeps waking to
     * update a number nothing reads is the scroll jank in a quieter form.
     */
    val somethingRunning = running ||
        messages.any { it is ChatMessage.ToolCall && it.state == ToolCallState.Running } ||
        messages.any { it is ChatMessage.Reasoning && it.text.isEmpty() }
    val motion = rememberAgentMotion(ticking = somethingRunning)

    // Follow the stream, but only when the user is already near the bottom.
    // Yanking the viewport while they are reading earlier output is worse than
    // missing a few tokens.
    //
    // Indexed on the *rows*, not the messages: a turn is one row that holds a
    // prompt and everything the model did about it, so the two counts are
    // different numbers and the old one could ask for an index past the end.
    LaunchedEffect(messages.size, waiting) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val atBottom = lastVisible >= groupedTranscript.size - 3
        if (atBottom) {
            listState.animateScrollToItem(groupedTranscript.lastIndex.coerceAtLeast(0))
        }
    }

    ProvideAgentGlass(glass) {
      ProvideAgentMotion(motion) {
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
                            is TranscriptRow.Turn -> TurnBlock(
                                prompt = row.prompt,
                                steps = row.steps,
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
                                onInspectTool = { tool ->
                                    // A search row opens the results sheet rather
                                    // than its raw output: the tool's text is
                                    // written for a model, and the user asked a
                                    // question rather than for a transcript of the
                                    // request that answered it.
                                    val search = searchOutcomeOf(tool)
                                    if (search != null) inspectingSearch = search else inspecting = tool
                                },
                            )

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
                                onInspectTool = { tool ->
                                    val search = searchOutcomeOf(tool)
                                    if (search != null) inspectingSearch = search else inspecting = tool
                                },
                            )
                        }
                    }

                    // The sources go at the end of the transcript rather than in
                    // a panel pinned under it: a pinned panel would take the
                    // bottom of the screen for the whole conversation whether or
                    // not anything was searched, and would hold the answer itself
                    // out of view.
                    if (sources.isNotEmpty()) {
                        item(key = "sources") {
                            AgentSourcesSection(
                                sources = sources,
                                onOpen = { url -> linkToOpen = url },
                                onShare = { picked ->
                                    scope.launch { shareSource(context, picked) }
                                },
                            )
                        }
                    }

                    if (messages.isEmpty()) {
                        item(key = "hint") { ChatEmptyHint() }
                    }
                }

                if (!usage.isEmpty) {
                    UsageStrip(usage = usage)
                }

                AgentAttachmentChips(
                    attachments = attachments,
                    onRemove = { gone -> attachments = attachments - gone },
                )

                AgentInputBar(
                    running = running,
                    draft = composerDraft,
                    onDraftChange = { composerDraft = it },
                    onSend = { text ->
                        composerDraft = ""
                        // Rendered in here rather than held in the draft: a photo
                        // is a few hundred kilobytes of base64, and a text field
                        // carrying that re-lays-out on every keystroke.
                        val body = buildString {
                            append(text)
                            if (attachments.isNotEmpty()) {
                                if (text.isNotBlank()) append("\n\n")
                                attachments.forEach { attachment ->
                                    append(attachment.renderIntoPrompt())
                                    append("\n\n")
                                }
                            }
                        }.trim()
                        attachments = emptyList()
                        viewModel.send(body)
                    },
                    onStop = viewModel::stop,
                    focusRequest = composerFocusRequest,
                    onAttachClick = { attachSheetOpen = true },
                    // A prompt with nothing typed in is still sendable when a
                    // picture is attached: that is a screenshot on its own.
                    canSendWithNothingTyped = attachments.isNotEmpty(),
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
                    title = tool.name,
                    subtitle = tool.summary,
                    body = tool.finalOutput ?: tool.output.joinToString("\n"),
                    state = tool.state,
                    onDismiss = { inspecting = null },
                    onCopy = clipboard,
                )
            }

            inspectingSearch?.let { outcome ->
                AgentSearchSheet(
                    outcome = outcome,
                    onOpen = { url -> linkToOpen = url },
                    onDismiss = { inspectingSearch = null },
                )
            }

            if (attachSheetOpen) {
                AgentAttachSheet(
                    webSearchEnabled = webSearchOn,
                    onWebSearchChange = attachSettings::setWebSearch,
                    // The per-model choice wins because that is the one the loop
                    // actually sends; the file's copy is the shell-readable
                    // mirror of it and the fallback when no model has been picked
                    // yet.
                    effort = providerState.selectedEffort
                        ?: savedEffort.takeIf { it.isNotBlank() },
                    // The catalog's own list is the source of truth: it is what
                    // the protocol can actually send for this model, so offering
                    // a level the endpoint rejects would be offering a lie.
                    efforts = effortLevels,
                    onEffortChange = { level ->
                        viewModel.selectEffort(level)
                        attachSettings.setEffort(level)
                    },
                    sessionLog = sessionLog,
                    onAttach = { picked -> attachments = attachments + picked },
                    onDismiss = { attachSheetOpen = false },
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

            // One confirmation for every link on the screen. Held here rather
            // than inside the sources section or the search sheet, so both ask
            // the same question in the same words: two wordings for one decision
            // is how a user learns which of them to trust.
            linkToOpen?.let { url ->
                LeaveAppDialog(url = url, onDismiss = { linkToOpen = null })
            }
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
            // One of the two glass surfaces on this screen. See [ChatPillRow].
            edged = true,
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
            // The other glass surface. This row and the composer are the only two
            // things on this screen that float over content rather than sitting in
            // it, so they are the only two that read the backdrop. Everything in
            // between — a reply button, a choice row, a tool pill — is a flat fill,
            // because a bordered surface in the middle of an answer reads as a form
            // field and the whole screen becomes a stack of boxes.
            edged = true,
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
                // The row's own glass, like the two beside it.
                edged = true,
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
private fun ReasoningBlock(message: ChatMessage.Reasoning) {
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
private fun ToolCallRow(
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
private fun TodoRow(todo: AgentTodo) {
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
private fun DecisionRow(decision: ApprovalDecision) {
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
 * A stable key for a row.
 *
 * A turn is keyed by its prompt: the steps under it change on every token, and a
 * key built from a turn's contents would make Compose tear the row down and
 * rebuild it while the answer streams.
 */
private fun rowKey(row: TranscriptRow): String = when (row) {
    is TranscriptRow.Turn -> "t_${row.prompt.id}"
    is TranscriptRow.Single -> "m_${row.message.id}"
}

/**
 * One row of the transcript.
 *
 * A turn is a prompt and everything the model did about it: the tool pills, the
 * reasoning, the answer. Grouping on the prompt rather than on the tool calls
 * keeps the conversation reading as a conversation — the user asked, the agent
 * worked, the agent answered — and it is what makes the prompt bubble
 * inescapable: it is the first thing in its row, and nothing is drawn above it.
 *
 * [Single] is anything outside a turn: the reasoning that arrived before a prompt
 * was ever sent, or a notice raised by something that was not the model
 * answering a question.
 */
private sealed interface TranscriptRow {

    data class Turn(
        val prompt: ChatMessage.User,
        val steps: List<ChatMessage>,
    ) : TranscriptRow

    data class Single(val message: ChatMessage) : TranscriptRow
}

/**
 * Walks the transcript into turns.
 *
 * Computed here rather than in the transcript because it is a property of what is
 * on screen: the grouping is what the user reads, so it has to agree with what
 * the list is about to draw.
 *
 * Every prompt starts a turn, including one with no answer under it yet — a turn
 * that is still running shows its prompt and whatever has arrived so far, which
 * is the one moment the prompt most needs to be on screen.
 */
private fun groupTranscript(messages: List<ChatMessage>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (message !is ChatMessage.User) {
            rows += TranscriptRow.Single(message)
            index += 1
            continue
        }
        var cursor = index + 1
        while (cursor < messages.size && messages[cursor] !is ChatMessage.User) {
            cursor += 1
        }
        rows += TranscriptRow.Turn(
            prompt = message,
            steps = messages.subList(index + 1, cursor).toList(),
        )
        index = cursor
    }
    return rows
}

/**
 * A turn: the prompt, and everything the agent did about it.
 *
 * ## The prompt is always drawn
 *
 * It used to be one row among the rest, which meant a turn that ran fifteen tools
 * pushed the question it was answering off the top of the screen, and the user
 * had to scroll back to remember what they had asked. Now the prompt is the row's
 * own header, so it cannot be separated from its answer: the answer lands
 * directly under the question that earned it, and the answer's quick actions land
 * directly under that.
 *
 * ## The flow this buys
 *
 * Message, then the model's work, then the answer, then the answer's quick
 * actions — in one row, in that order, for every turn on the screen.
 *
 * The steps are tighter than the gap between turns, 8dp rather than the list's
 * 18, so what one prompt caused reads as one thing while a new prompt is what
 * starts a new thought.
 */
@Composable
private fun TurnBlock(
    prompt: ChatMessage.User,
    steps: List<ChatMessage>,
    onAnswer: (String, ApprovalDecision) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    /** Edits what the user sent: the draft comes back for another go. */
    onEdit: (String) -> Unit,
    onInspectTool: (ChatMessage.ToolCall) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        UserBubble(prompt, onCopy = onCopy, onShare = onShare, onEdit = onEdit)

        if (steps.isEmpty()) return@Column

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            steps.forEach { step ->
                MessageRow(
                    message = step,
                    onAnswer = onAnswer,
                    onCopy = onCopy,
                    onShare = onShare,
                    onRetry = onRetry,
                    onEdit = onEdit,
                    onInspectTool = onInspectTool,
                )
            }
        }
    }
}
