package dev.drosh.ui.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshTilePressed
import dev.drosh.design.system.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.session.SessionLogSource
import dev.drosh.ui.session.SessionLogTarget

/**
 * Which session, then which lines, then it is done.
 *
 * Three stages on one sheet rather than three screens, because the log can be
 * thousands of lines and the session list a dozen: a sheet per stage puts the
 * line list half off the screen and makes the user dismiss things to get back
 * to the composer they came from.
 *
 * One session in the list skips the first stage. With nothing to choose
 * between, a list of one is a step that teaches nothing.
 */
enum class AgentLogPickStage { Sessions, Lines, Added }

/**
 * A piece of a terminal session, chosen from the composer's `+`.
 *
 * ## Why this is behind the `+` and nowhere else
 *
 * The log is read into the prompt the user is about to send, so it belongs
 * where that prompt is written. It used to be reachable from the terminal's
 * sidebar as well, which meant the same text could arrive at the agent by two
 * routes with two designs. This is the one way in: a session is picked from the
 * running sessions, its lines are chosen, and they become an attachment.
 *
 * Nothing here touches the agent's terminal pane. The chat's own shell output
 * is a different thing entirely — it belongs to the transcript, and it already
 * lives in the conversation.
 *
 * ## Why lines and not characters
 *
 * A terminal log is a grid of lines and a selection is a range of them. Two
 * reasons, and the second is the one that matters: a line range is two
 * numbers — "lines 40 to 96" — which is what the agent is told, while a
 * character range inside a soft-wrapped line describes a position that means
 * nothing once the text is out of the grid. And a line is the unit the user is
 * pointing at when they say "that bit".
 *
 * Three ways to set it, because a log is long and a finger is imprecise: tap
 * to start and tap again to extend, press and drag to sweep a screenful, or
 * take the lot.
 */
@Composable
fun AgentSessionLogPicker(
    sessions: List<SessionLogTarget>,
    source: SessionLogSource?,
    activeSessionId: String?,
    onAdd: (ChatAttachment) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Per-opening state throughout. The caller only composes this while it is
    // open, so a plain `remember` is what puts everything back where it was:
    // the last session's lines and half-made selection do not wait behind the
    // next opening.
    var stage by remember { mutableStateOf(AgentLogPickStage.Sessions) }
    var chosen by remember {
        mutableStateOf(
            sessions.firstOrNull { it.id == activeSessionId }?.id
                ?: sessions.singleOrNull()?.id,
        )
    }
    var added by remember { mutableStateOf<ChatAttachment.LogLines?>(null) }

    // One session is not a choice, so it is not offered as one.
    val canGoBack = sessions.size > 1
    BackHandler(enabled = stage == AgentLogPickStage.Lines && canGoBack) {
        stage = AgentLogPickStage.Sessions
    }

    when (stage) {
        AgentLogPickStage.Sessions -> SessionListStage(
            sessions = sessions,
            activeSessionId = activeSessionId,
            onPick = { id ->
                chosen = id
                stage = AgentLogPickStage.Lines
            },
            onDismiss = onDismiss,
            modifier = modifier,
        )

        AgentLogPickStage.Lines -> {
            val target = sessions.firstOrNull { it.id == chosen }
            if (target == null) {
                // The session went away while the sheet was open. There is
                // nothing to show and nowhere to go back to, so the sheet
                // closes rather than sitting on a log that is no longer there.
                onDismiss()
            } else {
                LinePickerStage(
                    target = target,
                    source = source,
                    onBack = if (canGoBack) ({ stage = AgentLogPickStage.Sessions }) else null,
                    onDismiss = onDismiss,
                    onAdded = { pick ->
                        added = pick
                        onAdd(pick)
                        stage = AgentLogPickStage.Added
                    },
                    modifier = modifier,
                )
            }
        }

        AgentLogPickStage.Added -> AddedStage(
            added = added,
            onDismiss = onDismiss,
            modifier = modifier,
        )
    }
}

// ── stage one: which session ────────────────────────────────────────────────

@Composable
private fun SessionListStage(
    sessions: List<SessionLogTarget>,
    activeSessionId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AgentSheet(
        modifier = modifier,
        title = stringResource(R.string.agent_logpick_title),
        subtitle = stringResource(R.string.agent_logpick_body),
        icon = DroshIcons.Terminal,
        heightFraction = 0.6f,
        onDismiss = onDismiss,
    ) {
        if (sessions.isEmpty()) {
            SheetEmptyNote(
                text = stringResource(R.string.agent_logpick_no_sessions),
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                sessions.forEach { session ->
                    SheetOptionRow(
                        title = session.name,
                        subtitle = if (session.id == activeSessionId) {
                            stringResource(R.string.agent_logpick_active)
                        } else {
                            null
                        },
                        icon = DroshIcons.SquareTerminal,
                        selected = session.id == activeSessionId,
                        onClick = { onPick(session.id) },
                    )
                }
            }
        }
    }
}

// ── stage two: which lines ──────────────────────────────────────────────────

