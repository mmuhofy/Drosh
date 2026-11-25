package dev.drosh.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.TerminalSettings
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The terminal's typography and behaviour: what a glyph looks like, how much
 * history sits behind the grid, and what a split does with the half nobody is
 * typing into.
 *
 * ## Why it is a section and not a screen
 *
 * These rows used to be the bottom of Appearance, under the theme and the
 * app's own font, where they read as more colour. They are not colour: they are
 * the terminal's own settings, and they belong on the terminal's screen, next
 * to the size and the cursor. A user who wants the rows closer together has no
 * reason to open a screen called Appearance to find them.
 */
@Composable
fun TerminalTypographySection(settings: DroshSettings, onUpdate: OnSettingsUpdate) {
    Spacer(Modifier.height(GROUP_GAP))

    SettingsGroupColumn(
        label = stringResource(R.string.settings_section_terminal),
        items = 7,
    ) { index, cap ->
        when (index) {
            // How much history the terminal holds. Below the typography:
            // it is the row a user comes to when the app is using too much
            // memory, and the row they leave at ten thousand until then.
            6 -> SettingsSelectRow(
                cap = cap,
                title = stringResource(R.string.settings_scrollback),
                icon = DroshIcons.Archive,
                supporting = stringResource(R.string.settings_scrollback_desc),
                options = TerminalSettings.SCROLLBACK_CHOICES.map { lines ->
                    lines to if (lines == 0) {
                        stringResource(R.string.settings_scrollback_off)
                    } else {
                        stringResource(R.string.settings_scrollback_value, lines)
                    }
                },
                selected = settings.terminal.scrollbackLines,
                onSelect = { lines ->
                    onUpdate { it.copy(terminal = it.terminal.copy(scrollbackLines = lines)) }
                },
            )

            // Spacing above weight: the row that changes how every line
            // sits comes before the ones that change how a few characters
            // are drawn.
            5 -> SettingsTile(
                cap = cap,
                stacked = true,
                title = stringResource(R.string.settings_line_height),
                icon = DroshIcons.Maximize,
                supporting = stringResource(R.string.settings_line_height_desc),
                value = String.format(
                    java.util.Locale.US,
                    "%.2f×",
                    settings.terminal.lineHeight,
                ),
            ) {
                SettingsSlider(
                    value = settings.terminal.lineHeight,
                    onValueChange = { value ->
                        onUpdate {
                            it.copy(
                                terminal = it.terminal.copy(
                                    lineHeight = value.coerceIn(
                                        TerminalSettings.MIN_LINE_HEIGHT,
                                        TerminalSettings.MAX_LINE_HEIGHT,
                                    ),
                                ),
                            )
                        }
                    },
                    valueRange = TerminalSettings.MIN_LINE_HEIGHT..
                        TerminalSettings.MAX_LINE_HEIGHT,
                    steps = 14,
                )
            }

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

            3 -> SettingsTile(
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

            // The bell is a terminal behaviour, not a look: it is the
            // flash a program asks for and the notification that follows
            // when nobody is watching. It lives here because this is the
            // screen someone opens to quiet the terminal down.
            else -> SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_bell),
                icon = DroshIcons.Bell,
                supporting = stringResource(R.string.settings_bell_desc),
            ) {
                SettingsSwitch(
                    checked = settings.input.bellEnabled,
                    onCheckedChange = { on ->
                        onUpdate { it.copy(input = it.input.copy(bellEnabled = on)) }
                    },
                )
            }
        }
    }
}
