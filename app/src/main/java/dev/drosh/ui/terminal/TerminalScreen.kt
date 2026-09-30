package dev.drosh.ui.terminal

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleEventObserver
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.terminal.SearchHighlightOverlay
import dev.drosh.terminal.TerminalManager
import dev.drosh.terminal.TerminalViewClientImpl
import dev.drosh.domain.session.DEFAULT_SESSION_NAME
import dev.drosh.terminal.UbuntuSetupState
import dev.drosh.ui.block.BlockEngineViewModel
import dev.drosh.ui.block.BlockInputField
import dev.drosh.ui.block.PromptBlock
import dev.drosh.ui.block.PromptDivider
import dev.drosh.ui.browser.WebViewSheet
import dev.drosh.ui.input.FlatKeyBar
import dev.drosh.ui.input.InputBarViewModel
import dev.drosh.ui.search.DraggableSearchBar
import dev.drosh.ui.search.SearchScope
import dev.drosh.ui.session.SessionSidebar
import dev.drosh.ui.session.SessionSwitcherViewModel
import dev.drosh.ui.session.rememberSidebarPushState
import dev.drosh.ui.session.sidebarPush
import dev.drosh.ui.terminal.MotdWidget
import dev.drosh.ui.terminal.SystemInfo
import dev.drosh.domain.settings.AboutInfo
import dev.drosh.domain.settings.MotdMode
import dev.drosh.ui.topbar.TerminalTopBar
import dev.drosh.ui.topbar.topBarInset
import com.termux.view.TerminalView
import kotlinx.coroutines.delay
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Shown in place of the shell prompt while an interactive program owns the
 * terminal. Block mode has no terminal view of its own, so the line editor at
 * the bottom is the only way in; it is relabelled so it is clear that the input
 * is going to the program rather than to a shell.
 */
private const val PROGRAM_PROMPT_MARKER = "›"