@Composable
private fun LinePickerStage(
    target: SessionLogTarget,
    source: SessionLogSource?,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
    onAdded: (ChatAttachment.LogLines) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read once per session rather than per recomposition: this walks the
    // terminal buffer, and the sheet recomposes on every selection change.
    val lines = remember(target.id, source) { source?.lines(target.id).orEmpty() }

    var anchor by remember(target.id) { mutableIntStateOf(-1) }
    var focus by remember(target.id) { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    val first = if (anchor < 0) -1 else minOf(anchor, focus)
    val last = if (anchor < 0) -1 else maxOf(anchor, focus)
    val hasSelection = first >= 0

    /**
     * A tap, given the states a selection can be in.
     *
     * With nothing selected it starts one. With one, it moves whichever end is
     * nearer — the nearer end is the end the user means, and asking them to
     * aim at one specific handle to grow past the selection would be a rule to
     * remember for no gain.
     */
    fun tapOn(index: Int) {
        if (anchor < 0) {
            anchor = index
            focus = index
            return
        }
        if (index < minOf(anchor, focus)) anchor = index else focus = index
    }

    AgentSheet(
        modifier = modifier,
        title = target.name,
        subtitle = if (lines.isEmpty()) {
            null
        } else {
            stringResource(R.string.agent_logpick_lines, lines.size)
        },
        icon = DroshIcons.SquareTerminal,
        heightFraction = 0.9f,
        onBack = onBack,
        onDismiss = onDismiss,
    ) {
        if (lines.isEmpty()) {
            SheetEmptyNote(
                text = stringResource(R.string.agent_logpick_empty),
                modifier = Modifier.weight(1f),
            )
        } else {
            LogGrid(
                lines = lines,
                listState = listState,
                first = first,
                last = last,
                onTap = ::tapOn,
                onDragStart = { index ->
                    anchor = index
                    focus = index
                },
                onDrag = { offset ->
                    // Resolved against the laid-out items rather than counted
                    // from the top: a LazyColumn recycles, so arithmetic from
                    // the first row is wrong the moment the user has scrolled.
                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                        offset.y >= it.offset && offset.y < it.offset + it.size
                    }
                    if (item != null) focus = item.index.coerceIn(0, lines.lastIndex)
                },
                modifier = Modifier.weight(1f),
            )

            Text(
                text = if (hasSelection) {
                    stringResource(R.string.agent_logpick_selected, last - first + 1)
                } else {
                    stringResource(R.string.agent_logpick_hint)
                },
                color = DroshTextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 10.dp, bottom = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetPillButton(
                    label = stringResource(R.string.agent_logpick_select_all),
                    enabled = !(hasSelection && first == 0 && last == lines.lastIndex),
                    onClick = {
                        anchor = 0
                        focus = lines.lastIndex
                    },
                    modifier = Modifier.weight(1f),
                )
                SheetPillButton(
                    label = stringResource(R.string.agent_logpick_clear),
                    enabled = hasSelection,
                    onClick = {
                        anchor = -1
                        focus = -1
                    },
                    modifier = Modifier.weight(1f),
                )
                SheetPillButton(
                    label = stringResource(R.string.agent_logpick_add),
                    enabled = hasSelection,
                    primary = true,
                    onClick = {
                        onAdded(
                            ChatAttachment.LogLines(
                                sessionName = target.name,
                                firstLine = first + 1,
                                lastLine = last + 1,
                                text = lines.subList(first, last + 1).joinToString("\n"),
                            ),
                        )
                    },
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

// ── stage three: it is in the prompt ────────────────────────────────────────

@Composable
private fun AddedStage(
    added: ChatAttachment.LogLines?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AgentSheet(
        modifier = modifier,
        title = stringResource(R.string.agent_logpick_added_title),
        subtitle = added?.sessionName,
        icon = DroshIcons.Terminal,
        heightFraction = 0.45f,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = DroshIcons.Check,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(
                    R.string.agent_logpick_added_body,
                    added?.lineCount ?: 0,
                    added?.sessionName.orEmpty(),
                ),
                color = DroshTextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── the grid ────────────────────────────────────────────────────────────────

/**
 * The scrollback, as a grid.
 *
 * Every line gets the same width — the width of the widest one — rather than
 * wrapping, because a wrapped terminal line is no longer the line the log has,
 * and the alignment it carried is the first thing a wrap destroys. So the list
 * is measured once, given a fixed width, and put under a single horizontal
 * scroll; one scroll state shared by every line is what keeps the grid a grid.
 */
@Composable
private fun LogGrid(
    lines: List<String>,
    listState: LazyListState,
    first: Int,
    last: Int,
    onTap: (Int) -> Unit,
    onDragStart: (Int) -> Unit,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(
        fontFamily = LocalFontSet.current.mono,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val contentWidth = remember(lines, style, density) {
        val columns = lines.maxOf { it.length }
        // Measured rather than assumed: the advance width of the app monospace
        // at this size is not a number anyone can state, and everything else
        // here depends on every line sharing one.
        val advance = measurer.measure(SAMPLE_RUN, style).size.width / SAMPLE_RUN.length
        with(density) { (advance * columns + 16.dp.toPx()).toDp() }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .width(contentWidth)
                .fillMaxHeight(),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val selected = index in first..last
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(
                            if (selected) DroshPrimary.copy(alpha = 0.16f) else Color.Transparent,
                        )
                        .pointerInput(index) {
                            detectTapGestures(onTap = { onTap(index) })
                        }
                        .pointerInput(index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart(index) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    onDrag(change.position)
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The two ends of the range, marked the way a text
                    // selection is marked. The wash of highlighted rows says
                    // what is selected; this says where it begins and stops,
                    // which a flat wash alone does not.
                    if (index == first || index == last) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(DroshPrimary),
                        )
                    }
                    Text(
                        text = line.ifBlank { " " },
                        style = style,
                        color = if (selected) DroshText else DroshTextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** A run of digits, to measure a monospace advance width from. */
private const val SAMPLE_RUN = "0123456789012345678901234567890123456789"

/** A flat action under the sheet's own body. The primary one is a fill. */
@Composable
private fun SheetPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    // Dimmed rather than absent: a control the user has not earned yet still
    // has to be visible, or the row of three looks broken.
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(if (primary) DroshPrimary else DroshTilePressed)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> DroshTextMuted
                primary -> DroshOnPrimary
                else -> DroshText
            }.copy(alpha = alpha),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
