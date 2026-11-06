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
 * It does not register with the manager, so a Ghostty pane does not appear in
 * the sidebar and cannot be restored. That is deliberate for a first run: the
 * question this host answers is "does the engine draw", and a session registry
 * entry that cannot be closed is a worse first question.
 */
@Composable
fun GhosttyTerminalHost(
    paneSlot: PaneSlot,
    terminalManager: TerminalManager,
    sessionName: String = "",
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val pane = remember { GhosttyTerminal(context) }

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
                    cellWidth = 0f,
                    cellHeight = 0f,
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

        // The session runs; keep the counters up until the screen has something
        // on it. A black frame with zero bytes read is a different bug from one
        // with four thousand, and the difference is one line here.
        while (pane.view.bytesRead == 0L && status != null) {
            status = "waiting: 0 bytes, ${pane.view.framesDrawn} frames"
            kotlinx.coroutines.delay(500)
        }
        status = null
    }

    // The engine's own report, for a build that has to be diagnosed on a phone.
    LaunchedEffect(pane) {
        Log.i(TAG, dev.drosh.terminal.engine.diagnostics.EngineDiagnostics.report())
    }

    pane.onSessionExited = { code -> status = "session ended ($code)" }

    DisposableEffect(pane) {
        onDispose { pane.release() }
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
                    pane.view.setCell(fontSizeSp, fontSizeSp * LINE_HEIGHT_RATIO)
                    addView(pane.view)
                }
            },
            update = { _ ->
                pane.setCell(fontSizeSp, fontSizeSp * LINE_HEIGHT_RATIO)
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

/** The grid a PTY starts with before the surface has been measured. */
private const val DEFAULT_COLUMNS = 100
private const val DEFAULT_ROWS = 30

/** A terminal's line box, as a multiple of the font size. */
private const val LINE_HEIGHT_RATIO = 1.25f