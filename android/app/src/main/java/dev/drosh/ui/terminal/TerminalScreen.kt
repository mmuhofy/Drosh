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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import dev.drosh.ui.R
import dev.drosh.ui.keyboard.droshImePadding
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import dev.drosh.ui.LocalDroshActivity
import java.io.File
import android.os.Build
import android.view.WindowManager
import dev.drosh.domain.UrlDetector
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.terminal.session.TerminalManager
import dev.drosh.domain.session.DEFAULT_SESSION_NAME
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.linux.UbuntuSetupState
import dev.drosh.ui.block.BlockEngineViewModel
import dev.drosh.ui.block.BlockInputField
import dev.drosh.ui.block.PromptBlock
import dev.drosh.ui.block.PromptDivider
import dev.drosh.ui.browser.WebViewSheet
import dev.drosh.ui.input.FlatKeyBar
import dev.drosh.ui.input.InputBarViewModel
import dev.drosh.ui.pane.PaneLayoutViewModel
import dev.drosh.ui.pane.SplitPaneHost
import dev.drosh.ui.session.SessionLogTarget
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
import dev.drosh.terminal.session.chromeCollapsed
import dev.drosh.ui.topbar.rememberTerminalTopPadding
import dev.drosh.ui.topbar.TerminalTopBar
import dev.drosh.ui.topbar.TOP_BAR_BACKDROP_STRIP
import dev.drosh.ui.topbar.rememberTerminalBackdrop
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

/**
 * Shown in place of the shell prompt while an interactive program owns the
 * terminal. Block mode has no terminal view of its own, so the line editor at
 * the bottom is the only way in; it is relabelled so it is clear that the input
 * is going to the program rather than to a shell.
 */
private const val PROGRAM_PROMPT_MARKER = "›"

/**
 * Clearance for the system bar, without insetting the background behind it.
 *
 * The visibility-agnostic inset, deliberately. The system status bar is shown
 * and hidden by the scroll position, and `statusBars` reads zero the moment it
 * hides — so anything measured with it here would jump a bar's height every time
 * the viewport crossed the live edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun statusBarInset(): Dp =
    WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()

/** Selection menu metrics. */
private val MENU_GUTTER = 12.dp
private val MENU_GAP = 10.dp
private val MENU_MIN_WIDTH = 210.dp

/** Light blur, not an Apple-style frosted slab. */
private val MENU_BLUR = 10.dp

/**
 * How hard the selection menu's backdrop is blurred.
 *
 * The Termux picture is a picture, and blurring it smears the output behind
 * the menu — which is the whole point of the glass. The engine's picture of
 * the region is one flat colour, the region's average, and blurring a flat
 * colour changes nothing at all: so there the blur is not paid for.
 */
private fun menuBlurRadius(kind: TerminalAppearanceKind?): Dp =
    if (kind == TerminalAppearanceKind.ENGINE) 0.dp else MENU_BLUR

private val MENU_HEIGHT_PX = 64.dp

