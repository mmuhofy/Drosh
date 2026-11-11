package dev.drosh.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshError
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
 * ## Hierarchy
 *
 * Three kinds of card and they must not look alike:
 *
 *  - **The active one** wears the accent: a border in the theme's primary
 *    colour and a "Buradasın" pill,
 *    so "you are here" survives a glance back after scrolling.
 *  - **A waiting or aimed-at card** also wears the accent, but over a tinted
 *    surface — the grouping gesture needs the loudest colour, and the tint is
 *    what separates it from plain "you are here".
 *  - **Everything else** is a quiet card on a subtle border.
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
 *
 * ## Closing
 *
 * A close — one card, from its menu, or all at once from the header — never
 * fires on the tap that asked for it. It lands in [pendingCloseId] /
 * [pendingCloseAll] and a dialog asks once. A card sits next to others the
 * finger is travelling over; an undoable-in-principle step still deserves one
 * question in practice, because the transcript it erases is not recoverable.
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

    /**
     * A close waiting for its confirmation.
     *
     * [pendingCloseId] is one session's close; [pendingCloseAll] is the header's
     * "close everything". The two never set together — the dialog is modal —
     * but they stay separate so confirming can read which one it is serving.
     */
    var pendingCloseId by remember { mutableStateOf<String?>(null) }
    var pendingCloseAll by remember { mutableStateOf(false) }

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
            onCloseAll = {
                if (live.isEmpty()) {
                    // Nothing left to close, and a dialog saying so would be
                    // noise. The empty state is already on screen.
                } else {
                    pendingCloseAll = true
                }
            },
        )

        if (live.isEmpty()) {
            SessionGridEmpty(onNewSession = onNewSession)
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
                        onClose = { pendingCloseId = session.id },
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
                // Through the same confirmation as the card's own X: the menu
                // is a second door to the same destructive step.
                pendingCloseId = menuTarget.id
                menuFor = null
            },
        )
    }

    // And the close confirmation, also drawn outside the grid so a scroll
    // underneath it cannot move the question out from under the finger.
    if (pendingCloseId != null || pendingCloseAll) {
        val targetName = pendingCloseId
            ?.let { id -> live.firstOrNull { it.id == id }?.name }
            ?: "Session"
        CloseSessionDialog(
            title = if (pendingCloseAll) "Tüm session'lar kapatılsın mı?" else "Session kapatılsın mı?",
            message = if (pendingCloseAll) {
                "${live.size} açık session'ın tamamı kapanacak."
            } else {
                "\"$targetName\" kapatılacak. Çıktısı geri alınamaz."
            },
            onConfirm = {
                val id = pendingCloseId
                if (id != null) {
                    onClose(id)
                } else {
                    onCloseAll()
                }
                pendingCloseId = null
                pendingCloseAll = false
            },
            onDismiss = {
                pendingCloseId = null
                pendingCloseAll = false
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
            SmallIconButton(DroshIcons.EyeOff, "Tüm session'ları kapat", onCloseAll)
        }
    }
}

/**
 * A small header action.
 *
 * The box is 48dp, not 36dp: it is a button someone reaches for while the other
 * hand is busy, and 36dp was under the touch-target floor. The icon stays small
 * so the row does not grow — only the reachable area does.
 */
@Composable
private fun SmallIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
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
 * The grid with nothing in it.
 *
 * An empty box says "broken". This says "start here": what happened (every
 * session is gone), and the one action that moves forward, as a button rather
 * than a suggestion, because on a first run the footer is below the fold of
 * attention and this is where the eye lands.
 */
@Composable
private fun SessionGridEmpty(onNewSession: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(DroshSurfaceVariant)
                .border(1.dp, DroshBorderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DroshIcons.Terminal,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Açık session yok",
            color = DroshText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Yeni bir shell aç, kart burada belirir.",
            color = DroshTextMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        NewSessionButton(onNewSession)
    }
}

/**
 * The one "add a session" pill, shared by the empty state and the footer so the
 * two cannot drift apart on how the action looks.
 */
@Composable
private fun NewSessionButton(onNewSession: () -> Unit) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(DroshSurfaceHigh)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
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

