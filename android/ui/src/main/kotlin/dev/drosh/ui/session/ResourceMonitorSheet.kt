package dev.drosh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.session.SessionState
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.R

/**
 * Bottom sheet with the session counts we can actually verify. There are no
 * RAM or frame-rate samples in the session store, so none are shown: the bar
 * is a proportion of running to idle sessions and nothing else. The full
 * engine report is built by the terminal screen and reached through
 * [onOpenDiagnostics].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceMonitorSheet(
    onDismiss: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val viewModel: SessionSwitcherViewModel = hiltViewModel()
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val running = sessions.count { it.state == SessionState.Running }
    val idle = sessions.count { it.state == SessionState.Idle }
    val live = running + idle
    val ended = sessions.count { it.state == SessionState.Closed }
    val background = sessions.count { it.state != SessionState.Closed && it.id != activeId }
    val active = sessions.firstOrNull { it.id == activeId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = DroshSurfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.sidebar_resource_title),
                color = DroshText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.sidebar_resource_desc),
                color = DroshTextMuted,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(
                    value = live,
                    label = stringResource(R.string.sidebar_resource_live),
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    value = background,
                    label = stringResource(R.string.sidebar_resource_background),
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    value = ended,
                    label = stringResource(R.string.sidebar_resource_ended),
                    modifier = Modifier.weight(1f),
                )
            }

            if (active != null) {
                Text(
                    text = stringResource(R.string.sidebar_resource_active, active.name),
                    color = DroshText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            // Only session counts feed this bar. Zero-count segments are not
            // emitted at all, because a `weight(0f)` is invalid in a Row.
            if (live > 0) {
                Text(
                    text = stringResource(R.string.sidebar_resource_breakdown, running, idle),
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(DroshSurfaceHigh),
                ) {
                    if (running > 0) {
                        Box(
                            modifier = Modifier
                                .weight(running.toFloat())
                                .fillMaxHeight()
                                .background(DroshPrimary),
                        )
                    }
                    if (idle > 0) {
                        Box(
                            modifier = Modifier
                                .weight(idle.toFloat())
                                .fillMaxHeight()
                                .background(DroshTextMuted.copy(alpha = 0.55f)),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    // Dismiss first so the report is not opened behind the sheet.
                    onDismiss()
                    onOpenDiagnostics()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.sidebar_resource_diagnostics))
            }
            Text(
                text = stringResource(R.string.sidebar_resource_diagnostics_note),
                color = DroshTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatCard(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceHigh)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value.toString(),
            color = DroshText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = DroshTextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
