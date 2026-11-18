package dev.drosh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.ui.DroshIcons

/**
 * The diagnostics eye, and the panel behind it.
 *
 * The terminal is drawn on its own EGL surface, so every way it can go wrong —
 * a black pane, a missing row, text that stops updating — looks the same from
 * outside. This is the one place the engine says what it did.
 *
 * The eye is always there, in the corner, and it is the only part that takes a
 * touch while the panel is closed: a terminal owns every gesture on its
 * surface, and a full-size invisible box here would swallow them. Open, the
 * panel is a scrollable column with the whole report, a copy button, and the
 * three controls of a benchmark run.
 */
@Composable
fun TerminalDebugOverlay(
    report: String,
    expanded: Boolean,
    recording: Boolean,
    status: String,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.84f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) DroshIcons.EyeOff else DroshIcons.Eye,
                    contentDescription = if (expanded) "hide diagnostics" else "show diagnostics",
                    tint = Color(0xFFB0FFB0),
                )
            }
            if (expanded) {
                Text(
                    text = if (recording) "diagnostics ● REC" else "diagnostics",
                    color = if (recording) Color(0xFFFF8080) else Color(0xFFB0FFB0),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = DroshIcons.Copy,
                        contentDescription = "copy diagnostics",
                        tint = Color(0xFFB0FFB0),
                    )
                }
            }
        }

        if (expanded) {
            // A run is explicit: REC starts a fresh log and zeroes the numbers,
            // STOP pauses the file, FIN closes it and says where it landed.
            Row(verticalAlignment = Alignment.CenterVertically) {
                BenchButton("REC", onRecord)
                BenchButton("STOP", onStop)
                BenchButton("FIN", onFinish)
            }
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    color = Color(0xFFFFD070),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            Text(
                text = if (report.isEmpty()) "collecting…" else report,
                color = Color(0xFFB0FFB0),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BenchButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = Color(0xFFB0FFB0),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