@Composable
fun TerminalScreen(
    terminalManager: TerminalManager,
    ubuntuSetupState: UbuntuSetupState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit = {},
    terminalViewModel: TerminalViewModel = hiltViewModel(),
    extraKeyState: dev.drosh.terminal.ExtraKeyState? = null,
    onExit: () -> Unit = {},
    onOpenAgent: () -> Unit = {},
) {
    var showProgress by remember { mutableStateOf(false) }

    LaunchedEffect(ubuntuSetupState) {
        if (ubuntuSetupState is UbuntuSetupState.Ready) {
            showProgress = false
        } else {
            delay(300)
            showProgress = true
        }
    }

    when (ubuntuSetupState) {
        UbuntuSetupState.Idle,
        UbuntuSetupState.Extracting,
        UbuntuSetupState.Configuring,
        is UbuntuSetupState.InstallingPackages,
        is UbuntuSetupState.InstallingOhMyZsh,
        UbuntuSetupState.Optimizing -> {
            if (showProgress) {
                SetupProgress(
                    state = ubuntuSetupState,
                )
            }
        }

        UbuntuSetupState.Ready -> {
            ReadyScreen(
                terminalManager = terminalManager,
                terminalViewModel = terminalViewModel,
                onOpenSettings = onOpenSettings,
                extraKeyState = extraKeyState,
                onExit = onExit,
                onOpenAgent = onOpenAgent,
            )
        }

        is UbuntuSetupState.Failed -> {
            SetupFailure(
                error = ubuntuSetupState.error,
                onRetry = onRetry,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReadyScreen(
    terminalManager: TerminalManager,
    terminalViewModel: TerminalViewModel,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    sessionSwitcherViewModel: SessionSwitcherViewModel = hiltViewModel(),
    blockEngineViewModel: BlockEngineViewModel = hiltViewModel(),
    inputBarViewModel: InputBarViewModel = hiltViewModel(),
    extraKeyState: dev.drosh.terminal.ExtraKeyState? = null,
    onOpenAgent: () -> Unit = {},
) {
    var fullscreen by remember { mutableStateOf(false) }
    val altBufferActive by terminalManager.altBufferActive.collectAsState()
    var sidebarOpen by remember { mutableStateOf(false) }
    val sidebarPush = rememberSidebarPushState(sidebarOpen)
    var browserUrl by remember { mutableStateOf<String?>(null) }

    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatch by remember { mutableStateOf(1) }
    var searchScope by remember { mutableStateOf(SearchScope.GLOBAL) }
    var terminalLines by remember { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val fontSizeSp by terminalViewModel.fontSizeSp.collectAsState()
    val colorProps by terminalViewModel.colorProps.collectAsState()
    val activeId by sessionSwitcherViewModel.activeId.collectAsState()
    val useBlockEngine by terminalViewModel.useBlockEngine.collectAsState()
    val shouldExit by sessionSwitcherViewModel.shouldExit.collectAsState()
    val inputBarState by inputBarViewModel.uiState.collectAsState()
    val processExitEvent by terminalManager.processExitEvent.collectAsState()
    val noSessionsLeft by terminalManager.noSessionsLeft.collectAsState()

    val motdMode by terminalViewModel.motdMode.collectAsState()
    val motdText by terminalViewModel.motdText.collectAsState()
    val appInfo by terminalViewModel.appInfo.collectAsState()
    val blocks by blockEngineViewModel.blocks.collectAsState()
    val awaitingShellInput by blockEngineViewModel.awaitingShellInput.collectAsState()

    var motdDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(activeId) { motdDismissed = false }

    val ctx = LocalContext.current
    val systemInfo = remember(appInfo) {
        buildSystemInfo(ctx, appInfo)
    }


    val sessionCount by terminalManager.sessionCountFlow.collectAsState()

    LaunchedEffect(searchActive, searchScope) {
        if (searchActive) {
            terminalLines = if (useBlockEngine) {
                val allBlocks = blockEngineViewModel.blocks.value
                if (searchScope == SearchScope.BLOCK) {
                    val currentBlock = blockEngineViewModel.runningBlock.value
                        ?: allBlocks.lastOrNull()
                    if (currentBlock != null) {
                        buildList {
                            if (currentBlock.prompt.isNotBlank()) add(currentBlock.prompt to currentBlock.id)
                            if (currentBlock.command.isNotBlank()) add(currentBlock.command to currentBlock.id)
                            currentBlock.outputLines.forEach { line ->
                                add(line to currentBlock.id)
                            }
                        }
            } else {
                        emptyList()
                    }
                } else {
                    buildList {
                        for (block in allBlocks) {
                            if (block.prompt.isNotBlank()) add(block.prompt to block.id)
                            if (block.command.isNotBlank()) add(block.command to block.id)
                            block.outputLines.forEach { line ->
                                add(line to block.id)
                            }
                        }
                    }
                }
            } else {
                val text = terminalManager.currentSession?.emulator?.getScreen()
                    ?.getTranscriptText() ?: ""
                text.lines().map { it to null }
            }
        }
    }

    val matchIndices = remember(searchQuery, terminalLines) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            terminalLines.mapIndexedNotNull { index, (lineText, _) ->
                if (lineText.contains(searchQuery, ignoreCase = true)) index else null
            }
        }
    }
    val matchCount = matchIndices.size

    val currentMatchBlockId = if (matchIndices.isNotEmpty() && currentMatch <= matchIndices.size) {
        terminalLines[matchIndices[currentMatch - 1]].second
    } else {
        null
    }

    var keyboardFocused by remember { mutableStateOf(true) }

    /**
     * This reference is also used by the Liquid Glass extra-key surface.
     *
     * It is populated only after TerminalView has a valid attached size.
     * Therefore the backdrop implementation never receives a zero-sized
     * TerminalView during its normal initialization path.
     */
    val terminalViewRef = remember {
        mutableStateOf<TerminalView?>(null)
    }

    val searchOverlayRef = remember {
        mutableStateOf<SearchHighlightOverlay?>(null)
    }

    fun showKeyboard() {
        try {
            val view = terminalViewRef.value ?: run {
                Log.w(
                    "TerminalScreen",
                    "TerminalView is not ready, cannot show keyboard",
                )
                return
            }

            if (
                !view.isAttachedToWindow ||
                view.width <= 0 ||
                view.height <= 0
            ) {
                Log.w(
                    "TerminalScreen",
                    "TerminalView is not attached or has zero size, cannot show keyboard",
                )
                return
            }

            view.requestFocusFromTouch()

            val imm = view.context.getSystemService(
                Context.INPUT_METHOD_SERVICE,
            ) as InputMethodManager

            imm.showSoftInput(
                view,
                InputMethodManager.SHOW_IMPLICIT,
            )

            keyboardFocused = true
        } catch (e: Exception) {
            Log.e(
                "TerminalScreen",
                "showKeyboard failed",
                e,
            )
        }
    }

    fun hideKeyboard() {
        try {
            val view = terminalViewRef.value ?: run {
                Log.w(
                    "TerminalScreen",
                    "TerminalView is not ready, cannot hide keyboard",
                )
                return
            }

            if (
                !view.isAttachedToWindow ||
                view.width <= 0 ||
                view.height <= 0
            ) {
                Log.w(
                    "TerminalScreen",
                    "TerminalView is not attached or has zero size, cannot hide keyboard",
                )
                return
            }

            val imm = view.context.getSystemService(
                Context.INPUT_METHOD_SERVICE,
            ) as InputMethodManager

            val token = view.windowToken

            if (token != null) {
                imm.hideSoftInputFromWindow(
                    token,
                    0,
                )

                keyboardFocused = false
            }
        } catch (e: Exception) {
            Log.e(
                "TerminalScreen",
                "hideKeyboard failed",
                e,
            )
        }
    }

    fun toggleKeyboard() {
        val view = terminalViewRef.value ?: run {
            Log.w(
                "TerminalScreen",
                "TerminalView is not ready, cannot toggle keyboard",
            )
            return
        }

        if (
            !view.isAttachedToWindow ||
            view.width <= 0 ||
            view.height <= 0
        ) {
            Log.w(
                "TerminalScreen",
                "TerminalView is not attached or has zero size, cannot toggle keyboard",
            )
            return
        }

        if (keyboardFocused) {
            hideKeyboard()
        } else {
            showKeyboard()
        }
    }

    // Terminal content is always fully visible.
    val appearScale = 1f
    val appearAlpha = 1f

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
    Box(
        modifier = Modifier
        .fillMaxSize()
        .sidebarPush(sidebarPush)
        // The frame the terminal sits inside was a different surface from the
        // drawer, so the seam between them showed as a step of grey. Same
        // surface as the panel, so the two read as one. The first
        // .background(DroshBackground) was also dead — the next line covered
        // it entirely.
        .background(DroshSurface)
        .padding(
            top = 20.dp * sidebarPush.progress,
            bottom = 20.dp * sidebarPush.progress,
        )
        .clip(RoundedCornerShape(20.dp * sidebarPush.progress))
        .background(DroshBackground)
    ) {
                	/*
         * Terminal content fills all available space.
         *
         * The extra-key bar remains below the terminal in the normal layout,
         * while its Liquid Glass layer samples the classic TerminalView that
         * sits behind it.
           */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        if (useBlockEngine) {
            val blocks by blockEngineViewModel.blocks.collectAsState()
            val promptDir by blockEngineViewModel.lastDir.collectAsState()
            val promptSuffix by blockEngineViewModel.promptSuffix.collectAsState()

            // When a TUI app runs (nano, vim, htop, etc.) it enters the
            // alternate screen buffer — switch to terminal fullscreen so the
            // raw terminal view is visible.
            if (altBufferActive) {
                TerminalViewHost(
                    terminalManager = terminalManager,
                    fontSizeSp = fontSizeSp,
                    colorProps = colorProps,
                    terminalViewModel = terminalViewModel,
                    terminalViewRef = terminalViewRef,
                    extraKeyState = extraKeyState,
                    onUrlClick = { browserUrl = it },
                    searchQuery = if (searchActive && searchQuery.isNotBlank()) searchQuery else null,
                    searchOverlayRef = searchOverlayRef,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    CompactFullscreenExit { fullscreen = false }
                }
             } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topBarInset(!fullscreen)),
                ) {
                    if (motdMode == MotdMode.Compose && !motdDismissed) {
                        MotdWidget(
                            motdText = motdText,
                            systemInfo = systemInfo,
                            onAgentClick = { onOpenAgent() },
                            onHelpClick = { /* TODO: open help */ },
                            onDismiss = { motdDismissed = true },
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        state = rememberLazyListState(),
                    ) {
                        items(blocks, key = { it.id }) { block ->
                            PromptBlock(
                                block = block,
                                promptDir = promptDir,
                                modifier = Modifier.padding(vertical = 8.dp),
                            onCopyCommand = { blockEngineViewModel.onCopyCommand(block) },
                            onCopyOutput = { blockEngineViewModel.onCopyOutput(block) },
                            onRerunCommand = { cmd -> blockEngineViewModel.onRerunCommand(cmd) },
                            onEditCommand = { cmd -> blockEngineViewModel.onEditCommand(cmd) },
                            onExportOutput = { blockEngineViewModel.onExportOutput(block) },
                            onDeleteBlock = { blockEngineViewModel.onDeleteBlock(block.id) },
                            onUrlClick = { browserUrl = it },
                        )
                        if (block.id != blocks.lastOrNull()?.id) {
                            PromptDivider()
                        }
                        }
                    }

                    PromptDivider()

                    // While the shell is at its prompt this records a command
                    // block. Once an interactive program takes the terminal the
                    // line goes straight to the program instead, so the bar stops
                    // labelling itself as a command prompt and its output is not
                    // mistaken for one.
                    BlockInputField(
                        onSubmit = { cmd ->
                            if (awaitingShellInput) {
                                blockEngineViewModel.onCommandSubmitted("", cmd)
                            } else {
                                blockEngineViewModel.onRawInput(cmd)
                            }
                        },
                        promptLabel = promptDir,
                        promptSuffix = promptSuffix,
                        programPrompt = if (awaitingShellInput) null else PROGRAM_PROMPT_MARKER,
                    )
                }
            }
        } else {
            /*
             * CLASSIC TERMINAL PATH
             *
             * terminalViewRef is handed to the caller so overlays (search
             * highlight, WebView chrome) can sample this exact TerminalView.
             */
            TerminalViewHost(
                terminalManager = terminalManager,
                fontSizeSp = fontSizeSp,
                colorProps = colorProps,
                terminalViewModel = terminalViewModel,
                terminalViewRef = terminalViewRef,
                extraKeyState = extraKeyState,
                onUrlClick = { browserUrl = it },
                searchQuery = if (searchActive && searchQuery.isNotBlank()) searchQuery else null,
                searchOverlayRef = searchOverlayRef,
                modifier = Modifier
                    .fillMaxSize()
                    // The bar is an overlay, so the band it sits in has to be
                    // reserved here or the first line of output lands under
                    // the buttons.
                    .padding(top = topBarInset(!fullscreen))
                    .graphicsLayer {
                        scaleX = appearScale
                        scaleY = appearScale
                        alpha = appearAlpha
                    },
            )
        }

        if (fullscreen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        CompactFullscreenExit {
                            fullscreen = false
                    }
                }
            }
        }

        if (!fullscreen && !inputBarState.hardwareKeyboardPresent) {
            FlatKeyBar(
                ctrlStuck = inputBarState.ctrlStuck,
                altStuck = inputBarState.altStuck,
                onIntent = inputBarViewModel::onIntent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

        // Top bar overlay — floats on terminal, takes no layout space.
        if (!fullscreen) {
            TerminalTopBar(
                viewModel = sessionSwitcherViewModel,
                isFullscreen = fullscreen,
                keyboardFocused = keyboardFocused,
                onToggleKeyboard = ::toggleKeyboard,
                onOpenSidebar = {
                    hideKeyboard()
                    scope.launch {
                        delay(100)
                        sidebarOpen = true
                    }
                },
                onFindInOutput = {
                    hideKeyboard()
                    searchActive = true
                },
                onRefresh = {
                    terminalManager.restartCurrentTab()
                },
                onToggleFullscreen = {
                    fullscreen = true
                },
                onOpenSettings = onOpenSettings,
                onOpenAgent = onOpenAgent,
            )
        }

        // Slider overlay trigger — BackHandler kalıyor, SessionSidebar
        // çağrısı bu transformlu Box'ın DIŞINA taşındı (aşağıda), çünkü bu
        // Box zaten .sidebarPush() ile translateX alıyor ve sidebar bunu
        // miras alıp yanlış konuma kayıyordu.
        if (sidebarOpen) {
            BackHandler {
                sidebarOpen = false
            }
        }

        if (browserUrl != null) {
            BackHandler {
                browserUrl = null
            }
            WebViewSheet(
                url = browserUrl!!,
                onDismiss = { browserUrl = null },
            )
        }

        // Search overlay — draggable, top-center.
        if (searchActive) {
            BackHandler {
                searchActive = false
                searchQuery = ""
                currentMatch = 1
            }

            DraggableSearchBar(
                searchText = searchQuery,
                onSearchTextChange = { searchQuery = it },
                matchCount = matchCount,
                currentMatch = currentMatch,
                onNext = {
                    if (matchCount > 1) {
                        currentMatch = if (currentMatch < matchCount) currentMatch + 1 else 1
                    }
                },
                onPrev = {
                    if (matchCount > 1) {
                        currentMatch = if (currentMatch > 1) currentMatch - 1 else matchCount
                    }
                },
                onClose = {
                    searchActive = false
                    searchQuery = ""
                    currentMatch = 1
                    searchScope = SearchScope.GLOBAL
                },
                searchScope = searchScope,
                onToggleScope = {
                    searchScope = if (searchScope == SearchScope.GLOBAL) SearchScope.BLOCK else SearchScope.GLOBAL
                    currentMatch = 1
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            )
        }

        // Sidebar açıkken terminal'le HİÇBİR etkileşim olmasın — sadece
        // tıklayıp kapatma değil, scroll/drag/tap dahil tüm pointer input'u
        // burada tüketip terminal'e hiç ulaştırmıyoruz. Görünmez, tam kaplı.
        if (sidebarOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { sidebarOpen = false },
                    ),
            )
        }

        // Only shown when the last session is gone, whether by typing `exit`, by
        // closing from the toolbar, or by deleting from the sidebar. A session
        // exiting while siblings remain is routine and says nothing here; the
        // user simply switches to another one. Nothing is replaced behind their
        // back — the dialog is the only way forward.
        // The last session is gone. Either the user deletes it and the app goes
        // with it, or they start over from a single new session. There is no
        // third option: the dialog is not dismissible, because with nothing left
        // to return to, swiping it away left a terminal that was not there and
        // no way forward.
        val sessionEndEvent = processExitEvent
        if (noSessionsLeft) {
            val quit: () -> Unit = {
                // Every shell is killed before the activity finishes, so none is
                // left running behind a closed app.
                terminalManager.closeAll()
                terminalManager.clearProcessExitEvent()
                terminalManager.clearNoSessionsLeft()
                onExit()
            }
            val startNew: () -> Unit = {
                terminalManager.closeAll()
                terminalManager.clearProcessExitEvent()
                terminalManager.clearNoSessionsLeft()
                // Through the repository, not addTab(). addTab opens a shell with
                // no persistent id: no Room row, absent from liveSessionIds, never
                // reconciled, and therefore invisible in the sidebar — a session
                // the system cannot manage. This way the row is written and the
                // normal reconcile spawns it a tick later.
                sessionSwitcherViewModel.createNew(DEFAULT_SESSION_NAME)
            }
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {
                    TextButton(onClick = quit) {
                        Text(
                            text = "Delete",
                            color = DroshError,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = startNew) {
                        Text(
                            text = "New session",
                            color = DroshPrimary,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        )
                    }
                },
                title = {
                    Text(
                        text = "Delete the last session?",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                },
                text = {
                    val exitCode = sessionEndEvent?.exitCode
                    Text(
                        text = if (exitCode != null) {
                            "It ended with exit code $exitCode. Deleting it closes the app; a new session starts from scratch."
                        } else {
                            "Deleting it closes the app; a new session starts from scratch."
                        },
                        color = DroshTextSecondary,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = DroshSurface,
            )
        }
    }

    SessionSidebar(
        isOpen = sidebarOpen,
        onOpenSettings = onOpenSettings,
        onOpenAgent = onOpenAgent,
        pushState = sidebarPush,
    )
    }
}

@Composable
private fun CompactFullscreenExit(
    onExitFullscreen: () -> Unit,
) {
    androidx.compose.material3.Surface(
        color = dev.drosh.design.system.DroshSurface.copy(
            alpha = 0.85f,
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(
                    horizontal = 12.dp,
                    vertical = 6.dp,
                )
                .clickable(
                    onClick = onExitFullscreen,
                ),
        ) {
            Text(
                text = "Tap to exit fullscreen",
                color = dev.drosh.design.system.DroshTextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SetupProgress(
    state: UbuntuSetupState,
) {
    val label = when (state) {
        UbuntuSetupState.Idle ->
            "Preparing…"

        UbuntuSetupState.Extracting ->
            "Extracting Ubuntu rootfs…"

        UbuntuSetupState.Configuring ->
            "Configuring system…"

        is UbuntuSetupState.InstallingPackages ->
            if (state.message.isNotEmpty()) {
                state.message
            } else {
                "Installing packages…"
            }

        is UbuntuSetupState.InstallingOhMyZsh ->
            state.message

        UbuntuSetupState.Optimizing ->
            "Cleaning up…"

        UbuntuSetupState.Ready ->
            "Ready"

        is UbuntuSetupState.Failed ->
            state.error
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()

            Text(
                text = "Setting up terminal",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )

            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    top = 8.dp,
                    start = 32.dp,
                    end = 32.dp,
                ),
            )
        }
    }
}

@Composable
private fun SetupFailure(
    error: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Failed to set up terminal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )

            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    top = 8.dp,
                    start = 32.dp,
                    end = 32.dp,
                ),
            )

            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Retry")
            }
        }
    }
}

