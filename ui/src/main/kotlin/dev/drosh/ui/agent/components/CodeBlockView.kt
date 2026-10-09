package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.utils.findChildOfType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * The code block, drawn by us and handed to the renderer.
 *
 * The language and the code text come out of the AST the same way the library's own
 * block gets them: the fence's info string is a `FENCE_LANG` child, and the code is
 * the span between the first and last child offset. Reading them here rather than
 * from the model's `content` matters — `content` is the whole block including the
 * backticks, so highlighting it would treat the fence as code.
 */
@Composable
fun DroshCodeBlock(
    model: MarkdownComponentModel,
    onCopy: (String) -> Unit,
) {
    val raw = remember(model.content, model.node) {
        model.node.fencedCode(model.content)
    }
    CodeBlockView(
        code = raw.code,
        language = SyntaxLanguages.resolve(raw.language),
        onCopy = onCopy,
    )
}

/** The language tag and the code, split out of a fenced block's AST node. */
private fun ASTNode.fencedCode(content: String): FencedCode {
    val language = findChildOfType(MarkdownTokenTypes.FENCE_LANG)
        ?.getTextInNode(content)
        ?.toString()
    // An unterminated fence — which is what a streaming answer looks like most of the
    // time — has the opening marker as its only child, and that offset spans the
    // whole remaining content. Clamping to the end keeps a half-written block visible
    // instead of blank until the closing backticks arrive.
    val start = children.firstOrNull()?.startOffset ?: 0
    val end = children.lastOrNull()?.endOffset ?: content.length
    return FencedCode(
        code = content.substring(start.coerceAtLeast(0), end.coerceIn(0, content.length)),
        language = language,
    )
}

private data class FencedCode(val code: String, val language: String?)

/**
 * Code the agent wrote: a language tag, a copy action, and the highlighted body.
 *
 * Horizontal rather than wrapping. A wrapped code line stops being the line that was
 * written — and the indentation carrying the structure is the first thing a wrap
 * destroys, so a wrapped block is harder to read than the same block scrolled.
 */
@Composable
internal fun CodeBlockView(
    code: String,
    language: String?,
    onCopy: (String) -> Unit,
) {
    val palette = CodeColors
    val highlighted = remember(code, language) {
        SyntaxHighlighter(language, palette).highlight(code)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CODE_BLOCK_CORNER))
            .background(palette.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.background)
                .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = SyntaxLanguages.label(language),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.gutter,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Kopyala",
                fontSize = 11.sp,
                color = palette.punctuation,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCopy(code) }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Kodu kopyala" },
            )
        }

        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = highlighted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.plain,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}