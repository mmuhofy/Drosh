package dev.drosh.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.drosh.domain.session.SessionState
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSuccess
import dev.drosh.theme.DroshSurfaceContainerLowest
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshWarning
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * One session, as a tall card with a small simulated terminal render.
 *
 * The render is not a screenshot: the terminal is a GL surface that may not be
 * on screen at all, so no pixels can be sampled from it. Short coloured lines
 * in a mono font are an honest stand-in — they say "this is a terminal" without
 * pretending to be this session's actual output.
 *
 * The drag is a long press followed by movement, reported in root coordinates
 * because the screen — not the card — decides what the finger is over. A drag
 * makes a UI group; splitting is only done from the ⋮ menu.
 */
@Composable
internal fun SessionCard(
    session: SessionSnapshot,
    displayName: String,
    isActive: Boolean,
    isBackground: Boolean,
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

    // Status pills were removed. The border says "active" and the left-hand
    // glyph identifies the session; nothing else is a status chip.
    val description = remember(isActive, isBackground, session.state, displayName) {
        buildString {
            append(displayName)
            append(", ")
            if (isActive) append("active, ")
            if (isBackground) append("running in background, ")
            append(stateLabel(session.state))
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
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    tint = stateColor(session.state),
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = displayName,
                color = DroshText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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

        // A rounded, pre-like box with short coloured lines: a small "render"
        // of a terminal rather than a transcript of the session's output.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .background(DroshSurfaceContainerLowest),
        ) {
            TerminalPreview(
                lines = previewLines(session),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            )
        }

        val meta = remember(session.lastUsedAtMs, title, session.state) {
            cardMeta(session, title)
        }
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

/** A short line of the simulated render, and the tone it is drawn in. */
private data class PreviewLineSpec(val text: String, val tone: PreviewTone)

private enum class PreviewTone { Prompt, Output, Success, Warning, Muted }

@Composable
private fun PreviewTone.color(): Color = when (this) {
    PreviewTone.Prompt -> DroshPrimary
    PreviewTone.Output -> DroshTextSecondary
    PreviewTone.Success -> DroshSuccess
    PreviewTone.Warning -> DroshWarning
    PreviewTone.Muted -> DroshTextMuted
}

/**
 * A fixed, deterministic render for a card. Running sessions show a build; the
 * rest show a shell prompt. It is decoration, not real session output.
 */
private fun previewLines(session: SessionSnapshot): List<PreviewLineSpec> =
    if (session.state == SessionState.Running) {
        listOf(
            PreviewLineSpec("\$ ${session.name.take(14)} --watch", PreviewTone.Prompt),
            PreviewLineSpec("resolving dependencies...", PreviewTone.Muted),
            PreviewLineSpec("[ok] core:compile", PreviewTone.Success),
            PreviewLineSpec("[ok] ui:compile", PreviewTone.Success),
            PreviewLineSpec("warn: 2 deprecated calls", PreviewTone.Warning),
            PreviewLineSpec("BUILD SUCCESSFUL in 8s", PreviewTone.Success),
            PreviewLineSpec("drosh@device:~$ ▌", PreviewTone.Prompt),
        )
    } else {
        listOf(
            PreviewLineSpec("drosh@device:~$ ls", PreviewTone.Prompt),
            PreviewLineSpec("build.gradle  README.md", PreviewTone.Output),
            PreviewLineSpec("drosh@device:~$ echo ready", PreviewTone.Prompt),
            PreviewLineSpec("ready", PreviewTone.Success),
            PreviewLineSpec("drosh@device:~$ ▌", PreviewTone.Prompt),
        )
    }

/**
 * The simulated render, drawn small rather than scaled: sizing the glyphs at
 * the size they are shown keeps the mono shapes readable.
 */
@Composable
private fun TerminalPreview(lines: List<PreviewLineSpec>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { line ->
            Text(
                text = line.text,
                color = line.tone.color(),
                fontFamily = LocalFontSet.current.mono,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** The card's under-render line: state in words, then recency. */
private fun cardMeta(session: SessionSnapshot, title: String?): String {
    val time = relativeTime(session.lastUsedAtMs, System.currentTimeMillis())
    return if (title.isNullOrBlank()) {
        "${stateLabel(session.state)} · $time"
    } else {
        "$title · ${stateLabel(session.state)} · $time"
    }
}

/**
 * Recency as a short human phrase, computed once per change of [thenMs] rather
 * than every time the grid recomposes for a search or a gesture.
 */
private fun relativeTime(thenMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1L -> "now"
        minutes < 60L -> "$minutes m ago"
        minutes < MINUTES_PER_DAY -> "${minutes / 60L} h ago"
        else -> "${minutes / MINUTES_PER_DAY} d ago"
    }
}

private fun stateLabel(state: SessionState): String = when (state) {
    SessionState.Running -> "Running"
    SessionState.Idle -> "Idle"
    SessionState.Closed -> "Closed"
}

@Composable
private fun stateColor(state: SessionState): Color = when (state) {
    SessionState.Running -> DroshSuccess
    SessionState.Closed -> DroshTextMuted.copy(alpha = 0.5f)
    SessionState.Idle -> DroshTextSecondary
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

/** Where "m" hands over to "h" and "h" to "d", in minutes. */
private const val MINUTES_PER_DAY = 60L * 24L

private const val HELD_SCALE = 1.04f
private const val HELD_ALPHA = 0.92f
private const val PRESS_SCALE = 0.98f
