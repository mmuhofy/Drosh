package dev.drosh.ui.agent.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.m3.Markdown

/**
 * Markdown for one assistant message.
 *
 * The parse and the render both belong to the library now. What is left here is the
 * three things it cannot know: our code block, the copy action, and GFM tables.
 *
 * ## Why a library and not a hand-written renderer
 *
 * The previous version of this file was 490 lines walking a flexmark AST, and it was
 * still incomplete — 17 of the roughly forty node types, so a task list or a
 * `~~struck~~` span rendered as its own markers. That ratio is the problem: markdown
 * is not a small grammar, and every construct handled by hand is one more thing to be
 * wrong in a way that looks like the renderer failed. The library's parser is
 * `org.jetbrains:markdown`, which also means strikethrough, GFM tables and task lists
 * arrive without anything extra to add.
 *
 * ## Tables are the exception
 *
 * The library parses a table and cannot draw one: `markdownComponents()` has no
 * `table` parameter, so there is no slot to put a renderer into. Unrendered, it is
 * not dropped — it is printed, pipes and delimiter row and all. [MarkdownTables]
 * finds those tables before the library sees them, they are drawn by
 * [DroshMarkdownTable], and everything between them is handed over unchanged.
 *
 * ## Styling
 *
 * Four interfaces rather than MaterialTheme, because the library does not know what
 * this app's surface hierarchy is. See [MarkdownConfig].
 *
 * ## Streaming
 *
 * A streaming answer changes on every token, so rendering it as one document re-parses
 * and re-lays-out the whole thing each time — including the part that has not changed
 * in twenty seconds. [StableMarkdown] cuts the text at the last block boundary that
 * is safely closed, and the two halves are rendered separately, so only the tail is
 * recomposed while the rest is skipped.
 *
 * The table scan is keyed the same way: each half is scanned on its own, and
 * `remember` compares the halves as strings, so the settled prefix is scanned once
 * per *cut move* rather than once per token. That is the whole point of the split,
 * and a second pass over the settled half on every token would give it back.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null,
) {
    val clipboard = rememberAgentClipboard()
    val components = droshMarkdownComponents(onCopy = onCopy ?: { clipboard(markdown) })
    val split = StableMarkdown.split(markdown)
    val stableBlocks = remember(split.stable) { MarkdownTables.blocks(split.stable) }
    val tailBlocks = remember(split.tail) { MarkdownTables.blocks(split.tail) }

    Column(modifier = modifier.fillMaxWidth()) {
        // `split.stable` is a String, which Compose treats as stable: while the answer
        // keeps streaming, this subtree is skipped rather than recomposed. It is not
        // worth a key() — a key would force the subtree to be rebuilt from scratch
        // every time the cut moves, which is the opposite of the point.
        MarkdownRun(stableBlocks, components)
        MarkdownRun(tailBlocks, components)
    }
}

/**
 * One half of the message, in document order.
 *
 * Extracted rather than inlined so the two halves are two calls and not one
 * concatenated list: the concatenation would allocate on every token, which is the
 * exact cost [StableMarkdown] exists to avoid.
 */
@Composable
private fun MarkdownRun(blocks: List<MarkdownBlock>, components: MarkdownComponents) {
    blocks.forEach { block ->
        val table = block.table
        if (table == null) {
            Markdown(content = block.source, components = components)
        } else {
            DroshMarkdownTable(table = table)
        }
    }
}