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

@Composable
fun TerminalScreen(
    terminalManager: TerminalSessionCoordinator,
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
    // emulator and republished by TerminalSessionCoordinator. Collected here because this is
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

/** "#RRGGBB" as a colour int; black when it is not one. */
internal fun hexToColorInt(hex: String): Int =
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
 * How long typing must be quiet before a find scan runs.
 *
 * The field follows every keystroke, but the scan is a pass over the whole
 * output; running one per character of a word would keep the bar a step behind
 * the typing and never let a long query finish. A short pause gives one scan
 * per word instead.
 */
internal const val FIND_DEBOUNCE_MILLIS = 150L

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
internal const val ENGINE_RESCAN_INTERVAL_MS = 1000L



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
internal fun humanReadableBytes(bytes: Long): String {
    val unit = 1024L
    if (bytes < unit) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(unit.toDouble())).toInt()
    val prefix = "KMGTPE"[exp - 1]
    val value = bytes.toDouble() / Math.pow(unit.toDouble(), exp.toDouble())
    return String.format("%.1f %cB", value, prefix)
}

internal fun buildSystemInfo(
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

/**
 * How long the session grid takes to come and go.
 *
 * Long enough to read as a movement, short enough that it is never in the way
 * of the thing the user opened it for.
 */
internal const val GRID_TRANSITION_MILLIS = 240

/**
 * How long the scrim takes to clear after a session switch.
 *
 * Short: it is there to soften the change, not to delay it.
 */
internal const val SESSION_TRANSITION_MILLIS = 180
