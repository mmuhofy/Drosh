package dev.drosh.ui.settings

import android.app.Activity
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import dev.drosh.R
import dev.drosh.ui.LocalDroshActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.core.LanguageCatalog
import dev.drosh.core.copyToClipboard
import dev.drosh.core.toast
import dev.drosh.design.system.DroshBackground
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
    val activity = LocalDroshActivity.current
    val scope = rememberCoroutineScope()
    var showPinEntry by rememberSaveable { mutableStateOf(false) }
    var showMotdDialog by rememberSaveable { mutableStateOf(false) }
    var showStartupDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageSheet by rememberSaveable { mutableStateOf(false) }

    // Stated rather than inherited: the sheet and the drawer sit on the same
    // value, so the settings page cannot silently drift onto the window's.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .testTag("settings_content"),
        ) {
            SettingsTopBar(title = stringResource(R.string.settings_title), onBack = onBack)

            SettingsGroupColumn(label = null, items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = deviceName(deviceIdentity?.marketingName),
                    leading = {
                        DeviceBadge(
                            imageUrl = deviceIdentity?.visualUrl,
                            fallbackLetter = (deviceIdentity?.marketingName ?: "?")
                                .firstOrNull()?.uppercase() ?: "?",
                            size = 46.dp,
                        )
                    },
                )
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = stringResource(R.string.settings_section_appearance), items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_theme),
                    icon = DroshIcons.Palette,
                    supporting = when (themeMode) {
                        ThemeMode.System -> stringResource(R.string.settings_theme_system_desc)
                        ThemeMode.Light -> stringResource(R.string.settings_theme_light_desc)
                        ThemeMode.Dark -> stringResource(R.string.settings_theme_dark_desc)
                    },
                ) {
                    SettingsSegmented(
                        options = listOf(
                            ThemeMode.System to stringResource(R.string.settings_theme_auto),
                            ThemeMode.Light to stringResource(R.string.settings_theme_light),
                            ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
                        ),
                        selected = themeMode,
                        onSelect = viewModel::setThemeMode,
                    )
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = stringResource(R.string.settings_section_terminal), items = 4) { index, cap ->
                when (index) {
                    0 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_block_mode),
                        icon = DroshIcons.SquareTerminal,
                        supporting = stringResource(R.string.settings_block_mode_desc),
                    ) {
                        SettingsSwitch(useBlockEngine, viewModel::setUseBlockEngine)
                    }

                    1 -> SettingsTile(
                        cap = cap,
                        stacked = true,
                        title = stringResource(R.string.settings_font_size),
                        icon = DroshIcons.Resize,
                        supporting = stringResource(R.string.settings_font_size_value, fontSizeSp),
                    ) {
                        SettingsSlider(
                            value = fontSizeSp.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toInt()) },
                            valueRange = 8f..24f,
                            steps = 15,
                        )
                    }

                    2 -> SettingsTile(
                        cap = cap,
                        stacked = true,
                        title = stringResource(R.string.settings_cursor_blink),
                        icon = DroshIcons.Clock,
                        supporting = if (cursorBlinkRateMs == 0) stringResource(R.string.settings_cursor_blink_off)
                        else stringResource(R.string.settings_cursor_blink_value, cursorBlinkRateMs),
                    ) {
                        SettingsSlider(
                            value = cursorBlinkRateMs.toFloat(),
                            onValueChange = { viewModel.setCursorBlinkRateMs(it.toInt()) },
                            valueRange = 0f..1200f,
                            steps = 5,
                        )
                    }

                    else -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_cursor_style),
                        icon = DroshIcons.Cursor,
                        supporting = stringResource(R.string.settings_cursor_style_block_desc),
                        onClick = { viewModel.setCursorStyle(CursorStyle.Underline) },
                    ) { SettingsChevron() }
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = stringResource(R.string.settings_section_startup), items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_startup_command),
                    icon = DroshIcons.Terminal,
                    supporting = prootStartCommand.ifBlank { stringResource(R.string.settings_startup_default) },
                    onClick = { showStartupDialog = true },
                ) { SettingsChevron() }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = stringResource(R.string.settings_section_motd), items = 2) { index, cap ->
                if (index == 0) {
                    SettingsTile(
                        cap = cap,
                        stacked = true,
                        title = stringResource(R.string.settings_motd_show_as),
                        icon = DroshIcons.Info,
                        supporting = when (motdMode) {
                            MotdMode.Disabled -> stringResource(R.string.settings_motd_disabled_desc)
                            MotdMode.PlainText -> stringResource(R.string.settings_motd_plain_desc)
                            MotdMode.Compose -> stringResource(R.string.settings_motd_compose_desc)
                        },
                    ) {
                        SettingsSegmented(
                            options = listOf(
                                MotdMode.Disabled to stringResource(R.string.settings_motd_off),
                                MotdMode.PlainText to stringResource(R.string.settings_motd_text),
                                MotdMode.Compose to stringResource(R.string.settings_motd_card),
                            ),
                            selected = motdMode,
                            onSelect = viewModel::setMotdMode,
                        )
                    }
                } else {
                    SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_motd_message),
                        icon = DroshIcons.Pencil,
                        supporting = motdText.lineSequence().firstOrNull { it.isNotBlank() }
                            ?: stringResource(R.string.settings_motd_empty),
                        onClick = { showMotdDialog = true },
                    ) { SettingsChevron() }
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = stringResource(R.string.settings_section_language), items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_language),
                    icon = DroshIcons.Languages,
                    supporting = LanguageCatalog.options
                        .firstOrNull { it.tag == locale }?.displayName
                        ?: stringResource(R.string.settings_language_system),
                    onClick = { showLanguageSheet = true },
                ) { SettingsChevron() }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = stringResource(R.string.settings_section_security), items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_pin_lock),
                    icon = DroshIcons.Shield,
                    supporting = stringResource(R.string.settings_pin_lock_desc),
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

            SettingsGroupColumn(label = stringResource(R.string.settings_section_about), items = 3) { index, cap ->
                when (index) {
                    0 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_version),
                        icon = DroshIcons.Info,
                        supporting = aboutInfo?.version ?: "—",
                        onClick = {},
                    ) { SettingsChevron() }

                    1 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_build),
                        icon = DroshIcons.Terminal,
                        supporting = aboutInfo?.build ?: "dev",
                        onClick = {
                            (activity as? Activity)?.let { activity ->
                                val build = aboutInfo?.build ?: "dev"
                                activity.copyToClipboard("Build", build)
                                activity.toast(activity.getString(R.string.settings_build_copied))
                            }
                        },
                    ) { SettingsChevron() }

                    else -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_license),
                        icon = DroshIcons.ShieldCheck,
                        supporting = stringResource(R.string.settings_license),
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
            // No recreate: the locale rides in the composition, so the whole
            // app re-resolves on the next frame with no state lost.
            onSelect = { tag -> viewModel.setLocale(tag) },
            onDismiss = { showLanguageSheet = false },
        )
    }

    if (showPinEntry) {
        dev.drosh.ui.pin.PinEntryScreen(
            title = stringResource(R.string.settings_pin_title),
            subtitle = stringResource(R.string.settings_pin_subtitle),
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

@Composable
private fun deviceName(marketing: String?): String =
    marketing?.takeIf { it.isNotBlank() } ?: stringResource(R.string.this_device)
