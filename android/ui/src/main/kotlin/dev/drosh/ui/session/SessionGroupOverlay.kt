package dev.drosh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * A UI group of sessions, like a Chrome tab group.
 *
 * Membership is local state only. TODO: persist — the terminal owns the real
 * split panes; this list just gathers cards on the screen.
 */
internal data class SessionGroup(val memberIds: List<String>)

/**
 * One group, occupying a single card slot in the grid.
 *
 * UNTESTED — verify before use: the drag hit-testing mirrors SessionCard, with
 * the group's first member id standing in for a single session's id.
 */
@Composable
internal fun SessionGroupCard(
    group: SessionGroup,
    members: List<SessionSnapshot>,
    isDropTarget: Boolean,
    isHeld: Boolean,
    pointer: Offset,
    onBounds: (Rect) -> Unit,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val origin = remember { mutableStateOf(Offset.Zero) }
    val borderColor = if (isDropTarget) DroshPrimary else DroshPrimary.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(GROUP_CARD_HEIGHT)
            .onGloballyPositioned {
                origin.value = it.boundsInRoot().topLeft
                onBounds(it.boundsInRoot())
            }
            .zIndex(if (isHeld) 1f else 0f)
            .graphicsLayer {
                val scale = if (isHeld) GROUP_HELD_SCALE else 1f
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (isHeld) GROUP_HELD_ALPHA else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(DroshSurfaceVariant)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onTap,
            )
            .pointerInput(group.memberIds.firstOrNull()) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local -> onDragStart(local + origin.value) },
                    onDrag = { change, _ ->
                        change.consume()
                        onDrag(change.position + origin.value)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = DroshIcons.SessionGrid,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.session_screen_group_title, members.size),
                color = DroshText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .background(DroshSurfaceHigh.copy(alpha = 0.5f))
                .padding(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                members.take(MAX_PREVIEW_MEMBERS).forEach { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = DroshIcons.SquareTerminal,
                            contentDescription = null,
                            tint = DroshPrimary.copy(alpha = 0.8f),
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = member.name,
                            color = DroshText,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.session_screen_group_open),
            color = DroshTextMuted,
            fontSize = 10.5.sp,
        )
    }
}

/**
 * The centred overlay a tap on a group card opens. It lists the grouped
 * sessions; picking one opens it.
 */
@Composable
internal fun SessionGroupOverlay(
    group: SessionGroup,
    members: List<SessionSnapshot>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(DroshSurfaceHigh)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = DroshIcons.SessionGrid,
                    contentDescription = null,
                    tint = DroshPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.session_screen_group_title, members.size),
                    color = DroshText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Column(modifier = Modifier.padding(top = 12.dp)) {
                members.forEach { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { onOpen(member.id) },
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = DroshIcons.SquareTerminal,
                            contentDescription = null,
                            tint = DroshTextMuted,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = member.name,
                            color = DroshText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (members.isEmpty()) {
                    Text(
                        text = stringResource(R.string.session_screen_group_empty),
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
                    .border(1.dp, DroshBorderSubtle, RoundedCornerShape(percent = 50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.session_screen_group_close),
                    color = DroshTextMuted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private val GROUP_CARD_HEIGHT = 200.dp
private const val GROUP_HELD_SCALE = 1.04f
private const val GROUP_HELD_ALPHA = 0.92f
private const val MAX_PREVIEW_MEMBERS = 4
