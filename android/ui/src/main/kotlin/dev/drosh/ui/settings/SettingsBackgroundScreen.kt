package dev.drosh.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The backdrop: one image behind the terminal and how it is drawn.
 *
 * ## Why it is its own category
 *
 * It used to be the bottom of Appearance, under the theme and the fonts, where
 * it read as a fourth kind of colour. It is not: the theme decides what the
 * terminal draws, and this decides what is behind it. A user looking for the
 * blur on the photo has no reason to open a screen about fonts to find it.
 *
 * The image is a convention rather than a path — a file called `background.*`
 * in the guest's `.drosh` — so the picker's job is to put one there, and a
 * shell's `cp` does the same thing the picker does.
 */
@Composable
fun BackgroundCategory(
    settings: DroshSettings,
    onBack: () -> Unit,
    onUpdate: OnSettingsUpdate,
    viewModel: SettingsViewModel,
) {
    // The system picker. It writes into the convention rather than into a
    // setting, so what it leaves behind is the same thing a shell's
    // `cp ~/.drosh/background.png` would.
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackgroundImage(it) } }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_category_background),
        onBack = onBack,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.settings_section_background),
            items = 7,
        ) { index, cap ->
            when (index) {
                // The file is the setting's subject, so whether it is used at
                // all sits above choosing one: a background someone turned
                // off is still the file they would turn back on.
                0 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_background_use_image),
                    icon = DroshIcons.Image,
                    supporting = stringResource(R.string.settings_background_use_image_desc),
                ) {
                    SettingsSwitch(
                        checked = settings.background.imageEnabled,
                        onCheckedChange = { on ->
                            onUpdate { it.copy(background = it.background.copy(imageEnabled = on)) }
                        },
                    )
                }

                1 -> {
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

                2 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.settings_background_remove),
                    icon = DroshIcons.Trash2,
                    supporting = stringResource(R.string.settings_background_remove_desc),
                    onClick = { viewModel.removeBackgroundImage() },
                )

                3 -> SettingsTile(
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

                4 -> SettingsTile(
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

                5 -> SettingsTile(
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
