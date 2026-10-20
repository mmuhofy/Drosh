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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshSuccess
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet

/**
 * Renders unified diff text, with the new lines syntax highlighted.
 *
 * Parsed here rather than handed to the widget as text: colour on a diff is the
 * only fast way to read one, and a diff shown as grey monospace forces the user to
 * work out what changed line by line.
 *
 * Colour is never the only cue — every line keeps its `+`/`-`/` ` marker, so the
 * diff is readable without colour vision and survives being read aloud. That is why
 * the marker is drawn rather than consumed: a highlighted line still begins with
 * the character that says it was added.
 *
 * ## Why only the added lines
 *
 * A removed line is a line that is about to stop existing. Reading it is reading
 * the file as it was, and colouring that does not help anyone decide whether the
 * replacement is right — the answer is in the `+` side. Highlighting both would
 * also put two palettes' worth of hue in one block, which is what makes a diff
 * hard to read rather than easy.
 *
 * ## The language comes from the diff's own header
 *
 * `+++ b/src/Main.kt` names the file, and the extension names the language. No
 * argument is added to this composable for it: the caller is a tool card that
 * has a diff string and nothing else, and threading a language through it would
 * mean every call site repeating what the diff already says. A diff without a
 * header, or with an extension nothing knows, stays grey — which is honest, and
 * the same answer [SyntaxLanguages] gives a code block with an unknown fence.
 */
@Composable
fun DiffBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 260.dp,
) {
    val lines = remember(text) { text.lines() }
    val palette = CodeColors
    val language = remember(text) { DiffLanguage.of(text) }
    // Null for a diff whose language is unknown, and the line then renders the
    // way it always did.
    val highlighter = remember(language, palette) { language?.let { SyntaxHighlighter(it, palette) } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DroshSurfaceVariant),
    ) {
        Column(modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            lines.forEachIndexed { index, line ->
                DiffLine(line = line, index = index, highlighter = highlighter)
            }
        }
    }
}

@Composable
private fun DiffLine(line: String, index: Int, highlighter: SyntaxHighlighter?) {
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
        // The gutter column. Present so a line of text starts where the next one
        // does, which is what makes the markers read as a column.
        Spacer(Modifier.width(4.dp))
        Text(
            text = highlightedDiffLine(line, tint, highlighter),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = LocalFontSet.current.mono,
            // The base colour. A token the highlighter recognised paints over it;
            // plain code inherits it, which is what keeps the added-line tint on
            // the words that carry no syntax of their own.
            color = tint,
        )
        // Keeps the block from collapsing when every line is short.
        if (index == 0) Spacer(Modifier.height(0.dp))
    }
}

/**
 * The line's text, with the added part tokenised and the marker left where it was.
 *
 * The `+++` header is not an addition — it is the diff saying which file this is —
 * so it is excluded, along with the file labels it carries. Without that exclusion
 * the header itself would be highlighted as the path it names.
 *
 * Each line is highlighted on its own. A string or a comment opened on an earlier
 * line has no terminator here, and that is not a failure: the scanner degrades to
 * colouring what it recognised, which is exactly the behaviour a half-streamed
 * diff wants.
 */
private fun highlightedDiffLine(
    line: String,
    tint: Color,
    highlighter: SyntaxHighlighter?,
): AnnotatedString {
    if (highlighter == null || !line.startsWith("+") || line.startsWith("+++")) {
        return AnnotatedString(line.ifEmpty { " " })
    }
    val marker = line.take(1)
    val source = line.substring(1)
    return buildAnnotatedString {
        pushStyle(SpanStyle(color = tint))
        append(marker)
        pop()
        append(highlighter.highlight(source))
    }
}

/**
 * The language a diff is written in, taken from the file it names.
 *
 * The header rather than the content, and `+++` rather than `---`: for a file that
 * was deleted the two are the same path, and for a new file only `+++` exists.
 * The `b/` and `a/` prefixes are the diff convention's own and are dropped before
 * the extension is read.
 */
internal object DiffLanguage {

    private const val NEW_HEADER = "+++ "
    private const val OLD_HEADER = "--- "
    private const val MAX_SCAN_LINES = 20

    /**
     * Resolves the language of [diff], or null when it cannot be told.
     *
     * Bounded to the first few lines: a header is one of the first two, and
     * scanning a thousand-line diff to find what line one said would be absurd.
     */
    fun of(diff: String): String? {
        var checked = 0
        for (line in diff.lineSequence()) {
            if (checked++ >= MAX_SCAN_LINES) break
            val path = when {
                line.startsWith(NEW_HEADER) -> line.removePrefix(NEW_HEADER).trim()
                line.startsWith(OLD_HEADER) -> line.removePrefix(OLD_HEADER).trim()
                else -> continue
            }
            // `+++ /dev/null` is how a diff says a side has no file at all.
            if (path.isEmpty() || path == "/dev/null") continue
            val extension = path.substringAfterLast('.', missingDelimiterValue = "")
            if (extension.isEmpty() || extension.contains('/')) continue
            SyntaxLanguages.resolve(extension)?.let { return it }
            // A header was found but its language is unknown. A later header
            // would be the same file, so there is nothing better to find.
            return null
        }
        return null
    }
}

// Tinted rather than solid: a solid red or green block behind monospace text
// fails contrast at this size, and a diff is mostly context lines.
private val ADD_BACKGROUND = Color(0x143DD68C)
private val REMOVE_BACKGROUND = Color(0x14F2555A)
private val HUNK_BACKGROUND = Color(0x0A000000)