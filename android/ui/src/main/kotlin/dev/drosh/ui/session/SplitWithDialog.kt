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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The only way to split a session from this screen.
 *
 * `⋮ -> Split with…` opens this list; choosing a session calls the screen's
 * `onGroupSessions` callback. A drag never reaches this dialog — a drag makes a
 * UI tab group instead.
 */
@Composable
internal fun SplitWithDialog(
    candidates: List<SessionSnapshot>,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(DroshSurfaceHigh)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.session_screen_split_title),
                color = DroshText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.session_screen_split_subtitle),
                color = DroshTextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Column(modifier = Modifier.padding(top = 12.dp)) {
                candidates.forEach { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { onChoose(candidate.id) },
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = DroshIcons.PanelLeft,
                            contentDescription = null,
                            tint = DroshPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = candidate.name,
                            color = DroshText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.session_screen_split_empty),
                        color = DroshTextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.session_screen_split_cancel),
                    color = DroshTextMuted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
