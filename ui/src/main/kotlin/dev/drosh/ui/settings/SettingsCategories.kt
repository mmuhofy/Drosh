package dev.drosh.ui.settings

import android.app.Activity
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import dev.drosh.domain.settings.AutoLockTimeout
import dev.drosh.domain.settings.BellMode
import dev.drosh.domain.settings.CursorStyle
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.FontPack
import dev.drosh.domain.settings.MotdDefaults
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.settings.TerminalMode
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.domain.terminal.PackageProfile
import dev.drosh.domain.terminal.ShellChoice
import dev.drosh.domain.terminal.TerminalZoom
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.LocalDroshActivity
import dev.drosh.ui.R
import kotlinx.coroutines.launch
import java.util.Locale

private val GROUP_GAP = 22.dp

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
fun AppearanceCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_appearance),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_appearance),
            items = 2,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_theme),
                    icon = DroshIcons.Palette,
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
                    stacked = true,
                    title = stringResource(R.string.settings_terminal_scrollback),
                    icon = DroshIcons.Archive,
                    supporting = stringResource(
                        R.string.settings_terminal_scrollback_value,
                        settings.terminal.scrollbackRows,
                    ),
                ) {
                    SettingsSlider(
                        value = settings.terminal.scrollbackRows.toFloat(),
                        onValueChange = { rows ->
                            onUpdate { it.copy(terminal = it.terminal.copy(scrollbackRows = rows.toInt())) }
                        },
                        valueRange = 500f..20000f,
                        steps = 18,
                    )
                }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_terminal),
            items = 5,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_terminal_bell),
                    icon = DroshIcons.Gauge,
                ) {
                    SettingsSegmented(
                        options = listOf(
                            BellMode.None to stringResource(R.string.settings_terminal_bell_none),
                            BellMode.Vibrate to stringResource(R.string.settings_terminal_bell_vibrate),
                            BellMode.Sound to stringResource(R.string.settings_terminal_bell_sound),
                        ),
                        selected = settings.terminal.bell,
                        onSelect = { bell ->
                            onUpdate { it.copy(terminal = it.terminal.copy(bell = bell)) }
                        },
                    )
                }

                1 -> SettingsTile(
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

                2 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_terminal_immersive),
                    icon = DroshIcons.Maximize,
                    supporting = stringResource(R.string.settings_terminal_immersive_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.terminal.immersive,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(terminal = it.terminal.copy(immersive = on)) }
                        },
                    )
                }

                3 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_terminal_link_detection),
                    icon = DroshIcons.ExternalLink,
                    supporting = stringResource(R.string.settings_terminal_link_detection_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.terminal.linkDetection,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(terminal = it.terminal.copy(linkDetection = on)) }
                        },
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_terminal_glass_effects),
                    icon = DroshIcons.Smartphone,
                    supporting = stringResource(R.string.settings_terminal_glass_effects_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.terminal.glassEffects,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(terminal = it.terminal.copy(glassEffects = on)) }
                        },
                    )
                }
            }
        }
    }
}

// ── Keyboard & input ─────────────────────────────────────────────────────────

@Composable
fun KeyboardCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_keyboard),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_keyboard),
            items = 3,
        ) { index, cap ->
            when (index) {
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
                    title = stringResource(R.string.settings_input_hardware_keyboard),
                    icon = DroshIcons.KeyboardOff,
                    supporting = stringResource(R.string.settings_input_hardware_keyboard_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.input.hideBarOnHardwareKeyboard,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(input = it.input.copy(hideBarOnHardwareKeyboard = on)) }
                        },
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_input_drosh_ime),
                    icon = DroshIcons.KeyboardOff,
                    supporting = stringResource(R.string.settings_input_drosh_ime_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.input.hideBarWhenDroshIme,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(input = it.input.copy(hideBarWhenDroshIme = on)) }
                        },
                    )
                }
            }
        }
    }
}

// ── Shell & rootfs ───────────────────────────────────────────────────────────

