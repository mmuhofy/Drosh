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
import androidx.compose.ui.graphics.vector.ImageVector
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
 * Every card receives its own `thumbnails[session.id]`; the preview composable
 * is keyed by that id so a grid slot can never inherit a neighbour's bitmap.
 *
 * The drag is a still hold plus a movement threshold, reported in window
 * coordinates because the screen — not the card — decides what the finger is
 * over. A drag reorders within a section or adds to a group; splitting is only
 * done from the ⋮ menu.
 */
@Composable
internal fun SessionCard(
    session: SessionSnapshot,
    /** The program's OSC 0/2 title, or the user rename, or "shell". */
    displayName: String,
    /** The user rename when the title took the first line, or null. */
    subtitle: String?,
    /** The program's `OSC 1` icon hint, when it set one. */
    iconName: String? = null,
    /**
     * The brand slug of the program running in the foreground, when the
     * registry knows it (`python`, `vim`, `claude`…).
     *
     * This is the card's first choice of glyph: it comes from the kernel's own
     * answer about what is running, so it is right even for a program that
     * never set `OSC 0/1/2`. Null falls back to the title heuristics.
     */
    glyphSlug: String? = null,
    /** The last screenshot of this session's terminal, or null if none yet. */
    thumbnail: ImageBitmap?,
    isActive: Boolean,
    isPinned: Boolean,
    isPersistent: Boolean,
    isDropTarget: Boolean,
    isHeld: Boolean,
    /** Chosen as the second pane of a split while the selection mode is on. */
    isSelected: Boolean = false,
    onBounds: (Rect) -> Unit,
    onTap: () -> Unit,
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
        targetValue = if (isActive || isDropTarget || isSelected) 2f else 1f,
        animationSpec = tween(200),
        label = "cardBorder",
    )
    val borderColor = when {
        isDropTarget -> DroshPrimary
        isSelected -> DroshPrimary
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
    // identifies the session, and the accessibility text is just its name plus
    // the rename when one is visible.
    val description = remember(displayName, subtitle) {
        if (subtitle.isNullOrBlank() || subtitle == displayName) {
            displayName
        } else {
            "$displayName, $subtitle"
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
                // The ⋮ button is the menu door. A hold alone opens nothing;
                // holding and moving is what starts the drag in sessionDrag.
                onLongClick = { /* reserved */ },
            )
            .padding(
                start = CARD_SIDE_INSET,
                top = CARD_TOP_INSET,
                end = CARD_SIDE_INSET,
                bottom = CARD_BOTTOM_INSET,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The glyph is the program's, not the app's: an editor, an SSH
            // host and a shell are three different things, and a grid of six
            // identical terminal marks says nothing about any of them. The
            // hint comes from `OSC 1`, with the title as the fallback.
            val glyph = remember(iconName, displayName) { sessionGlyph(iconName, displayName) }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DroshSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (BrandIcons.drawable(glyphSlug) != null) {
                    BrandGlyph(slug = glyphSlug, tint = DroshTextMuted, sizeDp = 14)
                } else {
                    Icon(
                        imageVector = glyph,
                        contentDescription = null,
                        tint = DroshTextMuted,
                        modifier = Modifier.size(13.dp),
                    )
                }
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
                // Termux shows the program title as the session identity, so the
                // OSC title takes the first line. A user rename still lives in
                // session.name and is shown here instead of being lost, but only
                // when it is distinct from the title above — never an empty line.
                if (!subtitle.isNullOrBlank() && subtitle != displayName) {
                    Text(
                        text = subtitle,
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
                                icon = DroshIcons.Pin,
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
 * The remembered last-good frame lives INSIDE the id-keyed subgroup, so a grid
 * slot reused for another session can never inherit the previous card's bitmap.
 * A short gap in the thumbnail flow must not blank a card that already has a
 * frame; a card with no frame at all draws a neutral placeholder surface.
 */
@Composable
internal fun SessionThumbnail(
    sessionId: String,
    bitmap: ImageBitmap?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    key(sessionId) {
        SessionThumbnailContent(bitmap, contentDescription, modifier)
    }
}

@Composable
private fun SessionThumbnailContent(
    bitmap: ImageBitmap?,
    contentDescription: String,
    modifier: Modifier,
) {
    val lastGood = remember { mutableStateOf<ImageBitmap?>(null) }
    if (bitmap != null) lastGood.value = bitmap
    val shown = bitmap ?: lastGood.value

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PREVIEW_CORNER))
            .background(DroshSurface),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Neutral placeholder: a terminal mark on the surface, not a fake
            // transcript. Once a real frame arrives this is replaced in place.
            Icon(
                imageVector = DroshIcons.SquareTerminal,
                contentDescription = null,
                tint = DroshTextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(PREVIEW_PLACEHOLDER_ICON),
            )
        }
    }
}

/** A small chip for a toggle that is on. PIN / PERSISTENT, nothing else. */
@Composable
internal fun SessionStateChip(
    label: String,
    tint: Color,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(CHIP_ICON_SIZE),
            )
        }
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

/**
 * 336dp leaves ~260dp for the preview after the 12dp top inset, 48dp header,
 * 8dp gap and 5dp bottom inset. Taller than the old 200dp card so the real
 * terminal capture dominates the slot.
 */
private val CARD_HEIGHT = 336.dp

/** The preview sits only 5dp inside the card on its sides and bottom. */
private val CARD_SIDE_INSET = 8.dp
private val CARD_BOTTOM_INSET = 8.dp

/** A little more breathing room above the header inside the card. */
private val CARD_TOP_INSET = 12.dp

private val PREVIEW_CORNER = 10.dp
private val PREVIEW_PLACEHOLDER_ICON = 28.dp
private val CHIP_ICON_SIZE = 9.dp

private const val HELD_SCALE = 1.04f
private const val HELD_ALPHA = 0.92f
private const val PRESS_SCALE = 0.98f

/** High stiffness + full damping: tracks the finger, then settles without a bounce. */
private const val SETTLE_STIFFNESS = 1100f

/**
 * The glyph a session's card wears.
 *
 * The program's `OSC 1` hint is the first answer, because that is exactly what
 * the sequence is for; the title is the second, because most programs set only
 * `OSC 0/2` (`vim` puts the file name there, `ssh` puts `user@host`). Both are
 * matched loosely — the hint is a file name or a command, the title is prose —
 * and anything unrecognised keeps the terminal mark rather than inventing a
 * picture.
 */
private fun sessionGlyph(iconName: String?, title: String?): ImageVector {
    // Whole words, not substrings: `vi` inside `virtual` is not a request for
    // the editor's glyph, and a title is usually a whole sentence.
    val words = "${iconName.orEmpty()} ${title.orEmpty()}"
        .lowercase()
        .split(NON_WORD)
        .filter { it.isNotEmpty() }
        .toSet()
    fun anyOf(vararg candidates: String) = candidates.any { it in words }
    return when {
        anyOf("vim", "nvim", "vi", "nano", "emacs", "code", "edit", "micro") ->
            DroshIcons.Code
        anyOf("ssh", "scp", "sftp", "rsync", "mosh") -> DroshIcons.Globe
        anyOf("htop", "top", "btop", "ps", "monitor") -> DroshIcons.Gauge
        anyOf("git", "make", "cargo", "gradle", "build", "npm", "pnpm") ->
            DroshIcons.Package
        anyOf("root", "sudo") -> DroshIcons.Shield
        else -> DroshIcons.SquareTerminal
    }
}

/** What separates the words of a title: whitespace and punctuation. */
private val NON_WORD = Regex("[^a-z0-9]+")
