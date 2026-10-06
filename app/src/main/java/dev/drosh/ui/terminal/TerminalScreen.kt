package dev.drosh.ui.terminal

import android.app.ActivityManager
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import dev.drosh.ui.keyboard.droshImePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import dev.drosh.ui.LocalDroshActivity
import dev.drosh.domain.UrlDetector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import dev.drosh.core.copyToClipboard
import dev.drosh.core.shareText
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.terminal.UbuntuSetupState
import dev.drosh.ui.block.BlockEngineViewModel
import dev.drosh.ui.block.BlockInputField
import dev.drosh.ui.block.PromptBlock
import dev.drosh.ui.block.PromptDivider
import dev.drosh.ui.browser.WebViewSheet
import dev.drosh.ui.input.FlatKeyBar
import dev.drosh.ui.input.InputBarViewModel
import dev.drosh.ui.pane.PaneLayoutViewModel
import dev.drosh.ui.pane.SplitPaneHost
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
import dev.drosh.ui.topbar.SelectionMenuSurface
import dev.drosh.ui.topbar.SelectionMenuBackdrop
import dev.drosh.ui.topbar.menuWidthFor
import dev.drosh.ui.topbar.SelectionMenuRow
import dev.drosh.ui.topbar.TerminalTopBar
import dev.drosh.ui.topbar.rememberTerminalBackdrop
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.drosh.domain.settings.SettingsRepository
import com.termux.view.TerminalView
import kotlinx.coroutines.delay
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext

/**
 * Shown in place of the shell prompt while an interactive program owns the
 * terminal. Block mode has no terminal view of its own, so the line editor at
 * the bottom is the only way in; it is relabelled so it is clear that the input
 * is going to the program rather than to a shell.
 */
private const val PROGRAM_PROMPT_MARKER = "›"

