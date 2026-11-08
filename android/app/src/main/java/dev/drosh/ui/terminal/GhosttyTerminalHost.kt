package dev.drosh.ui.terminal

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val cell = remember(fontSizeSp, context) {
        val density = context.resources.displayMetrics.scaledDensity
        val paint = android.graphics.Paint().apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = fontSizeSp * density
            isAntiAlias = true
        }
        CellMetrics(
            width = paint.measureText("M").coerceAtLeast(1f),
            height = paint.fontSpacing.coerceAtLeast(1f),
            // The font's own size, which is what a glyph is rasterised at. The
            // cell's height is the line box and is about a fifth taller.
            fontPx = paint.textSize,
        )
    }

    var status by remember { mutableStateOf<String?>("preparing") }

    // The guest is started once. It is a fork, a rootfs and an exec, so it does
    // not belong on the main thread.
    LaunchedEffect(pane, sessionName) {
        status = "preparing"

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
                pane.start(
                    command = guest,
                    columns = DEFAULT_COLUMNS,
                    rows = DEFAULT_ROWS,
                    cellWidth = cell.width,
                    cellHeight = cell.height,
                )
            }.getOrElse { error ->
                Log.e(TAG, "the engine refused the session", error)
                false
            }
        }

        if (!started) {
            status = "start failed: ${pane.lastError ?: "no reason"}"
            return@LaunchedEffect
        }

        // Same registration the other host performs: without it the manager
        // never knows this pane is a session, so it is absent from the grid,
        // cannot be restored, closed, named or previewed — a working terminal
        // that the app does not track.
        if (sessionId.isNotEmpty()) {
            terminalManager.registerEngineTerminal(
                paneSlot,
                pane,
                persistentId = sessionId,
                name = sessionName,
            )
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
            status = if (failure != null) {
                "renderer: $failure"
            } else {
                "${pane.view.bytesRead} bytes\n" +
                    "${pane.view.framesDrawn} frames\n" +
                    "frame ${pane.view.lastFrameShape}"
            }
            kotlinx.coroutines.delay(500)
            ticks += 1
        }
        status = null
    }

    // The engine's own report, for a build that has to be diagnosed on a phone.
    LaunchedEffect(pane) {
        Log.i(TAG, dev.drosh.terminal.engine.diagnostics.EngineDiagnostics.report())
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
            terminalManager.unregisterEngineTerminal(paneSlot)
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
                pane.setCell(cell.width, cell.height, cell.fontPx)
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

/** The cell a font measured, in pixels. */
private data class CellMetrics(val width: Float, val height: Float, val fontPx: Float)

/** The grid a PTY starts with before the surface has been measured. */
private const val DEFAULT_COLUMNS = 100
private const val DEFAULT_ROWS = 30
