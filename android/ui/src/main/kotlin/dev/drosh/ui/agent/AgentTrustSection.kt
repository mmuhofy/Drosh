package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTilePressed
import dev.drosh.theme.DroshTextSecondary
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.theme.DroshTextMuted
import dev.drosh.domain.agent.AutonomyLevel
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.settings.GROUP_GAP
import dev.drosh.ui.settings.SettingsGroupColumn
import dev.drosh.ui.settings.SettingsTile
import dev.drosh.ui.settings.SettingsViewModel
import dev.drosh.ui.settings.TileCap

/**
 * What the agent may do without asking.
 *
 * ## Why this is a choice and not a switch
 *
 * Three levels rather than one on/off, because the answer is not yes or no —
 * it is how much of the day you want to answer for. Someone watching the agent
 * refactor a project wants every `git` asked; someone who asked it to install a
 * toolchain does not want to tap through eighty approvals to get there.
 *
 * ## Why the caption under it is not reassurance
 *
 * The caption says what the level does *not* change: the deterministic layer
 * refuses its list at every level, because a level is a preference and that
 * list is a floor. Someone who turns this up deserves to know exactly what
 * they turned up, and exactly what they did not.
 *
 * ## Why the rules are visible
 *
 * A remembered "always allow" is the one permission that survives a restart,
 * so each one is a row and each row can be taken back. A permission nobody can
 * see is a permission nobody can revoke.
 */
@Composable
fun AgentTrustSection(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.snapshot.collectAsStateWithLifecycle()
    val agent = settings.agent

    val options = listOf(
        AutonomyLevel.ASK to stringResource(R.string.agent_trust_level_ask),
        AutonomyLevel.AUTO_SAFE to stringResource(R.string.agent_trust_level_auto),
        AutonomyLevel.YOLO to stringResource(R.string.agent_trust_level_yolo),
    )

    Spacer(Modifier.height(GROUP_GAP))

    Column(modifier = modifier) {
        SettingsGroupColumn(
            label = stringResource(R.string.agent_settings_section_trust),
            items = 1,
        ) { _, cap ->
            SettingsTile(
                cap = cap,
                stacked = true,
                title = stringResource(R.string.agent_trust_autonomy),
                icon = DroshIcons.Shield,
                supporting = stringResource(
                    when (agent.autonomy) {
                        AutonomyLevel.ASK -> R.string.agent_trust_desc_ask
                        AutonomyLevel.AUTO_SAFE -> R.string.agent_trust_desc_auto
                        AutonomyLevel.YOLO -> R.string.agent_trust_desc_yolo
                    },
                ),
            ) {
                TrustLevelChooser(
                    options = options,
                    selected = agent.autonomy,
                    onSelect = { level ->
                        viewModel.update { snap ->
                            snap.copy(agent = snap.agent.copy(autonomy = level))
                        }
                    },
                )
            }
        }

        TrustCaption(text = stringResource(R.string.agent_trust_floor_note))

        if (agent.alwaysAllow.isNotEmpty()) {
            Spacer(Modifier.height(GROUP_GAP))
            SettingsGroupColumn(
                label = stringResource(R.string.agent_trust_rules_title),
                items = agent.alwaysAllow.size,
            ) { index, cap ->
                SettingsTile(
                    cap = cap,
                    title = agent.alwaysAllow[index].pattern,
                    icon = DroshIcons.Terminal,
                    supporting = stringResource(R.string.agent_trust_rules_note),
                    // Tapping a rule revokes it. There is no confirm step:
                    // revoking restores the question rather than taking anything
                    // away, so the only thing it can cost is one more tap later.
                    onClick = {
                        val pattern = agent.alwaysAllow[index].pattern
                        viewModel.update { snap ->
                            snap.copy(
                                agent = snap.agent.copy(
                                    alwaysAllow = snap.agent.alwaysAllow.filterNot { it.pattern == pattern },
                                ),
                            )
                        }
                    },
                ) { TrustRevokeLabel() }
            }
        }
    }
}

/** The three levels, as a row of pills under the tile's own title. */
@Composable
private fun TrustLevelChooser(
    options: List<Pair<AutonomyLevel, String>>,
    selected: AutonomyLevel,
    onSelect: (AutonomyLevel) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (level, label) ->
            val isSelected = level == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) DroshPrimary else DroshTilePressed)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(level) },
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) DroshTile else DroshTextSecondary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Revoke", in the voice of the rest of the settings screen. */
@Composable
private fun TrustRevokeLabel() {
    Text(
        text = stringResource(R.string.agent_trust_rules_revoke),
        fontSize = 12.5.sp,
        color = DroshTextMuted,
    )
}

/**
 * A line of explanation beside a group.
 *
 * Not this file's own, because the one in [AgentSettingsScreen] is private to
 * it — and a caption is exactly the kind of small piece of vocabulary two
 * screens in one package should not each have their own copy of.
 */
@Composable
private fun TrustCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DroshTextMuted,
) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = modifier.padding(horizontal = 22.dp, vertical = 4.dp),
    )
}