package dev.drosh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.R

/** The label a session's state gets in this sheet. */
internal fun SessionState.labelRes(): Int = when (this) {
    SessionState.Idle -> R.string.sidebar_state_idle
    SessionState.Running -> R.string.sidebar_state_running
    SessionState.Closed -> R.string.sidebar_state_ended
}

/**
 * Bottom sheet listing the live processes, read straight from the session
 * store. `SessionSnapshot` has no pid and no cwd field, so each row shows the
 * session's name, its state and a short id rather than invented process data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessesSheet(
    onDismiss: () -> Unit,
) {
    val viewModel: SessionSwitcherViewModel = hiltViewModel()
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val live = remember(sessions) {
        sessions
            .filter { it.state != SessionState.Closed }
            .sortedByDescending { it.lastUsedAtMs }
    }
    val endedCount = sessions.count { it.state == SessionState.Closed }

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
                text = stringResource(R.string.sidebar_processes_title),
                color = DroshText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.sidebar_processes_desc),
                color = DroshTextMuted,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            if (live.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.sidebar_processes_empty),
                        color = DroshTextMuted,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = live, key = { it.id }) { snapshot ->
                        ProcessRow(snapshot = snapshot, isActive = snapshot.id == activeId)
                    }
                }
            }

            if (endedCount > 0) {
                Text(
                    text = stringResource(R.string.sidebar_processes_ended, endedCount),
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProcessRow(
    snapshot: SessionSnapshot,
    isActive: Boolean,
) {
    val stateLabel = stringResource(snapshot.state.labelRes())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DroshSurfaceHigh.copy(alpha = if (isActive) 0.9f else 0.5f))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (snapshot.state == SessionState.Running) DroshPrimary else DroshTextMuted),
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = snapshot.name,
                color = DroshText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.sidebar_processes_detail,
                    stateLabel,
                    snapshot.id.take(8),
                ),
                color = DroshTextMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isActive) {
            Text(
                text = stringResource(R.string.sidebar_active),
                color = DroshPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