@Composable
fun ShellCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    val scope = rememberCoroutineScope()
    var showMotdDialog by rememberSaveable { mutableStateOf(false) }
    var showStartupDialog by rememberSaveable { mutableStateOf(false) }
    val defaultMotd = remember { MotdDefaults.DEFAULT_MOTD_TEXT }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_shell),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_shell),
            items = 5,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_shell_choice),
                    icon = DroshIcons.Terminal,
                    supporting = stringResource(R.string.settings_shell_choice_desc),
                ) {
                    SettingsSegmented(
                        options = listOf(
                            ShellChoice.Zsh to stringResource(R.string.settings_shell_zsh),
                            ShellChoice.Bash to stringResource(R.string.settings_shell_bash),
                        ),
                        selected = settings.shell.shell,
                        onSelect = { shell ->
                            onUpdate { it.copy(shell = it.shell.copy(shell = shell)) }
                        },
                    )
                }

                1 -> SettingsTextFieldRow(
                    cap = cap,
                    title = stringResource(R.string.settings_shell_omz_theme),
                    icon = DroshIcons.Palette,
                    value = settings.shell.omzTheme,
                    onConfirm = { theme ->
                        onUpdate { it.copy(shell = it.shell.copy(omzTheme = theme)) }
                    },
                    placeholder = "agnoster",
                )

                2 -> SettingsListRow(
                    cap = cap,
                    title = stringResource(R.string.settings_shell_omz_plugins),
                    icon = DroshIcons.Package,
                    values = settings.shell.omzPlugins,
                    onConfirm = { plugins ->
                        onUpdate { it.copy(shell = it.shell.copy(omzPlugins = plugins)) }
                    },
                    placeholder = stringResource(R.string.settings_shell_omz_plugins_hint),
                )

                3 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_shell_history),
                    icon = DroshIcons.Clock,
                    supporting = stringResource(
                        R.string.settings_shell_history_value,
                        settings.shell.historySize,
                    ),
                ) {
                    SettingsSlider(
                        value = settings.shell.historySize.toFloat(),
                        onValueChange = { size ->
                            onUpdate { it.copy(shell = it.shell.copy(historySize = size.toInt())) }
                        },
                        valueRange = 100f..100000f,
                        steps = 18,
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_shell_integration),
                    icon = DroshIcons.Code,
                    supporting = stringResource(R.string.settings_shell_integration_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.shell.shellIntegration,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(shell = it.shell.copy(shellIntegration = on)) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        // MOTD
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

        // Startup
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

        Spacer(Modifier.height(GROUP_GAP))

        // Rootfs
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_rootfs),
            items = 5,
        ) { index, cap ->
            when (index) {
                0 -> SettingsSelectRow(
                    cap = cap,
                    title = stringResource(R.string.settings_rootfs_profile),
                    icon = DroshIcons.Package,
                    options = listOf(
                        PackageProfile.Minimal to stringResource(R.string.settings_rootfs_profile_minimal),
                        PackageProfile.Standard to stringResource(R.string.settings_rootfs_profile_standard),
                        PackageProfile.Full to stringResource(R.string.settings_rootfs_profile_full),
                        PackageProfile.Custom to stringResource(R.string.settings_rootfs_profile_custom),
                    ),
                    selected = settings.rootfs.packageProfile,
                    onSelect = { profile ->
                        onUpdate { it.copy(rootfs = it.rootfs.copy(packageProfile = profile)) }
                    },
                )

                1 -> SettingsListRow(
                    cap = cap,
                    title = stringResource(R.string.settings_rootfs_extra_packages),
                    icon = DroshIcons.Plus,
                    values = settings.rootfs.extraPackages,
                    onConfirm = { packages ->
                        onUpdate { it.copy(rootfs = it.rootfs.copy(extraPackages = packages)) }
                    },
                    placeholder = stringResource(R.string.settings_rootfs_extra_packages_hint),
                )

                2 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_rootfs_dedit),
                    icon = DroshIcons.Code,
                    supporting = stringResource(R.string.settings_rootfs_dedit_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.rootfs.installDedit,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(rootfs = it.rootfs.copy(installDedit = on)) }
                        },
                    )
                }

                3 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_rootfs_optimize),
                    icon = DroshIcons.Archive,
                    supporting = stringResource(R.string.settings_rootfs_optimize_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.rootfs.optimizeOnSetup,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(rootfs = it.rootfs.copy(optimizeOnSetup = on)) }
                        },
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_rootfs_max_edit),
                    icon = DroshIcons.Resize,
                    supporting = stringResource(
                        R.string.settings_rootfs_max_edit_value,
                        settings.rootfs.maxEditSizeKb,
                    ),
                ) {
                    SettingsSlider(
                        value = settings.rootfs.maxEditSizeKb.toFloat(),
                        onValueChange = { kb ->
                            onUpdate { it.copy(rootfs = it.rootfs.copy(maxEditSizeKb = kb.toInt())) }
                        },
                        valueRange = 256f..8192f,
                        steps = 14,
                    )
                }
            }
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

