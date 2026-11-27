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

@Composable
internal fun SetupProgress(
    state: UbuntuSetupState,
) {
    val label = when (state) {
        UbuntuSetupState.Idle ->
            stringResource(R.string.terminal_preparing)

        UbuntuSetupState.Extracting ->
            stringResource(R.string.terminal_extracting_rootfs)

        UbuntuSetupState.Configuring ->
            stringResource(R.string.terminal_configuring)

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
internal fun SetupFailure(
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
                Text(stringResource(dev.drosh.ui.R.string.action_retry))
            }
        }
    }
}

