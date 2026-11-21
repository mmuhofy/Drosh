package dev.drosh.ui.terminal

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
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
import dev.drosh.terminal.session.TerminalManager
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
    terminalManager: TerminalManager,
    sessionId: String = "",
    sessionName: String = "",
    fontSizeSp: Float,
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

    val cell = remember(effectiveSp, context) {
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
            height = (metrics.descent - metrics.ascent).coerceAtLeast(1f),
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
    val selectionText by remember(hasSelection, selectionBounds) {
        derivedStateOf {
            if (!hasSelection) {
                null
            } else {
                pane.selectionText().takeIf { it.isNotEmpty() }
            }
        }
    }
    val selectionUrl = remember(selectionText) {
        selectionText?.let { dev.drosh.domain.UrlDetector.findUrls(it).singleOrNull()?.url }
    }
    val hasClipboardText = remember(hasSelection) {
        if (!hasSelection) return@remember false
        context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
    }
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
            TerminalSelectionMenu(
                bounds = selectionBounds,
                paneOrigin = Offset.Zero,
                hasClipboardText = hasClipboardText,
                url = selectionUrl,
                onCopy = { pane.copySelection() },
                onPaste = { pane.pasteClipboard() },
                onSelectAll = { pane.selectAll() },
                onOpenUrl = { /* handled by the host's own link tap */ },
                onExtend = { x, y, fromEnd -> pane.extendSelectionTo(x, y, fromEnd) },
                terminalBackground = Color(backgroundColor),
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

/** How often a visible session's last frame is refreshed. */
private const val THUMBNAIL_CAPTURE_INTERVAL_MS = 2_000L

/**
 * Capture width for a session card.
 *
 * A card is a few hundred dp wide on the grid, and the old 160px capture was
 * being scaled up into it — the source of the soft, shifting previews. Capture
 * above the card's size so the downscale is done by the decoder, at a fixed
 * width so the bitmap's shape does not change with the pane's layout.
 */
private const val THUMBNAIL_WIDTH = 480

/** Guard against an absurdly tall bitmap for a very narrow pane. */
private const val THUMBNAIL_MAX_HEIGHT = 960

/**
 * Copy one frame from the pane's surface into a stable bitmap and hand it up.
 *
 * // UNTESTED — verify before use
 * PixelCopy is asynchronous and reports through a listener on the main looper;
 * neither the copy nor a failure waits on the render thread. A secure or already
 * destroyed surface can refuse the request — that is logged and dropped, never
 * retried in a tight loop and never allowed to block a frame.
 */
private fun captureSessionThumbnail(
    surface: Surface,
    viewWidth: Int,
    viewHeight: Int,
    sessionId: String,
    shouldPublish: (String, Bitmap) -> Boolean,
    onThumbnail: (String, ImageBitmap) -> Unit,
) {
    // UNTESTED — verify before use
    if (sessionId.isEmpty() || viewWidth <= 0 || viewHeight <= 0 || !surface.isValid) return
    val width = THUMBNAIL_WIDTH
    val height = (width.toFloat() * viewHeight / viewWidth)
        .toInt()
        .coerceIn(1, THUMBNAIL_MAX_HEIGHT)
    val bitmap = runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }.getOrElse { error ->
        Log.w(TAG, "thumbnail bitmap allocation failed: ${error.message}")
        return
    }
    PixelCopy.request(
        surface,
        bitmap,
        { result ->
            if (result == PixelCopy.SUCCESS) {
                // UNTESTED — verify before use: an idle terminal returns the same
                // pixels on every tick, and republishing those reallocated the
                // card's image at 2s intervals, which read as a flicker.
                if (shouldPublish(sessionId, bitmap)) {
                    onThumbnail(sessionId, bitmap.asImageBitmap())
                } else {
                    Log.d(TAG, "thumbnail unchanged for $sessionId — not published")
                }
            } else {
                Log.w(TAG, "PixelCopy failed for $sessionId: $result")
            }
        },
        Handler(Looper.getMainLooper()),
    )
}

/**
 * De-duplicates and throttles thumbnails before they reach the session grid.
 *
 * // UNTESTED — verify before use
 * A signature is a sparse sample of the frame rather than a full pixel compare:
 * a terminal is mostly still between keystrokes, and two 480px bitmaps are
 * cheap to compare this way on the main looper. A transparent capture is dropped
 * because the surface can exist before its first frame — publishing that gave
 * the card an empty picture.
 *
 * State is kept per session: this single publisher serves every live session's
 * captures (polled ones and the final frames of ones going background), so a
 * global "last session" would either throttle the wrong card or republish an
 * unchanged one. All calls arrive on the main looper — PixelCopy's listener and
 * the polling coroutine both run there — so the map needs no lock.
 */
private class ThumbnailPublisher(private val minIntervalMs: Long) {
    private data class Published(val signature: IntArray, val atMs: Long)

    private val published = HashMap<String, Published>()

    /**
     * [force] skips only the throttle: a final capture of a session leaving the
     * pane must land even when its last normal capture was seconds ago. Dedupe
     * and the transparency check are unchanged, so an unchanged or empty frame
     * is never published.
     */
    fun shouldPublish(sessionId: String, bitmap: Bitmap, force: Boolean = false): Boolean {
        if (sessionId.isEmpty() || bitmap.isRecycled) return false
        if (bitmap.width <= 0 || bitmap.height <= 0 || !bitmap.hasVisiblePixels()) return false

        val signature = bitmap.signature()
        val previous = published[sessionId]
        if (previous != null && signature.contentEquals(previous.signature)) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && previous != null && now - previous.atMs < minIntervalMs) return false

        published[sessionId] = Published(signature, now)
        return true
    }
}

