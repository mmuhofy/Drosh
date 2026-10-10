package dev.drosh.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.domain.settings.AutoLockTimeout
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.terminal.ShellChoice
import dev.drosh.domain.settings.TerminalMode
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.session.DeviceBadge
import dev.drosh.ui.session.DeviceIdentityViewModel

private val GROUP_GAP = 22.dp

/** Content clearance for the system bar, without insetting the background. */
@Composable
private fun statusBarInset(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * The categories the settings live behind.
 *
 * One row per category, each carrying a one-line summary of what is in it —
 * the same reason the terminal rows carry their current value: a settings
 * screen you have to open to read is a settings screen you open twice. The
 * agent and the projects rows leave for their own screens; everything else
 * edits the file in place behind [SettingsCategoryRoute].
 */
enum class SettingsCategoryRoute(val id: String) {
    Appearance("appearance"),
    Terminal("terminal"),
    Keyboard("keyboard"),
    Shell("shell"),
    Editor("editor"),
    Security("security"),
    Language("language"),
    About("about"),
    ;

    companion object {
        fun fromId(id: String?): SettingsCategoryRoute? =
            entries.firstOrNull { it.id == id }
    }
}

@Composable
fun SettingsHomeScreen(
    onBack: () -> Unit,
    onOpenCategory: (SettingsCategoryRoute) -> Unit,
    onOpenProjects: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    deviceIdentityViewModel: DeviceIdentityViewModel = hiltViewModel(),
) {
    val settings by viewModel.snapshot.collectAsStateWithLifecycle()
    val deviceIdentity by deviceIdentityViewModel.identity.collectAsStateWithLifecycle()

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

            SettingsGroupColumn(label = null, items = 8) { index, cap ->
                when (index) {
                    0 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_category_appearance),
                        icon = DroshIcons.Palette,
                        supporting = appearanceSummary(settings),
                        onClick = { onOpenCategory(SettingsCategoryRoute.Appearance) },
                    ) { SettingsChevron() }

                    1 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_category_terminal),
                        icon = DroshIcons.SquareTerminal,
                        supporting = terminalSummary(settings),
                        onClick = { onOpenCategory(SettingsCategoryRoute.Terminal) },
                    ) { SettingsChevron() }

                    2 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_category_keyboard),
                        icon = DroshIcons.Keyboard,
                        supporting = inputSummary(settings),
                        onClick = { onOpenCategory(SettingsCategoryRoute.Keyboard) },
                    ) { SettingsChevron() }

                    3 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_category_shell),
                        icon = DroshIcons.Terminal,
                        supporting = shellSummary(settings),
                        onClick = { onOpenCategory(SettingsCategoryRoute.Shell) },
                    ) { SettingsChevron() }

                    4 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_projects),
                        icon = DroshIcons.Folder,
                        supporting = stringResource(R.string.settings_projects_desc),
                        onClick = onOpenProjects,
                    ) { SettingsChevron() }

                    5 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_agent),
                        icon = DroshIcons.Code,
                        supporting = stringResource(R.string.settings_agent_desc),
                        onClick = onOpenAgentSettings,
                    ) { SettingsChevron() }

                    6 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_category_editor),
                        icon = DroshIcons.Pencil,
                        supporting = editorSummary(settings),
                        onClick = { onOpenCategory(SettingsCategoryRoute.Editor) },
                    ) { SettingsChevron() }

                    else -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_category_security),
                        icon = DroshIcons.Shield,
                        supporting = securitySummary(settings),
                        onClick = { onOpenCategory(SettingsCategoryRoute.Security) },
                    ) { SettingsChevron() }
                }
            }

            Spacer(Modifier.height(GROUP_GAP))

            SettingsGroupColumn(label = null, items = 2) { index, cap ->
                when (index) {
                    0 -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_language),
                        icon = DroshIcons.Languages,
                        supporting = languageSummary(settings),
                        onClick = { onOpenCategory(SettingsCategoryRoute.Language) },
                    ) { SettingsChevron() }

                    else -> SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_category_about),
                        icon = DroshIcons.Info,
                        supporting = stringResource(R.string.settings_default_build_description),
                        onClick = { onOpenCategory(SettingsCategoryRoute.About) },
                    ) { SettingsChevron() }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The one-line summaries.
 *
 * Each names the choice that a user would come here to check, and nothing
 * else: a summary that recites the whole category is the category again,
 * smaller.
 */
@Composable
private fun appearanceSummary(settings: DroshSettings): String =
    when (settings.appearance.themeMode) {
        ThemeMode.System -> stringResource(R.string.settings_theme_auto)
        ThemeMode.Light -> stringResource(R.string.settings_theme_light)
        ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
    }

@Composable
private fun terminalSummary(settings: DroshSettings): String {
    val mode = when (settings.terminal.mode) {
        TerminalMode.Blocks -> stringResource(R.string.settings_mode_block)
        TerminalMode.Stream -> stringResource(R.string.settings_mode_terminal)
    }
    return stringResource(
        R.string.settings_summary_terminal,
        mode,
        String.format(java.util.Locale.US, "%.0f", settings.terminal.defaultFontSizeSp),
    )
}

@Composable
private fun inputSummary(settings: DroshSettings): String =
    if (settings.input.extraKeysBar) {
        stringResource(R.string.settings_summary_on)
    } else {
        stringResource(R.string.settings_summary_off)
    }

@Composable
private fun shellSummary(settings: DroshSettings): String =
    when (settings.shell.shell) {
        ShellChoice.Zsh -> stringResource(R.string.settings_shell_zsh)
        ShellChoice.Bash -> stringResource(R.string.settings_shell_bash)
    }

@Composable
private fun editorSummary(settings: DroshSettings): String =
    stringResource(
        R.string.settings_font_size_value,
        String.format(java.util.Locale.US, "%.0f", settings.editor.fontSizeSp),
    )

@Composable
private fun securitySummary(settings: DroshSettings): String =
    when (settings.security.autoLock) {
        AutoLockTimeout.Immediately -> stringResource(R.string.settings_security_auto_lock_immediately)
        AutoLockTimeout.OneMinute -> stringResource(R.string.settings_security_auto_lock_one_minute)
        AutoLockTimeout.FiveMinutes -> stringResource(R.string.settings_security_auto_lock_five_minutes)
        AutoLockTimeout.FifteenMinutes -> stringResource(R.string.settings_security_auto_lock_fifteen_minutes)
        AutoLockTimeout.ThirtyMinutes -> stringResource(R.string.settings_security_auto_lock_thirty_minutes)
        AutoLockTimeout.Never -> stringResource(R.string.settings_security_auto_lock_never)
    }

@Composable
private fun languageSummary(settings: DroshSettings): String =
    dev.drosh.core.LanguageCatalog.options
        .firstOrNull { it.tag == settings.appearance.language }?.displayName
        ?: stringResource(R.string.settings_language_system)

@Composable
private fun deviceName(marketing: String?): String =
    marketing?.takeIf { it.isNotBlank() } ?: stringResource(R.string.this_device)
