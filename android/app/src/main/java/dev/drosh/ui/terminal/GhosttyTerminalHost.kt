package dev.drosh.ui.terminal

import android.util.Log
import android.os.SystemClock
import android.view.MotionEvent
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            typeface = android.graphics.Typeface.MONOSPACE
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
    var zoomSizeSp by remember { mutableStateOf(0f) }

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
            status = "no guest command"
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

    DisposableEffect(pane) {
        onDispose {
            terminalManager.unregisterPane(paneSlot)
            pane.release()
        }
    }

    DisposableEffect(lifecycleOwner, pane) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> pane.onHostResume()
                Lifecycle.Event.ON_PAUSE -> pane.onHostPause()
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
                    addView(pane.view)
                }
            },
            update = { _ ->
                // The theme's ground, on every recomposition that changes it.
                pane.setBackground(backgroundColor)
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
    }
}

private const val TAG = "GhosttyTerminalHost"

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
                if (wasTap) followLink(event.x, event.y)
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
