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
import dev.drosh.domain.settings.KeyboardActivation
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

@Composable
fun TerminalCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_terminal),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_terminal),
            items = 5,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_terminal_mode),
                    icon = DroshIcons.SquareTerminal,
                    supporting = stringResource(R.string.settings_terminal_mode_desc),
                ) {
                    SettingsTerminalModePicker(
                        blockSelected = settings.terminal.mode == TerminalMode.Blocks,
                        onSelect = { block ->
                            onUpdate {
                                it.copy(
                                    terminal = it.terminal.copy(
                                        mode = if (block) TerminalMode.Blocks else TerminalMode.Stream,
                                    ),
                                )
                            }
                        },
                    )
                }

                1 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_font_size),
                    icon = DroshIcons.Resize,
                    value = stringResource(
                        R.string.settings_font_size_value,
                        String.format(Locale.US, "%.1f", settings.terminal.defaultFontSizeSp),
                    ),
                ) {
                    SettingsSlider(
                        value = settings.terminal.defaultFontSizeSp,
                        onValueChange = { sp ->
                            onUpdate { it.copy(terminal = it.terminal.copy(defaultFontSizeSp = sp)) }
                        },
                        valueRange = TerminalZoom.MIN_SP..TerminalZoom.MAX_SP,
                        steps = ((TerminalZoom.MAX_SP - TerminalZoom.MIN_SP) / TerminalZoom.STEP_SP).toInt() - 1,
                    )
                }

                3 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_cursor_blink),
                    icon = DroshIcons.Clock,
                    value = if (settings.terminal.cursorBlinkMs == 0) {
                        stringResource(R.string.settings_cursor_blink_off)
                    } else {
                        stringResource(R.string.settings_cursor_blink_value, settings.terminal.cursorBlinkMs)
                    },
                ) {
                    SettingsSlider(
                        value = settings.terminal.cursorBlinkMs.toFloat(),
                        onValueChange = { ms ->
                            onUpdate { it.copy(terminal = it.terminal.copy(cursorBlinkMs = ms.toInt())) }
                        },
                        valueRange = 0f..2000f,
                        steps = 19,
                    )
                }

                2 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_cursor_style),
                    icon = DroshIcons.Cursor,
                    supporting = stringResource(
                        when (settings.terminal.cursorStyle) {
                            CursorStyle.Block -> R.string.settings_cursor_style_block_desc
                            CursorStyle.Beam -> R.string.settings_cursor_style_beam_desc
                            CursorStyle.Underline -> R.string.settings_cursor_style_underline_desc
                        },
                    ),
                ) {
                    SettingsSegmented(
                        options = listOf(
                            CursorStyle.Block to stringResource(R.string.settings_cursor_style_block),
                            CursorStyle.Beam to stringResource(R.string.settings_cursor_style_thin),
                            CursorStyle.Underline to stringResource(R.string.settings_cursor_style_underline),
                        ),
                        selected = settings.terminal.cursorStyle,
                        onSelect = { style ->
                            onUpdate { it.copy(terminal = it.terminal.copy(cursorStyle = style)) }
                        },
                    )
                }

                4 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_terminal_keep_awake),
                    icon = DroshIcons.Eye,
                    supporting = stringResource(R.string.settings_terminal_keep_awake_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.terminal.keepAwake,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(terminal = it.terminal.copy(keepAwake = on)) }
                        },
                    )
                }

                // The engine switch is gone: the old Rust engine's library is
                // not in the APK any more, so "off" was a choice between the
                // one engine and a crash. There is one engine now, and the
                // setting that could not be turned off is gone with it.
                //
                // "Keep the position while reading" is gone for the same
                // reason: the viewport is the engine's, the engine never pulled
                // it back on its own, and the switch guarded nothing.
            }
        }

        // The typography and the history: the rows that used to be the bottom
        // half of the Appearance screen, where nobody thought to look for a
        // line height.
        TerminalTypographySection(settings = settings, onUpdate = onUpdate)
    }
}

