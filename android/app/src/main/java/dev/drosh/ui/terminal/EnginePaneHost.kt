package dev.drosh.ui.terminal

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.terminal.session.EnginePane
import dev.drosh.terminal.session.TerminalManager
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    /**
     * How the terminal should look: the application's configuration.
     *
     * The engine's own answers still win — the colours a program set with
     * `OSC 4/10/11/12`, the cursor a `DECSCUSR` asked for — the way
     * alacritty splits it.
     */
    appearance: dev.drosh.domain.settings.TerminalConfig,
    /** The font file the user picked for the terminal, if any. */
    customFontUri: String,
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
     * The exit code of the session, when it ended.
     *
     * A guest that cannot start, or a shell that exits, leaves the surface
     * drawing an empty grid. The code says what happened instead of a blank
     * rectangle.
     */
    val sessionExit = remember { mutableStateOf<Int?>(null) }

    /** True when the engine refused to start the session at all. */
    val startFailed = remember { mutableStateOf(false) }

    /**
     * Why the guest is not running, when it is not.
     *
     * A message rather than a silent plain shell: a terminal that opens
     * `/system/bin/sh` looks exactly like a broken guest, and the user cannot
     * tell which of the two they are looking at.
     */
    val statusMessage = remember { mutableStateOf<String?>(null) }


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
    /**
     * The font file the user picked, handed over when it changes.
     *
     * The bytes are read here rather than kept in the settings: a font file
     * is megabytes, and the settings file is a small text file that is read
     * on every change.
     */
    LaunchedEffect(customFontUri) {
        val bytes = if (customFontUri.isBlank()) {
            null
        } else {
            readFontBytes(context, customFontUri)
        }
        if (bytes == null && customFontUri.isNotBlank()) {
            Log.w(TAG, "the font file could not be read; the bundled font stays")
        }
        pane.setCustomFont(bytes)
    }

    LaunchedEffect(pane) {
        // The configuration first: the cursor's fallback is fixed when the
        // session starts, and the cell the grid is laid out around is the one
        // the size asks for.
        pane.view.applyAppearance(appearance)

        // The guest command builds its shell rc files and asks the launcher
        // what it can run — both touch the disk, so they must not run on the
        // main thread. A composable's LaunchedEffect runs on the main
        // dispatcher, and doing this work there is what made the terminal
        // screen appear to hang on its first frame.
        val command = withContext(Dispatchers.IO) {
            val guest = terminalManager.guestCommand()
            if (guest == null) {
                // No guest, or no runtime: say which, on the screen, rather
                // than opening a plain shell the user did not ask for and
                // cannot tell from a broken terminal.
                statusMessage.value = terminalManager.guestUnavailableReason()
                return@withContext null
            }
            guest
        } ?: return@LaunchedEffect
        // Starting the session forks the guest and execs the launcher — with
        // chroot-ng that is a process spawn plus a pile of binds, and on the
        // main thread it is a freeze the system reports as an ANR.
        val started = withContext(Dispatchers.IO) { pane.start(command) }
        if (!started) {
            Log.w(TAG, "the engine refused the session")
            startFailed.value = true
            return@LaunchedEffect
        }
        statusMessage.value = null
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
     * The application's configuration, handed over as it changes.
     *
     * The palette travels whole, because the engine's wire format is a table
     * of colours and the theme is that table's source.
     */
    LaunchedEffect(appearance) {
        pane.view.applyAppearance(appearance)
        // The size is the user's own, not the setting's: the configuration
        // carries the size a session opens at, and a zoom is not that.
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
     * What the device can do, asked of chroot-ng itself.
     *
     * chroot-ng's own README says to run its probe on a target device
     * *first*: the whole in-process design depends on the SELinux `execmem`
     * permission being granted, and a device that cannot grant it cannot run
     * the guest. The answer is the difference between "the guest is broken" and
     * "this device cannot run it", so it is asked once and logged.
     */
    LaunchedEffect(pane, terminalManager.currentGuestIsolation) {
        // The probe is chroot-ng's own question, so it is only asked when
        // chroot-ng is the engine that would run the guest.
        if (terminalManager.currentGuestIsolation != dev.drosh.domain.settings.GuestIsolation.CHROOT_NG) {
            return@LaunchedEffect
        }
        // The probe starts a process and waits up to ten seconds for it. On the
        // main thread that is a ten-second freeze, which the system reports as
        // an ANR — so it runs off the UI thread and the terminal is usable
        // while it does.
        val report = withContext(Dispatchers.IO) {
            dev.drosh.terminal.engine.guest.ChrootProbe.run(context)
        }
        Log.i(TAG, "chroot-ng probe:\n$report")
        // The probe's first line is the one that matters: the runtime is
        // missing, or it cannot run here. That is the difference between "the
        // guest is broken" and "this build has no guest runtime", and it is
        // the difference the log would otherwise be the only place to find.
        val finding = report.lineSequence().firstOrNull { it.startsWith("probe: ") }
        if (finding != null) {
            // Logged, not shown. The probe's first line is its normal output
            // on most devices, and a diagnostic that takes the screen away
            // from the terminal it describes is worse than no diagnostic.
            Log.i(TAG, "probe finding: " + finding.removePrefix("probe: ").trim())
        }
    }

    /**
     * The pane's own news, logged.
     *
     * The engine's session can die — a guest that cannot start, a shell that
     * exits — and nothing in the view says so: the surface goes on drawing an
     * empty grid. A terminal that is empty and silent is the hardest kind of
     * bug to read, so the exit is logged here, where the app's log can be read
     * next to the engine's.
     */
    LaunchedEffect(pane) {
        pane.listener = object : dev.drosh.terminal.engine.view.TerminalView.Listener {
            override fun onSessionExited(code: Int) {
                Log.w(TAG, "the engine's session exited with $code")
                // The screen goes on drawing an empty grid after the session
                // is gone, so a terminal that is empty and silent is the
                // hardest kind of bug to read. The exit is said out loud.
                sessionExit.value = code
            }

            override fun onSessionEvent(record: String) {
                Log.i(TAG, "engine event: $record")
            }
        }
    }

    /**
     * The pane's actual height, in pixels.
     *
     * A surface of zero height draws nothing — no background, no cursor, no
     * text — and it draws it without an error, so the symptom is a blank
     * rectangle with no failure to report. The host measures the terminal as
     * it is laid out and says so when it is empty, rather than leaving the
     * user with a terminal that is blank for a reason only a layout inspector
     * could see.
     */
    val paneHeightPx = remember { mutableStateOf(0) }

    /**
     * The pane goes away with the composition.
     *
     * The manager is told first — it is the one that publishes the focus and
     * the selection — and the pane is released after: the pane owns the
     * session, and this is the only thing that closes it.
     *
     * **Keyed on nothing but the slot.** The effect must not restart for a
     * reason of its own, because restarting it means the undo below runs while
     * the host is still on screen.
     */
    DisposableEffect(paneSlot) {
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

    /**
     * What is wrong, in one string, or null when nothing is.
     *
     * The order is the order a user can act on: a session that never started
     * beats one that ended, and both beat a probe's opinion.
     *
     * **The probe's answer is deliberately not here.** It used to be, and a
     * probe line that began with `probe: ` — its normal output, not a failure
     * — replaced the terminal with a message. A diagnostic is not an error,
     * and it must never take the screen away from the terminal it describes.
     */
    val problem: String? = statusMessage.value
        ?: startError?.let { stringResource(R.string.terminal_engine_unavailable, it) }
        ?: if (startFailed.value) {
            stringResource(R.string.terminal_engine_session_exited, 0)
        } else {
            sessionExit.value?.let { stringResource(R.string.terminal_engine_session_exited, it) }
        }

    /**
     * The terminal, and the reason over it when there is one.
     *
     * The terminal is composed **first and always**; the message is an overlay
     * on top of it rather than a replacement for it. The two used to be
     * exclusive — a message returned before the view was built — so any
     * failure, and any false alarm, showed an empty screen where the terminal
     * should have been.
     */
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    onBoundsChanged(it.boundsInRoot())
                    paneHeightPx.value = it.size.height
                },
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

        // The reason, over the terminal rather than instead of it. A
        // translucent ground, because the surface underneath is the
        // terminal's own and a message on a bare grid is unreadable.
        if (problem != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = problem,
                    color = DroshTextSecondary,
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else if (paneHeightPx.value == 0) {
            // The host is on screen with no height. Nothing can be drawn in a
            // surface of zero pixels, and nothing reports it, so the reason is
            // named here instead of leaving a blank rectangle.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "the terminal has no height to draw in",
                    color = DroshTextSecondary,
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

private const val TAG = "EnginePaneHost"

/**
 * A font file's bytes, read through the content resolver.
 *
 * The URI is the one the system picker gave, and the permission to read it
 * was taken when it was picked. A file that cannot be read is `null`: the
 * terminal keeps the font it had.
 */
private fun readFontBytes(context: android.content.Context, uri: String): ByteArray? = runCatching {
    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
}.getOrNull()
