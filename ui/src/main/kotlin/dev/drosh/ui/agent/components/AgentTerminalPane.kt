package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.LocalFontSet
import dev.drosh.domain.agent.TerminalLine
import dev.drosh.ui.R

/**
 * A read-only view of what the agent ran, as cards.
 *
 * Deliberately not a terminal: there is no prompt, no cursor, and nothing here
 * accepts input. It is a record of commands and their output, and the record is
 * one card per command — the command on its own line where it can be read
 * whole, the output under it, the status under that.
 *
 * ## Why cards and not a stream
 *
 * A stream of terminal lines reads as *a terminal*, and the first thing anyone
 * does with a terminal is try to type into it. A card per command reads as
 * *what happened*, which is what this screen is for. It is also the difference
 * between one command you can find again and a wall of text you scroll past.
 *
 * The command itself is the only accent on a card, because it is the answer to
 * "what did it just do" — the question this whole view exists for.
 */
@Composable
fun AgentTerminalPane(
    lines: List<TerminalLine>,
    modifier: Modifier = Modifier,
) {
    val commands = remember(lines) { lines.toCommandCards() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(commands, key = { it.id }) { card ->
            CommandCard(card = card)
        }
    }
}

/**
 * One command, its output, and its status — as one card.
 *
 * The output starts collapsed when it is long: ten lines of `ls` should not
 * push the next command off the screen. One line per command by default, and
 * a tap opens the rest.
 */
@Composable
private fun CommandCard(card: TerminalCommandCard) {
    var expanded by rememberSaveable(card.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceLow)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // ── the command ────────────────────────────────────────────────────
        // A filled block where a shell would put its prompt character, so the
        // line reads as "this was typed" without depending on a glyph every
        // font happens to carry.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (card.status?.failed == true) DroshError else DroshSuccess),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = card.command,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = LocalFontSet.current.mono,
                fontWeight = FontWeight.Medium,
                color = DroshText,
                maxLines = if (expanded) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // ── the output ─────────────────────────────────────────────────────
        // A resting card shows one line, which is enough to say "it ran and
        // said this". The rest is behind a tap: the log is a reference, not a
        // wall to scroll past.
        if (card.output.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            val shown = if (expanded) card.output else card.output.take(1)
            shown.forEach { line ->
                Text(
                    text = line,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = LocalFontSet.current.mono,
                    color = DroshTextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!expanded && card.output.size > 1) {
                Text(
                    text = stringResource(R.string.agent_terminal_more_lines, card.output.size - 1),
                    fontSize = 11.sp,
                    fontFamily = LocalFontSet.current.mono,
                    color = DroshTextMuted,
                )
            }
        }

        // ── the status ─────────────────────────────────────────────────────
        card.status?.let { status ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (status.failed) DroshError.copy(alpha = 0.14f) else DroshSurfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = status.label(),
                        fontSize = 10.5.sp,
                        fontFamily = LocalFontSet.current.mono,
                        color = if (status.failed) DroshError else DroshTextMuted,
                    )
                }
            }
        }
    }
}

/**
 * One card's worth of terminal: a command, the output it produced, and how it
 * ended. The projection emits lines; the view reads them as commands, because
 * a command is the unit anyone asks questions about.
 */
private data class TerminalCommandCard(
    val id: Int,
    val command: String,
    val output: List<String>,
    val status: TerminalLine.Status?,
)

/** Group a flat line list into command cards. */
private fun List<TerminalLine>.toCommandCards(): List<TerminalCommandCard> {
    val cards = mutableListOf<TerminalCommandCard>()
    var command: String? = null
    var output = mutableListOf<String>()
    var status: TerminalLine.Status? = null
    var id = 0

    fun flush() {
        command?.let { cmd ->
            cards += TerminalCommandCard(id++, cmd, output.toList(), status)
        }
        output = mutableListOf()
        status = null
    }

    forEach { line ->
        when (line) {
            is TerminalLine.Divider -> flush()
            is TerminalLine.Command -> {
                flush()
                command = line.text
            }
            is TerminalLine.Output -> if (line.text.isNotEmpty()) output += line.text
            is TerminalLine.Status -> status = line
        }
    }
    flush()
    return cards
}

/**
 * The status line, with the duration when there is one.
 *
 * A cancelled call has no exit code — it never exited — so it says so in words.
 * Printing "exit -1" for it would read as a shell that exited -1, which is not
 * what happened: the user declined, or the run was stopped.
 *
 * Duration is omitted rather than zeroed when unknown. "0.0s" on a command that
 * was killed mid-flight is a claim, and it is false.
 */
@Composable
private fun TerminalLine.Status.label(): String {
    val base = when {
        failed && code < 0 -> stringResource(R.string.agent_terminal_cancelled)
        else -> stringResource(R.string.agent_terminal_exit, code)
    }
    val duration = durationMs ?: return base
    return "$base · ${formatDuration(duration)}"
}

/** Sub-second runs get one decimal; anything longer gets whole seconds. */
private fun formatDuration(ms: Long): String =
    if (ms < 1000) "${(ms / 10.0).let { String.format("%.1f", it) }}s"
    else "${ms / 1000}s"

/** Shown when the agent has not run anything yet. */
@Composable
fun AgentTerminalEmpty(
    modifier: Modifier = Modifier,
    running: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (running) {
                stringResource(R.string.agent_no_commands_running)
            } else {
                stringResource(R.string.agent_no_commands)
            },
            fontSize = 12.sp,
            fontFamily = LocalFontSet.current.mono,
            color = DroshTextMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (running) {
                stringResource(R.string.agent_no_commands_output_soon)
            } else {
                stringResource(R.string.agent_no_commands_yet)
            },
            fontSize = 11.sp,
            color = DroshTextMuted,
        )
    }
}
