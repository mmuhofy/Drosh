package dev.drosh.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.core.LanguageCatalog
import dev.drosh.core.copyToClipboard
import dev.drosh.core.toast
import dev.drosh.domain.settings.CursorStyle
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.session.DeviceBadge
import dev.drosh.ui.session.DeviceIdentityViewModel
import kotlinx.coroutines.launch

private val GROUP_GAP = 22.dp

/**
 * Settings.
 *
 * A flat column of tiles. Groups are not filled containers: the tiles run
 * together over a 4dp gutter, the ends of a run are rounded and the middle is
 * not, and there is not a single divider line on the screen.
 *
 * A row's shape follows what it carries. A switch, a value or a chevron sits
 * beside the label. A segmented control or a slider does not — it goes under
 * the label at full width. Beside it, a control that fills its row takes the
 * width from the text column and squeezes the label down to one character per
 * line, which is precisely what this screen used to do.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    deviceIdentityViewModel: DeviceIdentityViewModel = hiltViewModel(),
) {
    val themeMode         by viewModel.themeMode.collectAsStateWithLifecycle()
    val locale            by viewModel.locale.collectAsStateWithLifecycle("")
    val useBlockEngine    by viewModel.useBlockEngine.collectAsStateWithLifecycle(false)
    val fontSizeSp        by viewModel.fontSizeSp.collectAsStateWithLifecycle(14)
    val prootStartCommand by viewModel.prootStartCommand.collectAsStateWithLifecycle("")
    val isPinLockEnabled  by viewModel.isPinLockEnabled.collectAsStateWithLifecycle(false)
    val cursorBlinkRateMs by viewModel.cursorBlinkRateMs.collectAsStateWithLifecycle(500)
    val aboutInfo         by viewModel.aboutInfo.collectAsStateWithLifecycle(null)
    val motdMode          by viewModel.motdMode.collectAsStateWithLifecycle(MotdMode.PlainText)
    val motdText          by viewModel.motdText.collectAsStateWithLifecycle("")
    val deviceIdentity    by deviceIdentityViewModel.identity.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPinEntry by rememberSaveable { mutableStateOf(false) }
    var showMotdDialog by rememberSaveable { mutableStateOf(false) }
    var showStartupDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageSheet by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .testTag("settings_content"),
        ) {
            SettingsTopBar(title = "Settings", onBack = onBack)

            SettingsGroupColumn(label = null, items = 1) { _, cap ->
                SettingsTile(cap = cap, title = deviceName(deviceIdentity?.marketingName)) {
                    DeviceBadge(
                        imageUrl = deviceIdentity?.visualUrl,
                        fallbackLetter = (deviceIdentity?.marketingName ?: "?")
                            .firstOrNull()?.uppercase() ?: "?",
                        size = 46.dp,
                    )
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = "Appearance", items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = "Theme",
                    icon = DroshIcons.Palette,
                    supporting = when (themeMode) {
                        ThemeMode.System -> "Follows the system setting"
                        ThemeMode.Light -> "Always light"
                        ThemeMode.Dark -> "Always dark"
                    },
                ) {
                    SettingsSegmented(
                        options = listOf(
                            ThemeMode.System to "Auto",
                            ThemeMode.Light to "Light",
                            ThemeMode.Dark to "Dark",
                        ),
                        selected = themeMode,
                        onSelect = viewModel::setThemeMode,
                    )
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = "Terminal", items = 4) { index, cap ->
                when (index) {
                    0 -> SettingsTile(
                        cap = cap,
                        title = "Block mode",
                        icon = DroshIcons.SquareTerminal,
                        supporting = "Show each command and its output as a block",
                    ) {
                        SettingsSwitch(useBlockEngine, viewModel::setUseBlockEngine)
                    }

                    1 -> SettingsTile(
                        cap = cap,
                        stacked = true,
                        title = "Font size",
                        icon = DroshIcons.Resize,
                        supporting = "${fontSizeSp}sp",
                    ) {
                        SettingsSlider(
                            value = fontSizeSp.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toInt()) },
                            valueRange = 8f..24f,
                        )
                    }

                    2 -> SettingsTile(
                        cap = cap,
                        stacked = true,
                        title = "Cursor blink",
                        icon = DroshIcons.Clock,
                        supporting = if (cursorBlinkRateMs == 0) "Off" else "${cursorBlinkRateMs}ms",
                    ) {
                        SettingsSlider(
                            value = cursorBlinkRateMs.toFloat(),
                            onValueChange = { viewModel.setCursorBlinkRateMs(it.toInt()) },
                            valueRange = 0f..1200f,
                        )
                    }

                    else -> SettingsTile(
                        cap = cap,
                        title = "Cursor style",
                        icon = DroshIcons.Cursor,
                        supporting = CursorStyle.fromString("Block").label(),
                        onClick = { viewModel.setCursorStyle(CursorStyle.Underline) },
                    ) { SettingsChevron() }
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = "Startup", items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = "Startup command",
                    icon = DroshIcons.Terminal,
                    supporting = prootStartCommand.ifBlank { "\$shell --login" },
                    onClick = { showStartupDialog = true },
                ) { SettingsChevron() }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = "Message of the day", items = 2) { index, cap ->
                if (index == 0) {
                    SettingsTile(
                        cap = cap,
                        stacked = true,
                        title = "Show as",
                        icon = DroshIcons.Info,
                        supporting = when (motdMode) {
                            MotdMode.Disabled -> "Do not show it"
                            MotdMode.PlainText -> "The shell echoes the text"
                            MotdMode.Compose -> "Rendered as an interactive card"
                        },
                    ) {
                        SettingsSegmented(
                            options = listOf(
                                MotdMode.Disabled to "Off",
                                MotdMode.PlainText to "Text",
                                MotdMode.Compose to "Card",
                            ),
                            selected = motdMode,
                            onSelect = viewModel::setMotdMode,
                        )
                    }
                } else {
                    SettingsTile(
                        cap = cap,
                        title = "Message",
                        icon = DroshIcons.Pencil,
                        supporting = motdText.lineSequence().firstOrNull { it.isNotBlank() }
                            ?: "Tap to edit",
                        onClick = { showMotdDialog = true },
                    ) { SettingsChevron() }
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = "Language", items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = "Language",
                    icon = DroshIcons.Languages,
                    supporting = LanguageCatalog.options
                        .firstOrNull { it.tag == locale }?.displayName ?: "System",
                    onClick = { showLanguageSheet = true },
                ) { SettingsChevron() }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = "Security", items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = "PIN lock",
                    icon = DroshIcons.Shield,
                    supporting = "Ask for a PIN before opening a session",
                ) {
                    SettingsSwitch(
                        checked = isPinLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showPinEntry = true
                            } else {
                                scope.launch { viewModel.clearPin() }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = "About", items = 3) { index, cap ->
                when (index) {
                    0 -> SettingsTile(
                        cap = cap,
                        title = "Version",
                        icon = DroshIcons.Info,
                        supporting = aboutInfo?.version ?: "—",
                        onClick = {},
                    ) { SettingsChevron() }

                    1 -> SettingsTile(
                        cap = cap,
                        title = "Build",
                        icon = DroshIcons.Terminal,
                        supporting = aboutInfo?.build ?: "dev",
                        onClick = {
                            (context as? Activity)?.let { activity ->
                                val build = aboutInfo?.build ?: "dev"
                                activity.copyToClipboard("Build", build)
                                activity.toast("Build copied")
                            }
                        },
                    ) { SettingsChevron() }

                    else -> SettingsTile(
                        cap = cap,
                        title = "License",
                        icon = DroshIcons.ShieldCheck,
                        supporting = "GPLv3",
                        onClick = {},
                    ) { SettingsChevron() }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLanguageSheet) {
        LanguagePickerSheet(
            currentTag = locale,
            onSelect = { tag ->
                viewModel.setLocale(tag)
                (context as? Activity)?.recreate()
            },
            onDismiss = { showLanguageSheet = false },
        )
    }

    if (showPinEntry) {
        dev.drosh.ui.pin.PinEntryScreen(
            title = "Set a PIN",
            subtitle = "You will be asked for it before a session opens.",
            onPinReady = { pin ->
                scope.launch {
                    viewModel.setPin(pin)
                    viewModel.setPinLockEnabled(true)
                }
                showPinEntry = false
            },
            onCancel = { showPinEntry = false },
        )
    }

    if (showMotdDialog) {
        val defaultText = "  ╔══════════════════════════════════════╗\n" +
            "  ║   Welcome to Drosh v1.0            ║\n" +
            "  ╚══════════════════════════════════════╝"
        MotdTextDialog(
            initialText = motdText.ifBlank { defaultText },
            onDismiss = { showMotdDialog = false },
            onConfirm = { text -> viewModel.setMotdText(text) },
            onRestoreDefault = { viewModel.setMotdText(defaultText) },
        )
    }

    if (showStartupDialog) {
        StartupCommandDialog(
            initial = prootStartCommand,
            onDismiss = { showStartupDialog = false },
            onConfirm = { command -> viewModel.setProotStartCommand(command) },
        )
    }
}

private fun deviceName(marketing: String?): String =
    marketing?.takeIf { it.isNotBlank() } ?: "This device"

private fun CursorStyle.label(): String = when (this) {
    CursorStyle.Block -> "A solid block"
    CursorStyle.Underline -> "An underline under the character"
    CursorStyle.Beam -> "A thin vertical bar"
}
