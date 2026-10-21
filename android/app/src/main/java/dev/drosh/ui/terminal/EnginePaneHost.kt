package dev.drosh.ui.terminal

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.R
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.terminal.session.EnginePane
import dev.drosh.terminal.session.TerminalManager

/**
 * A pane that runs on the Rust engine rather than on the vendored Termux
 * terminal.
 *
 * ## What it is
 *
 * The engine's terminal is not a view the app attaches to a session: the
 * engine's view *owns* its session and starts it itself. So the app cannot
 * host it the way it hosts the vendored one — it holds an [EnginePane], tells
 * it which guest command to run, and puts the pane's own view into the
 * layout.
 *
 * The manager is told about the pane ([TerminalManager.registerEnginePane])
 * so that the app's own flows — the focus, the selection, the scroll
 * position, the clipboard — answer for it exactly as they do for a vendored
 * pane. The app does not keep a second opinion about any of them.
 *
 * ## The command
 *
 * The guest command is the manager's own ([TerminalManager.guestCommand]):
 * the same launcher, the same shell, the same environment and the same
 * working directory the vendored path runs. A guest that behaved differently
 * per terminal would be a guest nobody could debug.
 *
 * ## What is not here yet
 *
 * The engine's terminal has no pinch-to-zoom, no search overlay and no
 * background image behind it: the app's zoom is the vendored view's gesture,
 * the search reads the vendored view's transcript, and the vendored renderer
 * paints no background for default cells while the engine's surface is
 * opaque. The font-size setting does reach the engine.
 */
@Composable
fun EnginePaneHost(
    paneSlot: PaneSlot,
    terminalManager: TerminalManager,
    fontSizeSp: Float,
    defaultFontSizeSp: Float,
    /**
     * Where this pane's terminal is sampled for the chrome.
     *
     * Published by the pane that has the focus, because the chrome samples
     * the terminal the user is looking at.
     */
    appearanceSource: MutableState<TerminalAppearanceSource?>,
    /** Root-space bounds, for the top bar's backdrop. */
    onBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusedPane by terminalManager.focusedPane.collectAsStateWithLifecycle()

    /**
     * The pane: the engine's view and the session behind it.
     *
     * One per composition, and it outlives a recomposition — the session is
     * the expensive thing here, and a pane that was rebuilt on every
     * recomposition would be a shell that never lived long enough to say
     * anything.
     */
    val pane = remember { EnginePane(context) }

    /**
     * Where the chrome samples this terminal.
     *
     * The engine's pixels live in a surface of their own, so the chrome asks
     * the session for the colours of its cells instead.
     */
    val source = remember(pane) { EngineAppearanceSource(pane.session) }

    /**
     * The session, started once.
     *
     * After the first composition: the engine's view sizes its grid from the
     * surface it has, and a session started before the view is laid out opens
     * at the fallback size and is resized a moment later.
     */
    LaunchedEffect(pane) {
        val command = terminalManager.guestCommand()
        if (command == null) {
            Log.w(TAG, "the guest is not installed; the engine's terminal has nothing to run")
            return@LaunchedEffect
        }
        // The size first: the grid is laid out around the cell, and a session
        // that starts at one size and is resized to another afterwards is a
        // flash of the wrong grid.
        pane.view.setTextSize(defaultFontSizeSp)
        if (!pane.start(command)) {
            Log.w(TAG, "the engine refused the session")
            return@LaunchedEffect
        }
        // From here the manager answers for this pane: the focus, the
        // selection, the scroll position and the clipboard are its business.
        // Registering it at the slot that already has the focus is enough —
        // the manager activates a pane that arrives there.
        terminalManager.registerEnginePane(paneSlot, pane)
    }

    /**
     * The size the setting asks for.
     *
     * The engine re-measures its cell and lays the grid out around it, so
     * this is the same gesture the vendored view gets from `setTextSize`.
     */
    LaunchedEffect(fontSizeSp) {
        pane.view.setTextSize(fontSizeSp)
    }

    /**
     * The surface and the frame pump follow the host's lifecycle.
     *
     * The engine draws through a GL surface, and a surface that is not
     * resumed draws nothing — so the terminal would be a black rectangle
     * without this. The pane is created while the activity is already
     * resumed, so the first ON_RESUME has been and gone by the time the
     * observer is attached: the state is asked for as well.
     */
    DisposableEffect(lifecycleOwner, pane) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> pane.onHostResume()
                Lifecycle.Event.ON_PAUSE -> pane.onHostPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pane.onHostResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    /**
     * The pane goes away with the composition.
     *
     * The manager is told first — it is the one that publishes the focus and
     * the selection — and the pane is released after: the pane owns the
     * session, and this is the only thing that closes it.
     */
    DisposableEffect(pane, paneSlot) {
        onDispose {
            terminalManager.unregisterEnginePane(paneSlot)
            pane.release()
        }
    }

    /**
     * The focus follows the pane the user is in.
     *
     * The manager owns "which pane is active" for both kinds of terminal, so
     * the app asks it rather than keeping its own answer.
     */
    LaunchedEffect(focusedPane, pane) {
        if (focusedPane == paneSlot) {
            terminalManager.focusPane(paneSlot)
        }
    }

    /**
     * Where the chrome samples the terminal from.
     *
     * Published by the pane that has the focus, and only by it: the chrome
     * samples the terminal the user is looking at.
     */
    LaunchedEffect(focusedPane, source) {
        if (focusedPane == paneSlot) {
            appearanceSource.value = source
        }
    }

    /**
     * Why the engine could not start, when it could not.
     *
     * A build without the Rust core has no engine at all, and the symptom
     * without this is a white rectangle: the surface never draws a frame. So
     * the pane says what is missing instead of leaving the user with a blank
     * terminal and no reason.
     */
    val startError = pane.view.startError

    if (startError != null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.terminal_engine_unavailable, startError),
                color = DroshTextSecondary,
                fontFamily = LocalFontSet.current.sans,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    AndroidView(
        modifier = modifier
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
        factory = { ctx ->
            val frame = android.widget.FrameLayout(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            // The engine's view brings its own IME target along — a
            // one-pixel editor inside the view — so the host puts the view
            // in the hierarchy and nothing else.
            frame.addView(pane.view)
            // A tap is how the user says "I am working here", exactly as in
            // the vendored path: the platform focus listener, so the tap is
            // not consumed and the terminal still sees it.
            //
            // The engine's own tap gesture focuses the IME target rather than
            // the view, so the target is watched as well — otherwise a tap in
            // the second pane of a split would move the keyboard there and
            // leave the app's focus where it was.
            pane.view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) terminalManager.focusPane(paneSlot)
            }
            pane.imeTarget.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) terminalManager.focusPane(paneSlot)
            }
            frame
        },
    )
}

private const val TAG = "EnginePaneHost"
