package dev.drosh.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.font.InstalledFont
import dev.drosh.domain.font.StoreFont
import dev.drosh.domain.font.TerminalFontCatalog
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The terminal's font shelf.
 *
 * ## What the screen is for
 *
 * A terminal is read all day, and the face it is read in is the one setting
 * that changes every glyph on the screen. So the store is a list of faces with
 * a screenshot-sized idea of each — the family, what it is good at, its size —
 * and one button per row: download it if it is not here, use it if it is, and a
 * way back to the bundled font at the top.
 *
 * ## Why there is no preview
 *
 * A preview is a second renderer. The terminal itself is the preview: choosing
 * a face applies it, and the session behind this screen is already drawing with
 * it when the user goes back. A row of specimen text drawn here would be
 * measured with a different cell and would promise spacing the terminal does
 * not deliver.
 */
@Composable
fun FontStoreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FontStoreViewModel = hiltViewModel(),
) {
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val installedById = installed.associateBy { it.id }

    SettingsCategoryScreen(
        title = stringResource(R.string.font_store_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        message?.let { text ->
            SettingsGroupColumn(label = null, items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = text,
                    icon = DroshIcons.Info,
                    onClick = { viewModel.dismissMessage() },
                )
            }
            Spacer(Modifier.height(GROUP_GAP))
        }

        // The face the terminal is drawing with, and the way home from a font
        // the user liked less than they expected.
        SettingsGroupColumn(
            label = stringResource(R.string.font_store_section_active),
            items = 1,
        ) { _, cap ->
            SettingsTile(
                cap = cap,
                title = TerminalFontCatalog.byId(active)?.name
                    ?: stringResource(R.string.settings_font_store_bundled),
                icon = DroshIcons.Type,
                supporting = if (active.isEmpty()) {
                    stringResource(R.string.font_store_active_bundled)
                } else {
                    stringResource(R.string.font_store_active_note)
                },
            ) {
                if (active.isNotEmpty()) {
                    TextButton(onClick = { viewModel.apply("") }) {
                        Text(
                            text = stringResource(R.string.font_store_reset),
                            color = DroshPrimary,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    Icon(
                        imageVector = DroshIcons.Check,
                        contentDescription = null,
                        tint = DroshPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        // Everything on the device: downloaded faces first, because they are
        // the ones the user has already said yes to.
        if (installed.isNotEmpty()) {
            SettingsGroupColumn(
                label = stringResource(R.string.font_store_section_installed),
                items = installed.size,
            ) { index, cap ->
                val font = installed[index]
                SettingsTile(
                    cap = cap,
                    title = font.name,
                    icon = DroshIcons.Type,
                    supporting = font.license.ifBlank { null },
                    selected = font.id == active,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (font.id == active) {
                            Icon(
                                imageVector = DroshIcons.Check,
                                contentDescription = null,
                                tint = DroshPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            TextButton(onClick = { viewModel.apply(font.id) }) {
                                Text(
                                    text = stringResource(R.string.font_store_use),
                                    color = DroshPrimary,
                                    fontSize = 13.sp,
                                )
                            }
                            TextButton(onClick = { viewModel.remove(font.id) }) {
                                Text(
                                    text = stringResource(R.string.font_store_remove),
                                    color = DroshTextMuted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(GROUP_GAP))
        }

        SettingsGroupColumn(
            label = stringResource(R.string.font_store_section_store),
            items = viewModel.shelf.size,
        ) { index, cap ->
            val font = viewModel.shelf[index]
            FontShelfRow(
                cap = cap,
                font = font,
                installed = installedById[font.id],
                active = active,
                busy = busy == font.id,
                onGet = { viewModel.download(font) },
                onUse = { viewModel.apply(font.id) },
            )
        }

        Spacer(Modifier.height(GROUP_GAP))

        // The limit, said plainly rather than discovered: the typeface is
        // measured into the cell the grid is laid out with, and that happens
        // when a pane's terminal is built.
        Text(
            text = stringResource(R.string.font_store_note),
            color = DroshTextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}

/** One shelf row: what the font is, and the one action it can take. */
@Composable
private fun FontShelfRow(
    cap: TileCap,
    font: StoreFont,
    installed: InstalledFont?,
    active: String,
    busy: Boolean,
    onGet: () -> Unit,
    onUse: () -> Unit,
) {
    SettingsTile(
        cap = cap,
        title = font.name,
        icon = DroshIcons.Type,
        supporting = font.description,
        value = "${font.license} · ${megabytes(font.sizeBytes)}",
        selected = installed != null && font.id == active,
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = DroshPrimary,
                strokeWidth = 2.dp,
            )

            installed != null && font.id == active -> Icon(
                imageVector = DroshIcons.Check,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(18.dp),
            )

            installed != null -> TextButton(onClick = onUse) {
                Text(
                    text = stringResource(R.string.font_store_use),
                    color = DroshPrimary,
                    fontSize = 13.sp,
                )
            }

            else -> TextButton(onClick = onGet) {
                Text(
                    text = stringResource(R.string.font_store_get),
                    color = DroshPrimary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** "2.2 MB" — the download size, rounded the way a file manager rounds it. */
private fun megabytes(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
