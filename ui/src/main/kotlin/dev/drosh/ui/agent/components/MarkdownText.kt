package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladsch.flexmark.ext.tables.TableBlock
import com.vladsch.flexmark.ext.tables.TableCell
import com.vladsch.flexmark.ext.tables.TableHead
import com.vladsch.flexmark.ext.tables.TableRow
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node as FlexNode
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary

/**
 * Assistant prose, rendered as markdown.
 *
 * ## Why we render the AST ourselves
 *
 * The obvious library is `multiplatform-markdown-renderer`, and it cannot be used
 * here: its newest version, 0.45.0, has directories on Maven Central whose
 * artifacts 404, so it cannot be resolved at all. The parser under it (flexmark) is
 * fine — pure Java, nothing in its POM that constrains the Kotlin toolchain — so the
 * split is: flexmark parses, this renders.
 *
 * That also means the design tokens are ours. A library would bring its own spacing
 * scale, its own heading sizes and its own code-block chrome, and three of those
 * would be wrong against this app.
 *
 * ## Why a failure falls back to plain text
 *
 * [parse] returns the original string when the parser throws. Streaming means the
 * parser regularly sees half a document — an unclosed fence, a table with one row —
 * and a renderer that shows nothing until the document is complete would blank the
 * screen for every reply. Plain text is ugly but it is never wrong.
 *
 * ## Tables scroll
 *
 * A three-column table at 390dp cannot fit without either wrapping every cell to
 * one word per line or scrolling. It scrolls: the block scrolls horizontally while
 * the document scrolls vertically, so a wide table never reflows the page around it.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
) {
    val clipboard = rememberAgentClipboard()
    val nodes = remember(markdown) { parse(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MarkdownBlocks(
            nodes = nodes,
            indent = 0,
            onCopy = onCopy ?: { clipboard(markdown) },
            onShare = onShare,
        )
    }
}

/**
 * The parser, built once.
 *
 * flexmark's parser holds the extension registry and the parse state, and building
 * one per composition would re-register the table extension on every frame of a
 * streaming answer. `Parser` is documented as thread-safe for parsing once built, so
 * a single shared instance is correct here.
 *
 * `Extensions.TABLES` is what makes a pipe table a table. Without it a table parses
 * as a paragraph of literal `|` characters, which is worse than not supporting them:
 * it looks like the renderer failed rather than like the syntax being off.
 */
private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension()))
    .build()

/** Parsed outside composition — flexmark is not cheap enough to run per frame. */
private fun parse(markdown: String): List<FlexNode> =
    runCatching { parser.parse(markdown).children.toList() }.getOrElse { emptyList() }

@Composable
private fun MarkdownBlocks(
    nodes: List<FlexNode>,
    indent: Int,
    onCopy: (String) -> Unit,
    onShare: ((String) -> Unit)?,
) {
    nodes.forEach { node ->
        MarkdownBlock(node = node, indent = indent, onCopy = onCopy, onShare = onShare)
    }
}

