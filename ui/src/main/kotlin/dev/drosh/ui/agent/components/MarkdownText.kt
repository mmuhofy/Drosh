package dev.drosh.ui.agent.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown

/**
 * Markdown for one assistant message.
 *
 * The parse and the render both belong to the library now. What is left here is the
 * two things it cannot know: our code block, and the copy action.
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

    Column(modifier = modifier.fillMaxWidth()) {
        // `split.stable` is a String, which Compose treats as stable: while the answer
        // keeps streaming, this subtree is skipped rather than recomposed. It is not
        // worth a key() — a key would force the subtree to be rebuilt from scratch
        // every time the cut moves, which is the opposite of the point.
        Markdown(markdown = split.stable, components = components)
        Markdown(markdown = split.tail, components = components)
    }
}