/** Clearance for the system bar, without insetting the background behind it. */
@Composable
private fun statusBarInset(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** Selection menu metrics. */
private val MENU_GUTTER = 12.dp
private val MENU_GAP = 10.dp
private val MENU_MIN_WIDTH = 210.dp

/** Light blur, not an Apple-style frosted slab. */
private val MENU_BLUR = 10.dp

private val MENU_HEIGHT_PX = 64.dp

/** How much of the terminal's top edge the top bar backdrop samples. */
private val BACKDROP_STRIP = 72.dp

@Composable
fun TerminalScreen(
    terminalManager: TerminalManager,
    settingsRepository: SettingsRepository,
    ubuntuSetupState: UbuntuSetupState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit = {},
    terminalViewModel: TerminalViewModel = hiltViewModel(),
    extraKeyState: dev.drosh.terminal.ExtraKeyState? = null,
    onExit: () -> Unit = {},
    /**
     * Called with a guest path when the guest runs `dedit <path>`.
     *
     * A callback rather than navigating from here, so this screen keeps no
     * reference to a NavController - it is instantiated twice in `MainActivity`
     * (before and after the PIN gate) and navigating from inside it would tie
     * the terminal to one of those call sites.
     */
    onOpenEditor: (String) -> Unit = {},
    onOpenAgent: () -> Unit = {},
    onOpenSsh: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
) {
    var showProgress by remember { mutableStateOf(false) }

    // `editor <path>` arrives as an escape sequence from the shell, parsed by the
    // emulator and republished by TerminalManager. Collected here because this is
    // the screen the user is looking at when they type the command.
    LaunchedEffect(terminalManager) {
        terminalManager.editorRequests.requests.collect { guestPath ->
            onOpenEditor(guestPath)
        }
    }

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
                settingsRepository = settingsRepository,
                onOpenSettings = onOpenSettings,
                extraKeyState = extraKeyState,
                onExit = onExit,
                onOpenAgent = onOpenAgent,
                onOpenSsh = onOpenSsh,
                onOpenProjects = onOpenProjects,
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
    settingsRepository: SettingsRepository,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    sessionSwitcherViewModel: SessionSwitcherViewModel = hiltViewModel(),
    blockEngineViewModel: BlockEngineViewModel = hiltViewModel(),
    inputBarViewModel: InputBarViewModel = hiltViewModel(),
    paneLayoutViewModel: PaneLayoutViewModel = hiltViewModel(),
    extraKeyState: dev.drosh.terminal.ExtraKeyState? = null,
    onOpenAgent: () -> Unit = {},
    onOpenSsh: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
) {

    // ── Split panes ─────────────────────────────────────────────────────────
    val paneLayout by paneLayoutViewModel.layout.collectAsStateWithLifecycle()
    val liveSessionIds by terminalManager.liveSessionIdsFlow.collectAsStateWithLifecycle()

    /**
     * The second pane's session, bound to [PaneSlot.SECONDARY].
     *
     * Driven off the layout rather than left to the view to attach itself,
     * because the order is not guaranteed: the layout is restored from storage
     * on the first composition, which can land before or after the pane's view
     * is built. bindPaneToSession covers both orders, and re-running it is
     * cheap.
     */
    LaunchedEffect(paneLayout.secondarySessionId) {
        terminalManager.bindPaneToSession(PaneSlot.SECONDARY, paneLayout.secondarySessionId)
    }

    /**
     * Drops the second pane when its session is gone.
     *
     * A session ends, or the user deletes it, and the pane would otherwise sit
     * there as an empty rectangle. Guarded on the set being non-empty, because
     * on the first composition it is still empty — no session has spawned yet —
     * and an empty set must not be read as "the session you split into died".
     */
    LaunchedEffect(paneLayout.secondarySessionId, liveSessionIds) {
        val secondary = paneLayout.secondarySessionId ?: return@LaunchedEffect
        if (liveSessionIds.isEmpty()) return@LaunchedEffect
        if (secondary !in liveSessionIds) paneLayoutViewModel.closeSplit()
    }

    // Haze is gone. It records Compose's own draw commands, and the terminal is
    // a View inside an AndroidView, so it is not in the display list Haze sees
    // and the blur over the top bar had nothing to sample. The backdrop is
    // taken from the view directly now — see TerminalBackdrop.kt.
    //
    var terminalBounds by remember { mutableStateOf<Rect?>(null) }

    // The band above the grid, in the terminal's own background. Without it the
    // gap showed the app background and read as a black bar sitting on top of
    // the output; with it there is no seam at all.
    val terminalBg by settingsRepository.terminalBgColor
        .collectAsStateWithLifecycle(initialValue = "#0B0B0F")
    val terminalBgColor = remember(terminalBg) {
        runCatching { Color(android.graphics.Color.parseColor(terminalBg)) }
            .getOrDefault(Color.Black)
    }


    // ── Immersive status bar ───────────────────────────────────────────────
    // At the live edge the system status bar is hidden and the Drosh bar's
    // pills move up into the band it leaves. Scrolling back into the scrollback
    // puts it back, because that is when the row of controls is actually
    // wanted. Nothing is drawn over the band: the pills simply move.
    //
    // The dead zone matters. mTopRow is an integer that changes one row at a
    // time, and the bar translates on the first row of scrollback, so without
    // one the bar would strobe while the user reads the last few lines.
    val atLiveEdge by terminalManager.isAtLiveEdge.collectAsStateWithLifecycle()

    val immersiveSetting by settingsRepository.autoHideStatusBar
        .collectAsStateWithLifecycle(initialValue = true)
    // The system status bar is hidden outright when the setting is on, rather
    // than coming and going with scroll. Hiding and showing it was the visible
    // part of the jitter: every row of scroll crossed the boundary and the
    // system bars animated with it. The buttons still move, and that is a small
    // contained animation on a 44dp row.
    val immersive = immersiveSetting


    val activity = LocalDroshActivity.current
    LaunchedEffect(immersive) {
        val window = (activity as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: a swipe from the top edge can
        // still summon the bars, so the user is never trapped out of them.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    var sidebarOpen by remember { mutableStateOf(false) }
    val sidebarPush = rememberSidebarPushState(sidebarOpen)
    var browserUrl by remember { mutableStateOf<String?>(null) }

    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatch by remember { mutableStateOf(1) }
    var searchScope by remember { mutableStateOf(SearchScope.GLOBAL) }
    var terminalLines by remember { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }

    val scope = rememberCoroutineScope()

    /**
     * Moves the pane in or out of the system overlay.
     *
     * Suspends because starting the window can be refused — no permission, or the
     * pane has no session. A refusal leaves the layout exactly as it was, so there
     * is nothing to undo afterwards; the reason this is not fire-and-forget is
     * that a refused tap must leave no trace, and a coroutine that silently gave
     * up would look identical to one that had worked.
     */
    val toggleSystemOverlay: () -> Unit = {
        scope.launch {
            if (paneLayout.isSystemOverlay) {
                paneLayoutViewModel.dockFromOverlay()
            } else {
                paneLayoutViewModel.floatOverOtherApps()
            }
        }
    }
    val fontSizeSp by terminalViewModel.fontSizeSp.collectAsState()
    val colorProps by terminalViewModel.colorProps.collectAsState()
    val activeId by sessionSwitcherViewModel.activeId.collectAsState()
    val useBlockEngine by terminalViewModel.useBlockEngine.collectAsState()
    val shouldExit by sessionSwitcherViewModel.shouldExit.collectAsState()
    val inputBarState by inputBarViewModel.uiState.collectAsState()
    val processExitEvent by terminalManager.processExitEvent.collectAsState()
    val noSessionsLeft by terminalManager.noSessionsLeft.collectAsState()
    val sessions by sessionSwitcherViewModel.allSessions.collectAsStateWithLifecycle()

    /**
     * The second pane's session name, for its title bar.
     *
     * Looked up from the session list rather than kept alongside the layout,
     * because the name is editable and the layout only stores an id. A renamed
     * session would otherwise keep the name it had when it was split in.
     */
    val secondarySessionName = remember(paneLayout.secondarySessionId, sessions) {
        paneLayout.secondarySessionId
            ?.let { id -> sessions.firstOrNull { it.id == id }?.name }
            .orEmpty()
    }

    /**
     * The top pane's name, for the sidebar's split banner.
     *
     * The *focused* pane rather than the primary one: the banner sits above the
     * list and describes what is on screen, and when the user is working in the
     * lower pane, naming the one they are not looking at would be its own lie.
     */
    val activeSessionName = remember(activeId, sessions) {
        sessions.firstOrNull { it.id == activeId }?.name.orEmpty()
    }

    val motdMode by terminalViewModel.motdMode.collectAsState()
    val motdText by terminalViewModel.motdText.collectAsState()
    val appInfo by terminalViewModel.appInfo.collectAsState()
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

    // Text selection menu. The platform ActionMode is switched off when the
    // view registers — see TerminalManager.bindSelectionMenu — and this draws
    // the replacement, so it can use the app's own surface and a real blur of
    // the output behind it.
    val selectionBounds by terminalManager.selectionBounds.collectAsStateWithLifecycle()
    val hasSelection by terminalManager.hasSelection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selectionText by remember(hasSelection, selectionBounds) {
        derivedStateOf { if (hasSelection) terminalViewRef.value?.selectedText else null }
    }
    val selectionUrl = remember(selectionText) {
        selectionText?.let { UrlDetector.findUrls(it).singleOrNull()?.url }
    }
    val hasClipboardText = remember(hasSelection) {
        if (!hasSelection) return@remember false
        context.getSystemService(ClipboardManager::class.java)
            ?.hasPrimaryClip() == true
    }


    // Only the classic path has a View to sample; the block engine is a
    // LazyColumn and the alt buffer is a TUI, neither of which needs this.
    val backdrop by rememberTerminalBackdrop(
        terminalView = terminalViewRef.value,
        stripHeight = BACKDROP_STRIP,
        // In the band there is no terminal behind the row at all.
        active = immersive && atLiveEdge,
    )

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

            // The view only accepts showSoftInput while it reports itself as a
            // text editor, and that flag is off the rest of the time so that
            // focus alone cannot raise the keyboard.
            view.raiseKeyboardOnFocus = true
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
            terminalViewRef.value?.raiseKeyboardOnFocus = false
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

    /**
     * Opens the keyboard when the screen appears.
     *
     * Since the window no longer opens it on focus, something has to, and the
     * terminal is a typing surface first — arriving to a dead keyboard is the
     * wrong default. Tapping no longer opens it, and the toolbar button
     * toggles it.
     *
     * Waits for the view rather than firing blind: on first composition the
     * AndroidView factory has not attached it yet, and showKeyboard bails out
     * silently if the view has no size.
     */
    LaunchedEffect(Unit) {
        repeat(30) {
            val view = terminalViewRef.value
            if (view != null && view.isAttachedToWindow && view.width > 0) {
                showKeyboard()
                return@LaunchedEffect
            }
            delay(60)
        }
        Log.w("TerminalScreen", "Terminal view never became ready; keyboard not opened")
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
                // The IME inset belongs to the *screen*, not to the panes: it is
                // the keyboard's height, and each pane already takes what the
                // host gives it. Applying it above the host meant the lower pane
                // was drawn short by that amount and the space below it fell
                // through to the app background — a black band under the split,
                // growing with the keyboard.
                .droshImePadding(),
        ) {
        SplitPaneHost(
            layout = paneLayout,
            onSplitFractionChange = paneLayoutViewModel::dragSplitFraction,
            onSplitFractionCommit = paneLayoutViewModel::commitSplitFraction,
            onFloatingBoundsChange = paneLayoutViewModel::dragFloatingBounds,
            onFloatingBoundsCommit = paneLayoutViewModel::commitFloatingBounds,
            onExpandEdgeSnapped = paneLayoutViewModel::expandEdgeSnapped,
            onDock = paneLayoutViewModel::dock,
            onClosePane = paneLayoutViewModel::closeFloatingPane,
            onSwapPanes = paneLayoutViewModel::swapPanes,
            floatingTitle = secondarySessionName,
            // weight, not fillMaxSize: the extra-key bar is drawn by this
            // Column, below the host, and a host that filled the available height
            // would push it past the bottom edge.
            modifier = Modifier.weight(1f),
            primary = {
                TerminalPaneBody(
                    paneSlot = PaneSlot.PRIMARY,
                    terminalManager = terminalManager,
                    fontSizeSp = fontSizeSp,
                    colorProps = colorProps,
                    terminalViewModel = terminalViewModel,
                    blockEngineViewModel = blockEngineViewModel,
                    inputBarViewModel = inputBarViewModel,
                    extraKeyState = extraKeyState,
                    useBlockEngine = useBlockEngine,
                    terminalBgColor = terminalBgColor,
                    inputBarState = inputBarState,
                    motdMode = motdMode,
                    motdText = motdText,
                    systemInfo = systemInfo,
                    motdDismissed = motdDismissed,
                    onDismissMotd = { motdDismissed = true },
                    awaitingShellInput = awaitingShellInput,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    searchOverlayRef = searchOverlayRef,
                    terminalViewRef = terminalViewRef,
                    onBoundsChanged = { terminalBounds = it },
                    onUrlClick = { browserUrl = it },
                )
            },
            secondary = {
                TerminalPaneBody(
                    paneSlot = PaneSlot.SECONDARY,
                    terminalManager = terminalManager,
                    fontSizeSp = fontSizeSp,
                    colorProps = colorProps,
                    terminalViewModel = terminalViewModel,
                    blockEngineViewModel = blockEngineViewModel,
                    inputBarViewModel = inputBarViewModel,
                    extraKeyState = extraKeyState,
                    useBlockEngine = useBlockEngine,
                    terminalBgColor = terminalBgColor,
                    inputBarState = inputBarState,
                    motdMode = motdMode,
                    motdText = motdText,
                    systemInfo = systemInfo,
                    // The second pane does not repeat the welcome widget: it
                    // would appear twice on one screen, in a box half the
                    // width, saying nothing the first one has not.
                    motdDismissed = true,
                    onDismissMotd = {},
                    awaitingShellInput = awaitingShellInput,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    searchOverlayRef = remember { mutableStateOf<SearchHighlightOverlay?>(null) },
                    terminalViewRef = terminalViewRef,
                    // Bounds are the top bar's backdrop sample, and the top bar
                    // reads whichever pane has focus. Letting the unfocused pane
                    // report would leave the bar sampling a terminal the user is
                    // not looking at.
                    onBoundsChanged = {},
                    onUrlClick = { browserUrl = it },
                )
            },
        )

        /**
         * One extra-key bar for the whole screen, below the panes.
         *
         * It used to be drawn inside each pane, which meant a split carried two
         * — one under each terminal, both claiming the full width, both
         * answering for a keyboard that only ever types into one of them. A
         * special key is not a property of a pane; it is a property of *the*
         * keyboard. `inputBarViewModel` already routes an intent to whichever
         * pane `TerminalManager.focusedPane` names, so one bar is both correct
         * and sufficient.
         *
         * Drawn outside [SplitPaneHost] so it stays put when the divider moves —
         * inside it, the bar would travel with whichever pane it belonged to.
         */
        if (!inputBarState.hardwareKeyboardPresent) {
            FlatKeyBar(
                ctrlStuck = inputBarState.ctrlStuck,
                altStuck = inputBarState.altStuck,
                onIntent = inputBarViewModel::onIntent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

        // Selection menu. Anchored to the selection, whose bounds are in
        // terminal view coordinates; the host sits in this same Box, so the only
        // thing between the two is the status bar inset.
        selectionBounds?.let { bounds ->
            val density = LocalDensity.current
            val screenDp = LocalConfiguration.current.screenWidthDp
            // Derived from how many actions are actually showing, so the first
            // frame samples the right region instead of waiting to be measured.
            // Kopyala, Tümü, Paylaş and the keyboard toggle are unconditional.
            val actionCount = 4 + (if (hasClipboardText) 1 else 0) + (if (selectionUrl != null) 1 else 0)
            val densityPx = with(density) { menuWidthFor(actionCount).roundToPx() }
            val menuHeightPx = with(density) { MENU_HEIGHT_PX.roundToPx() }
            var measured by remember { mutableStateOf(IntSize.Zero) }
            val backdrop by SelectionMenuBackdrop(
                terminalView = terminalViewRef.value,
                bounds = bounds,
                sizePx = if (measured.width > 0) measured else IntSize(densityPx, menuHeightPx),
            )
            val inset = statusBarInset()
            val menuWidth = with(density) { menuWidthFor(actionCount) }
            val anchorX = with(density) {
                (bounds.left.toDp() - MENU_GUTTER)
                    .coerceIn(MENU_GUTTER, (screenDp.dp - menuWidth).coerceAtLeast(MENU_GUTTER))
            }
            val belowSelection = with(density) { bounds.bottom.toDp() } + MENU_GAP + inset

            SelectionMenuRow(
                backdrop = backdrop,
                selectedText = selectionText,
                url = selectionUrl,
                canPaste = hasClipboardText,
                keyboardFocused = keyboardFocused,
                blurRadius = MENU_BLUR,
                onCopy = {
                    selectionText?.let { context.copyToClipboard("terminal", it) }
                    terminalViewRef.value?.dismissSelection()
                },
                onPaste = { terminalViewRef.value?.pasteFromClipboard() },
                onOpenUrl = { url ->
                    browserUrl = url
                    terminalViewRef.value?.dismissSelection()
                },
                onSelectAll = { terminalViewRef.value?.selectAll() },
                onShare = { selectionText?.let { context.shareText(it) } },
                onToggleKeyboard = ::toggleKeyboard,
                modifier = Modifier
                    .offset(x = anchorX, y = belowSelection)
                    .onSizeChanged { measured = it },
            )
        }

        // Top bar overlay — floats on terminal, takes no layout space.
        TerminalTopBar(
                immersive = immersive,
                // Both the status bar and the row's position follow the
                // setting. Scrolling no longer moves anything: when fullscreen
                // is on the bar is gone and the pills sit where it was; when it
                // is off the status bar is there and the pills sit below it.
                rowInBand = immersive,
                backdrop = backdrop,
                terminalBounds = terminalBounds,
                viewModel = sessionSwitcherViewModel,
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
                onOpenSettings = onOpenSettings,
                onOpenAgent = onOpenAgent,
                isSplit = paneLayout.isSplit,
                isFloating = paneLayout.isFloating,
                onToggleFloat = paneLayoutViewModel::togglePresentation,
                onCloseSplit = paneLayoutViewModel::closeSplit,
                onCycleSplit = paneLayoutViewModel::cycleSplitFraction,
                onSwapPanes = paneLayoutViewModel::swapPanes,
                isSystemOverlay = paneLayout.isSystemOverlay,
                // Re-read on every recomposition rather than remembered once: the
                // user can grant or revoke this in the settings app while this
                // screen is alive, and a remembered value would keep offering a
                // menu entry that cannot work.
                canDrawOverlays = paneLayoutViewModel.canDrawOverlays(),
                onToggleSystemOverlay = { toggleSystemOverlay() },
        )

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
        onOpenSsh = onOpenSsh,
        onOpenProjects = onOpenProjects,
        pushState = sidebarPush,
        // Offered only when there is something to split into. With one live
        // session the grip would open a pane that can only ever be empty, and
        // the split would refuse it anyway.
        onSplitSession = if (liveSessionIds.size >= 2) {
            { id -> paneLayoutViewModel.openSplit(id, activeId) }
        } else {
            null
        },
        onFloatSession = if (liveSessionIds.size >= 2) {
            { id -> paneLayoutViewModel.openFloating(id, activeId) }
        } else {
            null
        },
        isSplit = paneLayout.isSplit,
        isFloatingPane = paneLayout.isFloating,
        /**
         * Ids, not names, and in the order they are drawn: the upper pane
         * first. The drawer's pair card mirrors the screen, and a swap changes
         * that order — a pair of names could not express it, so the card would
         * show the old arrangement while the terminal had already swapped.
         *
         * Both halves have to be a real session before the pair is offered.
         * [activeId] is nullable and the compiler infers a different nullability
         * for each branch here — one side is `secondary`, which is non-null by
         * the `let`, the other is `activeId` — so a `top to bottom` pair comes
         * out as `Pair<String, String?>` and does not fit. Both are resolved to
         * a local first, and a split missing either session is not drawn rather
         * than drawn with a blank half: a card naming one session and an empty
         * space beside it is worse than no card.
         */
        splitSessions = paneLayout.secondarySessionId?.let { secondary ->
            val primaryId = activeId
            if (primaryId != null) {
                if (paneLayout.secondarySwapped) secondary to primaryId
                else primaryId to secondary
            } else {
                null
            }
        },
        onCloseSplit = { paneLayoutViewModel.closeSplit() },
        onSwapPanes = { paneLayoutViewModel.swapPanes() },
        /**
         * The press-and-drop split: hold one session, tap another, and the two
         * share the screen with the held one on top.
         *
         * Which one goes on top follows the gesture rather than the terminal's
         * current session, because the gesture is the whole instruction — hold a
         * card, drop it on another, and the card you held is the one you were
         * pointing at.
         */
        onDragSplit = { draggedId, targetId ->
            paneLayoutViewModel.splitWithPrimary(draggedId, targetId)
        },
    )
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

/**
 * One pane's contents: the status-bar band, either the block list or the
 * classic terminal, and the extra-key bar.
 *
 * Extracted from what used to be inline in ReadyScreen so the same body can be
 * composed twice. Almost everything it needs is shared; the two things that are
 * not are [paneSlot] and the alt-buffer answer, and both are resolved here
 * rather than passed in, because the caller has no way to know the second
 * pane's answer without asking the manager anyway.
 *
 * It brings its own [Column] rather than relying on the caller's scope.
 * SplitPaneHost hands each pane a Box, and `weight` does not exist in a
 * BoxScope — the terminal has to take the pane's height and leave the rest to
 * the extra-key bar, so it has to be asked for in a Column.
 */
@Composable
private fun TerminalPaneBody(
    paneSlot: PaneSlot,
    terminalManager: TerminalManager,
    fontSizeSp: Int,
    colorProps: Properties,
    terminalViewModel: TerminalViewModel,
    blockEngineViewModel: BlockEngineViewModel,
    inputBarViewModel: InputBarViewModel,
    extraKeyState: dev.drosh.terminal.ExtraKeyState?,
    useBlockEngine: Boolean,
    terminalBgColor: Color,
    inputBarState: dev.drosh.ui.input.InputBarUiState,
    motdMode: MotdMode,
    motdText: String,
    systemInfo: SystemInfo,
    motdDismissed: Boolean,
    onDismissMotd: () -> Unit,
    awaitingShellInput: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    searchOverlayRef: MutableState<SearchHighlightOverlay?>,
    terminalViewRef: MutableState<TerminalView?>,
    onBoundsChanged: (Rect) -> Unit,
    onUrlClick: (String) -> Unit,
) {
    // A TUI (nano, vim, htop) takes over the alternate screen buffer, so the raw
    // terminal view has to be shown rather than the block list — the shell is
    // not producing line output to block up. Asked per pane: a vim in the other
    // pane says nothing about this one.
    val altBufferActive = terminalManager.isAltBufferActive(paneSlot)

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Behind the frame's own background, so the strip matches the
            // terminal it sits above without tinting anything else.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusBarInset())
                    .background(terminalBgColor),
            )

            if (useBlockEngine && !altBufferActive) {
                val blocks by blockEngineViewModel.blocks.collectAsState()
                val promptDir by blockEngineViewModel.lastDir.collectAsState()
                val promptSuffix by blockEngineViewModel.promptSuffix.collectAsState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = statusBarInset()),
                ) {
                    if (motdMode == MotdMode.Compose && !motdDismissed) {
                        MotdWidget(
                            motdText = motdText,
                            systemInfo = systemInfo,
                            onHelpClick = { /* TODO: open help */ },
                            onDismiss = onDismissMotd,
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
                                onUrlClick = onUrlClick,
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
            } else {
                /*
                 * CLASSIC TERMINAL PATH
                 *
                 * terminalViewRef is handed to the caller so overlays (search
                 * highlight, WebView chrome) can sample this exact TerminalView.
                 * Only the focused pane reports its bounds: the top bar samples
                 * one terminal to blur behind itself, and there is no sensible
                 * answer while two are on screen.
                 */
                TerminalViewHost(
                    paneSlot = paneSlot,
                    terminalManager = terminalManager,
                    fontSizeSp = fontSizeSp,
                    colorProps = colorProps,
                    terminalViewModel = terminalViewModel,
                    terminalViewRef = terminalViewRef,
                    extraKeyState = extraKeyState,
                    onUrlClick = onUrlClick,
                    searchQuery = if (searchActive && searchQuery.isNotBlank()) searchQuery else null,
                    searchOverlayRef = searchOverlayRef,
                    onBoundsChanged = onBoundsChanged,
                    modifier = Modifier
                        .fillMaxSize()
                        // The grid still starts below the system bar. Letting the
                        // first row run behind it puts the prompt under the clock,
                        // which is unreadable — a fixed grid is not scrolling
                        // content, so there is nothing to gain from it passing
                        // underneath. The band itself is painted with the
                        // terminal's own background just above, so it reads as
                        // part of the terminal rather than as a strip of its own.
                        .padding(top = statusBarInset()),
                )
            }
        }
        // No extra-key bar here.
        //
        // It used to be drawn per pane, which meant a split screen carried two
        // of them — one under each terminal, both claiming the whole width. A
        // special key is not a property of a pane, it is a property of *the*
        // keyboard: the thing being typed goes to whichever pane has focus, so
        // one bar at the bottom is the honest control. It is drawn by the caller,
        // outside SplitPaneHost, so it sits below both panes and stays put when
        // the divider moves.
    }
}

private const val TERMINAL_PINCH_THRESHOLD = 0.04f

@Composable
private fun TerminalViewHost(
    paneSlot: PaneSlot,
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
    /** Root-space bounds, for sampling the terminal as the top bar's backdrop. */
    onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val focusedPane by terminalManager.focusedPane.collectAsStateWithLifecycle()

    /**
     * This pane's own view.
     *
     * Local rather than the caller's shared ref, which now names whichever
     * pane has focus. Sharing one would make both panes apply their font size
     * and colours to the same view and leave the other one on the defaults.
     */
    val ownView = remember { mutableStateOf<TerminalView?>(null) }

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
        ownView.value?.setTextSize(fontSizeSp)
    }

    LaunchedEffect(colorProps) {
        ownView.value?.updateColors(colorProps)
    }

    // The caller's ref is what the keyboard, the selection menu and the top bar
    // backdrop all reach through, and all three mean "the terminal the user is
    // looking at". Republishing it on focus is what keeps them pointing at the
    // right one after a tap into the other pane.
    LaunchedEffect(focusedPane, ownView.value) {
        if (paneSlot == focusedPane) terminalViewRef.value = ownView.value
    }

    DisposableEffect(paneSlot) {
        onDispose { terminalManager.unregisterPaneView(paneSlot) }
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
        modifier = modifier
            .fillMaxSize()
            // Compose does not clip children to layout bounds the way a
            // ViewGroup does, and TerminalView draws a full-bleed background.
            .clipToBounds()
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },

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
                // This pane's session, not the active one. A View shows one
                // session, so the unfocused pane has to be attached to its own
                // or the split would render the same terminal twice.
                terminalManager.sessionForSlot(paneSlot)?.let { session ->
                    attachSession(session)
                }
                terminalManager.registerPaneView(paneSlot, this, ctx)

                // A tap is how the user says "I am working here". Wired to the
                // platform focus listener rather than a Compose click handler so
                // it does not consume the tap — the terminal still needs to see
                // it, to select a word or open a link, and an overlay handler
                // above the View would take both.
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) terminalManager.focusPane(paneSlot)
                }

                val listener =
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (width > 0 && height > 0 && isAttachedToWindow) {
                                viewTreeObserver.removeOnGlobalLayoutListener(this)
                                ownView.value = this@apply
                                if (paneSlot == focusedPane) {
                                    terminalViewRef.value = this@apply
                                    this@apply.requestFocus()
                                }
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
            val tv = ownView.value
            val overlay = searchOverlayRef.value

            tv?.setTextSize(fontSizeSp)
            tv?.updateColors(colorProps)
            terminalManager.sessionForSlot(paneSlot)?.let { session ->
                tv?.attachSession(session)
            }

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