package dev.drosh.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.AgentLogAttach
import dev.drosh.ui.agent.components.rememberAgentClipboard
import kotlin.math.roundToInt

/**
 * Pick a session, pick a piece of its output, and hand that piece to the agent.
 *
 * ## Three stages, one sheet
 *
 * Which session, which lines, then a confirmation — one surface rather than three
 * screens. A log can be thousands of lines and the session list a dozen, so each
 * of these wants a sheet; a sheet on top of a sheet puts the line list half off
 * the screen and makes the user dismiss things to get back to the terminal they
 * came from.
 *
 * One session skips the first stage. With nothing to choose between, a list of one
 * is a step that teaches nothing.
 *
 * ## Lines, not characters
 *
 * A terminal log is a grid of lines and the selection is a range of them. Two
 * reasons, and the second is the one that matters: a line range can be described
 * by two numbers — "lines 40 to 96" — which is what the agent is told, while a
 * character range inside a soft-wrapped line describes a position that means
 * nothing once the text is out of the grid. And a line is the unit the user is
 * pointing at when they say "that bit".
 *
 * Three ways to set it, because a log is long and a finger is imprecise: tap to
 * start and tap again to extend, press and drag to sweep a screenful, or take
 * the lot.
 *
 * ## Where the text goes
 *
 * Both places it can be reached from, on purpose. The clipboard is what the user
 * can paste anywhere, including the agent composer by hand;
 * [AgentLogAttach] is the automatic half, for a chat screen that has not been wired
 * to it yet. Neither is a substitute for the other, so neither is the only one.
 */
@Composable
fun SessionLogAttachSheet(
    visible: Boolean,
    sessions: List<SessionLogTarget>,
    source: SessionLogSource?,
    activeSessionId: String?,
    canGoBack: Boolean,
    onDismiss: () -> Unit,
    onOpenAgent: () -> Unit,
) {
    if (!visible) return

    // Everything below is per-opening state. `visible` toggling is what puts it
    // back to where it was, so keying on it is what stops the last session's lines
    // and half-made selection from waiting behind the next opening.
    var stage by remember(visible) {
        mutableStateOf(if (sessions.size == 1) Stage.Log else Stage.Pick)
    }
    var chosen by remember(visible) {
        mutableStateOf(
            sessions.firstOrNull { it.id == activeSessionId }?.id
                ?: sessions.singleOrNull()?.id,
        )
    }

    when (stage) {
        Stage.Pick -> SessionPickStage(
            sessions = sessions,
            onPick = { id ->
                chosen = id
                stage = Stage.Log
            },
            onDismiss = onDismiss,
        )

        Stage.Log -> {
            val target = sessions.firstOrNull { it.id == chosen }
            if (target == null) {
                // The session went away while the sheet was open. There is
                // nothing to show and nowhere to go back to, so the sheet closes
                // rather than sitting on an empty log.
                onDismiss()
            } else {
                SessionLogStage(
                    target = target,
                    source = source,
                    onBack = if (canGoBack) ({ stage = Stage.Pick }) else null,
                    onDismiss = onDismiss,
                    onAdded = { stage = Stage.Added },
                )
            }
        }

        Stage.Added -> AddedStage(
            sessionName = sessions.firstOrNull { it.id == chosen }?.name.orEmpty(),
            onOpenAgent = {
                onDismiss()
                onOpenAgent()
            },
            onDismiss = onDismiss,
        )
    }
}

private enum class Stage { Pick, Log, Added }

// ── the three stages ─────────────────────────────────────────────────────────

