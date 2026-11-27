package dev.drosh.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * One component's row on the version screen.
 *
 * Plain strings rather than the engine's own types: the screen is about what a
 * user can see — a name, what is installed, what is on offer — and the engine
 * types that produce those strings (a `ComponentId`, a release, an install
 * source) are not something a settings screen should have to know about.
 */
data class UpdateUiRow(
    /** Opaque handle the caller uses to say which row an action belongs to. */
    val id: String,
    val title: String,
    /** "Bundled", "1.2.3 (downloaded)", … — the state, already worded. */
    val installed: String,
    /** The version on the channel, or null when the channel has nothing. */
    val available: String?,
    /** Whether install/rollback/working belongs on this row at all. */
    val canInstall: Boolean,
    val canRollback: Boolean,
)

/**
 * The state the version screen draws.
 *
 * [channel] is `"release"` or `"nightly"` because that is what the setting is
 * called and what the file would say; the screen only needs to know which
 * segment is lit.
 */
data class UpdatesUiState(
    val channel: String = "release",
    val status: String = "",
    val busyId: String? = null,
    val rows: List<UpdateUiRow> = emptyList(),
)

/**
 * Version management.
 *
 * ## What this screen is
 *
 * A terminal app is two things that ship at different speeds: the app, which
 * the platform installs, and the engine behind the terminal, which can be
 * staged and swapped without one. This screen is where those two are seen
 * together — what is installed, what the chosen channel offers, and the one
 * action each row can take.
 *
 * The channel is a segment rather than a dropdown because it has two answers
 * and the difference between them is the point: release is the tag, nightly is
 * today's build.
 */
@Composable
fun UpdatesScreen(
    onBack: () -> Unit,
    state: UpdatesUiState,
    onChannel: (String) -> Unit,
    onCheck: () -> Unit,
    onInstall: (String) -> Unit,
    onRollback: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A staged engine waiting for a restart, which the dialog explains. */
    restartPending: Boolean = false,
    onRestart: () -> Unit = {},
    onLater: () -> Unit = {},
) {
    if (restartPending) {
        AlertDialog(
            onDismissRequest = onLater,
            title = { Text(stringResource(R.string.updates_restart_title)) },
            text = { Text(stringResource(R.string.updates_restart_body)) },
            confirmButton = {
                TextButton(onClick = onRestart) {
                    Text(stringResource(R.string.updates_restart_now), color = DroshPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = onLater) {
                    Text(stringResource(R.string.updates_later), color = DroshTextSecondary)
                }
            },
        )
    }

    SettingsCategoryScreen(
        title = stringResource(R.string.updates_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsGroupColumn(
            label = stringResource(R.string.updates_section_channel),
            items = 2,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    stacked = true,
                    title = stringResource(R.string.updates_channel),
                    icon = DroshIcons.Download,
                    supporting = stringResource(R.string.updates_channel_note),
                ) {
                    SettingsSegmented(
                        options = listOf(
                            "release" to stringResource(R.string.updates_channel_release),
                            "nightly" to stringResource(R.string.updates_channel_nightly),
                        ),
                        selected = state.channel,
                        onSelect = onChannel,
                    )
                }

                else -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.updates_check),
                    icon = DroshIcons.RotateCw,
                    supporting = state.status.ifBlank { null },
                    onClick = onCheck,
                )
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        SettingsGroupColumn(
            label = stringResource(R.string.updates_section_components),
            items = state.rows.size,
        ) { index, cap ->
            val row = state.rows[index]
            SettingsTile(
                cap = cap,
                title = row.title,
                icon = DroshIcons.Package,
                supporting = stringResource(R.string.updates_installed, row.installed),
                value = row.available?.let { stringResource(R.string.updates_available, it) },
            ) {
                when {
                    state.busyId == row.id -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = DroshPrimary,
                        strokeWidth = 2.dp,
                    )

                    row.canInstall -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (row.canRollback) {
                            TextButton(onClick = { onRollback(row.id) }) {
                                Text(
                                    text = stringResource(R.string.updates_rollback),
                                    color = DroshTextMuted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        TextButton(onClick = { onInstall(row.id) }) {
                            Text(
                                text = stringResource(R.string.updates_install),
                                color = DroshPrimary,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    row.canRollback -> TextButton(onClick = { onRollback(row.id) }) {
                        Text(
                            text = stringResource(R.string.updates_rollback),
                            color = DroshTextMuted,
                            fontSize = 13.sp,
                        )
                    }

                    row.available == null -> Icon(
                        imageVector = DroshIcons.Check,
                        contentDescription = null,
                        tint = DroshPrimary,
                        modifier = Modifier.size(18.dp),
                    )

                    else -> Icon(
                        imageVector = DroshIcons.Check,
                        contentDescription = null,
                        tint = DroshPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
