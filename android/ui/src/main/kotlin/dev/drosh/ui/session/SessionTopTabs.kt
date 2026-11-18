package dev.drosh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/** The two filters above the grid. */
internal enum class SessionTab { Groups, Singles }

/**
 * Four tiles for grouped sessions, one tile for single sessions.
 *
 * Both tabs are text-and-glyph buttons rather than a filled segmented control:
 * the grid is a quiet background for its cards, so the filter stays subtle too.
 */
@Composable
internal fun SessionTabs(
    selected: SessionTab,
    onSelect: (SessionTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(tab) },
                    )
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        imageVector = if (tab == SessionTab.Groups) {
                            DroshIcons.SessionGrid
                        } else {
                            DroshIcons.Square
                        },
                        contentDescription = null,
                        tint = if (active) DroshPrimary else DroshTextMuted,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(
                            if (tab == SessionTab.Groups) {
                                R.string.session_screen_tab_groups
                            } else {
                                R.string.session_screen_tab_singles
                            },
                        ),
                        color = if (active) DroshText else DroshTextMuted,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                if (active) {
                    Box(
                        modifier = Modifier
                            .height(2.dp)
                            .fillMaxWidth(0.28f)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(DroshPrimary),
                    )
                }
            }
        }
    }
}