@Composable
fun KeyboardCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_keyboard),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_keyboard),
            items = 4,
        ) { index, cap ->
            when (index) {
                // Above the keys themselves: this is the row that decides how
                // the keyboard gets here at all.
                3 -> SettingsSelectRow(
                    cap = cap,
                    title = stringResource(R.string.settings_keyboard_activation),
                    icon = DroshIcons.Keyboard,
                    supporting = stringResource(R.string.settings_keyboard_activation_desc),
                    options = KeyboardActivation.entries.map { mode ->
                        mode to stringResource(
                            when (mode) {
                                KeyboardActivation.Tap -> R.string.settings_keyboard_activation_tap
                                KeyboardActivation.TwoFingerTap ->
                                    R.string.settings_keyboard_activation_two_finger
                                KeyboardActivation.BottomEdgeTap ->
                                    R.string.settings_keyboard_activation_bottom
                                KeyboardActivation.TopEdgeTap ->
                                    R.string.settings_keyboard_activation_top
                                KeyboardActivation.Never -> R.string.settings_keyboard_activation_never
                            },
                        )
                    },
                    selected = settings.input.keyboardActivation,
                    onSelect = { mode ->
                        onUpdate { it.copy(input = it.input.copy(keyboardActivation = mode)) }
                    },
                )

                0 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_input_extra_keys),
                    icon = DroshIcons.Keyboard,
                    supporting = stringResource(R.string.settings_input_extra_keys_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.input.extraKeysBar,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(input = it.input.copy(extraKeysBar = on)) }
                        },
                    )
                }

                1 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_input_mouse_reporting),
                    icon = DroshIcons.Cursor,
                    supporting = stringResource(R.string.settings_input_mouse_reporting_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.input.mouseReporting,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(input = it.input.copy(mouseReporting = on)) }
                        },
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_input_clipboard_writes),
                    icon = DroshIcons.ClipboardPaste,
                    supporting = stringResource(R.string.settings_input_clipboard_writes_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.input.allowClipboardWrites,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(input = it.input.copy(allowClipboardWrites = on)) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun ShellCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    var showMotdDialog by rememberSaveable { mutableStateOf(false) }
    var showStartupDialog by rememberSaveable { mutableStateOf(false) }
    val defaultMotd = remember { MotdDefaults.DEFAULT_MOTD_TEXT }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_shell),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_motd),
            items = 2,
        ) { index, cap ->
            if (index == 0) {
                SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_motd_show_as),
                    icon = DroshIcons.Info,
                    supporting = when (settings.shell.motdMode) {
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
                        selected = settings.shell.motdMode,
                        onSelect = { mode ->
                            onUpdate { it.copy(shell = it.shell.copy(motdMode = mode)) }
                        },
                    )
                }
            } else {
                SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_motd_message),
                    icon = DroshIcons.Pencil,
                    supporting = settings.shell.motdText.lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        ?: stringResource(R.string.settings_motd_empty),
                    onClick = { showMotdDialog = true },
                ) { SettingsChevron() }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_startup),
            items = 1,
        ) { _, cap ->
            SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_startup_command),
                icon = DroshIcons.Terminal,
                supporting = settings.shell.startupCommand.ifBlank {
                    stringResource(R.string.settings_startup_default)
                },
                onClick = { showStartupDialog = true },
            ) { SettingsChevron() }
        }
    }

    if (showMotdDialog) {
        MotdTextDialog(
            initialText = settings.shell.motdText.ifBlank { defaultMotd },
            onDismiss = { showMotdDialog = false },
            onConfirm = { text ->
                onUpdate { it.copy(shell = it.shell.copy(motdText = text)) }
            },
            onRestoreDefault = {
                onUpdate { it.copy(shell = it.shell.copy(motdText = defaultMotd)) }
            },
        )
    }

    if (showStartupDialog) {
        StartupCommandDialog(
            initial = settings.shell.startupCommand,
            onDismiss = { showStartupDialog = false },
            onConfirm = { command ->
                onUpdate { it.copy(shell = it.shell.copy(startupCommand = command)) }
            },
        )
    }
}