// ── Editor ───────────────────────────────────────────────────────────────────

@Composable
fun EditorCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_editor),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_editor),
            items = 4,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_editor_font),
                    icon = DroshIcons.Type,
                    supporting = stringResource(
                        R.string.settings_font_size_value,
                        String.format(Locale.US, "%.1f", settings.editor.fontSizeSp),
                    ),
                ) {
                    SettingsSlider(
                        value = settings.editor.fontSizeSp,
                        onValueChange = { sp ->
                            onUpdate { it.copy(editor = it.editor.copy(fontSizeSp = sp)) }
                        },
                        valueRange = 10f..24f,
                        steps = 27,
                    )
                }

                1 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.settings_editor_tab_width),
                    icon = DroshIcons.Resize,
                ) {
                    SettingsSegmented(
                        options = listOf(2, 4, 8).map { it to it.toString() },
                        selected = settings.editor.tabWidth,
                        onSelect = { width ->
                            onUpdate { it.copy(editor = it.editor.copy(tabWidth = width)) }
                        },
                    )
                }

                2 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_editor_word_wrap),
                    icon = DroshIcons.PanelBottom,
                    supporting = stringResource(R.string.settings_editor_word_wrap_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.editor.wordWrap,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(editor = it.editor.copy(wordWrap = on)) }
                        },
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_editor_line_numbers),
                    icon = DroshIcons.ListChecks,
                    supporting = stringResource(R.string.settings_editor_line_numbers_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.editor.lineNumbers,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(editor = it.editor.copy(lineNumbers = on)) }
                        },
                    )
                }
            }
        }
    }
}

// ── Security ─────────────────────────────────────────────────────────────────

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
            items = 6,
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

                3 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_security_biometric),
                    icon = DroshIcons.ShieldCheck,
                    supporting = stringResource(R.string.settings_security_biometric_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.security.biometricUnlock,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(security = it.security.copy(biometricUnlock = on)) }
                        },
                    )
                }

                4 -> SettingsTile(
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

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_security_clipboard),
                    icon = DroshIcons.ClipboardPaste,
                    supporting = stringResource(R.string.settings_security_clipboard_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.security.clipboardSensitive,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(security = it.security.copy(clipboardSensitive = on)) }
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

// ── Language ─────────────────────────────────────────────────────────────────

@Composable
fun LanguageCategory(settings: DroshSettings, onBack: () -> Unit, onUpdate: OnSettingsUpdate) {
    var showLanguageSheet by rememberSaveable { mutableStateOf(false) }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_language),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_language),
            items = 1,
        ) { _, cap ->
            SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_language),
                icon = DroshIcons.Languages,
                supporting = LanguageCatalog.options
                    .firstOrNull { it.tag == settings.appearance.language }?.displayName
                    ?: stringResource(R.string.settings_language_system),
                onClick = { showLanguageSheet = true },
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
}

// ── About ────────────────────────────────────────────────────────────────────

@Composable
fun AboutCategory(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val activity = LocalDroshActivity.current
    val aboutInfo by viewModel.aboutInfo.collectAsStateWithLifecycle(null)

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_about),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_about),
            items = 3,
        ) { index, cap ->
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
                        (activity as? Activity)?.let { host ->
                            val build = aboutInfo?.build ?: "dev"
                            host.copyToClipboard("Build", build)
                            host.toast(host.getString(R.string.settings_build_copied))
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
    }
}
