package dev.drosh.feature.terminal

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import dev.drosh.feature.terminal.R
import dev.drosh.ui.keyboard.droshImePadding
import dev.drosh.ui.keyboard.droshNavigationBarPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.drosh.ui.LocalDroshActivity
import java.io.File
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import dev.drosh.core.copyToClipboard
import dev.drosh.core.shareText
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import dev.drosh.ui.DroshIcons
import dev.drosh.terminal.engine.native.NativeSearch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.terminal.session.GhosttySession
import dev.drosh.terminal.session.GhosttyTerminal
import dev.drosh.terminal.session.TerminalSessionCoordinator
import dev.drosh.domain.session.DEFAULT_SESSION_NAME
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.linux.UbuntuSetupState
import dev.drosh.ui.block.BlockEngineViewModel
import dev.drosh.ui.block.BlockScreen
import dev.drosh.ui.block.BlockInputField
import dev.drosh.ui.block.PromptBlock
import dev.drosh.ui.block.PromptDivider
import dev.drosh.ui.browser.WebViewSheet
import dev.drosh.ui.input.FlatKeyBar
import dev.drosh.ui.input.InputBarViewModel
import dev.drosh.ui.pane.PaneLayoutViewModel
import dev.drosh.ui.pane.SplitPaneHost
import dev.drosh.domain.session.SessionState
import dev.drosh.ui.session.SessionScreen
import dev.drosh.ui.session.SessionLogTarget
import dev.drosh.ui.session.Sidebar
import dev.drosh.ui.session.SessionSwitcherViewModel
import dev.drosh.ui.session.rememberSidebarPushState
import dev.drosh.ui.session.sidebarPush
import dev.drosh.domain.settings.AboutInfo
import dev.drosh.domain.settings.MotdMode
import dev.drosh.terminal.session.chromeCollapsed
import dev.drosh.ClipboardSink
import dev.drosh.TerminalBell
import dev.drosh.feature.terminal.topbar.rememberTerminalTopPadding
import dev.drosh.feature.terminal.topbar.TerminalTopBar
import dev.drosh.feature.terminal.topbar.TOP_BAR_BACKDROP_STRIP
import dev.drosh.feature.terminal.topbar.rememberTerminalBackdrop
import dev.drosh.domain.settings.SettingsRepository
// Termux view removed — engine is the only terminal now.
import kotlinx.coroutines.delay
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ReadyScreen(
    terminalManager: TerminalSessionCoordinator,
    terminalViewModel: TerminalViewModel,
    settingsRepository: SettingsRepository,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    sessionSwitcherViewModel: SessionSwitcherViewModel = hiltViewModel(),
    blockEngineViewModel: BlockEngineViewModel = hiltViewModel(),
    inputBarViewModel: InputBarViewModel = hiltViewModel(),
    paneLayoutViewModel: PaneLayoutViewModel = hiltViewModel(),
    extraKeyState: dev.drosh.terminal.session.ExtraKeyState? = null,
    onOpenAgent: () -> Unit = {},
    onOpenSsh: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
) {

    // ── Split panes ─────────────────────────────────────────────────────────
    val paneLayout by paneLayoutViewModel.layout.collectAsStateWithLifecycle()
    val liveSessionIds by terminalManager.liveSessionIdsFlow.collectAsStateWithLifecycle()

    val splitMessage by paneLayoutViewModel.splitMessage.collectAsStateWithLifecycle()
    // The context is read here, not inside the effect: a composable's value
    // cannot be read from a block that is not itself composable.
    val splitContext = LocalContext.current
    LaunchedEffect(splitMessage) {
        splitMessage?.let { msg ->
            android.widget.Toast.makeText(
                splitContext,
                msg,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            paneLayoutViewModel.clearSplitMessage()
        }
    }

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

    // Root-space bounds of the focused pane's terminal view, so the top bar can
    // find the slice of it that sits behind a pill. Only the focused pane reports:
    // the bar samples one terminal to blur behind itself, and there is no
    // sensible answer while two are on screen.
    var terminalBounds by remember { mutableStateOf<Rect?>(null) }

    // ── Chrome: fullscreen up in the scrollback, normal at the live edge ───────
    //
    // Two states, and nothing else:
    //
    //  - **Collapsed** — the viewport is up in the scrollback, a session nobody
    //    has scrolled yet, or a TUI owns the terminal. The app goes fullscreen, the
    //    pills move up into the space the status bar vacated, and the grid's
    //    padding shrinks to that band so the first line is not lost to it.
    //  - **Expanded** — at the live edge, once the user has scrolled and come
    //    back. The status bar is back and the pills sit below it, over the output.
    //
    // A session opens collapsed. There is nothing to have come back from yet, so
    // `hasScrolled` is what tells "just opened" from "sitting at the prompt" --
    // position alone cannot, because both sit at the same row.
    //
    // Read before the chrome's own state, because the immersive decision is the
    // two of them multiplied together: the scroll says the chrome may go, and
    // the setting says whether it is allowed to.
    val appearance by terminalViewModel.appearance.collectAsState()

    // The direction is the point: you are not looking for the clock while you are
    // typing, and you are definitely looking for it while reading something that
    // scrolled off. The bar disappears exactly when it is not needed.
    val atLiveEdge by terminalManager.isAtLiveEdge.collectAsStateWithLifecycle()
    val hasScrolled by terminalManager.hasScrolled.collectAsStateWithLifecycle()
    val tuiActive by terminalManager.focusedPaneAltBuffer
        .collectAsStateWithLifecycle()

    // Named `immersive` rather than `chromeCollapsed` so the call reads as a
    // call: a local of the same name would shadow the function it is calling.
    //
    // The setting gates the whole behaviour: with immersive off the chrome is
    // told to leave and never told anything else, so the status bar and the
    // pill row stay put whatever the terminal is doing. This is the one
    // appearance setting the chrome reads, and it reads it here because the
    // chrome's own state — scroll position, alt buffer — is computed in this
    // scope where the two can be married.
    //
    // `atLiveEdge`, not the scroll row: the manager applies the dead-zone rule
    // to every scroll and publishes the outcome, so reading the row here would
    // recompose the whole screen once per row of a fling to arrive at the same
    // boolean.
    // Whether the chrome is up: the user is reading the scrollback, or a
    // full-screen program owns the terminal.
    //
    // This is the *scroll's* answer, not the setting's. The setting decides
    // whether the system's own bars go away with it; the row's position and
    // the grid's clearance follow the scroll, so the row always tells the user
    // where they are.
    val chromeUp = chromeCollapsed(atLiveEdge, tuiActive, hasScrolled)

    // The system's bars follow the setting *and* the chrome.
    val immersive = appearance.terminal.immersive && chromeUp

    // The grid's own top padding follows the row, on the same tween. See
    // `rememberTerminalTopPadding` for why it cannot be a constant.
    // The grid's clearance follows the row, on the same tween.
    val terminalTopPadding = rememberTerminalTopPadding(immersive)

    // The bell's setting.
    val bellEnabled by terminalViewModel.bellEnabled.collectAsStateWithLifecycle()

    // The bell.
    //
    // A program that rings it is answered three ways: the terminal flashes,
    // the phone buzzes, and — when the app is not on screen — a notification
    // says it, because a program that says something while the user is
    // looking at something else has to be able to say it.
    val bellActivity = LocalDroshActivity.current as? android.app.Activity
    val bellLifecycleOwner = LocalLifecycleOwner.current

    // Whether the app is on screen. The bell is a notification only when it is
    // not: a notification for a flash the user just watched is noise.
    var appOnScreen by remember { mutableStateOf(true) }
    DisposableEffect(bellLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            appOnScreen = event == Lifecycle.Event.ON_RESUME
        }
        bellLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { bellLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(terminalManager, bellActivity) {
        TerminalBell.onCommandEnd = { exitCode ->
            bellActivity?.let { activity ->
                val text = activity.getString(
                    if (exitCode == 0) {
                        R.string.terminal_command_ok
                    } else {
                        R.string.terminal_command_failed
                    },
                )
                if (appOnScreen) {
                    // On screen: a toast, which says it and gets out of the way.
                    android.widget.Toast
                        .makeText(activity, text, android.widget.Toast.LENGTH_SHORT)
                        .show()
                } else {
                    // Not on screen: a notification, because a toast nobody
                    // sees is a toast that did not happen.
                    TerminalBell.notifyWith(activity, text, "")
                }
            }
        }
        TerminalBell.onNotify = { title, body ->
            bellActivity?.let { TerminalBell.notifyWith(it, title, body) }
        }
        TerminalBell.onBell = {
            if (bellEnabled) {
                terminalManager.focusedTerminal()?.bell()
            }
            if (!appOnScreen && bellActivity != null) {
                val name = terminalManager.focusedTerminal()?.sessionName
                    ?.takeIf { it.isNotBlank() }
                    ?: bellActivity.getString(dev.drosh.ui.R.string.app_name)
                TerminalBell.notify(bellActivity, name)
            }
        }
        onDispose {
            TerminalBell.onBell = null
            TerminalBell.onNotify = null
            TerminalBell.onCommandEnd = null
        }
    }

    // The clipboard gate. A program may write the clipboard only when the user
    // says so, and the sink is the one that answers the engine.
    val allowClipboardWrites by terminalViewModel.allowClipboardWrites
        .collectAsStateWithLifecycle()
    LaunchedEffect(allowClipboardWrites) {
        ClipboardSink.allowWrites = allowClipboardWrites
    }

    // The system bars are hidden globally by MainActivity's root effect, on
    // every route. This screen used to hide the status bar and toggle the
    // navigation bar on scroll; both are gone so the two owners cannot fight.
    // `immersive` still drives the pill row's position and the grid's clearance.

    var sidebarOpen by remember { mutableStateOf(false) }
    // Resource Monitor'un açtığı engine diagnostics raporu.
    var resourceDiagnosticsOpen by remember { mutableStateOf(false) }
    var resourceDiagnosticsText by remember { mutableStateOf("") }

    /**
     * Whether the session grid is showing.
     *
     * A full-screen overlay rather than a sheet: the grid *is* the terminal
     * switcher, and it wants the whole screen for its cards. It is not a
     * dialog either — it has no dismiss button of its own, because choosing a
     * card is the dismissal.
     */
    var sessionsGridOpen by remember { mutableStateOf(false) }
    val sidebarPush = rememberSidebarPushState(sidebarOpen)
    /**
     * The session the drawer is carrying, or null.
     *
     * The drawer owns the gesture and the terminal owns the screen, so the one
     * thing that crosses between them is this: which session is in the air. It
     * is what turns the terminal into a drop target without the two of them
     * having to agree on where the finger is.
     */
    var heldSessionId by remember { mutableStateOf<String?>(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }

    /**
     * Whether the session-log sheet is open.
     *
     * Owned here rather than by the sheet because two things reach it from
     * outside: the drawer footer opens it, and the sheet's own confirmation asks
     * for the agent screen afterwards.
     */

    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    /**
     * The query the last scan actually ran on, as opposed to what the field
     * shows.
     *
     * The field follows every keystroke; the scan waits for the typing to
     * settle, because a scan is a pass over the whole output and one per
     * keystroke would be a scan per character of a word.
     */
    var scannedQuery by remember { mutableStateOf("") }


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
    val defaultFontSizeSp by terminalViewModel.defaultFontSizeSp.collectAsState()
    val mouseReporting by terminalViewModel.mouseReporting.collectAsState()
    /**
     * Which terminal implementation the panes run.
     *
     * The vendored Termux terminal, or the Rust engine. Termux until the
     * engine has been through a device — the setting is what lets it be
     * tried without a rebuild, and what lets it be undone without one.
     */

    /** The terminal's configuration, as the engine is handed it. */
    val terminalConfig by terminalViewModel.terminalConfig.collectAsStateWithLifecycle()

    val colorProps by terminalViewModel.colorProps.collectAsState()
    val activeId by sessionSwitcherViewModel.activeId.collectAsState()
    val useBlockEngine by terminalViewModel.useBlockEngine.collectAsState()
    // Block mode's escape hatch. Immediate for this composition's panes, and
    // persisted so the next launch does not drop the user back into a blank
    // block view. The setting flow alone would wait for the write to land.
    var blockClassicOverride by remember { mutableStateOf(false) }
    val effectiveUseBlockEngine = useBlockEngine && !blockClassicOverride
    val onBackToClassic: () -> Unit = {
        blockClassicOverride = true
        scope.launch { settingsRepository.setUseBlockEngine(false) }
    }

    // Classic → block: the grid holds output the OSC 133 marks never described,
    // so the block view would be blank. Seed one bounded, read-only history
    // snapshot of the current session's transcript. The repository seeds at most
    // once per session, so navigating or toggling never stacks snapshots.
    LaunchedEffect(effectiveUseBlockEngine, activeId) {
        if (effectiveUseBlockEngine) {
            terminalManager.seedBlockHistorySnapshotForActiveSession()
        }
    }
    val backgroundFile by terminalViewModel.backgroundFile.collectAsStateWithLifecycle()
    val inputBarState by inputBarViewModel.uiState.collectAsState()
    val processExitEvent by terminalManager.processExitEvent.collectAsState()
    val noSessionsLeft by terminalManager.noSessionsLeft.collectAsState()
    val sessions by sessionSwitcherViewModel.allSessions.collectAsStateWithLifecycle()

    // The sessions that are running but not on screen: the grid marks them.
    val backgroundSessionIds by terminalManager.backgroundSessions
        .collectAsStateWithLifecycle()

    // The titles the programs asked for (OSC 0/2): `vim` puts the file name
    // there, a shell puts the directory.
    val sessionTitles by terminalManager.sessionTitles.collectAsStateWithLifecycle()

    /**
     * The sessions a log can be taken from, most recently used first.
     *
     * Live ones only, and live is what the terminal manager says rather than
     * what the database says: a row can exist for a process that has exited, and
     * offering it would open a sheet with nothing in it.
     */

    /**
     * Where the sheet reads a log from.
     *
     * Remembered against the manager rather than built per call: it is stateless
     * and the sheet asks for a transcript on every opening, so there is nothing
     * to recompute.
     */

    /**
     * The active input method can change while the app is in the background
     * (user switches to or away from the Drosh Keyboard), so the extra-keys
     * bar's companion check is re-read on every resume.
     */
    val resumeLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(resumeLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                inputBarViewModel.refreshImePresence()
            }
            // A floating pane outlives the app only over other apps: while the
            // app is on screen the window belongs to the app, and the moment
            // it goes away the pane has to move to the overlay or it dies with
            // the activity it was drawn in. It comes home the same way — the
            // overlay is a place to wait, not a place to stay.
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    scope.launch { paneLayoutViewModel.floatOverOtherApps() }
                }
                Lifecycle.Event.ON_START -> {
                    paneLayoutViewModel.dockFromOverlay()
                }
                else -> Unit
            }
        }
        resumeLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { resumeLifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    var keyboardFocused by remember { mutableStateOf(true) }

    /**
     * Where the chrome reads the focused terminal's appearance from.
     *
     * The pane publishes it, because only the pane knows what draws its
     * terminal: the Termux view today, the engine's session once the app
     * hosts one. The chrome asks for colours and never for a renderer, so the
     * two cannot be mixed up — a Termux-backed terminal is sampled from its
     * pixels and an engine-backed one from its cells.
     *
     // Termux view removed.
     * focused pane is the terminal the user is looking at.
     */
    val appearanceSource = remember {
        mutableStateOf<TerminalAppearanceSource?>(null)
    }


    // ── Find in the output ──────────────────────────────────────────────────
    //
    // Everything below is about one question the find bar asks: what does this
    // query find in the terminal the user is looking at. Three things hang off
    // it — the results, the counter the bar prints, and the terminal they came
    // from — and the order matters, because the results are computed against
    // the terminal and not the other way round.

    /**
     * What the find query found, and where the user is in it.
     *
     * Null until a query has actually been scanned. That is a different state
     * from "nothing matched", and conflating them is how a user comes to
     * believe a terminal with a thousand matches has none.
     *
     * The state carries the kind of terminal the matches came from, because
     * the two kinds answer in different coordinates and the bar has to say
     * which one it is showing.
     */
    var findState by remember { mutableStateOf<TerminalFindState?>(null) }

    /** How many matches the query found. Zero before it has been scanned. */
    val matchCount = findState?.count ?: 0

    /**
     * The match the user is on, one-based, so it prints as "3/41".
     *
     * Reads as 1 with nothing scanned: the bar has no counter at all until a
     * query has been typed, so the only number it can ever show alongside
     * zero matches is the one the arrows are already disabled for.
     */
    val currentMatch = ((findState?.activeIndex ?: -1) + 1).coerceAtLeast(1)

    /**
     * Which pane has focus, and therefore which pane the find bar is reading.
     *
     * Declared here, above everything that asks: the scan targets this pane's
     * terminal, the matches belong to it, and the bar's own navigation scrolls
     * it.
     */
    val focusedPaneSlot by terminalManager.focusedPane.collectAsStateWithLifecycle()

    // The appearance is read from the cells of the session the focused pane
    // is showing. It is asked for when it is needed rather than kept: the
    // pane registers itself with the manager after the first composition, so
    // a source resolved once would keep answering for a terminal that was
    // not there yet.
    LaunchedEffect(focusedPaneSlot) {
        val pane = terminalManager.focusedTerminal()
        appearanceSource.value = pane?.let { SessionAppearanceSource(it) }
    }

    /**
     * The focused pane's session, which is the whole record the find bar
     * searches.
     *
     * The engine's session owns the grid and its scrollback, and the engine's
     * own text of both is what [GhosttySession.search] scans. The manager hands
     * out panes, so the session is reached through the focused one and asked
     * for at the moment it is needed — a session resolved once would keep
     * answering for a terminal that is no longer on screen.
     */
    fun focusedSession(): GhosttySession? =
        (terminalManager.focusedTerminal() as? GhosttyTerminal)?.session

    /**
     * The find matches, for whichever pane they were scanned against.
     *
     * Rows are rows of one particular buffer. A split carries two of those,
     * and the scan ran against the focused pane's — so the other one gets
     * nothing, rather than highlights marking text in *its* transcript that
     * nobody asked about. The counter and the arrows stay global; they are
     * about the terminal the user is working in.
     */
    fun findResultsFor(slot: PaneSlot): Any? = null

    /**
     * Bumped by every chunk of output the focused terminal draws, so an open
     * find bar re-scans while the transcript grows under it.
     *
     * The counter is the reason. A count that says 41 while the terminal has
     * since printed twelve more matches is a count the user cannot trust, and
     * the active match drifting up out of view is the same detachment the
     * absolute-row anchoring fixes for the highlight.
     *
     // Termux view removed.
     * is the one that has focus — the terminal the user is looking at is the
     * terminal they mean — and a snapshot of it in a val would be the view as
     * it was when this composable first ran, before it had been measured.
     */
    var searchContentTick by remember { mutableStateOf(0) }

    /**
     * Bumped when the engine's output has moved on, so an open find bar
     * re-scans while the log grows under it.
     *
     * The engine has no tick of its own: its output arrives as a stream of
     * bytes, and the only thing that says whether the grid moved is the grid
     * itself. So the scan is repeated on a slow timer instead — see the
     * effect below.
     */
    var engineContentTick by remember { mutableStateOf(0) }

    /**
     * Settles the query before it is scanned.
     *
     * The field follows every keystroke, but a scan is a pass over the whole
     * output; running one per character of a word would keep the bar a step
     * behind the typing and never let a long query finish. Waiting a moment for
     * the typing to stop gives one scan per word instead.
     */
    LaunchedEffect(searchQuery) {
        if (searchQuery == scannedQuery) return@LaunchedEffect
        delay(FIND_DEBOUNCE_MILLIS)
        scannedQuery = searchQuery
    }

    /**
     * Repeats the scan while the find bar is open.
     *
     * The engine keeps no tick of its own: its output arrives as a stream of
     * bytes, and the only thing that says whether the grid moved is the grid
     * itself. So the scan is repeated on a slow timer instead, and the counter
     * the bar shows stays a step behind the output rather than becoming a lie
     * about it.
     */
    LaunchedEffect(searchActive, scannedQuery.isBlank()) {
        if (!searchActive || scannedQuery.isBlank()) return@LaunchedEffect
        while (isActive) {
            delay(ENGINE_RESCAN_INTERVAL_MS)
            engineContentTick++
        }
    }

    /**
     * Scans the focused session's whole output for the settled query.
     *
     * Re-run by the query settling, the terminal changing under the bar — the
     * pane taking focus, a session opening in it — and every tick of new
     * output. The scan reads the engine's whole transcript, so it runs on
     * Dispatchers.Default and the bar keeps its previous answer until the new
     * one lands rather than flashing a count of zero.
     */
    /**
     * Highlights the match the user is on.
     *
     * The match's line is a transcript row — the engine's own row space — and
     * the drawing is in the viewport's, so the viewport's first row is what
     * turns one into the other.
     */
    /**
     * Highlights the match the user is on.
     *
     * The active match is drawn in the strong colour, and every other match
     * on the screen in the weaker one, so the match the user is on stays the
     * strongest thing on the screen. The active one is left out of that set:
     * it is drawn by [TerminalEngine.setHighlight] in its own colour.
     */
    fun highlightMatch(state: TerminalFindState? = findState) {
        val s = state ?: return
        if (s.activeRow < 0) return
        val pane = terminalManager.focusedTerminal() ?: return
        val handle = focusedSession()?.terminalHandle ?: 0L
        val others = NativeSearch.viewportMatches(handle)
            .filter { it.first != s.activeRow || it.second != s.activeColumn }
        pane.setHighlights(others)
        pane.setHighlight(s.activeRow, s.activeColumn, s.activeLength)
    }

    LaunchedEffect(
        searchActive,
        scannedQuery,
        searchContentTick,
        engineContentTick,
        activeId,
    ) {
        if (!searchActive || scannedQuery.isBlank()) {
            findState = null
            terminalManager.focusedTerminal()?.setHighlight(0, 0, 0)
            return@LaunchedEffect
        }
        val session = focusedSession()
        if (session == null) {
            findState = null
            return@LaunchedEffect
        }

        val query = scannedQuery
        val previous = findState
        if (previous != null && previous.query != query) findState = null
        val rememberedIndex = previous
            ?.takeIf { it.query == query }
            ?.activeIndex
            ?: -1

        // The engine's own search: set the needle, then feed and tick until it
        // is caught up with the terminal. Runs off the main thread, the way the
        // transcript scan used to. The viewport is not moved by a scan — only
        // by the arrows — so the scroll policy is set to NONE for the initial
        // selection and restored afterwards.
        val handle = session.terminalHandle
        var total = 0
        var selectedIdx = -1
        withContext(Dispatchers.Default) {
            NativeSearch.setSelectScroll(handle, NativeSearch.SCROLL_NONE)
            NativeSearch.setNeedle(handle, query)
            var guard = 0
            while (guard < 100_000) {
                NativeSearch.feed(handle)
                var status = NativeSearch.tick(handle)
                while (status == NativeSearch.STATUS_RUNNING) {
                    status = NativeSearch.tick(handle)
                }
                if (status == NativeSearch.STATUS_COMPLETE) break
                guard++
            }
            total = NativeSearch.totalMatches(handle)
            if (total > 0 && rememberedIndex < 0) {
                NativeSearch.selectNext(handle)
            }
            NativeSearch.setSelectScroll(handle, NativeSearch.SCROLL_IF_NEEDED)
            selectedIdx = if (total > 0) NativeSearch.selectedIndex(handle) else -1
        }

        val active = when {
            total == 0 -> -1
            rememberedIndex >= 0 -> rememberedIndex.coerceIn(0, total - 1)
            else -> 0
        }
        // If the remembered index differs from the engine's selection, walk to it.
        // (Best effort: select next until there; bounded by total.)
        if (total > 0 && active >= 0) {
            // ensure engine selection matches active index by repeated selectNext
            // starting from current selectedIdx
            var current = selectedIdx
            var steps = 0
            while (current != active && steps < total) {
                NativeSearch.selectNext(handle)
                current = NativeSearch.selectedIndex(handle).let { if (it < 0) 0 else it }
                steps++
            }
        }

        val finalVp = NativeSearch.selectedMatch(handle)
        val finalRow = finalVp.getOrElse(0) { -1 }
        val finalCol = finalVp.getOrElse(1) { 0 }
        val finalLen = finalVp.getOrElse(2) { 0 }
        val finalText = NativeSearch.selectedMatchText(handle)

        findState = TerminalFindState(
            query = query,
            kind = TerminalFindKind.ENGINE,
            matches = TerminalFindMatches.Engine(emptyList()),
            activeIndex = active,
            matchCountOverride = total,
            activeTextOverride = finalText,
            activeRow = finalRow,
            activeColumn = finalCol,
            activeLength = finalLen,
        )
        if (active >= 0) {
            highlightMatch(findState)
        } else {
            terminalManager.focusedTerminal()?.clearHighlights()
            terminalManager.focusedTerminal()?.setHighlight(0, 0, 0)
        }
    }

    /**
     * Puts the match at [index] on the screen.
     *
     * A match's line is an absolute row of the engine's scroll space — 0 is the
     * oldest row the engine holds — and the engine's scrollbar counts from the
     * other end, where "rows above the live edge" is 0 at the bottom. The
     * conversion is the subtraction that turns one into the other, and the
     * margin leaves a few rows of context above the match so it is not pinned
     * to the top of the screen.
     *
     * The engine clamps the scroll to what history it has, so a request past
     * either end lands on the end rather than being refused.
     */
    /**
     * Walks the matches, wrapping at both ends, and puts the new one on screen.
     *
     * The engine selects the next/previous match and scrolls the viewport to
     * it; the app then reads the selected match's viewport coordinates and
     * quotes its text.
     */
    fun stepMatch(forward: Boolean) {
        val state = findState ?: return
        val count = state.count
        if (count == 0) return
        val session = focusedSession() ?: return
        val handle = session.terminalHandle
        val next = when {
            forward -> (state.activeIndex + 1) % count
            state.activeIndex < 0 -> count - 1
            else -> (state.activeIndex - 1 + count) % count
        }
        // Drive the engine's selection. It scrolls the viewport itself.
        if (forward) NativeSearch.selectNext(handle) else NativeSearch.selectPrev(handle)
        // If wrapping was needed, the modulo above already describes it; the
        // engine wraps on its own, so a single select is enough.
        val vp = NativeSearch.selectedMatch(handle)
        val row = vp.getOrElse(0) { -1 }
        val col = vp.getOrElse(1) { 0 }
        val len = vp.getOrElse(2) { 0 }
        val text = NativeSearch.selectedMatchText(handle)
        val idx = NativeSearch.selectedIndex(handle).let { if (it < 0) next else it }
        findState = state.copy(
            activeIndex = idx.coerceIn(0, (count - 1).coerceAtLeast(0)),
            matchCountOverride = NativeSearch.totalMatches(handle).let { if (it <= 0) count else it },
            activeTextOverride = text,
            activeRow = row,
            activeColumn = col,
            activeLength = len,
        )
        highlightMatch(findState)
    }

    // The selection's menu is drawn by the pane's own host; see the note where
    // it used to be composed here.

    // Only the classic path has a View to sample; the block engine is a
    // LazyColumn and the alt buffer is a TUI, neither of which needs this.
    //
    // Off, deliberately, and not deleted. The row's pills are opaque now, so
    // nothing on screen reads the strip — but the capture draws the whole
    // terminal view every 500ms, and paying that for a bitmap no one draws is
    // the one thing this switch is for. Flip `active` back to `!immersive` to
    // bring the glass back.
    val backdrop by rememberTerminalBackdrop(
        source = appearanceSource.value,
        stripHeight = TOP_BAR_BACKDROP_STRIP,
        active = false,
    )

    val searchOverlayRef = remember {
        mutableStateOf<Any?>(null)
    }

    fun showKeyboard() {
        try {
            val engine = terminalManager.focusedTerminal()
            if (engine != null) {
                engine.focus()
                val imm = engine.imeTarget.context.getSystemService(
                    Context.INPUT_METHOD_SERVICE,
                ) as InputMethodManager
                imm.showSoftInput(engine.imeTarget, InputMethodManager.SHOW_IMPLICIT)
                keyboardFocused = true
                return
            }
            Log.w("TerminalScreen", "No engine pane, cannot show keyboard")
        } catch (e: Exception) {
            Log.e("TerminalScreen", "showKeyboard failed", e)
        }
    }

    fun hideKeyboard() {
        try {
            val engine = terminalManager.focusedTerminal()
            if (engine != null) {
                val imm = engine.imeTarget.context.getSystemService(
                    Context.INPUT_METHOD_SERVICE,
                ) as InputMethodManager
                engine.imeTarget.windowToken?.let { token ->
                    imm.hideSoftInputFromWindow(token, 0)
                    keyboardFocused = false
                }
                return
            }
            Log.w("TerminalScreen", "No engine pane, cannot hide keyboard")
        } catch (e: Exception) {
            Log.e("TerminalScreen", "hideKeyboard failed", e)
        }
    }

    fun toggleKeyboard() {
        if (keyboardFocused) {
            hideKeyboard()
        } else {
            showKeyboard()
        }
    }

    /**
     * Runs a selected command in a session of its own.
     *
     * The command is handed to the manager *before* the tab exists: the tab's
     * shell reads one queued command as it starts, so the new session is
     * already running the selection by the time the user looks at it — no
     * typing into a prompt that may not be up yet.
     *
     * The tab is named after the command's first words, which is what makes
     * four of them distinguishable in the session grid.
     */
    fun runSelectionInNewTab(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        terminalManager.queueStartupCommand(trimmed)
        val name = trimmed.lineSequence().first().trim().take(24).ifBlank { "run" }
        sessionSwitcherViewModel.createNew(name)
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
            if (terminalManager.focusedTerminal() != null) {
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
        // The root is painted with the drawer's own surface. Without it, the
        // terminal's card — inset by the drawer's progress and rounded to match
        // it — showed its corners straight through to whatever was behind the
        // window, which on a device with a dark wallpaper reads as the app
        // having emptied itself out.
        modifier = Modifier
            .fillMaxSize()
            .background(DroshSurface),
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
        // The terminal's own colour, and not the app's.
        //
        // This is the ground of the whole terminal screen, and the chrome
        // floats over it: the clock, the pill row, the extra-key bar. The
        // app's background was behind all of it, which in the light theme is a
        // near-white field — so a dark terminal had a white strip at the top,
        // the one part of the screen that belonged to neither the terminal nor
        // the chrome. The terminal's background is the honest colour there:
        // it is the colour the grid is drawn on.
        .background(Color(hexToColorInt(appearance.terminal.palette.background)))
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
                .droshImePadding()
                // And the navigation bar's band below it: the render ends
                // above the bar, where the output can still be read.
                .droshNavigationBarPadding(),
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
            // The name the dock pill shows when the seam is dragged to an edge.
            secondaryTitle = secondarySessionName,
            // weight, not fillMaxSize: the extra-key bar is drawn by this
            // Column, below the host, and a host that filled the available height
            // would push it past the bottom edge.
            modifier = Modifier.weight(1f),
            primary = {
                TerminalPaneBody(
                    paneSlot = PaneSlot.PRIMARY,
                    terminalManager = terminalManager,
                    fontSizeSp = fontSizeSp,
                    defaultFontSizeSp = defaultFontSizeSp,
                    mouseReporting = mouseReporting,
                    highlightColour = hexToColorInt(appearance.terminal.palette.yellow),
                    highlightAllColour = DroshPrimary.toArgb(),
                    bellColour = hexToColorInt(appearance.terminal.palette.accent),
                    colorProps = colorProps,
                    backgroundFile = backgroundFile,
                    terminalBackgroundColor = hexToColorInt(appearance.terminal.palette.background),
                    backgroundOpacity = appearance.background.imageOpacity,
                    contentPaddingDp = appearance.background.contentPaddingDp,
                    cornerRadiusDp = appearance.background.cornerRadiusDp,
                    dimmed = appearance.terminal.dimInactivePanes &&
                        focusedPaneSlot != PaneSlot.PRIMARY,
                    keepAwake = appearance.terminal.keepAwake,
                    keepPositionWhileReading = appearance.terminal.keepPositionWhileReading,
                    terminalViewModel = terminalViewModel,
                    blockEngineViewModel = blockEngineViewModel,
                    inputBarViewModel = inputBarViewModel,
                    extraKeyState = extraKeyState,
                    useBlockEngine = effectiveUseBlockEngine,
                    onBackToClassic = onBackToClassic,
                    // The pane the chrome actually floats over. The pill row
                    // and the status bar are both above it, so it is the one
                    // pane that has to clear them: without this the first
                    // line of output sat under the pills, and the clearance
                    // had to move with the chrome rather than being a
                    // constant — see rememberTerminalTopPadding.
                    topPadding = terminalTopPadding,
                    inputBarState = inputBarState,
                    motdMode = motdMode,
                    motdText = motdText,
                    systemInfo = systemInfo,
                    motdDismissed = motdDismissed,
                    onDismissMotd = { motdDismissed = true },
                    awaitingShellInput = awaitingShellInput,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    searchResults = findResultsFor(PaneSlot.PRIMARY),
                    searchOverlayRef = searchOverlayRef,
                    appearanceSource = appearanceSource,
                    terminalConfig = terminalConfig,
                    onBoundsChanged = { terminalBounds = it },
                    onUrlClick = { browserUrl = it },
                    onSearchSelection = { query ->
                        // One find bar serves both panes and one terminal's
                        // matches at a time; opening it from a selection is
                        // what makes "search what I just selected" a single
                        // gesture instead of a copy, a bar and a paste.
                        hideKeyboard()
                        findState = null
                        searchQuery = query
                        scannedQuery = query
                        searchActive = true
                    },
                    onRunInNewSession = { command -> runSelectionInNewTab(command) },
                    onThumbnail = { id, bitmap ->
                        sessionSwitcherViewModel.setThumbnail(id, bitmap)
                    },
                )
            },
            secondary = {
                TerminalPaneBody(
                    paneSlot = PaneSlot.SECONDARY,
                    terminalManager = terminalManager,
                    fontSizeSp = fontSizeSp,
                    defaultFontSizeSp = defaultFontSizeSp,
                    mouseReporting = mouseReporting,
                    highlightColour = hexToColorInt(appearance.terminal.palette.yellow),
                    highlightAllColour = DroshPrimary.toArgb(),
                    bellColour = hexToColorInt(appearance.terminal.palette.accent),
                    colorProps = colorProps,
                    backgroundFile = backgroundFile,
                    terminalBackgroundColor = hexToColorInt(appearance.terminal.palette.background),
                    backgroundOpacity = appearance.background.imageOpacity,
                    contentPaddingDp = appearance.background.contentPaddingDp,
                    cornerRadiusDp = appearance.background.cornerRadiusDp,
                    dimmed = appearance.terminal.dimInactivePanes &&
                        focusedPaneSlot != PaneSlot.SECONDARY,
                    keepAwake = appearance.terminal.keepAwake,
                    keepPositionWhileReading = appearance.terminal.keepPositionWhileReading,
                    terminalViewModel = terminalViewModel,
                    blockEngineViewModel = blockEngineViewModel,
                    inputBarViewModel = inputBarViewModel,
                    extraKeyState = extraKeyState,
                    useBlockEngine = effectiveUseBlockEngine,
                    onBackToClassic = onBackToClassic,
                    // The pane below the seam, and it clears nothing: what
                    // sits above it is the divider line, and the pane's grid
                    // starts directly on that line. Giving it the chrome
                    // clearance instead — which is what it had — pushed the
                    // lower session's whole grid down by the height of a bar
                    // that was never over it, a band of nothing between the
                    // seam and the first row.
                    topPadding = 0.dp,
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
                    searchResults = findResultsFor(PaneSlot.SECONDARY),
                    searchOverlayRef = remember { mutableStateOf<Any?>(null) },
                    appearanceSource = appearanceSource,
                    terminalConfig = terminalConfig,
                    // Bounds are the top bar's backdrop sample, and the top bar
                    // reads whichever pane has focus. Letting the unfocused pane
                    // report would leave the bar sampling a terminal the user is
                    // not looking at.
                    onBoundsChanged = {},
                    onUrlClick = { browserUrl = it },
                    onSearchSelection = { query ->
                        hideKeyboard()
                        findState = null
                        searchQuery = query
                        scannedQuery = query
                        searchActive = true
                    },
                    onRunInNewSession = { command -> runSelectionInNewTab(command) },
                    onThumbnail = { id, bitmap ->
                        sessionSwitcherViewModel.setThumbnail(id, bitmap)
                    },
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
         * pane `TerminalSessionCoordinator.focusedPane` names, so one bar is both correct
         * and sufficient.
         *
         * Drawn outside [SplitPaneHost] so it stays put when the divider moves —
         * inside it, the bar would travel with whichever pane it belonged to.
         */
        if (!inputBarState.hardwareKeyboardPresent && !inputBarState.droshKeyboardActive) {
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
        // Top bar overlay — floats on terminal, takes no layout space.
        TerminalTopBar(
                chromeCollapsed = immersive,
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
                onPreviousPrompt = {
                    terminalManager.focusedTerminal()?.scrollToPrompt(-1)
                },
                onNextPrompt = {
                    terminalManager.focusedTerminal()?.scrollToPrompt(1)
                },
                onFindInOutput = {
                    // The terminal's own keyboard goes first: the find bar is
                    // about to raise its own, and two keyboards in one row is
                    // one keyboard too many.
                    hideKeyboard()
                    // A bar opened again searches nothing until the user types:
                    // a stale query from the last time is not this time's.
                    searchQuery = ""
                    scannedQuery = ""
                    findState = null
                    searchActive = true
                },
                onRefresh = {
                    // The engine's terminal owns its session, so the refresh
                    // restarts that session; the vendored path restarts the
                    // session the manager owns.
                    val engine = terminalManager.focusedTerminal()
                    if (engine != null) {
                        val command = terminalManager.guestCommand()
                        if (command != null) {
                            engine.stop()
                            engine.start(command)
                        }
                    } else {
                        terminalManager.restartCurrentTab()
                    }
                },
                onOpenSettings = onOpenSettings,
                onOpenSessions = {
                    hideKeyboard()
                    sessionsGridOpen = true
                },
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

        // Find bar — docked to the bottom edge.
        //
        // It sat under the chrome row at the top, which put it as far as
        // possible from the keyboard it raises and over the first lines of the
        // output — the lines a find result is least likely to be on. Docked at
        // the bottom it is where the thumb already is, it clears the grid it is
        // searching, and it cannot be left adrift somewhere else on screen.
        if (searchActive) {
            BackHandler {
                hideKeyboard()
                searchActive = false
                searchQuery = ""
                scannedQuery = ""
                findState = null
                // The match is not highlighted any more.
                terminalManager.focusedTerminal()?.clearHighlights()
                terminalManager.focusedTerminal()?.setHighlight(0, 0, 0)
            }

            TerminalFindBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = matchCount,
                currentMatch = currentMatch,
                onNext = { stepMatch(forward = true) },
                onPrevious = { stepMatch(forward = false) },
                onClose = {
                    hideKeyboard()
                    searchActive = false
                    searchQuery = ""
                    scannedQuery = ""
                    val handle = focusedSession()?.terminalHandle ?: 0L
                    NativeSearch.free(handle)
                    findState = null
                    // The match is not highlighted any more.
                    terminalManager.focusedTerminal()?.clearHighlights()
                    terminalManager.focusedTerminal()?.setHighlight(0, 0, 0)
                },
                // Which terminal the matches came from, and the text of the
                // one the user is on. The engine's matches now sit on grid
                // rows, so the arrows scroll the viewport to them; the quote
                // is still shown because seeing the matched text is useful
                // even when the screen has moved to it.
                kind = findState?.kind,
                matchText = findState?.activeText,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // The selection's menu and its handles are drawn by the pane's own
        // host (`GhosttyTerminalHost`), next to the view that owns the gesture.
        // They used to be drawn here, an ancestor of the host: the rectangle is
        // in the pane's pixels, and a menu placed from outside the pane had to
        // be told where the pane sat before it could sit on the selection —
        // which is why the grips never appeared.

        // The session grid, over everything.
        //
        // Drawn before the drawer in the Box, so a drawer opened from inside the
        // grid (there is no such path today, but the nav rows are the reason
        // this could change) still wins. It is a sibling of the terminal rather
        // than a sheet because it wants the whole screen for its cards.
        // The grid comes up over the terminal and goes back down into it, so
        // the two read as one screen changing rather than one replacing
        // another. The exit has to keep the grid in the composition until the
        // animation is over, which is why this is an AnimatedVisibility and
        // not an `if`.
        AnimatedVisibility(
            visible = sessionsGridOpen,
            enter = fadeIn(tween(GRID_TRANSITION_MILLIS)) +
                slideInVertically(tween(GRID_TRANSITION_MILLIS)) { it / 8 },
            exit = fadeOut(tween(GRID_TRANSITION_MILLIS)) +
                slideOutVertically(tween(GRID_TRANSITION_MILLIS)) { it / 8 },
        ) {
            BackHandler { sessionsGridOpen = false }

            SessionScreen(
                backgroundIds = backgroundSessionIds,
                titles = sessionTitles,
                sessions = sessions,
                activeId = activeId,
                onOpen = { id ->
                    sessionSwitcherViewModel.activate(id)
                    sessionsGridOpen = false
                },
                onClose = { id -> sessionSwitcherViewModel.delete(id) },
                onNewSession = {
                    sessionSwitcherViewModel.createNew("shell")
                    sessionsGridOpen = false
                },
                onCloseAll = {
                    // Every live session closed at once. The exit dialog owns
                    // what happens when the last one goes, so this only has to
                    // ask for the closes.
                    sessions
                        .filter { it.state != SessionState.Closed }
                        .forEach { sessionSwitcherViewModel.delete(it.id) }
                },
                onGroupSessions = { anchor, partner ->
                    // The same call the drawer's drop makes, and the same one the
                    // row menu makes: a split is a pair, and the terminal owns
                    // which pane each session lands in.
                    //
                    // The anchor goes to the primary pane, and the primary
                    // pane shows the *active* session — so the anchor has to
                    // become the active one. Without this the pair was made
                    // and the primary pane kept showing whatever was active
                    // before it.
                    sessionSwitcherViewModel.activate(anchor)
                    paneLayoutViewModel.openSplit(partner, anchor)
                    sessionsGridOpen = false
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(DroshSurface)
                    .zIndex(3f),
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

        // A card carried out of the drawer lights the terminal as the place it
        // will land. Purely visual: the gesture belongs to the row, and a target
        // that consumed touches would end the very drag it is advertising.
        //
        // It sits at the drawer's far edge rather than the screen's middle,
        // because the drawer is over the middle — the visible terminal is the
        // strip beside it, and a target under the drawer is a target nobody
        // can drop on.
        if (sidebarOpen && heldSessionId != null) {
            val held = sessions.firstOrNull { it.id == heldSessionId }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(DroshPrimary.copy(alpha = 0.12f))
                    .border(1.dp, DroshPrimary, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = DroshIcons.PanelBottom,
                        contentDescription = null,
                        tint = DroshPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(dev.drosh.ui.R.string.sidebar_drop_to_split),
                        color = DroshPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (held != null) {
                        Text(
                            text = held.name,
                            color = DroshTextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // The single exit confirmation lives here. MainActivity does not show a
        // second one when the last session goes — its two dialogs are the PIN
        // gate and the external-settings-change notice — so there is nothing to
        // consolidate. The stale `shouldExit` read this screen used to collect
        // was never shown, and has been dropped so this remains the one dialog.
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
                            fontFamily = LocalFontSet.current.sans,
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
                            fontFamily = LocalFontSet.current.sans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.terminal_delete_last_session_title),
                        color = DroshText,
                        fontFamily = LocalFontSet.current.sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                },
                text = {
                    val exitCode = sessionEndEvent?.exitCode
                    // A reason the app knows beats an exit code alone: a guest
                    // runtime that is not in the build is a packaging fact, and
                    // "exit 127" says nothing about it.
                    val reason = sessionEndEvent?.reason
                    Text(
                        text = when {
                            reason != null -> stringResource(
                                R.string.terminal_guest_runtime_missing,
                                reason,
                            )

                            exitCode != null ->
                                stringResource(R.string.terminal_delete_last_session_ended_body, exitCode)

                            else -> stringResource(R.string.terminal_delete_last_session_body)
                        },
                        color = DroshTextSecondary,
                        fontFamily = LocalFontSet.current.sans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = DroshSurface,
            )
        }
    }

    Sidebar(
        isOpen = sidebarOpen,
        onClose = { sidebarOpen = false },
        onOpenDiagnostics = {
            sidebarOpen = false
            resourceDiagnosticsText =
                dev.drosh.terminal.engine.diagnostics.EngineDiagnostics.report()
            resourceDiagnosticsOpen = true
        },
        onOpenSettings = onOpenSettings,
        onOpenKeyboard = { toggleKeyboard() },
    )

    if (resourceDiagnosticsOpen) {
        AlertDialog(
            onDismissRequest = { resourceDiagnosticsOpen = false },
            title = { Text("Engine diagnostics") },
            text = {
                Text(
                    resourceDiagnosticsText,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { resourceDiagnosticsOpen = false }) { Text("Close") }
            },
        )
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