@Composable
fun TerminalScreen(
    terminalManager: TerminalManager,
    settingsRepository: SettingsRepository,
    ubuntuSetupState: UbuntuSetupState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit = {},
    terminalViewModel: TerminalViewModel = hiltViewModel(),
    extraKeyState: dev.drosh.terminal.session.ExtraKeyState? = null,
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
    extraKeyState: dev.drosh.terminal.session.ExtraKeyState? = null,
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
    val immersive = appearance.terminal.immersive &&
        chromeCollapsed(atLiveEdge, tuiActive, hasScrolled)

    // The grid's own top padding follows the row, on the same tween. See
    // `rememberTerminalTopPadding` for why it cannot be a constant.
    val terminalTopPadding = rememberTerminalTopPadding(immersive)

    val activity = LocalDroshActivity.current
    LaunchedEffect(immersive) {
        val window = (activity as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // BEHAVIOR_DEFAULT, not BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE. The pills
        // live at the very top of the screen — which is where Android's edge
        // gesture lives — and the transient behaviour reveals the bars *over the
        // app* and then times itself out, so on a slow upward scroll the system
        // drew a status bar across the row over and over. With this one, a
        // deliberate top-edge swipe brings the bars back for good.
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    var sidebarOpen by remember { mutableStateOf(false) }
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
    /**
     * Which terminal implementation the panes run.
     *
     * The vendored Termux terminal, or the Rust engine. Termux until the
     * engine has been through a device — the setting is what lets it be
     * tried without a rebuild, and what lets it be undone without one.
     */

    /** The terminal's configuration, as the engine is handed it. */
    val terminalConfig by terminalViewModel.terminalConfig.collectAsStateWithLifecycle()

    /** The font file the user picked for the terminal, if any. */
    val customFontUri by terminalViewModel.customFontUri.collectAsStateWithLifecycle()
    val colorProps by terminalViewModel.colorProps.collectAsState()
    val activeId by sessionSwitcherViewModel.activeId.collectAsState()
    val useBlockEngine by terminalViewModel.useBlockEngine.collectAsState()
    val backgroundFile by terminalViewModel.backgroundFile.collectAsStateWithLifecycle()
    val shouldExit by sessionSwitcherViewModel.shouldExit.collectAsState()
    val inputBarState by inputBarViewModel.uiState.collectAsState()
    val processExitEvent by terminalManager.processExitEvent.collectAsState()
    val noSessionsLeft by terminalManager.noSessionsLeft.collectAsState()
    val sessions by sessionSwitcherViewModel.allSessions.collectAsStateWithLifecycle()

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

    /**
     * The source the find bar asks for the matches.
     *
     * The bar does not know what draws the terminal it is searching: it asks
     * a source, and the source answers in its own coordinates — the rows of a
     * Termux buffer, or the lines of the engine's output log. The two are
     * told apart by the kind, so an engine answer is never passed off as a
     * Termux one and the other way round.
     *
     * Asked for at the moment it is needed rather than kept: the engine's
     * pane registers itself with the manager after the first composition, so
     * a source resolved once would keep answering for a terminal that was not
     * there yet.
     */
    fun findSource(): TerminalFindSource? {
        val enginePane = terminalManager.focusedEnginePane()
        return if (enginePane != null) EngineFindSource(enginePane.session) else null
    }

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
     * Follows the focused terminal's screen, which is where new output lands.
     *
     * A flow on the view rather than a listener: the view is replaced when a
     * pane re-opens, and a callback left on the old one would keep counting
     * for a terminal that is no longer on screen.
     *
     * Gated on time as well, and that gate is the whole point of it. A firehose
     * would otherwise restart the scan once per chunk, forever — the effect
     * restarts on every key, so a scan that never finishes is a scan that never
     * answers, and a find bar stuck on "no matches" under heavy output is worse
     * than one that is a fifth of a second behind it.
     */
    /**
     * Repeats the scan while the find bar is open on an engine-backed
     * terminal.
     *
     * The vendored path does not come through here: the view's own ticks
     * drive its rescan, and a second timer on top of that would only read
     * the same scrollback twice.
     *
     * The engine's timer is the slower of the two on purpose: its scan is a
     * pass over the whole output log, and the log is what the user is
     * searching in, not a screenful of rows.
     */
    LaunchedEffect(searchActive, findState?.kind) {
        if (!searchActive) return@LaunchedEffect
        if (findState?.kind != TerminalFindKind.ENGINE) return@LaunchedEffect
        while (isActive) {
            delay(ENGINE_RESCAN_INTERVAL_MS)
            engineContentTick++
        }
    }

    /**
     * Scans the focused terminal's whole scrollback for the current query.
     *
     * Re-run by the query changing, the terminal changing under the bar — the
     * pane taking focus, a session opening in it — and every gated tick of new
     * output. A scan reads a few thousand rows, so it runs on
     * Dispatchers.Default and the bar keeps its previous answer until the new
     * one lands rather than flashing a count of zero.
     */
    LaunchedEffect(
        searchActive,
        searchQuery,
        searchContentTick,
        engineContentTick,
        activeId,
    ) {
        if (!searchActive || searchQuery.isBlank()) {
            findState = null
            return@LaunchedEffect
        }
        val source = findSource() ?: return@LaunchedEffect

        val query = searchQuery
        // Where the user was, carried across a rescan. The row a match sits on
        // moves as output arrives; the text they navigated to does not. Held
        // as an index rather than a row, so a list that has grown stays
        // navigable instead of pointing at a line that is now somewhere else.
        //
        // Kept for the *same* query and dropped for a new one: a rescan of what
        // is already on screen should not move the user, and the answers to a
        // different question are not answers to this one.
        val previous = findState
        if (previous != null && previous.query != query) findState = null
        val rememberedIndex = previous
            ?.takeIf { it.query == query }
            ?.activeIndex
            ?: -1

        val matches = withContext(Dispatchers.Default) {
            source.search(query)
        }
        // The first match is active from the moment the query resolves, so the
        // bar reads "1/41" and the first hit is the one drawn stronger. No
        // reveal here on purpose: a scan runs on every keystroke, and jumping
        // the viewport to the oldest match each time would throw the user out
        // of the output they are typing in. The viewport moves when they ask
        // it to, with the arrows.
        findState = TerminalFindState(
            query = query,
            kind = source.kind,
            matches = matches,
            activeIndex = when {
                matches.count == 0 -> -1
                rememberedIndex >= 0 -> rememberedIndex.coerceIn(0, matches.count - 1)
                else -> 0
            },
        )
    }

    /**
     * Walks the matches, wrapping at both ends, and puts the new one on screen.
     *
     * Wrapping rather than stopping: a bar whose next arrow dies at the last
     * match makes the user reach for the other arrow to get back to the first,
     * which is a longer way of saying "start again".
     */
    fun stepMatch(forward: Boolean) {
        val state = findState ?: return
        val count = state.count
        if (count == 0) return
        // Nothing active yet is the position *before* the first match, so
        // previous from there wraps to the last one. Reading -1 as a modular
        // index would land one short of it, which is the kind of off-by-one
        // that looks like a skipped match.
        val next = when {
            forward -> (state.activeIndex + 1) % count
            state.activeIndex < 0 -> count - 1
            else -> (state.activeIndex - 1 + count) % count
        }
        findState = state.copy(activeIndex = next)
        // Reveal after the state write, so the viewport follows the match the
        // user is now on rather than the one they were.
        //
        // The source says whether it can: the vendored terminal scrolls to
        // the row the match is on, and the engine cannot — a line of its
        // output log is not a grid row, so there is nowhere on the screen to
        // scroll to. It says no, and the screen stays where it is.
        findSource()?.reveal(state.matches, next)
    }

    // Text selection menu. The platform ActionMode is switched off when the
    // view registers — see TerminalManager.bindSelectionMenu — and this draws
    // the replacement, so it can use the app's own surface and a real blur of
    // the output behind it.
    val selectionBounds by terminalManager.selectionBounds.collectAsStateWithLifecycle()
    val hasSelection by terminalManager.hasSelection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    /**
     * The selected text, or null when there is no selection.
     *
     * The vendored view's own selection, or the engine pane's: the manager
     * publishes the bounds for both kinds of terminal, and each one knows
     * its own text. The vendored view is asked first, so the path the app
     * has always taken is unchanged.
     */
    val selectionText by remember(hasSelection, selectionBounds) {
        derivedStateOf {
            if (!hasSelection) {
                null
            } else {
                terminalManager.focusedEnginePane()?.selectionText()?.takeIf { it.isNotEmpty() }
            }
        }
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
        source = appearanceSource.value,
        stripHeight = TOP_BAR_BACKDROP_STRIP,
        // Only while the pills float over output. Collapsed they sit in the band
        // the status bar vacated, above the grid, so there is nothing behind them
        // to take — and the capture draws the whole terminal to produce a slice
        // nothing shows.
        active = !immersive,
    )

    val searchOverlayRef = remember {
        mutableStateOf<Any?>(null)
    }

    fun showKeyboard() {
        try {
            val engine = terminalManager.focusedEnginePane()
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
            val engine = terminalManager.focusedEnginePane()
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
            if (terminalManager.focusedEnginePane() != null) {
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
                    colorProps = colorProps,
                    backgroundFile = backgroundFile,
                    terminalBackgroundColor = hexToColorInt(appearance.terminal.palette.background),
                    backgroundOpacity = appearance.background.imageOpacity,
                    contentPaddingDp = appearance.background.contentPaddingDp,
                    cornerRadiusDp = appearance.background.cornerRadiusDp,
                    dimInactivePanes = appearance.terminal.dimInactivePanes,
                    keepAwake = appearance.terminal.keepAwake,
                    keepPositionWhileReading = appearance.terminal.keepPositionWhileReading,
                    terminalViewModel = terminalViewModel,
                    blockEngineViewModel = blockEngineViewModel,
                    inputBarViewModel = inputBarViewModel,
                    extraKeyState = extraKeyState,
                    useBlockEngine = useBlockEngine,
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
                    customFontUri = customFontUri,
                    onBoundsChanged = { terminalBounds = it },
                    onUrlClick = { browserUrl = it },
                )
            },
            secondary = {
                TerminalPaneBody(
                    paneSlot = PaneSlot.SECONDARY,
                    terminalManager = terminalManager,
                    fontSizeSp = fontSizeSp,
                    defaultFontSizeSp = defaultFontSizeSp,
                    colorProps = colorProps,
                    backgroundFile = backgroundFile,
                    terminalBackgroundColor = hexToColorInt(appearance.terminal.palette.background),
                    backgroundOpacity = appearance.background.imageOpacity,
                    contentPaddingDp = appearance.background.contentPaddingDp,
                    cornerRadiusDp = appearance.background.cornerRadiusDp,
                    dimInactivePanes = appearance.terminal.dimInactivePanes,
                    keepAwake = appearance.terminal.keepAwake,
                    keepPositionWhileReading = appearance.terminal.keepPositionWhileReading,
                    terminalViewModel = terminalViewModel,
                    blockEngineViewModel = blockEngineViewModel,
                    inputBarViewModel = inputBarViewModel,
                    extraKeyState = extraKeyState,
                    useBlockEngine = useBlockEngine,
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
                    customFontUri = customFontUri,
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
                source = appearanceSource.value,
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
                blurRadius = menuBlurRadius(appearanceSource.value?.kind),
                onCopy = {
                    selectionText?.let { context.copyToClipboard("terminal", it) }
                    // The selection is dismissed in whichever terminal made
                    // it: the vendored view owns its own, and the engine's
                    // pane owns the engine's.
                    terminalManager.focusedEnginePane()?.clearSelection()
                },
                onPaste = {
                    terminalManager.focusedEnginePane()?.pasteClipboard()
                },
                onOpenUrl = { url ->
                    browserUrl = url
                    terminalManager.focusedEnginePane()?.clearSelection()
                },
                onSelectAll = { terminalManager.focusedEnginePane()?.selectAll() },
                onShare = { selectionText?.let { context.shareText(it) } },
                modifier = Modifier
                    .offset(x = anchorX, y = belowSelection)
                    .onSizeChanged { measured = it },
            )
        }

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
                onFindInOutput = {
                    // The terminal's own keyboard goes first: the find bar is
                    // about to raise its own, and two keyboards in one row is
                    // one keyboard too many.
                    hideKeyboard()
                    searchActive = true
                },
                onRefresh = {
                    // The engine's terminal owns its session, so the refresh
                    // restarts that session; the vendored path restarts the
                    // session the manager owns.
                    val engine = terminalManager.focusedEnginePane()
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
                onOpenAgent = onOpenAgent,
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

        // Find bar — fixed, just under the chrome row.
        //
        // Top-centre and below the pill row rather than anywhere else, because
        // it is a control over the terminal and a control that can be left
        // adrift is one the user has to look for. The chrome row is 44dp tall
        // under a status bar, so 64dp clears it in both chrome states.
        if (searchActive) {
            BackHandler {
                searchActive = false
                searchQuery = ""
                findState = null
            }

            TerminalFindBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = matchCount,
                currentMatch = currentMatch,
                onNext = { stepMatch(forward = true) },
                onPrevious = { stepMatch(forward = false) },
                onClose = {
                    searchActive = false
                    searchQuery = ""
                    findState = null
                },
                // Which terminal the matches came from, and the text of the
                // one the user is on. The engine's matches are lines of its
                // output log and not rows of a grid, so the bar says so
                // rather than pretending the screen moved to them.
                kind = findState?.kind,
                matchText = findState?.activeText,
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
                        text = stringResource(R.string.sidebar_drop_to_split),
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
        // The drawer's keyboard button, reaching the same toggle the top
        // bar's own control does — the drawer is simply the one place that
        // stays reachable while the keyboard is up.
        onToggleKeyboard = ::toggleKeyboard,
        /**
         * A card carried out of the drawer and dropped on the terminal.
         *
         * The same split the row menu's "Split below" makes, so the gesture and
         * the menu agree about what they do. Offered when there is a second
         * session to make a pair with, which is the same condition the row's
         * own grip uses.
         */
        onHoldChange = { id -> heldSessionId = id },
        onCycleSplit = paneLayoutViewModel::cycleSplitFraction,
        onDropOnTerminal = if (liveSessionIds.size >= 2) {
            { id -> paneLayoutViewModel.openSplit(id, activeId) }
        } else {
            null
        },
        /**
         * Ids, not names, and in the order they are drawn: the upper pane
         * first. The drawer's pair card mirrors the screen, and a swap changes
         * that order — a pair of names could not express it, so the card would
         * show the old arrangement while the terminal had already swapped.
         *
         * The upper pane is **the primary pane**, which is not the same thing as
         * the active session. [activeId] follows focus, and with the split up the
         * user taps the lower pane more often than not — so reading it here named
         * the lower session on *both* halves of the card, made both of its taps
         * activate the same session, and left the upper session listed as an
         * ordinary row. [PaneSlot.PRIMARY]'s own session is the only value that
         * answers "which one is on top".
         *
         * A split missing either session is not drawn rather than drawn with a
         * blank half: a card naming one session and an empty space beside it is
         * worse than no card.
         */
        splitSessions = paneLayout.secondarySessionId?.let { secondary ->
            val primaryId = terminalManager.sessionIdForSlot(PaneSlot.PRIMARY)
            if (primaryId != null && primaryId != secondary) {
                if (paneLayout.secondarySwapped) secondary to primaryId
                else primaryId to secondary
            } else {
                null
            }
        },
        onCloseSplit = { paneLayoutViewModel.closeSplit() },
        onSwapPanes = { paneLayoutViewModel.swapPanes() },
        /**
         * The row menu's "Dock pane", on a row that is already a floating
         * window. It was drawn with nothing behind it before, so it did
         * nothing when tapped.
         */
        onDock = { paneLayoutViewModel.dock() },
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
            stringResource(R.string.terminal_preparing)

        UbuntuSetupState.Extracting ->
            stringResource(R.string.terminal_extracting_rootfs)

        UbuntuSetupState.Configuring ->
            stringResource(R.string.terminal_configuring)

        is UbuntuSetupState.InstallingPackages ->
            if (state.message.isNotEmpty()) {
                state.message
            } else {
                stringResource(R.string.terminal_installing_packages)
            }

        is UbuntuSetupState.InstallingOhMyZsh ->
            state.message

        UbuntuSetupState.Optimizing ->
            stringResource(R.string.terminal_cleaning)

        UbuntuSetupState.Ready ->
            stringResource(R.string.terminal_ready)

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
                text = stringResource(R.string.terminal_setting_up),
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
                text = stringResource(R.string.terminal_setup_failed),
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
                Text(stringResource(R.string.action_retry))
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
    fontSizeSp: Float,
    defaultFontSizeSp: Float,
    colorProps: Properties,
    backgroundFile: File?,
    terminalBackgroundColor: Int,
    backgroundOpacity: Int,
    contentPaddingDp: Int,
    cornerRadiusDp: Int,
    dimInactivePanes: Boolean,
    keepAwake: Boolean,
    /**
     * New output does not pull the viewport back to the live edge while the
     * user is reading something further up.
     */
    keepPositionWhileReading: Boolean,
    terminalViewModel: TerminalViewModel,
    blockEngineViewModel: BlockEngineViewModel,
    inputBarViewModel: InputBarViewModel,
    extraKeyState: dev.drosh.terminal.session.ExtraKeyState?,
    useBlockEngine: Boolean,
    /**
     * The grid's top padding, already animated in step with the pill row.
     *
     * Passed in rather than derived here because it is a property of what sits
     * above the pane, not of the pane itself: the primary pane has the status
     * bar and the pill row above it and clears them both, and the second pane
     * has the first pane above it and clears nothing. Giving both the same
     * number is what pushed the lower session's whole grid down by the height
     * of a bar that was never over it.
     */
    topPadding: Dp,
    inputBarState: dev.drosh.ui.input.InputBarUiState,
    motdMode: MotdMode,
    motdText: String,
    systemInfo: SystemInfo,
    motdDismissed: Boolean,
    onDismissMotd: () -> Unit,
    awaitingShellInput: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    /**
     * What the query found, or null before it has been scanned.
     *
     * Only the pane the search ran against gets these: they are rows of one
     * particular terminal, and another pane's rows are a different buffer.
     */
    searchResults: Any? = null,
    searchOverlayRef: MutableState<Any?>? = null,
    /** Where this pane's terminal is sampled for the chrome. */
    appearanceSource: MutableState<TerminalAppearanceSource?>,
    /**
     * The terminal's configuration, as the engine is handed it.
     *
     * The application's half: the colours, the cursor, the blink, the bold,
     * the ligatures, the size. The engine's own answers still win.
     */
    terminalConfig: dev.drosh.domain.settings.TerminalConfig,
    /** The font file the user picked for the terminal, if any. */
    customFontUri: String,
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
                .weight(1f)
                // The terminal's own colour behind the whole pane, the band
                // above the grid included.
                //
                // The band above the grid is the chrome's ground: the pill row
                // and the clock float over it, and what is behind them used to
                // be the *app's* background. In the light theme that is a
                // near-white field, so a dark terminal had a white strip over
                // it — the one part of the screen that belonged to neither the
                // terminal nor the chrome. The terminal's background is the
                // honest colour there: it is the colour the grid is drawn on,
                // and the chrome floats over the grid.
                .background(Color(terminalBackgroundColor))
                // The grid's top padding, and it moves with the chrome — but the two
                // states are not symmetric. Collapsed, the status bar is gone and
                // the grid picks up right where the pills end. Expanded, only the
                // status bar is cleared and the pills float over the output, the
                // way the prototype does.
                //
                // Collapsed does mean the grid gains a few rows over expanded, and
                // TerminalView holds the viewport across that resize rather than
                // dropping it back to the live edge — see `updateSize`.
                .padding(top = topPadding),
        ) {

            /*
             * THE ENGINE'S TERMINAL
             *
             * The pane runs on the Rust engine. The engine owns its
             * session and its view, so the app hosts a pane instead of
             * a view — see `EnginePaneHost`.
             */
            EnginePaneHost(
                paneSlot = paneSlot,
                terminalManager = terminalManager,
                // The session this pane is *for*. Without it the manager never
                // records the pane as a session, and everything that names,
                // lists, restores or closes a session — the sidebar, the
                // reconciliation, the relaunch — is looking at a terminal it
                // cannot see.
                sessionId = terminalManager.sessionIdForSlot(paneSlot).orEmpty(),
                sessionName = terminalManager.sessionNameForSlot(paneSlot).orEmpty(),
                fontSizeSp = fontSizeSp,
                appearance = terminalConfig,
                customFontUri = customFontUri,
                appearanceSource = appearanceSource,
                onBoundsChanged = onBoundsChanged,
                modifier = Modifier.fillMaxSize(),
            )
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
/** "#RRGGBB" as a colour int; black when it is not one. */
private fun hexToColorInt(hex: String): Int =
    runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(android.graphics.Color.BLACK)

private fun loadBackgroundDrawable(context: Context, file: File): android.graphics.drawable.Drawable? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val drawable = android.graphics.ImageDecoder.decodeDrawable(
                android.graphics.ImageDecoder.createSource(file),
            )
            (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
            drawable
        } else {
            android.graphics.drawable.BitmapDrawable(
                context.resources,
                android.graphics.BitmapFactory.decodeFile(file.absolutePath),
            )
        }
    }.getOrNull()

private const val ZOOM_CHIP_LINGER_MS = 900L

/**
 * How long output must be quiet before a find rescan is worth another one.
 *
 * Only the output path waits this long; typing a query scans at once, because
 * the query is the user typing and they are waiting for the answer. Under a
 * firehose the terminal would otherwise restart the scan once per chunk, and a
 * scan that keeps restarting is a scan that never finishes — which is how a
 * find bar ends up claiming there are no matches while there are fifty.
 */
private const val FIND_RESCAN_GATE_MS = 150L

/**
 * How often the find bar rescans an engine-backed terminal.
 *
 * The vendored path is driven by the view's own ticks and is gated at
 * [FIND_RESCAN_GATE_MS]; the engine has no ticks, and its scan is a pass
 * over the whole output log, so it is repeated on this slower timer. The
 * count is the reason: a count that says 41 while the session has since
 * printed twelve more matches is a count the user cannot trust.
 */
private const val ENGINE_RESCAN_INTERVAL_MS = 1000L

/** How far the unfocused pane of a split is dimmed. */
private val DimInactivePane = Color.Black.copy(alpha = 0.4f)

/**
 * Chip size used to keep it on screen, in dp.
 *
 * Estimated rather than measured: the chip is positioned by offset, so it needs
 * its extent before it is composed, and asking a composable to measure itself in
 * order to place itself is a circular dependency for a few dozen pixels.
 */
private val ZOOM_CHIP_ESTIMATED_WIDTH = 72.dp
private val ZOOM_CHIP_ESTIMATED_HEIGHT = 40.dp

/** How far above the fingers the chip sits. */
private val ZOOM_CHIP_OFFSET = 48.dp
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