@Composable
fun SecurityCategory(
    settings: DroshSettings,
    onBack: () -> Unit,
    onUpdate: OnSettingsUpdate,
    viewModel: SettingsViewModel,
) {
    val scope = rememberCoroutineScope()
    val isPinLockEnabled by viewModel.isPinLockEnabled.collectAsStateWithLifecycle()
    var showPinEntry by rememberSaveable { mutableStateOf(false) }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_security),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_security),
            items = 4,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
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

                1 -> SettingsSelectRow(
                    cap = cap,
                    title = stringResource(R.string.settings_security_auto_lock),
                    icon = DroshIcons.Lock,
                    options = listOf(
                        AutoLockTimeout.Immediately to stringResource(R.string.settings_security_auto_lock_immediately),
                        AutoLockTimeout.OneMinute to stringResource(R.string.settings_security_auto_lock_one_minute),
                        AutoLockTimeout.FiveMinutes to stringResource(R.string.settings_security_auto_lock_five_minutes),
                        AutoLockTimeout.FifteenMinutes to stringResource(R.string.settings_security_auto_lock_fifteen_minutes),
                        AutoLockTimeout.ThirtyMinutes to stringResource(R.string.settings_security_auto_lock_thirty_minutes),
                        AutoLockTimeout.Never to stringResource(R.string.settings_security_auto_lock_never),
                    ),
                    selected = settings.security.autoLock,
                    onSelect = { timeout ->
                        onUpdate { it.copy(security = it.security.copy(autoLock = timeout)) }
                    },
                )

                2 -> SettingsSelectRow(
                    cap = cap,
                    title = stringResource(R.string.settings_security_pin_length),
                    icon = DroshIcons.Lock,
                    options = PinLockRepository.PIN_LENGTH_RANGE.map { it to it.toString() },
                    selected = settings.security.pinLength,
                    onSelect = { length ->
                        onUpdate { it.copy(security = it.security.copy(pinLength = length)) }
                    },
                )

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_security_screenshots),
                    icon = DroshIcons.EyeOff,
                    supporting = stringResource(R.string.settings_security_screenshots_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.security.blockScreenshots,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(security = it.security.copy(blockScreenshots = on)) }
                        },
                    )
                }
            }
        }
    }

    if (showPinEntry) {
        dev.drosh.ui.pin.PinEntryScreen(
            title = stringResource(R.string.settings_pin_title),
            subtitle = stringResource(R.string.settings_pin_subtitle),
            pinLength = settings.security.pinLength,
            onPinReady = { pin ->
                scope.launch {
                    viewModel.setPin(pin, settings.security.pinLength)
                    viewModel.setPinLockEnabled(true)
                }
                showPinEntry = false
            },
            onCancel = { showPinEntry = false },
        )
    }
}

@Composable
fun LanguageSection(settings: DroshSettings, onUpdate: OnSettingsUpdate) {
    var showLanguageSheet by rememberSaveable { mutableStateOf(false) }
    var showShellLocaleSheet by rememberSaveable { mutableStateOf(false) }
    SettingsGroupColumn(
        label = stringResource(R.string.settings_section_language),
        items = 2,
    ) { index, cap ->
        when (index) {
            0 -> SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_language),
                icon = DroshIcons.Languages,
                supporting = LanguageCatalog.options
                    .firstOrNull { it.tag == settings.appearance.language }?.displayName
                    ?: stringResource(R.string.settings_language_system),
                onClick = { showLanguageSheet = true },
            ) { SettingsChevron() }

            else -> SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_shell_locale),
                icon = DroshIcons.Terminal,
                // The shell speaks this one, from the file rather than from
                // anyone's dotfiles.
                supporting = ShellLocaleCatalog.displayName(settings.shell.locale)
                    .ifBlank { stringResource(R.string.settings_language_system) },
                onClick = { showShellLocaleSheet = true },
            ) { SettingsChevron() }
        }
    }

    if (showLanguageSheet) {
        LanguagePickerSheet(
            currentTag = settings.appearance.language,
            // No recreate: the locale rides in the composition, so the whole
            // app re-resolves on the next frame with no state lost.
            onSelect = { tag ->
                onUpdate { it.copy(appearance = it.appearance.copy(language = tag)) }
            },
            onDismiss = { showLanguageSheet = false },
        )
    }

    if (showShellLocaleSheet) {
        ShellLocaleSheet(
            currentTag = settings.shell.locale,
            onSelect = { tag ->
                onUpdate { it.copy(shell = it.shell.copy(locale = tag)) }
            },
            onDismiss = { showShellLocaleSheet = false },
        )
    }
}

