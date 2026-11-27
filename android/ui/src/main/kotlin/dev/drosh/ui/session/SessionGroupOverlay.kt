package dev.drosh.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * A UI group of sessions, like a Chrome tab group.
 *
 * The members are ordered: the panel moves them, and a group card draws its
 * tiles in that order. A blank [name] means "use the default title" — the grid
 * still reads "Group · 3 sessions" for a group the user never named.
 */
internal data class SessionGroup(
    val memberIds: List<String>,
    val name: String = "",
) {
    /** The group's first member owns the grid slot. */
    val slotId: String get() = memberIds.first()
}

/**
 * One group, as a single card in the grid.
 *
 * Four tiles, always: two columns by two rows, each a real terminal preview of
 * a member. A group with more than four members fills the last tile with a
 * `+N` counter instead of dropping the extra members silently; a group with
 * fewer leaves dimmed tiles so the card keeps the same shape as its
 * neighbours, and a tap anywhere opens the management panel.
 */
@Composable
internal fun SessionGroupCard(
    group: SessionGroup,
    members: List<SessionSnapshot>,
    thumbnails: Map<String, ImageBitmap>,
    displayNames: Map<String, String>,
    glyphSlugs: Map<String, String> = emptyMap(),
    isActive: Boolean,
    isDropTarget: Boolean,
    isHeld: Boolean,
    onBounds: (Rect) -> Unit,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val dragDelta = remember { mutableStateOf(Offset.Zero) }

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

    val borderColor = when {
        isDropTarget -> DroshPrimary
        isActive -> DroshPrimary.copy(alpha = 0.7f)
        else -> DroshPrimary.copy(alpha = 0.3f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(GROUP_CARD_HEIGHT)
            .sessionDrag(
                dragKey = group.slotId,
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
            .border(if (isDropTarget) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onTap,
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = DroshIcons.SessionGrid,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = group.displayTitle(members.size),
                color = DroshText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = members.size.toString(),
                color = DroshTextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            for (row in 0 until TILES_PER_SIDE) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                ) {
                    for (column in 0 until TILES_PER_SIDE) {
                        val index = row * TILES_PER_SIDE + column
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            // The last slot is the counter once there are more
                            // members than tiles; before that every slot shows
                            // its own member.
                            val overflowing = members.size > TILE_COUNT && index == TILE_COUNT - 1
                            val member = members.getOrNull(index)
                            when {
                                overflowing -> OverflowTile(members.size - (TILE_COUNT - 1))
                                member != null -> GroupTile(
                                    sessionId = member.id,
                                    name = displayNames[member.id] ?: member.name,
                                    bitmap = thumbnails[member.id],
                                    glyphSlug = glyphSlugs[member.id],
                                )
                                else -> EmptyTile()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTile(
    sessionId: String,
    name: String,
    bitmap: ImageBitmap?,
    glyphSlug: String?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(DroshSurface),
    ) {
        SessionThumbnail(
            sessionId = sessionId,
            bitmap = bitmap,
            contentDescription = stringResource(R.string.session_screen_thumbnail, name),
            modifier = Modifier.fillMaxSize(),
        )
        // The name sits on the preview's own foot, over a soft gradient so it
        // stays readable on a bright terminal.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .padding(start = 5.dp, end = 5.dp, top = 10.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandGlyph(slug = glyphSlug, tint = DroshTextMuted, sizeDp = 9)
                Text(
                    text = name,
                    color = DroshText,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun OverflowTile(count: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(DroshPrimary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$count",
            color = DroshPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyTile() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(DroshSurface.copy(alpha = 0.45f))
            .border(1.dp, DroshBorderSubtle.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
    )
}

/**
 * The big panel a tap on a group card opens — on the screen, not in a dialog.
 *
 * It owns everything a group needs: a name the user can edit in place, the
 * members in their saved order with up/down controls, a way into each session,
 * a way to remove one, and one exit that dissolves the group. Deleting a
 * session is deliberately the grid's close question, not this panel's.
 */
@Composable
internal fun SessionGroupPanel(
    group: SessionGroup,
    members: List<SessionSnapshot>,
    displayNames: Map<String, String>,
    thumbnails: Map<String, ImageBitmap>,
    glyphSlugs: Map<String, String> = emptyMap(),
    isActiveId: (String) -> Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDeleteMember: (String) -> Unit,
    onUngroupAll: () -> Unit,
) {
    var nameDraft by remember(group.slotId, group.name) { mutableStateOf(group.name) }
    var renaming by remember(group.slotId) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(DroshBackground)
                .border(1.dp, DroshBorderSubtle, RoundedCornerShape(26.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    // Absorb taps: the scrim behind this panel closes it, and a
                    // tap inside must not fall through to that scrim.
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DroshPrimary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DroshIcons.SessionGrid,
                        contentDescription = null,
                        tint = DroshPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    if (renaming) {
                        BasicTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it.take(60) },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = DroshText,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            cursorBrush = SolidColor(DroshPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    onRename(nameDraft)
                                    renaming = false
                                },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = group.displayTitle(members.size),
                            color = DroshText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(R.string.session_screen_group_members, members.size),
                        color = DroshTextMuted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (renaming) {
                    IconButton(
                        onClick = {
                            onRename(nameDraft)
                            renaming = false
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = DroshIcons.Check,
                            contentDescription = stringResource(R.string.session_screen_group_rename_save),
                            tint = DroshPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            nameDraft = group.name
                            renaming = true
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = DroshIcons.Pencil,
                            contentDescription = stringResource(R.string.session_screen_group_rename),
                            tint = DroshTextSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = DroshIcons.X,
                        contentDescription = stringResource(R.string.session_screen_close_screen),
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(members, key = { it.id }) { member ->
                    val name = displayNames[member.id] ?: member.name
                    val index = group.memberIds.indexOf(member.id)
                    GroupMemberRow(
                        sessionId = member.id,
                        name = name,
                        bitmap = thumbnails[member.id],
                        glyphSlug = glyphSlugs[member.id],
                        isActive = isActiveId(member.id),
                        canMoveUp = index > 0,
                        canMoveDown = index in 0 until group.memberIds.lastIndex,
                        onOpen = { onOpen(member.id) },
                        onMoveUp = { onMove(member.id, -1) },
                        onMoveDown = { onMove(member.id, 1) },
                        onRemove = { onRemoveMember(member.id) },
                        onDelete = { onDeleteMember(member.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, DroshBorderSubtle, RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onUngroupAll,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.session_screen_group_ungroup),
                        color = DroshTextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    sessionId: String,
    name: String,
    bitmap: ImageBitmap?,
    glyphSlug: String?,
    isActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    val surface = if (isActive) DroshPrimary.copy(alpha = 0.12f) else DroshSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onOpen,
            )
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionThumbnail(
            sessionId = sessionId,
            bitmap = bitmap,
            contentDescription = stringResource(R.string.session_screen_thumbnail, name),
            modifier = Modifier.size(width = 62.dp, height = 40.dp),
        )
        BrandGlyph(slug = glyphSlug, tint = DroshTextMuted, sizeDp = 12)
        Text(
            text = name,
            color = DroshText,
            fontSize = 13.5.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MoveButton(DroshIcons.ChevronUp, canMoveUp, stringResource(R.string.session_screen_group_move_up), onMoveUp)
        MoveButton(DroshIcons.ChevronDown, canMoveDown, stringResource(R.string.session_screen_group_move_down), onMoveDown)
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = DroshIcons.X,
                contentDescription = stringResource(R.string.session_screen_group_remove),
                tint = DroshTextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = DroshIcons.Trash2,
                contentDescription = stringResource(R.string.session_screen_menu_close),
                tint = DroshError.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun MoveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) DroshTextSecondary else DroshTextMuted.copy(alpha = 0.3f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A group's title: the user's name when there is one. */
@Composable
internal fun SessionGroup.displayTitle(memberCount: Int): String =
    name.ifBlank { stringResource(R.string.session_screen_group_title, memberCount) }

/** Matches a single card's height so the grid keeps its rhythm. */
private val GROUP_CARD_HEIGHT = 336.dp
private val TILE_GAP = 6.dp

private const val TILES_PER_SIDE = 2
private const val TILE_COUNT = TILES_PER_SIDE * TILES_PER_SIDE

private const val GROUP_HELD_SCALE = 1.04f
private const val GROUP_HELD_ALPHA = 0.92f
private const val GROUP_SETTLE_STIFFNESS = 1100f