@Composable
private fun SessionGridFooter(onNewSession: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        NewSessionButton(onNewSession)
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
 *
 * Pressing is a real state, not silence: with `indication = null` a tap gave no
 * visual answer until the switch happened, and a card that answers nothing
 * reads as dead. The pressed card dips slightly — the same gesture the sidebar
 * rows speak — and returns over 200ms.
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val borderWidth by animateFloatAsState(
        targetValue = if (isActive || isPending || isDropTarget) 2f else 1f,
        animationSpec = tween(200),
        label = "cardBorder",
    )
    // The accent means "this card is in play" for all three states — active,
    // waiting, aimed at. What separates them is the surface, not the border:
    // the grouping states add a tint, and the active one adds its pill.
    val borderColor = when {
        isPending || isDropTarget -> DroshPrimary
        isActive -> DroshPrimary.copy(alpha = 0.7f)
        awaitingPartner -> DroshPrimary.copy(alpha = 0.35f)
        else -> DroshBorderSubtle
    }
    val cardSurface by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(200),
        label = "cardActiveSurface",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = tween(200),
        label = "cardPress",
    )
    // The active card's surface brightens with its border, not in a snap — the
    // same 200ms keeps the two moving as one change rather than two.
    val background = lerpColor(DroshSurfaceVariant, DroshSurfaceHigh, cardSurface)

    // Screen-reader one-liner: which card this is and what state it is in,
    // before the user has to guess from a dot. Remembered on the inputs it
    // reads, so a recomposition driven by anything else rebuilds nothing.
    val stateDescription = remember(isActive, session.state) {
        when {
            isActive -> "Şu an buradasın, ${stateLabel(session.state)}"
            else -> stateLabel(session.state)
        }
    }

    // The card's origin in the window, captured at layout. The gesture reports
    // positions in this node's own space; the grid decides what the finger is
    // over in the window's. One offset is the whole translation between them.
    val origin = remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_ASPECT)
            .onGloballyPositioned {
                origin.value = it.boundsInRoot().topLeft
                onBounds(it.boundsInRoot())
            }
            // `this.` on the left: the name on the right is the card's own
            // description, and without it the assignment would be to that
            // one — which is a value, not a property.
            .semantics { this.stateDescription = stateDescription }
            // A carried card lifts: it is above its neighbours and out of the
            // grid's flow, which is what makes it read as something being moved
            // rather than something that grew.
            .zIndex(if (isHeld) 1f else 0f)
            .graphicsLayer {
                val scale = if (isHeld) HELD_SCALE else pressScale
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (isHeld) HELD_ALPHA else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            // The grouping states get their tint over the card surface, so the
            // accent border never stands alone as the only signal.
            .then(
                if (isPending || isDropTarget) {
                    Modifier.background(DroshPrimary.copy(alpha = 0.08f))
                } else {
                    Modifier
                },
            )
            .border(borderWidth.dp, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .pointerInput(session.id) {
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
                // 48dp of reach around a small mark: the icon stays out of the
                // card's way visually, but the finger does not have to find a
                // 22dp dot to hit it.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
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

        // The row of facts under the picture: state in words, then how long
        // since the session was last touched. The pill rides on the same row
        // only when it has something to say — an inactive card's footer stays
        // one quiet line.
        // Keyed on the timestamp the phrase is built from: the label can go
        // stale by a minute — invisible — but must not reformat on every
        // recomposition the grid's other state (drag, pending, press) causes.
        val meta = remember(session.lastUsedAtMs) { cardMeta(session) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = meta,
                color = DroshTextSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                ActivePill()
            }
        }
    }
}

/**
 * The "you are here" mark.
 *
 * A filled pill in the accent: the active card already has the border, but a
 * border alone says "special", not "current". The words are the difference
 * between coming back to the grid after a scroll and knowing instantly which
 * session a tap will land in.
 */
@Composable
private fun ActivePill() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(DroshPrimary.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "Buradasın",
            color = DroshPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
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

/**
 * The close question.
 *
 * Styled after [SessionCardMenu] — same surface, same radius — so a dialog and
 * a menu read as the same family. Confirm is destructive-coloured, because the
 * dangerous button should be the one that announces itself.
 */
@Composable
private fun CloseSessionDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(DroshSurfaceHigh)
                .padding(20.dp),
        ) {
            Text(
                text = title,
                color = DroshText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = DroshTextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButton("İptal", DroshTextSecondary, onDismiss)
                DialogButton("Kapat", DroshError, onConfirm)
            }
        }
    }
}

/**
 * A dialog action, sized to the 48dp floor and coloured by its caller: the
 * destructive entry in [DroshError], the way out in plain secondary text.
 */
@Composable
private fun DialogButton(
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The card's under-picture line: state in words, then recency. */
private fun cardMeta(session: SessionSnapshot): String {
    val time = relativeTime(session.lastUsedAtMs, System.currentTimeMillis())
    return "${stateLabel(session.state)} · $time"
}

/** The state as a word, shared by the meta line and the semantics. */
private fun stateLabel(state: SessionState): String = when (state) {
    SessionState.Running -> "Çalışıyor"
    SessionState.Idle -> "Boşta"
    SessionState.Closed -> "Kapalı"
}

/**
 * Recency as a short human phrase, not a clock time.
 *
 * A clock time forces arithmetic ("was that 14:02 an hour ago?"); a distance
 * answers the only question the footer is asked: is this the one I just left?
 * Computed once per change of [thenMs], not per frame — the label going stale
 * by a minute is invisible, recomposing every card every minute is not.
 */
private fun relativeTime(thenMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1L -> "şimdi"
        minutes < 60L -> "$minutes dk önce"
        minutes < MINUTES_PER_DAY -> "${minutes / 60L} sa önce"
        else -> "${minutes / MINUTES_PER_DAY} g önce"
    }
}

/** A naive two-colour blend, enough for a surface that brightens on activation. */
private fun lerpColor(from: Color, to: Color, fraction: Float): Color =
    Color(
        red = from.red + (to.red - from.red) * fraction,
        green = from.green + (to.green - from.green) * fraction,
        blue = from.blue + (to.blue - from.blue) * fraction,
        alpha = from.alpha + (to.alpha - from.alpha) * fraction,
    )

/** A card's width to its height. Slightly taller than wide, like a tab. */
private const val CARD_ASPECT = 0.82f

/** How many lines of the transcript a thumbnail holds. */
private const val THUMBNAIL_LINES = 14

/** How much a carried card grows, and how far it fades. */
private const val HELD_SCALE = 1.04f
private const val HELD_ALPHA = 0.92f

/** How far a pressed card dips. Small, so it reads as touch and not as motion. */
private const val PRESS_SCALE = 0.98f

/** Where "dk" hands over to "sa" and "sa" to "g", in minutes. */
private const val MINUTES_PER_DAY = 60L * 24L
