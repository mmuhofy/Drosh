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
import dev.drosh.ui.session.DeviceBadge
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
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
import dev.drosh.domain.font.TerminalFontCatalog
import dev.drosh.domain.settings.TerminalMode
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.domain.terminal.TerminalZoom
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.LocalDroshActivity
import dev.drosh.ui.R
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The app's own half: how it looks, what it speaks, and where its data lives.
 *
 * ## Why one screen
 *
 * Appearance, the backdrop and the language were three screens behind three
 * rows, and each of them was four to seven rows long. A settings app with more
 * rooms than furniture is a settings app you search instead of browse. They are
 * one screen now, in the order a person meets them: the theme, the font, the
 * picture behind it, the language, and the backup that takes the lot with it.
 */
@Composable
fun GeneralCategory(
    settings: DroshSettings,
    onBack: () -> Unit,
    onUpdate: OnSettingsUpdate,
    onOpenThemeStore: () -> Unit,
    onOpenFontStore: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    viewModel: SettingsViewModel,
) {
    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_general),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_appearance),
            items = 5,
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

                2 -> SettingsTile(
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

                // The terminal's face, which is a shelf rather than a list: a
                // downloaded font is a file with a licence, and the store is
                // where the user picks one.
                4 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_font_store),
                    icon = DroshIcons.Type,
                    supporting = TerminalFontCatalog.byId(settings.appearance.terminalFontId)
                        ?.name
                        ?: stringResource(R.string.settings_font_store_bundled),
                    onClick = onOpenFontStore,
                ) { SettingsChevron() }

                // The drawer's shape, last: it is the one row here that changes
                // how the session list is built rather than how it is coloured.
                3 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_session_surface),
                    icon = DroshIcons.Smartphone,
                    supporting = stringResource(R.string.settings_session_surface_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.appearance.sessionSurface,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(appearance = it.appearance.copy(sessionSurface = on)) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        BackgroundSection(settings = settings, onUpdate = onUpdate, viewModel = viewModel)

        Spacer(Modifier.height(GROUP_GAP))

        LanguageSection(settings = settings, onUpdate = onUpdate)

        Spacer(Modifier.height(GROUP_GAP))

        SettingsGroupColumn(label = null, items = 1) { _, cap ->
            SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_backup_title),
                icon = DroshIcons.Archive,
                supporting = stringResource(R.string.settings_backup_supporting),
                onClick = onOpenBackupRestore,
            ) { SettingsChevron() }
        }
    }
}