@Composable
private fun SessionPickStage(
    sessions: List<SessionLogTarget>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(
        title = stringResource(R.string.session_log_pick_title),
        subtitle = stringResource(R.string.session_log_pick_body),
        onDismiss = onDismiss,
    ) {
        if (sessions.isEmpty()) {
            EmptyNote(
                text = stringResource(R.string.session_log_no_sessions),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sessions, key = { it.id }) { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onPick(session.id) },
                            )
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = DroshIcons.Terminal,
                            contentDescription = null,
                            tint = DroshTextMuted,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = session.name,
                            color = DroshText,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = DroshIcons.ChevronRight,
                            contentDescription = null,
                            tint = DroshTextMuted,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionLogStage(
    target: SessionLogTarget,
    source: SessionLogSource?,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    // Read once per opening rather than per recomposition: this walks the
    // terminal buffer, and the sheet recomposes on every selection change.
    val lines = remember(target.id, source) { source?.lines(target.id).orEmpty() }

    var anchor by remember(target.id) { mutableIntStateOf(-1) }
    var focus by remember(target.id) { mutableIntStateOf(-1) }
    val clipboard = rememberAgentClipboard()
    val listState = rememberLazyListState()

    val first = if (anchor < 0) -1 else minOf(anchor, focus)
    val last = if (anchor < 0) -1 else maxOf(anchor, focus)
    val hasSelection = first >= 0

    /**
     * A tap, given the states a selection can be in.
     *
     * With nothing selected it starts one. With one, it moves whichever end is
     * nearer — the nearer end is the end the user means, and asking them to aim
     * at one specific handle to grow past the selection would be a rule to
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

    SheetScaffold(
        title = target.name,
        subtitle = if (lines.isEmpty()) {
            null
        } else {
            stringResource(R.string.session_log_line_count, lines.size)
        },
        onDismiss = onDismiss,
        onBack = onBack,
    ) {
        if (lines.isEmpty()) {
            EmptyNote(
                text = stringResource(R.string.session_log_empty),
                modifier = Modifier.weight(1f),
            )
        } else {
            LogBody(
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
                    // from the top: a LazyColumn recycles, so arithmetic from the
                    // first row is wrong the moment the user has scrolled.
                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                        offset.y >= it.offset && offset.y < it.offset + it.size
                    }
                    if (item != null) focus = item.index.coerceIn(0, lines.lastIndex)
                },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = if (hasSelection) {
                stringResource(R.string.session_log_selected, last - first + 1)
            } else {
                stringResource(R.string.session_log_hint)
            },
            color = DroshTextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp, bottom = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                label = stringResource(R.string.session_log_select_all),
                enabled = !(hasSelection && first == 0 && last == lines.lastIndex),
                onClick = {
                    anchor = 0
                    focus = lines.lastIndex
                },
                modifier = Modifier.weight(1f),
            )
            PillButton(
                label = stringResource(R.string.session_log_clear),
                enabled = hasSelection,
                onClick = {
                    anchor = -1
                    focus = -1
                },
                modifier = Modifier.weight(1f),
            )
            PillButton(
                label = stringResource(R.string.session_log_add_to_agent),
                enabled = hasSelection,
                primary = true,
                onClick = {
                    val body = lines.subList(first, last + 1).joinToString("\n")
                    clipboard(body)
                    AgentLogAttach.attach(
                        sessionName = target.name,
                        firstLine = first + 1,
                        lastLine = last + 1,
                        text = AgentLogAttach.format(target.name, first + 1, last + 1, body),
                    )
                    onAdded()
                },
                modifier = Modifier.weight(1.5f),
            )
        }
    }
}

/**
 * The scrollback, as a grid.
 *
 * Every line gets the same width — the width of the widest one — rather than
 * wrapping, because a wrapped terminal line is no longer the line the log has,
 * and the alignment it carried is the first thing a wrap destroys. So the list is
 * measured once, given a fixed width, and put under a single horizontal scroll;
 * one scroll state shared by every line is what keeps the grid a grid.
 */
@Composable
private fun LogBody(
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
        // Measured rather than assumed: the advance width of the app monospace at
        // this size is not a number anyone can state, and everything else here
        // depends on every line sharing one.
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
                        .background(if (selected) DroshPrimary.copy(alpha = 0.16f) else Color.Transparent)
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
                    // The two ends of the range, marked the way a text selection
                    // is marked. The wash of highlighted rows says what is
                    // selected; this says where it begins and stops, which a flat
                    // wash alone does not.
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

@Composable
private fun AddedStage(
    sessionName: String,
    onOpenAgent: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(
        title = stringResource(R.string.session_log_added_title),
        subtitle = null,
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
                text = stringResource(R.string.session_log_added_body, sessionName),
                color = DroshTextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.session_log_added_clipboard),
                color = DroshTextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(
                label = stringResource(R.string.action_close),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                label = stringResource(R.string.session_log_continue),
                primary = true,
                onClick = onOpenAgent,
                modifier = Modifier.weight(1.5f),
            )
        }
    }
}

// ── chrome ───────────────────────────────────────────────────────────────────

/**
 * The sheet itself: a scrim, a draggable panel, and a title row.
 *
 * Drags down to dismiss rather than offering only a close button, because the
 * panel covers most of the screen and the button is in the corner it covers. Same
 * shape as the tool detail sheet, for the same reasons.
 *
 * The drag modifier sits on the whole panel, which is why a drag that starts on
 * the log scrolls the log: a child gets the pointer event first, so the list wins
 * its own gesture and the sheet is dragged from the title row. That is the right
 * way round for a panel whose content is a long scroller.
 */
@Composable
private fun SheetScaffold(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack ?: onDismiss)
    val density = LocalDensity.current
    val dismissAt = with(density) { 160.dp.toPx() }
    var dragDown by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DroshSurfaceHigh.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(top = 64.dp)
                .offset { IntOffset(0, dragDown) }
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(DroshSurfaceHigh)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragDown > dismissAt) onDismiss() else dragDown = 0 },
                        onDragCancel = { dragDown = 0 },
                    ) { _, delta ->
                        // Downwards only. Upward, there is the log being scrolled,
                        // and a panel that rises when the user reaches for the top
                        // of a long list is a panel fighting the list.
                        if (delta > 0f) dragDown += delta.roundToInt()
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    RoundIconButton(
                        icon = DroshIcons.ArrowLeft,
                        contentDescription = stringResource(R.string.action_back),
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = DroshText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            color = DroshTextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                RoundIconButton(
                    icon = DroshIcons.X,
                    contentDescription = stringResource(R.string.action_close),
                    onClick = onDismiss,
                )
            }
            content()
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DroshSurfaceVariant.copy(alpha = 0.75f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DroshTextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A flat action. The primary one is a fill, the rest are the surface. */
@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(if (primary) DroshPrimary else DroshSurfaceVariant)
            // Dimmed rather than absent: a control the user has not earned yet
            // still has to be visible, or the row of three looks broken.
            .alpha(if (enabled) 1f else 0.4f)
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
            color = if (primary) DroshOnPrimary else DroshText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = DroshTextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** A run of digits, to measure a monospace advance width from. */
private const val SAMPLE_RUN = "0123456789012345678901234567890123456789"