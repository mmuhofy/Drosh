package dev.drosh.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.UrlDetector
import dev.drosh.domain.block.Block
import dev.drosh.domain.block.BlockState

@Composable
fun BlockBody(
    block: Block,
    onLongClick: (() -> Unit)? = null,
    onUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,
    isCurrentMatchBlock: Boolean = false,
) {
    val prompt = block.prompt
    val command = block.command
    val showPrompt = !isLikelyPrompt(prompt)
    val inputAnnotated: AnnotatedString = if (showPrompt) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = DroshPrimary)) { append(prompt) }
            append(" ")
            withStyle(SpanStyle(color = DroshText)) { append(command) }
        }
    } else {
        buildAnnotatedString { append(command) }
    }

    Column(modifier = modifier) {
        // Input line: long-press opens context menu. Selection is
        // available too — combinedClickable handles both.
        if (onLongClick != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onLongClick,
                    ),
            ) {
                SelectionContainer {
                    Text(
                        text = inputAnnotated,
                        style = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Start,
                        ),
                    )
                }
            }
        } else {
            SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = inputAnnotated,
                    style = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Start,
                    ),
                )
            }
        }
        if (!block.isCollapsed) {
            if (block.outputLines.isNotEmpty()) {
                SelectionContainer(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Column {
                        block.outputLines.forEach { line ->
                            val isSearchMatch = searchQuery?.isNotEmpty() == true &&
                                line.contains(searchQuery, ignoreCase = true)

                            if (isSearchMatch) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isCurrentMatchBlock)
                                                DroshPrimary.copy(alpha = 0.2f)
                                            else
                                                DroshPrimary.copy(alpha = 0.1f),
                                        ),
                                ) {
                                    OutputLineWithLinks(
                                        line = line,
                                        onUrlClick = onUrlClick,
                                        searchQuery = searchQuery,
                                        highlightMatch = isCurrentMatchBlock,
                                    )
                                }
                            } else {
                                OutputLineWithLinks(
                                    line = line,
                                    onUrlClick = onUrlClick,
                                    searchQuery = null,
                                    highlightMatch = false,
                                )
                            }
                        }
                    }
                }
            } else {
                EmptyOutputPlaceholder(isRunning = block.state is BlockState.Running)
            }
        }
    }
}

@Composable
private fun OutputLineWithLinks(
    line: String,
    onUrlClick: (String) -> Unit,
    searchQuery: String? = null,
    highlightMatch: Boolean = false,
) {
    val textStyle = LocalTextStyle.current.copy(
        color = DroshTextSecondary,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        textAlign = TextAlign.Start,
    )

    val urlMatches = UrlDetector.findUrls(line)
    val text = line.ifEmpty { " " }

    if (urlMatches.isEmpty() && searchQuery.isNullOrEmpty()) {
        Text(
            text = text,
            color = DroshTextSecondary,
            style = textStyle,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val annotated = buildAnnotatedStringWithHighlights(
        text = text,
        urlMatches = urlMatches,
        searchQuery = searchQuery,
        highlightMatch = highlightMatch,
    )

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotated,
        style = textStyle,
        softWrap = true,
        onTextLayout = { layoutResult = it },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures { tapPosition: Offset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(tapPosition)
                    val clamped = offset.coerceAtLeast(0).coerceAtMost(text.length - 1)
                    val charIndex = if (clamped < 0) 0 else clamped
                    for (match in urlMatches) {
                        if (charIndex in match.start..match.end) {
                            onUrlClick(match.url)
                            return@detectTapGestures
                        }
                    }
                }
            },
    )
}

private fun buildAnnotatedStringWithHighlights(
    text: String,
    urlMatches: List<UrlDetector.UrlMatch>,
    searchQuery: String?,
    highlightMatch: Boolean,
): AnnotatedString {
    val searchBg = if (highlightMatch)
        DroshPrimary.copy(alpha = 0.4f)
    else
        DroshPrimary.copy(alpha = 0.25f)

    val urlFlags = BooleanArray(text.length)
    val searchFlags = BooleanArray(text.length)

    for (match in urlMatches) {
        for (i in match.start until minOf(match.end, text.length)) {
            urlFlags[i] = true
        }
    }

    if (!searchQuery.isNullOrEmpty()) {
        val query = searchQuery.lowercase()
        val lower = text.lowercase()
        var start = 0
        while (true) {
            val idx = lower.indexOf(query, start)
            if (idx == -1) break
            val end = idx + query.length
            for (i in idx until minOf(end, text.length)) {
                searchFlags[i] = true
            }
            start = end
        }
    }

    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val inUrl = urlFlags[cursor]
            val inSearch = searchFlags[cursor]
            var end = cursor + 1
            while (end < text.length &&
                urlFlags[end] == inUrl &&
                searchFlags[end] == inSearch
            ) {
                end++
            }

            val spanStyle = when {
                inUrl && inSearch -> SpanStyle(
                    background = searchBg,
                    color = DroshPrimary,
                    textDecoration = TextDecoration.Underline,
                )
                inUrl -> SpanStyle(
                    color = DroshPrimary,
                    textDecoration = TextDecoration.Underline,
                )
                inSearch -> SpanStyle(
                    background = searchBg,
                    color = DroshTextSecondary,
                )
                else -> SpanStyle(color = DroshTextSecondary)
            }

            withStyle(spanStyle) {
                append(text.substring(cursor, end))
            }
            cursor = end
        }
    }
}

private fun isLikelyPrompt(text: String): Boolean {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty()) return true
    return trimmed.endsWith("$ ") ||
        trimmed.endsWith("# ") ||
        trimmed.endsWith("❯ ") ||
        trimmed.endsWith("➜ ")
}

@Composable
private fun EmptyOutputPlaceholder(isRunning: Boolean, modifier: Modifier = Modifier) {
    val text = if (isRunning) "running…" else "no output"
    Text(
        text = text,
        color = DroshTextMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        modifier = modifier.fillMaxWidth().padding(top = 2.dp),
    )
}