/** Sparse sample of a bitmap, stable enough to tell a changed frame from a still one. */
private fun Bitmap.signature(): IntArray {
    val samples = THUMBNAIL_SIGNATURE_SAMPLES
    val out = IntArray(samples * samples)
    for (y in 0 until samples) {
        val py = y * (height - 1) / (samples - 1)
        for (x in 0 until samples) {
            val px = x * (width - 1) / (samples - 1)
            out[y * samples + x] = getPixel(px, py)
        }
    }
    return out
}

/** True when any sampled pixel is not fully transparent. */
private fun Bitmap.hasVisiblePixels(): Boolean {
    val samples = THUMBNAIL_SIGNATURE_SAMPLES
    for (y in 0 until samples) {
        val py = y * (height - 1) / (samples - 1)
        for (x in 0 until samples) {
            val px = x * (width - 1) / (samples - 1)
            if (getPixel(px, py) and OPAQUE_ALPHA_MASK != 0) return true
        }
    }
    return false
}

private const val THUMBNAIL_SIGNATURE_SAMPLES = 16
private const val OPAQUE_ALPHA_MASK = -0x1000000

/** How often the open panel reads the moving numbers again. */
private const val DIAGNOSTICS_REFRESH_MS = 500L

/** One line in benchmark.log per second. */
private const val BENCHMARK_SAMPLE_MS = 1_000L

/** How many half-seconds the status line stays up before it gets out of the way. */
private const val STATUS_TICKS = 40

/**
 * How long a touch may last and still count as a tap.
 *
 * A terminal's own gestures are drags and pinches; a tap is the short, still
 * one between them. The window is short because a long press that happened to
 * move a pixel should scroll, not follow a link.
 */
private const val TAP_TIMEOUT_MILLIS = 250L

/** The cell a font measured, in pixels. */
private data class CellMetrics(val width: Float, val height: Float, val fontPx: Float)

/**
 * Follows the hyperlink, if any, under a tap on the terminal.
 *
 * ## Why a listener that returns false
 *
 * The surface handles its own touches — scroll, fling, pinch, focus — and this
 * must not take any of that away. An `OnTouchListener` runs before the view's
 * own `onTouchEvent`, and returning false hands the event straight on, so the
 * surface behaves exactly as it did. What is left here is the one thing the
 * surface does not do: spotting a tap and asking the engine about the cell
 * under it.
 *
 * ## Why the cell is read at tap time
 *
 * The cell size and the resolver are functions, not values: a pinch or a font
 * change moves the cell, and a listener built once would otherwise resolve the
 * tap against the cell it was built with. They are read when the tap lands.
 */
private class HyperlinkTap(
    /** How far a touch may wander and still be a tap. */
    private val slop: Int,
    /** How long a touch may last and still be a tap. */
    private val timeoutMillis: Long,
    /** The pane's cell, in pixels, read at tap time. */
    private val cellSize: () -> Pair<Float, Float>,
    /** Whether touch is being reported to the program as mouse events. */
    private val mouseActive: () -> Boolean,
    /** The engine's link for a viewport cell, or null. */
    private val resolve: (row: Int, column: Int) -> String?,
    /** Told the URI that was found. */
    private val onLink: (String) -> Unit,
) : View.OnTouchListener {

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** True while the touch could still become a tap. */
    private var candidate = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = SystemClock.uptimeMillis()
                // One finger only: a two-finger touch is a pinch's beginning,
                // and a pinch is not a tap on a link.
                candidate = event.pointerCount == 1
            }

            MotionEvent.ACTION_POINTER_DOWN -> candidate = false

            MotionEvent.ACTION_MOVE -> {
                if (candidate &&
                    (abs(event.x - downX) > slop || abs(event.y - downY) > slop)
                ) {
                    candidate = false
                }
            }

            MotionEvent.ACTION_UP -> {
                val wasTap = candidate && SystemClock.uptimeMillis() - downAt <= timeoutMillis
                candidate = false
                // Under mouse reporting the tap is the program's click: the
                // surface has already sent it, and opening a link on top of
                // the click the user did not ask for would be two actions for
                // one touch.
                if (wasTap && !mouseActive()) followLink(event.x, event.y)
            }

            MotionEvent.ACTION_CANCEL -> candidate = false
        }
        // Never consume: the surface's own gestures must go on receiving every
        // event exactly as they did before.
        return false
    }

    /** Resolve the tapped cell and report its link, if it has one. */
    private fun followLink(x: Float, y: Float) {
        val (width, height) = cellSize()
        if (width <= 0f || height <= 0f) return
        // Viewport coordinates: the grid is drawn from the surface's origin, so
        // the cell under the finger is the finger divided by the cell. The
        // sub-line scroll remainder is not applied — a tap after a scroll can
        // be one row out until the picture has settled on a whole row.
        val column = (x / width).toInt()
        val row = (y / height).toInt()
        if (column < 0 || row < 0) return
        val uri = resolve(row, column) ?: return
        if (uri.isNotEmpty()) onLink(uri)
    }
}

/** The grid a PTY starts with before the surface has been measured. */
private const val DEFAULT_COLUMNS = 100
private const val DEFAULT_ROWS = 30