private const val TERMINAL_PINCH_THRESHOLD = 0.04f

@Composable
private fun TerminalViewHost(
    terminalManager: TerminalManager,
    fontSizeSp: Int,
    colorProps: Properties,
    terminalViewModel: TerminalViewModel,
    terminalViewRef: MutableState<TerminalView?>,
    onUrlClick: (String) -> Unit,
    searchQuery: String?,
    searchOverlayRef: MutableState<SearchHighlightOverlay?>,
    modifier: Modifier = Modifier,
    extraKeyState: dev.drosh.terminal.ExtraKeyState? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val viewClient = remember(
        terminalViewModel,
        extraKeyState,
        onUrlClick,
    ) {
        TerminalViewClientImpl(
            onScaleChange = { factor ->
                terminalViewModel.bumpFontSize(factor)
                factor
            },
            extraKeyState = extraKeyState,
            context = context,
            onUrlClick = onUrlClick,
        )
    }

    LaunchedEffect(fontSizeSp) {
        terminalViewRef.value?.setTextSize(fontSizeSp)
    }

    LaunchedEffect(colorProps) {
        terminalViewRef.value?.updateColors(colorProps)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            // Lifecycle hook intentionally kept here.
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),

        factory = { ctx ->
            val frameLayout = android.widget.FrameLayout(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }

            val tv = TerminalView(ctx, null).apply {
                setTextSize(fontSizeSp)
                isFocusable = true
                isFocusableInTouchMode = true
                setTerminalViewClient(viewClient)
                viewClient.terminalView = this
                terminalManager.currentSession?.let { session ->
                    attachSession(session)
                }
                terminalManager.registerTerminalView(this, ctx)
                val listener =
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (width > 0 && height > 0 && isAttachedToWindow) {
                                viewTreeObserver.removeOnGlobalLayoutListener(this)
                                terminalViewRef.value = this@apply
                                this@apply.requestFocus()
                            }
                        }
                    }
                viewTreeObserver.addOnGlobalLayoutListener(listener)
            }

            val overlay = SearchHighlightOverlay(ctx).apply {
                terminalView = tv
                updateQuery(searchQuery)
                isFocusable = false
                isFocusableInTouchMode = false
            }
            tv.searchHighlightOverlay = overlay
            // Let taps resolve against the overlay's logical lines so a URL the
            // terminal wrapped across rows opens whole.
            viewClient.urlHighlightOverlay = overlay
            // Feed raw touches to the overlay so a held link can show its
            // surface. Returning false leaves the terminal's own handling intact.
            tv.setOnTouchListener { _, event ->
                overlay.onTerminalTouch(event)
                false
            }

            frameLayout.addView(tv)
            frameLayout.addView(overlay)
            searchOverlayRef.value = overlay

            frameLayout
        },

        update = { _ ->
            val tv = terminalViewRef.value
            val overlay = searchOverlayRef.value

            tv?.setTextSize(fontSizeSp)
            terminalManager.currentSession?.let { session ->
                tv?.attachSession(session)
            }
            tv?.let { terminalManager.registerTerminalView(it, it.context) }

            overlay?.updateQuery(searchQuery)
        },
    )
}

