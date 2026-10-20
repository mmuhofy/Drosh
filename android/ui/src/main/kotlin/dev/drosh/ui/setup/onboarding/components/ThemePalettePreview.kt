package dev.drosh.ui.setup.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.settings.ThemePalette

/**
 * What a palette looks like before you commit to it.
 *
 * Nine colours, because nine is what [ThemePalette.logoSwatchCount] says a
 * palette is judged by: the background edge, then the eight ANSI ones. A grid
 * of nine equal cells rather than the store's single band of colour, because a
 * band reads as "dark theme" from across the room and a grid reads as *which*
 * dark theme — and at card size that difference is the only thing telling two
 * neighbours apart.
 *
 * Every swatch is drawn, including the two a card is too small to tell apart.
 * The point is the shape of the whole palette, not a legend.
 */
@Composable
fun ThemePalettePreview(
    palette: ThemePalette,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    /** Drawn as the border; [DroshOutline] when null. */
    borderColor: Color? = null,
) {
    val swatches = remember(palette) {
        listOf(
            palette.background,
            palette.black,
            palette.red,
            palette.green,
            palette.yellow,
            palette.blue,
            palette.magenta,
            palette.cyan,
            palette.white,
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .border(1.dp, borderColor ?: DroshOutline, RoundedCornerShape(cornerRadius))
            .background(previewColor(palette.background)),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        swatches.chunked(GRID_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                row.forEach { hex ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(previewColor(hex)),
                    )
                }
                // Padding a short row rather than dropping it: nine swatches in
                // rows of three is a fixed shape, and a card whose last row is
                // narrower than the first is a card that looks broken.
                repeat(GRID_COLUMNS - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * A palette drawn as the terminal will use it: the background, with a prompt in
 * the foreground and the results in the ANSI colours.
 *
 * A second preview rather than a variation of [ThemePalettePreview], because
 * the two answer different questions. The grid says *which* palette; this says
 * what a prompt in it will look like, which is the thing actually being chosen —
 * a theme is judged on its foreground against its background long before it is
 * judged on its cyan.
 *
 * Real text in the mono face, at the size the preview is drawn: a picture of a
 * terminal made of rectangles reads as a loading skeleton, and the one thing
 * this preview exists to convey is legibility.
 */
@Composable
fun ThemeTerminalPreview(
    palette: ThemePalette,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    borderColor: Color? = null,
) {
    val lines = remember(palette) {
        listOf(
            PreviewLine("drosh --version", palette.foreground, palette.green, prompt = "\$ "),
            PreviewLine("v1.0.0", palette.cyan, palette.green),
            PreviewLine("4 files, 128K", palette.brightBlack, palette.green),
            PreviewLine("ok", palette.brightGreen, palette.green),
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .border(1.dp, borderColor ?: DroshOutline, RoundedCornerShape(cornerRadius))
            .background(previewColor(palette.background))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (line.prompt != null) {
                    Text(
                        text = line.prompt,
                        color = previewColor(line.promptColor),
                        fontFamily = LocalFontSet.current.mono,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    text = line.text,
                    color = previewColor(line.color),
                    fontFamily = LocalFontSet.current.mono,
                    fontSize = 9.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private data class PreviewLine(
    val text: String,
    val color: String,
    val promptColor: String,
    val prompt: String? = null,
)

private const val GRID_COLUMNS = 3