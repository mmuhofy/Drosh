package dev.drosh.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.DroshIcons

/**
 * Every session, as a card with a thumbnail of its output.
 *
 * ## Why a grid and not a list
 *
 * A list answers "which session am I in" with a name. A grid answers "what is
 * each session doing" with a picture of it, which is the question a user
 * actually has when four shells are open: the one running a build looks
 * different from the one sitting at a prompt, and a name does not say which is
 * which. The model is Chrome's tab switcher, and the reason it works there is
 * the same reason it works here.
 *
 * ## Grouping
 *
 * Two cards can be joined into a split, which puts their sessions one above the
 * other in the terminal. There are two ways in and they are the same operation:
 *
 *  - **The menu**: hold a card, choose "Split", and the card waits with a
 *    half-filled square until another card is tapped.
 *  - **The drag**: hold a card, carry it onto another, and let go.
 *
 * Both end at [onGroupSessions]. The waiting state is [pendingGroup], and the
 * drag's target is [dropTarget]; the two never run at once.
 *
 * ## What a group is not
 *
 * The grid does not own the grouping. The terminal's pane layout is what
 * actually shows two sessions at once, and it holds exactly two panes — so a
 * third card dropped on a pair replaces the pair's second member rather than
 * adding to it. The grid says which two, and never more than the terminal can
 * show.
 */
@Composable
fun SessionGrid(
    sessions: List<SessionSnapshot>,
    activeId: String?,
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewSession: () -> Unit,
    onCloseAll: () -> Unit,
    /**
     * Joins two sessions into a split.
     *
     * The first id is the session already waiting or held, the second the one it
     * landed on. Order matters: it decides which session lands in the upper pane.
     */
    onGroupSessions: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    /**
     * The session waiting for a partner, or null.
     *
     * One piece of state for both entry points: the menu sets it, the drag sets
     * it on release, and a tap on another card consumes it. Nothing else has to
     * know which way the user got here.
     */
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    /** The card under a held finger, or null. */
    var heldId by remember { mutableStateOf<String?>(null) }
    /** Where the finger is, in root coordinates. */
    var pointer by remember { mutableStateOf(Offset.Zero) }
    /** The card the finger is over, when it is over one and not the held one. */
    var dropTarget by remember { mutableStateOf<String?>(null) }

    /**
     * Every card's rectangle, in root coordinates.
     *
     * Root coordinates rather than the grid's: a card's own pointer event
     * reports its position in the card's space, and the only common frame
     * between a card and the card under the finger is the window. A plain map
     * rather than Compose state — it is written during layout and read during a
     * gesture, and routing it through state would make layout invalidate itself
     * on every frame of a drag.
     */
    val bounds = remember { mutableMapOf<String, Rect>() }

    val live = remember(sessions) {
        sessions
            .filter { it.state != SessionState.Closed }
            .sortedByDescending { it.lastUsedAtMs }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SessionGridHeader(
            count = live.size,
            pending = pendingGroup != null,
            onCancelPending = { pendingGroup = null },
            onCloseAll = onCloseAll,
        )

        if (live.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Açık session yok.\nAşağıdan yenisini aç.",
                    color = DroshTextMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(live, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        isActive = session.id == activeId,
                        isPending = session.id == pendingGroup,
                        isDropTarget = session.id == dropTarget,
                        isHeld = session.id == heldId,
                        awaitingPartner = pendingGroup != null && session.id != pendingGroup,
                        pointer = pointer,
                        onBounds = { bounds[session.id] = it },
                        onTap = {
                            val pending = pendingGroup
                            when {
                                pending == null -> onOpen(session.id)
                                pending == session.id -> pendingGroup = null
                                else -> {
                                    onGroupSessions(pending, session.id)
                                    pendingGroup = null
                                }
                            }
                        },
                        onLongPress = { menuFor = session.id },
                        onDragStart = { position ->
                            heldId = session.id
                            pointer = position
                            dropTarget = null
                        },
                        onDrag = { position ->
                            pointer = position
                            dropTarget = bounds.entries
                                .firstOrNull { (id, rect) ->
                                    id != session.id && rect.contains(position)
                                }
                                ?.key
                        },
                        onDragEnd = {
                            val target = dropTarget
                            val held = heldId
                            heldId = null
                            dropTarget = null
                            if (target != null && held != null) {
                                onGroupSessions(held, target)
                            }
                        },
                        onClose = { onClose(session.id) },
                    )
                }
            }
        }

        SessionGridFooter(onNewSession = onNewSession)
    }

    // The card menu, one at a time. Drawn outside the grid so it is not clipped
    // by a card's bounds and does not scroll with the list.
    val menuTarget = menuFor?.let { id -> live.firstOrNull { it.id == id } }
    if (menuTarget != null) {
        SessionCardMenu(
            session = menuTarget,
            onDismiss = { menuFor = null },
            onSplit = {
                pendingGroup = menuTarget.id
                menuFor = null
            },
            onClose = {
                onClose(menuTarget.id)
                menuFor = null
            },
        )
    }
}

