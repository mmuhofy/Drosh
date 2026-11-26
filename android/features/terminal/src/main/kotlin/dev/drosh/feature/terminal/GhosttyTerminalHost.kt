package dev.drosh.feature.terminal

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.drosh.domain.terminal.PaneSlot
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.terminal.session.GhosttyTerminal
import dev.drosh.terminal.session.TerminalSessionCoordinator
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A pane on the Ghostty engine.
 *
 * ## Why its own host
 *
 * The old host reaches into members only the Rust engine has — its session, its
 * listener, its zoom callback, its frame counters. Those are telemetry and
 * chrome, not terminal work, and widening the engine interface to carry them
 * would spread the old engine's shape over the new one.
 *
 * So there are two hosts and one screen. `TerminalScreen` picks between them
 * from the setting, and neither knows about the other.
 *
 * ## What this one does not do yet
 *
 * The pane registers with the manager once started, the same as the other
 * host: it gets a session id, appears in the grid, can be named, closed and
 * previewed. What is still missing is the block engine and the marker-driven
 * chrome — those are the next commit, not a design change.
 */
@Composable
fun GhosttyTerminalHost(
    paneSlot: PaneSlot,
    terminalManager: TerminalSessionCoordinator,
    sessionId: String = "",
    sessionName: String = "",
    fontSizeSp: Float,
    /** Whether the font may join adjacent operator cells into ligatures. */
    ligatures: Boolean = true,
    /**
     * The cell's height as a multiple of the font's own line box.
     *
     * The width never changes with it — a monospace cell is as wide as its
     * advance — so the setting trades rows for air and nothing else.
     */
    lineHeight: Float = 1f,
    /** Which touch raises the keyboard; the surface owns the gesture. */
    keyboardActivation: dev.drosh.domain.settings.KeyboardActivation =
        dev.drosh.domain.settings.KeyboardActivation.Tap,
    /**
     * Whether touch is reported to programs that turned a tracking mode on.
     *
     * The surface reads the engine's own tracking state on every gesture; this
     * is the user's master switch over it, and off means gestures are always
     * local whatever the program asked.
     */
    mouseReporting: Boolean = true,
    /**
     * Told the size a pinch settled on, once, when the fingers lift.
     *
     * The size is a setting with one owner — the view model — so the cell the
     * host measures comes back from the same number the settings store holds. A
     * pinch that wrote the cell directly would be undone by the next
     * recomposition, which is what made an earlier attempt last one frame.
     */
    onZoomCommitted: (Float) -> Unit = {},
    /**
     * The theme's terminal background.
     *
     * The surface is wiped to it, so it is the colour of everything the grid
     * does not cover — the band under a zoomed-out grid, the edge of the last
     * column, and the whole pane before the first frame.
     */
    backgroundColor: Int,
    /**
     * The colour the bell's wash is in.
     *
     * The app's accent: the bell is the app saying "something happened", and
     * it says it in the app's own colour.
     */
    bellColour: Int = 0xFF3B82F6.toInt(),
    /**
     * The picture behind the terminal, and how opaque it is.
     *
     * The picture is the view's own background and the surface is wiped
     * transparent, so it shows through the grid where no cell has a colour of
     * its own.
     */
    backgroundFile: java.io.File? = null,
    backgroundOpacity: Int = 255,
    /**
     * Told the last captured frame of a session, for the session grid's card.
     *
     * The host owns the only SurfaceView showing that session, so it is the only
     * place a picture can be taken. Without a session id nothing is captured.
     */
    onThumbnail: (String, ImageBitmap) -> Unit = { _, _ -> },
    /**
     * The colour a search match is drawn in.
     *
     * The match is a tint over the cell's own background, so the text under it
     * stays readable whatever the theme is.
     */
    highlightColour: Int = 0xFFFFE000.toInt(),
    /**
     * The colour the search's other matches are drawn in.
     *
     * A weaker tint than [highlightColour], so the match the user is on stays
     * the strongest thing on the screen.
     */
    highlightAllColour: Int = 0xFF4C9EFF.toInt(),
    /**
     * Told the URI of a hyperlink the user tapped.
     *
     * `OSC 8` links are the terminal's own; a tap on a cell that carries one is
     * the only way to reach it, because the cell shows text and the link is not
     * in it. The pane resolves the tapped cell against the engine and reports
     * the URI, and the caller decides what opening it means.
     */
    onUrlClick: (String) -> Unit = {},
    /**
     * Search the terminal for the selected text.
     *
     * The find bar belongs to the screen, not the pane: one bar serves both
     * panes and remembers one query. The pane only hands the text up.
     */
    onSearchSelection: (String) -> Unit = {},
    /**
     * Open a new session that runs this text.
     *
     * Starting a session is the screen's job — it owns the session list and
     * the switch that follows — so the pane hands the command up and nothing
     * else. Running it *here* needs no such help: the pane has the PTY.
     */
    onRunInNewSession: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusedPane by terminalManager.focusedPane.collectAsStateWithLifecycle()

    val pane = remember { GhosttyTerminal(context) }

    // A tap on this pane moves the app's focus to it. The manager owns which
    // pane is active, and without this a second pane could never take the
    // keyboard: nothing else told the manager that the view gained focus.
    // Set once; the listener reads the current slot through the closure.
    LaunchedEffect(pane) {
        pane.view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) terminalManager.focusPane(paneSlot)
        }
    }

    // The renderer owns its shaping state, so the setting is pushed once per
    // change rather than on every recomposition.
    LaunchedEffect(pane, ligatures) {
        pane.view.setLigatures(ligatures)
    }

    // The same for the gesture that raises the keyboard: it is read from a
    // touch handler, so it is pushed once per change rather than held in a
    // composable state.
    LaunchedEffect(pane, keyboardActivation) {
        pane.view.setKeyboardActivation(keyboardActivation)
    }

    // Whether the viewport is at the live edge.
    //
    // The manager follows the scroll and publishes it; the surface asks this
    // instead of the engine, so that typing does not cost a crossing and a
    // lock for every character.
    val atLiveEdge by terminalManager.isAtLiveEdge.collectAsStateWithLifecycle()
    pane.atLiveEdge = { atLiveEdge }

    // The picture behind the terminal, decoded off the main thread.
    //
    // The decode is a file read and a bitmap allocation; doing it on the main
    // thread would stutter the very animation the picture is behind.
    val backgroundBitmap by produceState<android.graphics.Bitmap?>(null, backgroundFile) {
        val file = backgroundFile
        if (file == null || !file.exists()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            }.getOrNull()
        }
    }
    LaunchedEffect(backgroundBitmap, backgroundOpacity) {
        pane.setBackgroundImage(backgroundBitmap, backgroundOpacity)
    }

    // The cell, measured in Kotlin rather than asked of a font stack.
    //
    // The old engine measured through its own native shaper; this one has no
    // business depending on a library that is on its way out, and a monospace
    // cell is a `Paint` call. The measure is exact for the width — a terminal's
    // font is monospace by definition — and the font's own line spacing for the
    // height, which is what every terminal uses.
    // The size the terminal is drawn at.
    //
    // The pinch changes the *font*, and the font's size is the cell's size,
    // so the grid is laid out again as the fingers move: the text really grows
    // and fewer columns fit. The live size is the pinch's while it runs and the
    // setting's otherwise.
    var liveZoomSp by remember { mutableStateOf(0f) }
    val effectiveSp = if (liveZoomSp > 0f) liveZoomSp else fontSizeSp

    val cell = remember(effectiveSp, lineHeight, context) {
        val density = context.resources.displayMetrics.scaledDensity
        val paint = android.graphics.Paint().apply {
            // The same face the atlas rasterises with: a cell measured in one
            // face and a glyph drawn in another is text that does not fit its
            // own advance.
            typeface = dev.drosh.terminal.engine.font.TerminalFont.of(context)
            textSize = effectiveSp * density
            isAntiAlias = true
        }
        val metrics = paint.fontMetrics
        CellMetrics(
            width = paint.measureText("M").coerceAtLeast(1f),
            // The glyph's own line box, and nothing more.
            //
            // `fontSpacing` is ascent + descent + *leading*, and a terminal cell
            // is not the leading's business: a program draws its own rows, and
            // the cell a background fills is the box the text sits in. Using the
            // spacing made every cell taller than its text by the leading, so
            // the text floated away from the coloured block above it.
            // The line box the user asked for, which is the font's own unless
            // the line-height setting says otherwise. The glyph is centred in
            // it by the renderer, so a taller cell is air above and below the
            // text rather than text sitting at the top of its row.
            height = (metrics.descent - metrics.ascent).coerceAtLeast(1f) *
                lineHeight.coerceIn(0.5f, 2f),
            fontPx = paint.textSize,
        )
    }

    var status by remember { mutableStateOf<String?>("preparing") }

    // The selection's chrome. It is drawn here, next to the view that owns the
    // gesture, rather than in the screen that hosts this one: the rectangle the
    // renderer measures is in this pane's own pixels, and a menu placed by an
    // ancestor would have to be told where the pane is before it could sit on
    // the selection.
    // The selection is owned by the surface: its bounds are updated the
    // moment a gesture installs them, not after the next frame is drawn. The
    // manager's flow lags the gesture by a frame, which is why the grips never
    // appeared.
    val selectionBounds by pane.view.selectionBoundsFlow.collectAsStateWithLifecycle()
    val hasSelection = selectionBounds != null
    var zoomSizeSp by remember { mutableStateOf(0f) }

    // The diagnostics eye. The panel is off by default: a terminal's surface
    // owns every gesture, and a full-size debug box on it would steal them.
    // The eye itself is a couple of buttons in the corner.
    //
    // The system bars used to be hidden here, which made this host fight the
    // terminal screen and the activity over the same window flags. They are now
    // owned once, globally, by MainActivity for every route in the app.

    var diagnosticsOpen by remember { mutableStateOf(false) }
    var diagnosticsText by remember { mutableStateOf("") }
    var benchmarkRecording by remember { mutableStateOf(false) }
    var benchmarkStatus by remember { mutableStateOf("") }

    // Refresh only while open. The self tests run once; the moving numbers —
    // frames, their distribution, keys, bytes — come from the live report.
    LaunchedEffect(diagnosticsOpen) {
        if (!diagnosticsOpen) return@LaunchedEffect
        val statics = dev.drosh.terminal.engine.diagnostics.EngineDiagnostics.staticReport()
        var lastSampleAt = 0L
        while (diagnosticsOpen) {
            val session = pane.session
            val live = buildString {
                appendLine(statics)
                appendLine(dev.drosh.terminal.engine.diagnostics.EngineDiagnostics.liveReport())
                appendLine("--- session ---")
                appendLine("bytes read: ${session?.bytesRead ?: 0}")
                appendLine("frames drawn: ${pane.view.framesDrawn}")
                appendLine("last frame: ${pane.view.lastFrameShape}")
                appendLine("renderer failure: ${pane.view.rendererFailure ?: "none"}")
                appendLine("fling gear: ${pane.view.flingGear}")
            }
            diagnosticsText = live

            // One complete line per second of a recording. The loop wakes
            // twice as often so the panel itself stays fresh.
            val now = System.currentTimeMillis()
            if (dev.drosh.terminal.engine.bench.BenchmarkLog.isRecording() &&
                now - lastSampleAt >= BENCHMARK_SAMPLE_MS
            ) {
                lastSampleAt = now
                dev.drosh.terminal.engine.bench.BenchmarkLog.sample(
                    dev.drosh.terminal.engine.bench.RenderBench.summary() +
                        " | " + dev.drosh.terminal.engine.bench.RenderBench.detail(),
                )
            }
            delay(DIAGNOSTICS_REFRESH_MS)
        }
    }
    val clipboard = LocalClipboardManager.current

    // The tap that follows a hyperlink.
    //
    // The surface owns every gesture — scroll, fling, pinch, the focus tap —
    // and its own touch handling is not this host's business. What the host can
    // do without taking those away is *observe*: an `OnTouchListener` that
    // returns false sees each event first and lets the surface handle it as it
    // always did. A tap is then the host's to recognise, and a cell under it
    // can be resolved against the engine.
    //
    // The cell and the callback are read through `rememberUpdatedState`, so the
    // listener built once still sees the pane's current metrics after a font
    // change or a pinch, and the caller's current `onUrlClick`.
    val currentCell = rememberUpdatedState(cell)
    val currentOnUrlClick = rememberUpdatedState(onUrlClick)
    val linkTap = remember(context, pane) {
        HyperlinkTap(
            slop = ViewConfiguration.get(context).scaledTouchSlop,
            timeoutMillis = TAP_TIMEOUT_MILLIS,
            cellSize = { currentCell.value.width to currentCell.value.height },
            // Read at tap time: a program can turn its tracking mode on or off
            // mid-session, and a tap that became a click for the program is
            // not the link's to follow.
            mouseActive = { pane.view.mouseReportingActive },
            resolve = { row, column -> pane.hyperlinkAt(row, column) },
            onLink = { uri -> currentOnUrlClick.value(uri) },
        )
    }

    // True while the fingers are on the screen. A pinch changes the cell and
    // nothing else: the grid keeps its shape while the gesture runs, and it is
    // laid out again — once — when the fingers lift. Reflowing on every step
    // of the gesture is what makes a zoom stutter.
    var pinchActive by remember { mutableStateOf(false) }

    // The view's font size always reflects what is on screen; the pinch
    // measures from it and reports back to it.
    LaunchedEffect(pane, effectiveSp) {
        pane.view.textSizeSp = effectiveSp
    }

    // The setting is the truth again once it has caught up with the pinch.
    // Until then the pinch's size stands, so the text does not jump back to
    // the old size for a frame while the setting is written.
    LaunchedEffect(fontSizeSp) {
        liveZoomSp = 0f
    }

    LaunchedEffect(pane) {
        pane.view.zoomListener = { sp ->
            pinchActive = true
            liveZoomSp = sp
            zoomSizeSp = sp
        }
        pane.view.zoomEndListener = {
            pinchActive = false
            zoomSizeSp = 0f
        }
        pane.view.zoomCommitListener = { sp ->
            liveZoomSp = sp
            onZoomCommitted(sp)
        }
    }

    // The guest is started once. It is a fork, a rootfs and an exec, so it does
    // not belong on the main thread.
    //
    // Keyed on the session id as well as the pane: a pane is one view and one
    // session, and switching to another session binds the same pane to a
    // different session. Keyed on the pane alone, the effect never re-ran and
    // the screen kept drawing the old session.
    LaunchedEffect(pane, sessionId) {
        status = "preparing"

        // The pane is registered with the manager before anything else. The
        // registration is what makes the manager know this pane is a session:
        // without it the pane is absent from the grid, cannot be restored,
        // closed, named or previewed — and, worse, the manager's "show this
        // session" has no pane to point at, so switching sessions did nothing
        // at all.
        //
        // It used to be registered only on the path where a session was
        // *opened*, so a pane whose session was already alive was never
        // registered, and the switch found no pane.
        if (sessionId.isNotEmpty()) {
            terminalManager.registerPane(
                paneSlot,
                pane,
                persistentId = sessionId,
                name = sessionName,
            )
        }

        // A session that is already alive is shown as it is. Switching
        // sessions must not restart the one being switched to: it has been
        // running, and it has the output the grid's thumbnail is made of.
        val alive = terminalManager.liveSession(sessionId)
        if (alive != null) {
            pane.showSession(alive)
            status = null
            return@LaunchedEffect
        }

        val guest = withContext(Dispatchers.IO) {
            runCatching { terminalManager.guestCommand() }.getOrNull()
        }
        if (guest == null) {
            // The reason the guest is missing, not a bare "no guest command":
            // a user can act on "run setup", not on a null return. guestCommand
            // logs the same reason when it refuses.
            val reason = terminalManager.guestUnavailableReason() ?: "no guest command"
            Log.w(TAG, "session not started: $reason")
            status = reason
            return@LaunchedEffect
        }

        status = "starting"
        val started = withContext(Dispatchers.IO) {
            runCatching {
                // The session is the manager's: it is created once, registered
                // under its id, and it keeps running whether or not this pane
                // is showing it. The pane only points at it.
                val session = terminalManager.openSession(
                    persistentId = sessionId,
                    name = sessionName,
                    command = guest,
                    columns = DEFAULT_COLUMNS,
                    rows = DEFAULT_ROWS,
                    cellWidth = cell.width,
                    cellHeight = cell.height,
                )
                if (session != null) {
                    pane.showSession(session)
                }
                session != null
            }.getOrElse { error ->
                Log.e(TAG, "the engine refused the session", error)
                false
            }
        }

        if (!started) {
            status = "start failed: ${pane.lastError ?: "no reason"}"
            return@LaunchedEffect
        }

        // A command queued for the next session — the selection's "run in a
        // new tab" — is typed once this shell is up, so it runs here and in
        // no other session. Consumed, not remembered: the tab after this one
        // is an ordinary shell.
        terminalManager.takeStartupCommand()?.let { command ->
            pane.view.runCommand(command)
        }

        // The session runs; keep the counters up until frames are being drawn.
        // Zero bytes is one bug, zero frames another, and a renderer that never
        // came up is a third — and it is the only one whose cause is invisible.
        // The numbers stay up for a while whatever happens: a screen that is
        // drawing something invisible and a screen that is drawing nothing look
        // the same, and only these say which.
        var ticks = 0
        while (ticks < STATUS_TICKS) {
            val failure = pane.view.rendererFailure
            val session = pane.session
            status = if (failure != null) {
                "renderer: $failure"
            } else {
                "${session?.bytesRead ?: 0} bytes\n" +
                    "${pane.view.framesDrawn} frames\n" +
                    "frame ${pane.view.lastFrameShape}"
            }
            kotlinx.coroutines.delay(500)
            ticks += 1
        }
        status = null
    }

    // The engine's own report, for a build that has to be diagnosed on a phone.
    //
    // It goes to the log as well as the screen: `adb logcat -s GhosttyTerminalHost`
    // is one command, and the screen is a picture of it.
    LaunchedEffect(pane) {
        val report = dev.drosh.terminal.engine.diagnostics.EngineDiagnostics.report()
        Log.i(TAG, report)
    }

    // The focus follows the pane the user is in — without this, the manager
    // never treats the Ghostty pane as active, so the grid shows no "you are
    // here" card and the restore/reconcile path cannot find its current
    // session.
    LaunchedEffect(focusedPane, pane) {
        if (focusedPane == paneSlot) {
            terminalManager.focusPane(paneSlot)
        }
    }

    pane.onSessionExited = { code -> status = "session ended ($code)" }

    // Thumbnails. The surface is only readable while the host is on screen; the
    // last frame before a session is switched away (or the app backgrounds) is
    // kept for the grid's card. The callbacks are read live so these effects do
    // not have to be rebuilt when the caller changes.
    var hostVisible by remember { mutableStateOf(true) }
    val currentSessionId = rememberUpdatedState(sessionId)
    val currentOnThumbnail = rememberUpdatedState(onThumbnail)
    val thumbnailPublisher = remember { ThumbnailPublisher(THUMBNAIL_CAPTURE_INTERVAL_MS) }
    val captureThumbnail: (String, Boolean) -> Unit = { id, force ->
        // The surface is the only thing that can be sampled, and the id is the
        // only key the grid can store it under. Neither being ready is a silent
        // no-op, never a publish under an empty key.
        if (id.isNotEmpty()) {
            val surface = runCatching { pane.view.holder.surface }.getOrNull()
            if (surface != null) {
                captureSessionThumbnail(
                    surface = surface,
                    viewWidth = pane.view.width,
                    viewHeight = pane.view.height,
                    sessionId = id,
                    shouldPublish = { key, bitmap -> thumbnailPublisher.shouldPublish(key, bitmap, force) },
                    onThumbnail = currentOnThumbnail.value,
                )
            }
        }
    }

    // The active session is refreshed at the normal interval. The dedupe and
    // throttle are per session now, so publishing a background session's final
    // frame cannot disturb the active one's cadence.
    LaunchedEffect(hostVisible, pane, sessionId) {
        if (!hostVisible || sessionId.isEmpty()) return@LaunchedEffect
        while (isActive && hostVisible) {
            delay(THUMBNAIL_CAPTURE_INTERVAL_MS)
            captureThumbnail(sessionId, false)
        }
    }

    // A session that loses pane focus in a split still has a live surface, so a
    // final frame here keeps its card honest even if it is polled less often.
    LaunchedEffect(focusedPane, sessionId) {
        if (focusedPane != paneSlot && sessionId.isNotEmpty()) {
            captureThumbnail(sessionId, true)
        }
    }

    DisposableEffect(pane, sessionId) {
        onDispose {
            // One last frame for the session that is leaving this pane. Declared
            // before the release effect below, so the surface is still ours.
            // Forced: the session's last normal capture may have been inside the
            // 2s throttle window, but a card must keep a last-good picture.
            captureThumbnail(sessionId, true)
        }
    }

    DisposableEffect(pane) {
        onDispose {
            terminalManager.unregisterPane(paneSlot)
            pane.release()
        }
    }

    DisposableEffect(lifecycleOwner, pane) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hostVisible = true
                    pane.onHostResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    hostVisible = false
                    pane.onHostPause()
                    // Going to the background is the last chance to sample this
                    // surface. Force past the 2s throttle; transparency and
                    // per-session dedupe still apply inside the publisher.
                    captureThumbnail(currentSessionId.value, true)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    pane.view.setCell(cell.width, cell.height, cell.fontPx)
                    pane.setHighlightColour(highlightColour)
                    pane.setHighlightAllColour(highlightAllColour)
                    pane.setBellColour(bellColour)
                    addView(pane.view)
                }
            },
            update = { _ ->
                // The theme's ground, on every recomposition that changes it.
                pane.setBackground(backgroundColor)
                // The user's half of the mouse handshake; the engine's half is
                // the tracking mode the running program turned on, which the
                // surface reads per gesture.
                pane.view.mouseReportingEnabled = mouseReporting
                // The hyperlink tap. Set here rather than in the factory so a
                // recomposition after a font change re-points it at the pane
                // that is on screen now — though the listener itself reads the
                // cell and the callback live, so it is the same object either
                // way.
                pane.view.setOnTouchListener(linkTap)
                // While the pinch runs the cell changes and the grid does not;
                // when it ends the size is committed and the grid is laid out
                // again, so the text fits the screen exactly.
                if (pinchActive) {
                    pane.view.setZoomCell(cell.width, cell.height, cell.fontPx)
                } else {
                    pane.setCell(cell.width, cell.height, cell.fontPx)
                }
            },
        )

        // The selection's grips and menu. Drawn after the terminal so it sits
        // on top of the text it is about; `paneOrigin` is zero because this Box
        // *is* the pane, and the bounds are already in the pane's pixels.
        if (hasSelection && selectionBounds != null) {
            // The grips are placed from the engine's endpoints, not the box's
            // corners: a wrapped selection that starts mid-row has its top
            // endpoint there, while the box's left edge is column zero.
            //
            // The sub-row shift moves the grid *down*, so it is added: the
            // grips that subtracted it slid up a row for every row of travel.
            val scrollOffset = pane.selectionScrollOffsetPx()
            val topLeft = pane.selectionTopLeftCell()?.let { (row, column) ->
                Offset(column * cell.width, row * cell.height + scrollOffset)
            }
            // The bottom grip hangs off the *end* of the selected cell: the
            // cell's left edge would put the knob on the last column's first
            // pixel, half a character short of the text it is holding.
            val bottomRight = pane.selectionBottomRightCell()?.let { (row, column) ->
                Offset((column + 1) * cell.width, row * cell.height + scrollOffset)
            }
            TerminalSelectionOverlay(
                bounds = selectionBounds!!,
                paneOrigin = Offset.Zero,
                onCopy = { pane.copySelection() },
                onSelectWord = {
                    pane.selectionAnchorCell()?.let { (row, column) ->
                        pane.selectWord(row, column, chrome = true)
                    }
                },
                onSelectLine = {
                    pane.selectionAnchorCell()?.let { (row, column) ->
                        pane.selectLine(row, column, chrome = true)
                    }
                },
                onSelectAll = { pane.selectAll() },
                onSearch = {
                    // The engine searches one terminal line at a time, so a
                    // multi-line selection is searched by its first meaningful
                    // line rather than by a needle that contains a newline and
                    // can never match.
                    val text = pane.selectionText()
                        .lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        ?.trim()
                        .orEmpty()
                    if (text.isNotEmpty()) onSearchSelection(text)
                },
                onDismiss = { pane.clearSelection() },
                // The clipboard is the app's business, not the engine's: the
                // manager already owns the paste path (bracketed when the
                // shell asked for it) and the focus that decides who gets it.
                onPaste = { terminalManager.pasteClipboard() },
                onShare = {
                    val text = pane.selectionText().trim()
                    if (text.isNotEmpty()) {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, text)
                        runCatching {
                            context.startActivity(
                                android.content.Intent.createChooser(send, null)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                },
                onRun = { pane.view.runSelection() },
                onRunInNewSession = {
                    val command = pane.selectionText().trim()
                    if (command.isNotEmpty()) {
                        onRunInNewSession(command)
                        pane.clearSelection()
                    }
                },
                onOpenInBrowser = {
                    // A selection that is not a link still has a good guess in
                    // it — a bare host name is a link as far as a browser is
                    // concerned — so the first word is what is opened.
                    val candidate = pane.selectionText()
                        .lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        ?.trim()
                        ?.substringBefore(' ')
                        .orEmpty()
                    if (candidate.isNotEmpty()) {
                        onUrlClick(
                            if (candidate.contains("://")) candidate else "https://$candidate",
                        )
                    }
                },
                onExtend = { x, y, topLeft -> pane.extendSelectionTo(x, y, topLeft) },
                onExtendEnd = { pane.endSelectionGripDrag() },
                topLeftHandle = topLeft,
                bottomRightHandle = bottomRight,
                terminalBackground = Color(backgroundColor),
                cellHeight = cell.height,
                // The menu places and clamps itself against the pane's edges,
                // so it has to be measured at the pane's size rather than at
                // the size of its own two grips.
                modifier = Modifier.fillMaxSize(),
            )
        }

        // The size, while the fingers are on the screen.
        //
        // A pinch changes the font's size and nothing else on the screen says
        // so: the text is simply bigger, and the number that was asked for is
        // gone. The chip appears for as long as the gesture runs.
        if (liveZoomSp > 0f) {
            TerminalZoomChip(
                textSizeSp = liveZoomSp,
                cellSizePx = cell.width.toInt() to cell.height.toInt(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
            )
        }

        status?.let { message ->
            Text(
                text = message,
                color = DroshTextSecondary,
                fontFamily = LocalFontSet.current.sans,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }

        TerminalDebugOverlay(
            report = diagnosticsText,
            expanded = diagnosticsOpen,
            recording = benchmarkRecording,
            status = benchmarkStatus,
            onToggle = { diagnosticsOpen = !diagnosticsOpen },
            onCopy = {
                clipboard.setText(AnnotatedString(diagnosticsText))
            },
            onRecord = {
                try {
                    dev.drosh.terminal.engine.bench.BenchmarkLog.start(
                        context,
                        "session ${pane.session?.persistentId ?: "none"}",
                    )
                    dev.drosh.terminal.engine.bench.RenderBench.reset()
                    dev.drosh.terminal.engine.input.InputStats.reset()
                    benchmarkRecording = true
                    benchmarkStatus =
                        "recording to ${dev.drosh.terminal.engine.bench.BenchmarkLog.path(context)}"
                } catch (error: java.io.IOException) {
                    benchmarkStatus = "cannot start: ${error.message}"
                }
            },
            onStop = {
                dev.drosh.terminal.engine.bench.BenchmarkLog.stop()
                benchmarkRecording = false
                benchmarkStatus = "stopped"
            },
            onFinish = {
                benchmarkStatus = dev.drosh.terminal.engine.bench.BenchmarkLog.finish()
                benchmarkRecording = false
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 4.dp),
        )
    }
}

private const val TAG = "GhosttyTerminalHost"

/** How often the open panel reads the moving numbers again. */
private const val DIAGNOSTICS_REFRESH_MS = 500L

/** One line in benchmark.log per second. */
private const val BENCHMARK_SAMPLE_MS = 1_000L

/** How many half-seconds the status line stays up before it gets out of the way. */
private const val STATUS_TICKS = 40

/** The cell a font measured, in pixels. */
private data class CellMetrics(val width: Float, val height: Float, val fontPx: Float)

/** The grid a PTY starts with before the surface has been measured. */
private const val DEFAULT_COLUMNS = 100
private const val DEFAULT_ROWS = 30
