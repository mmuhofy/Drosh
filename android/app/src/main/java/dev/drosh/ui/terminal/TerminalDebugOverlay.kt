package dev.drosh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.terminal.engine.render.FrameReport

/**
 * A corner of numbers describing the last frame.
 *
 * The terminal is drawn by a Rust engine through a GL surface, and every
 * symptom of it going wrong — a black pane, a missing row of colour, text that
 * stops updating — looks the same from outside: an empty rectangle. This is the
 * one place the frame says what it did: how long the build and the draw took,
 * how many cells carried a colour, how full the glyph atlas is.
 *
 * Toggled with Ctrl+Shift+D, and never focusable: it must not take a touch the
 * terminal underneath wanted.
 */
@Composable
fun TerminalDebugOverlay(
    report: FrameReport?,
    modifier: Modifier = Modifier,
) {
    if (report == null) {
        return
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // `String.format` reads a space between `%` and a conversion as a
        // flag, and two in a row is a duplicate — so the numbers are composed,
        // not formatted together.
        Line(
            "frame " + report.frames +
                "  " + one(report.frameMillis) + " ms" +
                "  build " + one(report.buildMillis) +
                "  draw " + one(report.drawMillis),
        )
        Line("grid ${report.columns}x${report.rows}  damage ${report.damage}")
        Line("glyphs ${report.glyphs}  coloured ${report.coloredCells}")
        Line(
            "atlas " + report.atlasPercent + "%  bg #" +
                (report.background and 0xffffff).toString(16).padStart(6, '0'),
        )
        if (report.backgroundProbe.isNotEmpty()) {
            Line(report.backgroundProbe)
        }
        if (report.samples.isNotEmpty()) {
            Line("bgs " + report.samples.joinToString("  "))
        }
        Line(report.gl)
    }
}

/** One decimal place, without `String.format`'s flag parsing. */
private fun one(value: Float): String {
    val tenths = (value * 10f).toInt()
    return (tenths / 10).toString() + "." + (tenths % 10)
}

@Composable
private fun Line(text: String) {
    Text(
        text = text,
        color = androidx.compose.ui.graphics.Color(0xFFB0FFB0),
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
}