@Composable
private fun MarkdownBlock(
    node: FlexNode,
    indent: Int,
    onCopy: (String) -> Unit,
    onShare: ((String) -> Unit)?,
) {
    when (node) {
        is com.vladsch.flexmark.ast.Paragraph -> {
            InlineText(
                annotated = inlineText(node),
                modifier = Modifier.padding(start = (indent * 12).dp),
            )
            Spacer(Modifier.size(2.dp))
        }

        is com.vladsch.flexmark.ast.Heading -> {
            InlineText(
                annotated = inlineText(node),
                style = when (node.level) {
                    1 -> TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    2 -> TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    3 -> TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    else -> TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
                },
                color = DroshText,
                modifier = Modifier.padding(top = 6.dp, start = (indent * 12).dp),
            )
            Spacer(Modifier.size(2.dp))
        }

        // Bare text under a heading or a list item. `ast.Text` is not a Node, so it
        // cannot be walked as one; inlineText handles it through its segments.
        is com.vladsch.flexmark.ast.Text -> {
            InlineText(
                annotated = inlineText(node),
                modifier = Modifier.padding(start = (indent * 12).dp),
            )
        }

        is com.vladsch.flexmark.ast.FencedCodeBlock -> CodeBlockView(node = node, onCopy = onCopy)

        is com.vladsch.flexmark.ast.IndentedCodeBlock ->
            CodeBlockView(node = node, onCopy = onCopy)

        is com.vladsch.flexmark.ast.BulletList -> {
            MarkdownList(node = node, indent = indent, ordered = false, onCopy = onCopy, onShare = onShare)
        }

        is com.vladsch.flexmark.ast.OrderedList -> {
            MarkdownList(node = node, indent = indent, ordered = true, onCopy = onCopy, onShare = onShare)
        }

        is com.vladsch.flexmark.ast.BlockQuote -> {
            // A rule on the left and muted text: the convention, and the only cue a
            // reader needs to know the words are quoted rather than asserted.
            Row(modifier = Modifier.padding(start = 2.dp, top = 2.dp)) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(DroshOutline)
                        .align(Alignment.Top)
                        .padding(top = 2.dp)
                        .fillMaxWidth(0.004f)
                        .size(width = 2.dp, height = 1.dp),
                )
                MarkdownBlocks(
                    nodes = node.children.toList(),
                    indent = 0,
                    onCopy = onCopy,
                    onShare = onShare,
                )
            }
        }

        is com.vladsch.flexmark.ast.ThematicBreak -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .size(height = 1.dp, width = 1.dp)
                .background(DroshOutline),
        )

        is TableBlock -> MarkdownTable(node = node, onCopy = onCopy)

        // Anything the AST has that this does not handle renders its text, so an
        // unsupported element shows its content rather than vanishing.
        else -> node.children.toList().takeIf { it.isNotEmpty() }?.let { children ->
            MarkdownBlocks(children, indent, onCopy, onShare)
        } ?: InlineText(
            annotated = inlineText(node),
            modifier = Modifier.padding(start = (indent * 12).dp),
        )
    }
}

@Composable
private fun MarkdownList(
    node: FlexNode,
    indent: Int,
    ordered: Boolean,
    onCopy: (String) -> Unit,
    onShare: ((String) -> Unit)?,
) {
    Column(
        modifier = Modifier.padding(start = (indent * 12).dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        node.children.toList().forEachIndexed { index, item ->
            Row {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    fontSize = 14.sp,
                    color = DroshTextMuted,
                    modifier = Modifier.width(18.dp),
                )
                MarkdownBlock(node = item, indent = 0, onCopy = onCopy, onShare = onShare)
            }
        }
    }
}

/**
 * A table, scrolling sideways.
 *
 * The scroll is on the block and not on the document, so a wide table is panned with
 * a horizontal drag of its own and the transcript keeps its vertical scroll.
 */
/**
 * A node's text, for the two places that need a flat string.
 *
 * flexmark 0.64 has no `literal` accessor at all — text lives on `segments`, and a
 * container has only children. So the rule is: a delimited leaf is its own segments,
 * and anything else is its children concatenated. A table cell holds text, links and
 * emphasis at once, and a code block holds one text node, so both flatten correctly.
 */
private fun nodeText(node: FlexNode): String = when (node) {
    // A delimited leaf carries its text directly. Everything else is a container
    // whose text is the concatenation of its children, in document order.
    is com.vladsch.flexmark.ast.DelimitedNodeImpl ->
        node.segments.joinToString("") { it.toString() }

    else -> node.children.joinToString("") { nodeText(it) }
}

@Composable
private fun MarkdownTable(node: TableBlock, onCopy: (String) -> Unit) {
    val palette = CodeColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.background)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        node.children.toList().filterIsInstance<TableRow>().forEach { row ->
            val header = row.parent is TableHead
            Row {
                row.children.toList().filterIsInstance<TableCell>().forEach { cell ->
                    // A cell's text is whatever its children flatten to, which is
                    // how a cell holding text, a link and emphasis comes out whole.
                    val text = cell.children.joinToString("") { nodeText(it) }
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (header) DroshText else DroshTextSecondary,
                        modifier = Modifier.width(112.dp).padding(6.dp),
                    )
                }
            }
        }
    }
}

