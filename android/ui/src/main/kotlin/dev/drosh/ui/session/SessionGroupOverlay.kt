package dev.drosh.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.drosh.theme.DroshSuccess
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
 * UNTESTED — verify before use: the shared long-press drag mirrors SessionCard,
 * with the group's first member id standing in for a single session's id. Every
 * member is drawn as a small tile; a `+N more` line keeps an unlimited group
 * inside a fixed-height card, and the overlay lists all of them.
 */
@Composable
internal fun SessionGroupCard(
    group: SessionGroup,
    members: List<SessionSnapshot>,
    thumbnails: Map<String, ImageBitmap>,
    displayNames: Map<String, String>,
    /** The brand slug of each member's foreground program, by session id. */
    glyphSlugs: Map<String, String> = emptyMap(),
    pinnedIds: Set<String>,
    persistentIds: Set<String>,
    isDropTarget: Boolean,
    isHeld: Boolean,
    onBounds: (Rect) -> Unit,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val dragDelta = remember { mutableStateOf(Offset.Zero) }
    val borderColor = if (isDropTarget) DroshPrimary else DroshPrimary.copy(alpha = 0.45f)

    // UNTESTED — verify before use: same stiff, damped spring as a single card,
    // so the held group tracks the finger and settles without overshoot.
    val dragScale by animateFloatAsState(
        targetValue = if (isHeld) GROUP_HELD_SCALE else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = GROUP_SETTLE_STIFFNESS),
        label = "groupDragScale",
    )
    val settleX by animateFloatAsState(
        targetValue = if (isHeld) dragDelta.value.x else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = GROUP_SETTLE_STIFFNESS),
        label = "groupSettleX",
    )
    val settleY by animateFloatAsState(
        targetValue = if (isHeld) dragDelta.value.y else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = GROUP_SETTLE_STIFFNESS),
        label = "groupSettleY",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(GROUP_CARD_HEIGHT)
            .sessionDrag(
                dragKey = group.memberIds.firstOrNull(),
                dragDelta = dragDelta,
                onBounds = onBounds,
                onStart = onDragStart,
                onDrag = onDrag,
                onEnd = onDragEnd,
            )
            .zIndex(if (isHeld) 1f else 0f)
            .graphicsLayer {
                scaleX = dragScale
                scaleY = dragScale
                translationX = settleX
                translationY = settleY
            }
            .alpha(if (isHeld) GROUP_HELD_ALPHA else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(DroshSurfaceVariant)
            .then(
                if (isDropTarget) {
                    Modifier.background(DroshPrimary.copy(alpha = 0.12f))
                } else {
                    Modifier
                },
            )
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onTap,
            )
            .padding(
                start = CARD_SIDE_INSET,
                top = CARD_TOP_INSET,
                end = CARD_SIDE_INSET,
                bottom = CARD_BOTTOM_INSET,
            ),
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .background(DroshSurfaceHigh.copy(alpha = 0.5f))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            members.take(MAX_TILES).forEach { member ->
                val name = displayNames[member.id] ?: member.name
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MEMBER_TILE_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SessionThumbnail(
                        sessionId = member.id,
                        bitmap = thumbnails[member.id],
                        contentDescription = stringResource(R.string.session_screen_thumbnail, name),
                        modifier = Modifier.size(MEMBER_TILE_THUMBNAIL),
                    )
                    BrandGlyph(
                        slug = glyphSlugs[member.id],
                        tint = DroshTextMuted,
                        sizeDp = 12,
                    )
                    Text(
                        text = name,
                        color = DroshText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (member.id in pinnedIds) {
                        SessionStateChip(
                            label = stringResource(R.string.session_screen_chip_pin),
                            tint = DroshPrimary,
                            icon = DroshIcons.Pin,
                        )
                    }
                    if (member.id in persistentIds) {
                        SessionStateChip(
                            label = stringResource(R.string.session_screen_chip_persistent),
                            tint = DroshSuccess,
                        )
                    }
                }
            }
            if (members.size > MAX_TILES) {
                Text(
                    text = stringResource(R.string.session_screen_group_more, members.size - MAX_TILES),
                    color = DroshTextMuted,
                    fontSize = 10.sp,
                )
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
 * The centred overlay a tap on a group card opens. It lists every grouped
 * session, opens one on tap, and offers the way back out of the group — removing
 * one member or dissolving the whole group — so grouping is never a one-way
 * door out of the singles view.
 */
@Composable
internal fun SessionGroupOverlay(
    group: SessionGroup,
    members: List<SessionSnapshot>,
    displayNames: Map<String, String>,
    thumbnails: Map<String, ImageBitmap>,
    /** The brand slug of each member's foreground program, by session id. */
    glyphSlugs: Map<String, String> = emptyMap(),
    pinnedIds: Set<String>,
    persistentIds: Set<String>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onUngroupAll: () -> Unit,
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
                    val name = displayNames[member.id] ?: member.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { onOpen(member.id) },
                            )
                            .padding(start = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SessionThumbnail(
                            sessionId = member.id,
                            bitmap = thumbnails[member.id],
                            contentDescription = stringResource(R.string.session_screen_thumbnail, name),
                            modifier = Modifier.size(30.dp),
                        )
                        BrandGlyph(
                            slug = glyphSlugs[member.id],
                            tint = DroshTextMuted,
                            sizeDp = 14,
                        )
                        Text(
                            text = name,
                            color = DroshText,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (member.id in pinnedIds) {
                            SessionStateChip(
                                label = stringResource(R.string.session_screen_chip_pin),
                                tint = DroshPrimary,
                                icon = DroshIcons.Pin,
                            )
                        }
                        if (member.id in persistentIds) {
                            SessionStateChip(
                                label = stringResource(R.string.session_screen_chip_persistent),
                                tint = DroshSuccess,
                            )
                        }
                        IconButton(
                            onClick = { onRemoveMember(member.id) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = DroshIcons.X,
                                contentDescription = stringResource(R.string.session_screen_group_remove),
                                tint = DroshTextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
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
                    .padding(top = 8.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .border(1.dp, DroshBorderSubtle, RoundedCornerShape(percent = 50))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onUngroupAll,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.session_screen_group_ungroup),
                    color = DroshTextMuted,
                    fontSize = 14.sp,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
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

/** Matches a single card's height so the grid keeps its rhythm. */
private val GROUP_CARD_HEIGHT = 336.dp
private val CARD_SIDE_INSET = 5.dp
private val CARD_TOP_INSET = 12.dp
private val CARD_BOTTOM_INSET = 5.dp
private val MEMBER_TILE_HEIGHT = 36.dp
private val MEMBER_TILE_THUMBNAIL = 28.dp
private const val GROUP_HELD_SCALE = 1.04f
private const val GROUP_HELD_ALPHA = 0.92f
private const val GROUP_SETTLE_STIFFNESS = 1100f

/** Four tiles fit the card; the overlay always shows the full membership. */
private const val MAX_TILES = 4