private fun humanReadableBytes(bytes: Long): String {
    val unit = 1024L
    if (bytes < unit) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(unit.toDouble())).toInt()
    val prefix = "KMGTPE"[exp - 1]
    val value = bytes.toDouble() / Math.pow(unit.toDouble(), exp.toDouble())
    return String.format("%.1f %cB", value, prefix)
}

private fun buildSystemInfo(
    context: Context,
    appInfo: AboutInfo?,
): SystemInfo {
    val version = appInfo?.version ?: "—"

    var ramTotal: String
    var ramAvailable: String
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        ramTotal = humanReadableBytes(memInfo.totalMem)
        ramAvailable = humanReadableBytes(memInfo.availMem)
    } catch (e: Exception) {
        ramTotal = "—"
        ramAvailable = "—"
    }

    var storageTotal: String
    var storageAvailable: String
    try {
        val sf = StatFs(context.filesDir.absolutePath)
        storageTotal = humanReadableBytes(sf.totalBytes)
        storageAvailable = humanReadableBytes(sf.availableBytes)
    } catch (e: Exception) {
        storageTotal = "—"
        storageAvailable = "—"
    }

    return SystemInfo(
        version = version,
        ramTotal = ramTotal,
        ramAvailable = ramAvailable,
        storageTotal = storageTotal,
        storageAvailable = storageAvailable,
    )
}