/** A fenced code block: language label, copy button, highlighted body. */
@Composable
private fun CodeBlockView(
    node: FlexNode,
    onCopy: (String) -> Unit,
) {
    val palette = CodeColors
    val language = SyntaxLanguages.resolve(
        (node as? com.vladsch.flexmark.ast.FencedCodeBlock)?.info?.toString(),
    )
    val raw = node.children.joinToString("\n") { nodeText(it) }
    val highlighted = remember(raw, language) {
        SyntaxHighlighter(language, palette).highlight(raw)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
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
                    .clickable { onCopy(raw) }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Kodu kopyala" },
            )
        }

        // Horizontal so a long line does not wrap into something that no longer looks
        // like code — wrapped code hides the indentation that carries the structure.
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

/** Prose with inline styling: bold, italic, code, links, strikethrough. */
@Composable
private fun InlineText(
    annotated: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    color: Color = DroshText,
) {
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Flatten a paragraph's children into a styled string.
 *
 * Inline styling is carried on the AST nodes themselves, so this walks the tree
 * rather than re-parsing the segments — the alternative, stripping the markers and
 * searching for `**` afterwards, gets confused by a literal `*` inside a code span,
 * which is exactly what a path glob or a regex looks like.
 */
/**
 * An autolink's visible text.
 *
 * `AutoLink` extends `DelimitedLinkNode`, which is not a `Node`, so it has neither
 * children nor a literal. Its text is in `segments` — the bracketed runs flexmark
 * splits the URL into, which is exactly the part a reader should see.
 */
private fun childLabel(link: com.vladsch.flexmark.ast.AutoLink): String =
    link.segments.joinToString("") { it.toString() }

@Composable
private fun inlineText(node: FlexNode): AnnotatedString {
    // Inline code's chip and a link's tint both read theme tokens, which are
    // @Composable getters over LocalDroshColors. Reading them here rather than
    // inside the builder is what keeps this function composable — a
    // buildAnnotatedString block is not a composable scope.
    val codeBackground = DroshSurfaceHigh.copy(alpha = 0.55f)
    val linkColor = DroshPrimary

    return buildAnnotatedString {
        fun walk(current: FlexNode, emphasis: SpanStyle) {
            current.children.toList().forEach { child ->
                when (child) {
                    is com.vladsch.flexmark.ast.Emphasis ->
                        walk(
                            child,
                            emphasis.merge(
                                SpanStyle(
                                    fontStyle = FontStyle.Italic,
                                    fontWeight = FontWeight.Medium,
                                ),
                            ),
                        )

                    is com.vladsch.flexmark.ast.StrongEmphasis ->
                        walk(child, emphasis.merge(SpanStyle(fontWeight = FontWeight.Bold)))

                    is com.vladsch.flexmark.ast.Code -> {
                        // Inline code is a monospace chip, not just a font change:
                        // the background is what tells a path from a word.
                        pushStyle(
                            emphasis.merge(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    background = codeBackground,
                                ),
                            ),
                        )
                        append(child.segments.joinToString("") { it.toString() })
                        pop()
                    }

                    is com.vladsch.flexmark.ast.AutoLink -> {
                        // Not a Node — no children, no literal. Its text is in the
                        // bracketed segments flexmark splits the URL into, which is
                        // the part a reader should see.
                        pushStyle(emphasis.merge(SpanStyle(color = linkColor)))
                        append(child.segments.joinToString("") { it.toString() })
                        pop()
                    }

                    is com.vladsch.flexmark.ast.Link -> {
                        pushStyle(
                            emphasis.merge(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        )
                        child.children.toList().forEach { walk(it, emphasis) }
                        pop()
                    }

                    is com.vladsch.flexmark.ast.Text -> {
                        // A soft break is a newline in the source that markdown says
                        // is a space. Rendering it literally would make every wrapped
                        // paragraph look like it has a line break in it.
                        pushStyle(emphasis)
                        append(child.segments.joinToString("") { it.toString() })
                        pop()
                    }

                    is com.vladsch.flexmark.ast.HardLineBreak -> append("\n")

                    else -> walk(child, emphasis)
                }
            }
        }
        walk(node, SpanStyle())
    }
}