@Composable
fun AboutCategory(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    versionName: String,
    versionCode: Int,
    gitSha: String,
) {
    val activity = LocalDroshActivity.current
    val scope = rememberCoroutineScope()
    val aboutInfo by viewModel.aboutInfo.collectAsStateWithLifecycle(null)
    // The star count is fetched once and remembered across opens: the API
    // is unauthenticated and sixty an hour, which is not a number to spend
    // every time someone looks at an About screen.
    val stars = remember { StarsState() }
    LaunchedEffect(Unit) { stars.load() }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_about),
        onBack = onBack,
    ) {
        // ── Drosh ───────────────────────────────────────────────────────────
        SettingsGroupColumn(label = stringResource(R.string.about_section_app), items = 4) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.app_name),
                    icon = DroshIcons.SquareTerminal,
                    supporting = stringResource(R.string.about_tagline),
                    leading = {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(DroshPrimary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "D",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    },
                )

                // The person, not the team: the repo is one person's and the
                // About screen is where a user looks for who to talk to.
                1 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.about_developer),
                    icon = DroshIcons.CircleUser,
                    supporting = DEVELOPER_URL,
                    onClick = { openUrl(activity, DEVELOPER_URL) },
                ) { SettingsChevron() }

                2 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.about_repo),
                    icon = DroshIcons.Globe,
                    supporting = REPO_URL.removePrefix("https://"),
                    onClick = { openUrl(activity, REPO_URL) },
                ) { SettingsChevron() }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.about_stars),
                    icon = DroshIcons.Check,
                    supporting = when {
                        stars.count == null && stars.failed -> "—"
                        stars.count != null -> stringResource(R.string.about_stars_value, stars.count!!)
                        else -> "…"
                    },
                    onClick = { openUrl(activity, REPO_URL) },
                ) { SettingsChevron() }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        // ── Sürüm ──────────────────────────────────────────────────────────
        SettingsGroupColumn(label = stringResource(R.string.about_section_version), items = 3) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.about_version),
                    icon = DroshIcons.Info,
                    supporting = aboutInfo?.version ?: versionName,
                    onClick = {
                        (activity as? Activity)?.let { host ->
                            host.copyToClipboard("Version", aboutInfo?.version ?: "")
                            host.toast(host.getString(R.string.settings_build_copied))
                        }
                    },
                ) { SettingsChevron() }

                1 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.about_version_code),
                    icon = DroshIcons.Terminal,
                    supporting = versionCode.toString(),
                    onClick = {
                        (activity as? Activity)?.let { host ->
                            host.copyToClipboard("Version code", versionCode.toString())
                            host.toast(host.getString(R.string.settings_build_copied))
                        }
                    },
                ) { SettingsChevron() }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.about_build_hash),
                    icon = DroshIcons.Code,
                    supporting = gitSha.ifBlank { "—" },
                    onClick = {
                        (activity as? Activity)?.let { host ->
                            host.copyToClipboard("Commit", gitSha)
                            host.toast(host.getString(R.string.settings_build_copied))
                        }
                    },
                ) { SettingsChevron() }
            }
        }
    }
}

private const val DEVELOPER_URL = "https://github.com/dexbycode"
private const val REPO_URL = "https://github.com/dexbycode/Drosh"

/** Opens [url] in the browser, quietly doing nothing if nothing can. */
private fun openUrl(activity: Activity?, url: String) {
    if (activity == null) return
    runCatching {
        activity.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
    }
}

/**
 * The repository's star count, fetched once.
 *
 * A plain `HttpURLConnection` rather than the app's HTTP stack: this module
 * has no HTTP client of its own, and one GET of a few hundred bytes does
 * not justify inventing a seam for it. A failure is remembered too, so a
 * phone with no network asks once rather than on every composition.
 */
private class StarsState {
    var count by mutableStateOf<Int?>(null)
    var failed by mutableStateOf(false)

    fun load() {
        if (count != null || failed) return
        Thread {
            try {
                val url = java.net.URL("https://api.github.com/repos/dexbycode/Drosh")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val match = Regex("\"stargazers_count\"\\s*:\\s*(\\d+)").find(body)
                val value = match?.groupValues?.get(1)?.toIntOrNull()
                if (value != null) {
                    count = value
                } else {
                    failed = true
                }
            } catch (e: Exception) {
                failed = true
            }
        }.start()
    }
}

