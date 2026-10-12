package dev.drosh.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import dev.drosh.ui.session.DeviceBadge
import dev.drosh.design.system.DroshPrimary
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.core.LanguageCatalog
import dev.drosh.core.copyToClipboard
import dev.drosh.core.toast
import dev.drosh.design.system.DroshBackground
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
import dev.drosh.domain.terminal.PackageProfile
import dev.drosh.domain.terminal.TerminalZoom
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.LocalDroshActivity
import dev.drosh.ui.R
import kotlinx.coroutines.launch
import java.util.Locale

internal val GROUP_GAP = 22.dp

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

@Composable
fun AppearanceCategory(
    settings: DroshSettings,
    onBack: () -> Unit,
    onUpdate: OnSettingsUpdate,
    onOpenThemeStore: () -> Unit,
    viewModel: SettingsViewModel,
) {
    // The system picker. It writes into the convention rather than into a
    // setting, so what it leaves behind is the same thing a shell's
    // `cp ~/.drosh/background.png` would.
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackgroundImage(it) } }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_appearance),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_appearance),
            items = 3,
        ) { index, cap ->
            // The theme row leads: it is the one appearance setting that
            // rewrites the other twenty-two, and burying it below the theme
            // and font rows is why nobody found it.
            if (index == 0) {
                SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_theme_store),
                    icon = DroshIcons.Palette,
                    supporting = settings.terminal.themeId.ifBlank {
                        stringResource(R.string.settings_theme_active_none)
                    },
                    onClick = onOpenThemeStore,
                ) { SettingsChevron() }
                return@SettingsGroupColumn
            }
            when (index) {
                1 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_theme),
                    icon = DroshIcons.Sun,
                    supporting = when (settings.appearance.themeMode) {
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
                        selected = settings.appearance.themeMode,
                        onSelect = { mode ->
                            onUpdate { it.copy(appearance = it.appearance.copy(themeMode = mode)) }
                        },
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_font),
                    icon = DroshIcons.Type,
                    supporting = when (settings.appearance.fontPack) {
                        FontPack.Geist -> stringResource(R.string.settings_font_geist_desc)
                        FontPack.Inter -> stringResource(R.string.settings_font_inter_desc)
                    },
                ) {
                    SettingsSegmented(
                        options = listOf(
                            FontPack.Geist to stringResource(R.string.settings_font_geist),
                            FontPack.Inter to stringResource(R.string.settings_font_inter),
                        ),
                        selected = settings.appearance.fontPack,
                        onSelect = { pack ->
                            onUpdate { it.copy(appearance = it.appearance.copy(fontPack = pack)) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_terminal),
            items = 3,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_bold_brightens),
                    icon = DroshIcons.Type,
                    supporting = stringResource(R.string.settings_bold_brightens_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.terminal.boldBrightens,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(terminal = it.terminal.copy(boldBrightens = on)) }
                        },
                    )
                }

                1 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_ligatures),
                    icon = DroshIcons.Type,
                    supporting = stringResource(R.string.settings_ligatures_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.terminal.ligatures,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(terminal = it.terminal.copy(ligatures = on)) }
                        },
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_dim_inactive_panes),
                    icon = DroshIcons.Monitor,
                    supporting = stringResource(R.string.settings_dim_inactive_panes_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.terminal.dimInactivePanes,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(terminal = it.terminal.copy(dimInactivePanes = on)) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_background),
            items = 6,
        ) { index, cap ->
            when (index) {
                0 -> {
                    // The convention, not a path: a file called
                    // background.* in .drosh IS the background, and the
                    // picker's job is to put one there rather than to
                    // configure one.
                    val backgroundName = remember { viewModel.backgroundFileName() }
                    SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_background_image),
                        icon = DroshIcons.Monitor,
                        supporting = backgroundName
                            ?: stringResource(R.string.settings_background_none),
                        onClick = { pickerLauncher.launch(arrayOf("image/*")) },
                    ) { SettingsChevron() }
                }

                1 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_background_remove),
                    icon = DroshIcons.Trash2,
                    supporting = stringResource(R.string.settings_background_remove_desc),
                    onClick = { viewModel.removeBackgroundImage() },
                )

                2 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_background_opacity),
                    icon = DroshIcons.Eye,
                    supporting = stringResource(
                        R.string.settings_background_opacity_value,
                        settings.background.imageOpacity,
                    ),
                ) {
                    SettingsSlider(
                        value = settings.background.imageOpacity.toFloat(),
                        onValueChange = { value ->
                            onUpdate { it.copy(background = it.background.copy(imageOpacity = value.toInt())) }
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                    )
                }

                3 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_background_blur),
                    icon = DroshIcons.Eye,
                    supporting = if (settings.background.blurRadius == 0) {
                        stringResource(R.string.settings_background_blur_off)
                    } else {
                        stringResource(
                            R.string.settings_background_blur_value,
                            settings.background.blurRadius,
                        )
                    },
                ) {
                    SettingsSlider(
                        value = settings.background.blurRadius.toFloat(),
                        onValueChange = { value ->
                            onUpdate { it.copy(background = it.background.copy(blurRadius = value.toInt())) }
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                    )
                }

                4 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_content_padding),
                    icon = DroshIcons.Maximize,
                    supporting = stringResource(
                        R.string.settings_content_padding_value,
                        settings.background.contentPaddingDp,
                    ),
                ) {
                    SettingsSlider(
                        value = settings.background.contentPaddingDp.toFloat(),
                        onValueChange = { value ->
                            onUpdate { it.copy(background = it.background.copy(contentPaddingDp = value.toInt())) }
                        },
                        valueRange = 0f..48f,
                        steps = 15,
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_content_corners),
                    icon = DroshIcons.Square,
                    supporting = stringResource(
                        R.string.settings_content_corners_value,
                        settings.background.cornerRadiusDp,
                    ),
                ) {
                    SettingsSlider(
                        value = settings.background.cornerRadiusDp.toFloat(),
                        onValueChange = { value ->
                            onUpdate { it.copy(background = it.background.copy(cornerRadiusDp = value.toInt())) }
                        },
                        valueRange = 0f..32f,
                        steps = 15,
                    )
                }
            }
        }
    }
}



// ── Terminal ─────────────────────────────────────────────────────────────────

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
                    supporting = stringResource(
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

                2 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_cursor_blink),
                    icon = DroshIcons.Clock,
                    supporting = if (settings.terminal.cursorBlinkMs == 0) {
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

                3 -> SettingsTile(
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

                else -> SettingsTile(
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
            }
        }
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
            items = 1,
        ) { _, cap ->
            SettingsTile(
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
fun LanguageCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    var showLanguageSheet by rememberSaveable { mutableStateOf(false) }
    var showShellLocaleSheet by rememberSaveable { mutableStateOf(false) }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_language),
        onBack = onBack,
    ) {
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

private const val DEVELOPER_URL = "https://github.com/mmuhofy"
private const val REPO_URL = "https://github.com/mmuhofy/Drosh"

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
                val url = java.net.URL("https://api.github.com/repos/mmuhofy/Drosh")
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

