package dev.drosh.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import dev.drosh.ui.session.DeviceBadge
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshTextMuted
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.core.LanguageCatalog
import dev.drosh.core.copyToClipboard
import dev.drosh.core.toast
import dev.drosh.theme.DroshBackground
import dev.drosh.ui.session.DeviceIdentityViewModel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import dev.drosh.core.ShellLocaleCatalog
import dev.drosh.domain.settings.AutoLockTimeout
import dev.drosh.domain.settings.CursorStyle
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.FontPack
import dev.drosh.domain.settings.MotdDefaults
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.settings.TerminalMode
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.domain.terminal.TerminalZoom
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.LocalDroshActivity
import dev.drosh.ui.R
import kotlinx.coroutines.launch
import java.util.Locale

val GROUP_GAP = 18.dp

/** Content clearance for the system bar, without insetting the background. */
@Composable
private fun statusBarInset(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * The frame every category screen sits in.
 *
 * One scaffold rather than eight copies of the same column: the categories
 * differ in what fills the groups and in nothing else, and a top bar that
 * behaved differently per category would be a bug factory.
 */
@Composable
fun SettingsCategoryScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = statusBarInset()),
        ) {
            SettingsTopBar(title = title, onBack = onBack)
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Applies a transform produced by a row to the whole settings file.
 *
 * Public because the category screens take it: a row never writes a key,
 * it hands the whole file a transform and the store answers.
 */
typealias OnSettingsUpdate = ((DroshSettings) -> DroshSettings) -> Unit

// ── Appearance ───────────────────────────────────────────────────────────────
