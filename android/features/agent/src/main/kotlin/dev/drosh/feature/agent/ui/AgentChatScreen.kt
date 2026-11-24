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
    // Which terminals the picker can read from, and how it reads them. The
    // chat screen cannot ask either question itself — `:ui` does not see
    // `:agent`, so the source arrives from `:app` that can see both, and the
    // list is plain [SessionLogTarget]s for the same reason.
    sessionLogSource: SessionLogSource? = null,
    sessionLogTargets: List<SessionLogTarget> = emptyList(),
    activeSessionId: String? = null,
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
                    // The picker is the same feature the terminal used to host,
                    // and the terminal no longer does: reading a session is the
                    // agent's business, and its + sheet is where the user is
                    // looking for it.
                    logSource = sessionLogSource,
                    logSessions = sessionLogTargets,
                    activeSessionId = activeSessionId,
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