@Composable
private fun SessionGridHeader(
    count: Int,
    pending: Boolean,
    onCancelPending: () -> Unit,
    onCloseAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (pending) "Split için bir session seç" else "Sessions",
                color = DroshText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (pending) "İptal etmek için seçili karta dokun" else "$count açık",
                color = DroshTextMuted,
                fontSize = 12.sp,
            )
        }

        if (pending) {
            SmallIconButton(DroshIcons.X, "Split'i iptal et", onCancelPending)
        } else {
            SmallIconButton(DroshIcons.EyeOff, "Tümünü kapat", onCloseAll)
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = DroshTextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * One session, as a card.
 *
 * The thumbnail is the session's own output, drawn small. It is not a picture
 * of the terminal: the terminal is a GL surface in a pane that may not be on
 * screen at all, and there is no way to sample one that is not. The transcript
 * is what the engine keeps regardless, so it is what a card can honestly show.
 *
 * The drag is a long press followed by movement, reported in root coordinates
 * because the grid — not the card — decides what the finger is over.
 */
@Composable
private fun SessionCard(
    session: SessionSnapshot,
    isActive: Boolean,
    isPending: Boolean,
    isDropTarget: Boolean,
    isHeld: Boolean,
    awaitingPartner: Boolean,
    pointer: Offset,
    onBounds: (Rect) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onClose: () -> Unit,
) {
    val borderWidth by animateFloatAsState(
        targetValue = if (isActive || isPending || isDropTarget) 2f else 1f,
        animationSpec = tween(180),
        label = "cardBorder",
    )
    val borderColor = when {
        isPending || isDropTarget -> DroshPrimary
        isActive -> DroshText.copy(alpha = 0.55f)
        awaitingPartner -> DroshPrimary.copy(alpha = 0.35f)
        else -> DroshBorderSubtle
    }

    // The card's origin in the window, captured at layout. The gesture reports
    // positions in this node's own space; the grid decides what the finger is
    // over in the window's. One offset is the whole translation between them.
    var origin by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_ASPECT)
            .onGloballyPositioned {
                origin = it.boundsInRoot().topLeft
                onBounds(it.boundsInRoot())
            }
            // A carried card lifts: it is above its neighbours and out of the
            // grid's flow, which is what makes it read as something being moved
            // rather than something that grew.
            .zIndex(if (isHeld) 1f else 0f)
            .graphicsLayer {
                if (isHeld) {
                    scaleX = HELD_SCALE
                    scaleY = HELD_SCALE
                }
            }
            .alpha(if (isHeld) HELD_ALPHA else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) DroshSurfaceHigh else DroshSurfaceVariant)
            .border(borderWidth.dp, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .pointerInput(session.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local -> onDragStart(local + origin) },
                    onDrag = { change, _ ->
                        change.consume()
                        onDrag(change.position + origin)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(session.state)
            Text(
                text = session.name,
                color = DroshText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isPending) {
                // The first half of a group: a square, half filled. It says
                // "this one is waiting for a partner" without a sentence.
                HalfFilledSquare()
            } else {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DroshIcons.X,
                        contentDescription = "${session.name} kapat",
                        tint = DroshTextMuted,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .background(DroshSurface),
        ) {
            SessionThumbnail(
                lines = session.liveSnapshotLines,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The half-filled square a waiting card shows.
 *
 * A filled left half and an empty right one: the shape of a split, and the shape
 * of "one of two chosen". Drawn rather than an icon so the two halves are
 * exactly equal at any size.
 */
@Composable
private fun HalfFilledSquare() {
    Box(
        modifier = Modifier
            .size(15.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(1.5.dp, DroshPrimary, RoundedCornerShape(3.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .fillMaxSize()
                .background(DroshPrimary),
        )
    }
}

/**
 * The status of a session, as a dot.
 *
 * Running and closed are colours; idle is the plain foreground. A session that
 * has ended is not one the user can return to, and the dot is the only thing on
 * the card that says so before the name is read.
 */
@Composable
private fun StatusDot(state: SessionState) {
    val color = when (state) {
        SessionState.Running -> DroshPrimary
        SessionState.Closed -> DroshTextMuted.copy(alpha = 0.5f)
        SessionState.Idle -> DroshTextSecondary
    }
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * A session's last lines, drawn small.
 *
 * Drawn at a small size rather than scaled down: a scaled column would blur the
 * glyphs at exactly the size where they are already hard to read, and the point
 * of the thumbnail is to be readable as shapes. The tail is drawn, not the head
 * — the last lines are what the session is doing now, and the first lines of a
 * shell are the same banner in every session.
 */
@Composable
private fun SessionThumbnail(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val visible = remember(lines) { lines.takeLast(THUMBNAIL_LINES) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        visible.forEach { line ->
            Text(
                text = line.ifBlank { " " },
                color = DroshTextMuted,
                fontFamily = LocalFontSet.current.mono,
                fontSize = 6.sp,
                lineHeight = 8.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun SessionGridFooter(onNewSession: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(DroshSurfaceHigh)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNewSession,
                )
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = DroshIcons.Plus,
                contentDescription = null,
                tint = DroshText,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Yeni session",
                color = DroshText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** A card's width to its height. Slightly taller than wide, like a tab. */
private const val CARD_ASPECT = 0.82f

/** How many lines of the transcript a thumbnail holds. */
private const val THUMBNAIL_LINES = 14

/** How much a carried card grows, and how far it fades. */
private const val HELD_SCALE = 1.04f
private const val HELD_ALPHA = 0.92f
