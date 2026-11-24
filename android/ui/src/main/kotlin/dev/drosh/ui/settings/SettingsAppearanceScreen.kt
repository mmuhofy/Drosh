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

/**
 * What the system picker offers for a font file.
 *
 * The MIME types a font file can arrive under are not agreed on: some
 * providers say `font/ttf`, some `application/x-font-ttf`, some nothing at
 * all. The last one is the escape hatch, and a file with no type is still a
 * file the user picked.
 */
private val FONT_FILE_TYPES = arrayOf(
    "font/*",
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-truetype",
    "application/octet-stream",
)


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

    val context = LocalContext.current

    // The user's own font file. The system picker gives a URI, and the
    // permission to read it is taken for as long as the app lives — without
    // that the file is readable now and gone after a restart.
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        onUpdate {
            it.copy(appearance = it.appearance.copy(customFontUri = uri.toString()))
        }
    }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_appearance),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_appearance),
            items = 4,
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
                    // A font of the user's own, for the terminal. The packs
                    // above are the app's text; this one is the terminal's
                    // face, and it is a file the user picks.
                    SettingsSubRow(
                        icon = DroshIcons.File,
                        label = stringResource(R.string.settings_font_custom),
                        description = settings.appearance.customFontUri
                            .ifBlank { stringResource(R.string.settings_font_custom_none) }
                            .substringAfterLast('/'),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (settings.appearance.customFontUri.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            onUpdate {
                                                it.copy(
                                                    appearance = it.appearance.copy(customFontUri = ""),
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = DroshIcons.X,
                                            contentDescription = stringResource(
                                                R.string.settings_font_custom_clear,
                                            ),
                                            tint = DroshTextMuted,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { fontPicker.launch(FONT_FILE_TYPES) },
                                ) {
                                    Icon(
                                        imageVector = DroshIcons.Folder,
                                        contentDescription = stringResource(
                                            R.string.settings_font_custom_choose,
                                        ),
                                        tint = DroshPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                    )
                }

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

        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_terminal),
            items = 4,
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

                2 -> SettingsTile(
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

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_immersive),
                    icon = DroshIcons.Maximize,
                    supporting = stringResource(R.string.settings_immersive_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.terminal.immersive,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(terminal = it.terminal.copy(immersive = on)) }
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
                    value = stringResource(
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
                    value = if (settings.background.blurRadius == 0) {
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
                    value = stringResource(
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
                    value = stringResource(
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

