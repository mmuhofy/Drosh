package dev.drosh.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
 * The drag is a long press followed by movement, reported in root coordinates
 * because the screen — not the card — decides what the finger is over. A drag
 * makes a UI group; splitting is only done from the ⋮ menu.
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
    pointer: Offset,
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

    // The card's origin in the window, captured at layout. The gesture reports
    // positions in this node's own space; the screen decides the drop target in
    // the window's. One offset is the whole translation between them.
    val origin = remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .onGloballyPositioned {
                origin.value = it.boundsInRoot().topLeft
                onBounds(it.boundsInRoot())
            }
            .semantics { stateDescription = description }
            .zIndex(if (isHeld) 1f else 0f)
            .graphicsLayer {
                val scale = if (isHeld) HELD_SCALE else pressScale
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (isHeld) HELD_ALPHA else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .then(
                if (isDropTarget) {
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
            // UNTESTED — verify before use: the drag detector is unchanged from
            // the original grid; grouping now consumes its drop instead of split.
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
            // Equal padding on every side; the header gets a little extra room
            // above it inside the card so it can breathe.
            .padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(top = HEADER_EXTRA_TOP),
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
                            StateChip(label = stringResource(R.string.session_screen_chip_pin), tint = DroshPrimary)
                        }
                        if (isPersistent) {
                            StateChip(
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

        // The real preview. The bitmap fills the whole area; until one exists
        // the neutral surface is the entire placeholder, with no fake content.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .background(DroshSurface),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** A small chip for a toggle that is on. PIN / PERSISTENT, nothing else. */
@Composable
private fun StateChip(label: String, tint: Color) {
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

/** A card is deliberately vertical: 200dp tall at the grid's two-column width. */
private val CARD_HEIGHT = 200.dp

/** Equal padding on all four sides of the card's content. */
private val CARD_PADDING = 12.dp

/** A little extra top room so the header is not crowded by the card's edge. */
private val HEADER_EXTRA_TOP = 2.dp

private const val HELD_SCALE = 1.04f
private const val HELD_ALPHA = 0.92f
private const val PRESS_SCALE = 0.98f
