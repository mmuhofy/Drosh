package dev.drosh.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSuccess
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * One session, as a tall card with the last screenshot of its terminal.
 *
 * The preview is the real thing, captured by the terminal and handed to the
 * ViewModel as an [ImageBitmap]. No simulated coloured lines: a session with no
 * screenshot yet shows a neutral surface instead of pretending to have output.
 *
 * The drag is a long press plus a movement threshold, reported in window
 * coordinates because the screen — not the card — decides what the finger is
 * over. A drag reorders within a section or adds to a group; splitting is only
 * done from the ⋮ menu.
 */
@Composable
internal fun SessionCard(
    session: SessionSnapshot,
    displayName: String,
    /** The last screenshot of this session's terminal, or null if none yet. */
    thumbnail: ImageBitmap?,
    isActive: Boolean,
    /** The title the program asked for, or null. */
    title: String?,
    isPinned: Boolean,
    isPersistent: Boolean,
    isDropTarget: Boolean,
    isHeld: Boolean,
    onBounds: (Rect) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onSplitWith: () -> Unit,
    onTogglePin: () -> Unit,
    onTogglePersistent: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onCloseAll: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dragDelta = remember { mutableStateOf(Offset.Zero) }

    val borderWidth by animateFloatAsState(
        targetValue = if (isActive || isDropTarget) 2f else 1f,
        animationSpec = tween(200),
        label = "cardBorder",
    )
    val borderColor = when {
        isDropTarget -> DroshPrimary
        isActive -> DroshPrimary.copy(alpha = 0.7f)
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
    // UNTESTED — verify before use: the held scale and the drag translation both
    // use a stiff, non-bouncing spring so release settles without overshoot.
    val dragScale by animateFloatAsState(
        targetValue = if (isHeld) HELD_SCALE else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = SETTLE_STIFFNESS),
        label = "cardDragScale",
    )
    val settleX by animateFloatAsState(
        targetValue = if (isHeld) dragDelta.value.x else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = SETTLE_STIFFNESS),
        label = "cardSettleX",
    )
    val settleY by animateFloatAsState(
        targetValue = if (isHeld) dragDelta.value.y else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = SETTLE_STIFFNESS),
        label = "cardSettleY",
    )
    val background = lerpColor(DroshSurfaceVariant, DroshSurfaceHigh, cardSurface)

    // No status or recency labels. The border says "active", the left-hand glyph
    // identifies the session, and the accessibility text is just its name.
    val description = remember(displayName, title) {
        if (title.isNullOrBlank() || title == displayName) {
            displayName
        } else {
            "$displayName, $title"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .sessionDrag(
                dragKey = session.id,
                dragDelta = dragDelta,
                onBounds = onBounds,
                onStart = onDragStart,
                onDrag = onDrag,
                onEnd = onDragEnd,
            )
            .semantics { stateDescription = description }
            .zIndex(if (isHeld) 1f else 0f)
            .graphicsLayer {
                val scale = dragScale * pressScale
                scaleX = scale
                scaleY = scale
                translationX = settleX
                translationY = settleY
            }
            .alpha(if (isHeld) HELD_ALPHA else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .then(
                if (isDropTarget) {
                    Modifier.background(DroshPrimary.copy(alpha = 0.12f))
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
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Placeholder glyph. TODO: user-selectable session icons are a
            // future step; until then every card uses the same terminal mark.
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DroshSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DroshIcons.SquareTerminal,
                    contentDescription = null,
                    tint = DroshTextMuted,
                    modifier = Modifier.size(13.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = DroshText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Termux's second line: the OSC 0/2 title. Shown only when a user
                // rename makes it distinct from the name above, and never as an
                // empty line when the program set no title.
                if (!title.isNullOrBlank() && title != displayName) {
                    Text(
                        text = title,
                        color = DroshTextMuted,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Toggle chips stay, but they are not status/recency labels.
                if (isPinned || isPersistent) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (isPinned) {
                            SessionStateChip(
                                label = stringResource(R.string.session_screen_chip_pin),
                                tint = DroshPrimary,
                            )
                        }
                        if (isPersistent) {
                            SessionStateChip(
                                label = stringResource(R.string.session_screen_chip_persistent),
                                tint = DroshSuccess,
                            )
                        }
                    }
                }
            }
            SessionCardActionsButton(
                sessionName = displayName,
                isPinned = isPinned,
                isPersistent = isPersistent,
                onSplitWith = onSplitWith,
                onTogglePin = onTogglePin,
                onTogglePersistent = onTogglePersistent,
                onRename = onRename,
                onClose = onClose,
                onCloseAll = onCloseAll,
            )
        }

        // The real preview: keyed by session id, no alpha animation, no fake
        // content. It occupies most of the now-taller card.
        SessionThumbnail(
            sessionId = session.id,
            bitmap = thumbnail,
            contentDescription = stringResource(R.string.session_screen_thumbnail, displayName),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

/**
 * The stable terminal preview.
 *
 * A short gap in the thumbnail flow must not blank a card that already has a
 * frame, so the last good bitmap is remembered per session id. The composable
 * is keyed by that id too: a reordered grid slot never reuses another card's
 * image node, which is what made previews flicker and look soft.
 */
@Composable
internal fun SessionThumbnail(
    sessionId: String,
    bitmap: ImageBitmap?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val lastGood = remember(sessionId) { mutableStateOf<ImageBitmap?>(null) }
    if (bitmap != null) lastGood.value = bitmap
    val shown = bitmap ?: lastGood.value

    key(sessionId) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(PREVIEW_CORNER))
                .background(DroshSurface),
        ) {
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** A small chip for a toggle that is on. PIN / PERSISTENT, nothing else. */
@Composable
internal fun SessionStateChip(label: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
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

/** Taller than the old 200dp card so the terminal preview dominates the slot. */
private val CARD_HEIGHT = 300.dp

/** The preview sits only 5dp inside the card on its sides and bottom. */
private val CARD_SIDE_INSET = 5.dp
private val CARD_BOTTOM_INSET = 5.dp

/** A little more breathing room above the header inside the card. */
private val CARD_TOP_INSET = 12.dp

private val PREVIEW_CORNER = 9.dp

private const val HELD_SCALE = 1.04f
private const val HELD_ALPHA = 0.92f
private const val PRESS_SCALE = 0.98f

/** High stiffness + full damping: tracks the finger, then settles without a bounce. */
private const val SETTLE_STIFFNESS = 1100f
