package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.TerminalLine

/**
 * A read-only view of what the agent ran.
 *
 * Deliberately not a terminal: there is no prompt, no cursor, and nothing here
 * accepts input. It is a transcript of commands and their output, styled like a
 * terminal so it reads as one.
 *
 * The distinction is the point. A row that looks like an input and is not is
 * worse than no terminal at all — the user taps it, nothing happens, and the
 * surface stops being trustworthy. Everything here is text.
 */
@Composable
fun AgentTerminalPane(
    lines: List<TerminalLine>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items(lines.size) { index ->
            TerminalRow(line = lines[index])
        }
    }
}

@Composable
private fun TerminalRow(line: TerminalLine) {
    when (line) {
        is TerminalLine.Divider -> {
            Spacer(Modifier.height(10.dp))
        }

        is TerminalLine.Command -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A filled block, the way a shell renders a prompt, rather than a
                // character that would need escaping in every font.
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(DroshSuccess),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = line.text,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DroshSuccess,
                )
            }
        }

        is TerminalLine.Output -> {
            if (line.text.isEmpty()) return
            Text(
                text = line.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = DroshTextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 15.dp),
            )
        }

        is TerminalLine.Status -> {
            Spacer(Modifier.height(3.dp))
            Text(
                text = line.statusLabel(),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = if (line.failed) DroshError else DroshTextMuted,
                modifier = Modifier.padding(start = 15.dp),
            )
        }
    }
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
private fun TerminalLine.Status.statusLabel(): String {
    val base = when {
        failed && code < 0 -> "✕ iptal edildi"
        else -> "✓ exit $code"
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
            text = if (running) "çalışıyor…" else "henüz komut çalıştırılmadı",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = DroshTextMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (running) {
                "çıktı burada belirir"
            } else {
                "agent bir komut çalıştırdığında burada görünür"
            },
            fontSize = 11.sp,
            color = DroshOutline,
        )
    }
}
