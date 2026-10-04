package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary

/**
 * Renders unified diff text.
 *
 * Parsed here rather than handed to the widget as text: colour on a diff is the
 * only fast way to read one, and a diff shown as grey monospace forces the user to
 * work out what changed line by line.
 *
 * Colour is never the only cue — every line keeps its `+`/`-`/` ` marker, so the
 * diff is readable without colour vision and survives being read aloud.
 */
@Composable
fun DiffBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 260.dp,
) {
    val lines = remember(text) { text.lines() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DroshSurfaceVariant),
    ) {
        Column(modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            lines.forEachIndexed { index, line ->
                DiffLine(line = line, index = index)
            }
        }
    }
}

@Composable
private fun DiffLine(line: String, index: Int) {
    val (tint, background) = when {
        line.startsWith("+") -> DroshSuccess to ADD_BACKGROUND
        line.startsWith("-") -> DroshError to REMOVE_BACKGROUND
        line.startsWith("@@") -> DroshTextMuted to HUNK_BACKGROUND
        line.startsWith("---") || line.startsWith("+++") -> DroshTextSecondary to null
        else -> DroshTextSecondary to null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background ?: Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 1.dp),
    ) {
        Spacer(Modifier.width(4.dp))
        Text(
            text = line.ifEmpty { " " },
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = tint,
        )
        // Keeps the block from collapsing when every line is short.
        if (index == 0) Spacer(Modifier.height(0.dp))
    }
}

// Tinted rather than solid: a solid red or green block behind monospace text
// fails contrast at this size, and a diff is mostly context lines.
private val ADD_BACKGROUND = Color(0x143DD68C)
private val REMOVE_BACKGROUND = Color(0x14F2555A)
private val HUNK_BACKGROUND = Color(0x0A000000)
