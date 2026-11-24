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

/** How far the unfocused pane of a split is dimmed. */
private val DimInactivePane = Color.Black.copy(alpha = 0.4f)

@Composable
internal fun TerminalPaneBody(
    paneSlot: PaneSlot,
    terminalManager: TerminalSessionCoordinator,
    fontSizeSp: Float,
    defaultFontSizeSp: Float,
    /**
     * Whether the program's mouse-tracking mode is honoured.
     *
     * The surface reads the engine's modes itself; this is the user's half of
     * the handshake.
     */
    mouseReporting: Boolean,
    /** The colour a search match is drawn in. */
    highlightColour: Int,
    /** The colour the search's other matches are drawn in. */
    highlightAllColour: Int,
    /** The colour the bell's wash is in. */
    bellColour: Int,
    colorProps: Properties,
    backgroundFile: File?,
    terminalBackgroundColor: Int,
    backgroundOpacity: Int,
    contentPaddingDp: Int,
    cornerRadiusDp: Int,
    /**
     * Whether this pane is the unfocused half of a split and should be dimmed.
     *
     * Decided by the caller, which is the only place that knows both settings:
     * the preference and which pane the keys go to.
     */
    dimmed: Boolean,
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
    /** Leaves block mode for the classic terminal from the block empty state. */
    onBackToClassic: () -> Unit,
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
    onBoundsChanged: (Rect) -> Unit,
    onUrlClick: (String) -> Unit,
    /** The selection asked the screen's find bar to search its text. */
    onSearchSelection: (String) -> Unit = {},
    /** Told this pane's last frame, for the session grid's card. */
    onThumbnail: (String, ImageBitmap) -> Unit,
) {
    // A TUI (nano, vim, htop) takes over the alternate screen buffer, so the raw
    // terminal view has to be shown rather than the block list — the shell is
    // not producing line output to block up. Asked per pane: a vim in the other
    // pane says nothing about this one.
    val altBufferActive = terminalManager.isAltBufferActive(paneSlot)

    // Block mode renders the engine's own structured output. The bridge fills
    // these from OSC 133 events, not from a transcript diff.
    val blockList by blockEngineViewModel.blocks.collectAsState()
    val runningBlock by blockEngineViewModel.runningBlock.collectAsState()
    val paneContext = LocalContext.current

    // Which session this slot shows. Read from the flow, not from a lookup: a
    // plain function call is not a state read, so the grid could open a session
    // and the screen would never hear about it.
    val slotSessionIds by terminalManager.slotSessionIds.collectAsStateWithLifecycle()

    // The session this pane is showing.
    val paneSessionId = slotSessionIds[paneSlot]
        ?: terminalManager.sessionIdForSlot(paneSlot).orEmpty()

    // The transition between one session and the next.
    //
    // The terminal is one view for the whole app: switching sessions changes
    // what that view draws, and the change is immediate. A scrim that fades
    // out over the new content makes the switch read as a movement rather
    // than as a jump — the one thing a cross-fade cannot do here, because
    // there is only ever one surface to fade.
    val transitionAlpha = remember { Animatable(0f) }
    LaunchedEffect(paneSessionId) {
        if (paneSessionId.isEmpty()) return@LaunchedEffect
        transitionAlpha.snapTo(1f)
        transitionAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(SESSION_TRANSITION_MILLIS),
        )
    }

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
             * a view — see `GhosttyTerminalHost`.
             */
            // One engine, one host. The old Rust engine's library is not in
            // the APK any more, so the switch that chose between them was a
            // choice between the one engine and a missing library.
            if (useBlockEngine) {
                BlockScreen(
                    blocks = blockList,
                    runningBlock = runningBlock,
                    isAltScreen = altBufferActive,
                    onCopy = { text -> paneContext.copyToClipboard("Drosh", text) },
                    onCollapse = { id, collapsed ->
                        if (blockList.firstOrNull { it.id == id }?.isCollapsed != collapsed) {
                            blockEngineViewModel.onToggleCollapsed(id)
                        }
                    },
                    onClassicFallback = { /* classic surface stays mounted below */ },
                    onBackToClassic = onBackToClassic,
                    // Same path BlockEngineViewModel uses for commands: its
                    // onCommandSubmitted forwards to SubmitBlockCommandUseCase,
                    // which writes to the PTY. The engine's marks still own the
                    // block, so no speculative card is opened here.
                    onSubmitCommand = { command ->
                        blockEngineViewModel.onCommandSubmitted("", command)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
            GhosttyTerminalHost(
                paneSlot = paneSlot,
                terminalManager = terminalManager,
                // The session this pane is *for*. Without it the manager never
                // records the pane as a session, and everything that names,
                // lists, restores or closes a session — the grid, the
                // reconciliation, the relaunch — is looking at a terminal it
                // cannot see.
                // Read from the flow, not from a lookup: the grid opens a
                // session by changing what a slot shows, and a plain function
                // call is not a state read, so the screen would never learn
                // that it happened.
                sessionId = paneSessionId,
                sessionName = terminalManager.sessionNameForSlot(paneSlot).orEmpty(),
                fontSizeSp = fontSizeSp,
                ligatures = terminalConfig.ligatures,
                // Whether the program's mouse-tracking mode is honoured. The
                // surface reads the engine's modes itself; this is the user's
                // half of the handshake.
                mouseReporting = mouseReporting,
                // The pinch reports the size it settled on; the view model
                // owns it, so the setting and the cell cannot drift apart.
                onZoomCommitted = terminalViewModel::onZoomCommitted,
                // The theme's terminal background: the ground the grid is drawn
                // on, and the colour of everywhere it does not reach.
                backgroundColor = terminalBackgroundColor,
                // The picture behind the terminal, and how opaque it is.
                backgroundFile = backgroundFile,
                backgroundOpacity = backgroundOpacity,
                highlightColour = highlightColour,
                highlightAllColour = highlightAllColour,
                bellColour = bellColour,
                // A tap on a cell that carries an `OSC 8` link. The pane
                // resolves the cell against the engine and hands the URI up;
                // the screen decides what opening it means.
                onUrlClick = onUrlClick,
                onSearchSelection = onSearchSelection,
                onThumbnail = onThumbnail,
                modifier = Modifier.fillMaxSize(),
            )

            }

            // An unfocused pane, dimmed.
            //
            // Two terminals side by side read as one surface unless the half
            // the keys are going to is the brighter one — and a split with no
            // such signal is a split you type into by trial. Drawn as an
            // overlay rather than as an alpha on the pane: a layer over a
            // terminal is a copy of the frame, and the copy is the expensive
            // part.
            if (dimmed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DimInactivePane),
                )
            }

            // The scrim, over the terminal and under nothing else.
            if (transitionAlpha.value > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DroshBackground.copy(alpha = transitionAlpha.value)),
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
