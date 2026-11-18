package dev.drosh.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.domain.UrlDetector
import dev.drosh.domain.UrlDetector.UrlMatch
import dev.drosh.domain.block.Block
import dev.drosh.domain.block.BlockState
import dev.drosh.domain.block.StyledLine
import dev.drosh.domain.block.StyledSpan
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.DroshDropdownMenu
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.DroshMenuItem
import dev.drosh.ui.R
import kotlinx.coroutines.launch

/**
 * Warp-style block view, adapted for a touch screen.
 *
 * Each block is one card holding a prompt + command and its output. Blocks grow
 * bottom-to-top: the newest block sits just above the input area and older ones
 * stack upward. A long block whose command header has scrolled away gets a
 * compact sticky bar at the top; tapping it jumps back to the command.
 *
 * The caller owns the input field and the clipboard — this screen only renders
 * cards and reports copy/collapse intents back through the callbacks.
 *
 * UNTESTED — verify before use (scroll and sticky-header geometry especially).
 */
@Composable
fun BlockScreen(
    blocks: List<Block>,
    runningBlock: Block?,
    isAltScreen: Boolean,
    onCopy: (String) -> Unit,
    onCollapse: (String, Boolean) -> Unit,
    onClassicFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The running block streams partial output and live counters. Swap the stale
    // copy in [blocks] for the fresh one so only that keyed card recomposes.
    // UNTESTED — verify before use: assumes blocks contains the running block.
    val displayBlocks = remember(blocks, runningBlock) {
        if (runningBlock == null) {
            blocks
        } else {
            val merged = blocks.map { if (it.id == runningBlock.id) runningBlock else it }
            if (merged.any { it.id == runningBlock.id }) {
                merged
            } else {
                listOf(runningBlock) + merged
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(DroshBackground)) {
        if (isAltScreen) {
            // A full-screen program (less, vim, htop) owns the PTY now. The caller
            // switches to the classic terminal; render a neutral placeholder here.
            LaunchedEffect(Unit) { onClassicFallback() }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.block_ui_alt_screen_placeholder),
                    color = DroshTextMuted,
                    fontFamily = LocalFontSet.current.mono,
                    fontSize = OUTPUT_FONT_SIZE,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = LIST_VERTICAL_PADDING),
                verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING),
            ) {
                items(items = displayBlocks, key = { it.id }) { block ->
                    BlockCard(
                        block = block,
                        onCopy = onCopy,
                        onCollapse = onCollapse,
                    )
                }
            }

            // UNTESTED — verify before use: reverseLayout sticky-command geometry.
            // With reverseLayout, index 0 is the newest block at the bottom and a
            // greater first-visible index means the user scrolled up toward older
            // output. The topmost visible card is the one with the largest index;
            // its command header is gone once its top edge passes the viewport top.
            val stickyIndex by remember(listState, displayBlocks) {
                derivedStateOf {
                    val layout = listState.layoutInfo
                    val topItem = layout.visibleItemsInfo.maxByOrNull { it.index }
                        ?: return@derivedStateOf null
                    if (listState.firstVisibleItemIndex <= 0) return@derivedStateOf null
                    if (topItem.offset >= layout.viewportStartOffset) return@derivedStateOf null
                    val candidate = displayBlocks.getOrNull(topItem.index) ?: return@derivedStateOf null
                    if (candidate.outputLineCount <= LONG_OUTPUT_LINE_THRESHOLD) return@derivedStateOf null
                    topItem.index
                }
            }

            val stickyBlock = stickyIndex?.let { displayBlocks.getOrNull(it) }
            if (stickyBlock != null) {
                StickyCommandBar(
                    command = stickyBlock.command,
                    onClick = {
                        // UNTESTED — verify before use: animating in a reversed list.
                        scope.launch {
                            val index = stickyIndex ?: return@launch
                            listState.animateScrollToItem(index = index)
                        }
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun BlockCard(
    block: Block,
    onCopy: (String) -> Unit,
    onCollapse: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    // Per-card state, derived from this block only — no sibling recomposition.
    val isRunning = block.state is BlockState.Running
    val isCancelled = block.state is BlockState.Cancelled
    val exitCode = block.exitCode
    val isError = exitCode != null && exitCode != 0

    val accent = when {
        isError -> DroshError
        isRunning -> DroshPrimary
        isCancelled -> DroshTextMuted
        else -> DroshBorderSubtle
    }
    val cardBackground = when {
        isError -> DroshError.copy(alpha = ERROR_CARD_TINT_ALPHA)
        isRunning -> DroshPrimary.copy(alpha = RUNNING_CARD_TINT_ALPHA)
        else -> DroshSurface
    }

    val richOutput = remember(block.outputRich) {
        if (block.outputRich.isEmpty()) null else buildRichOutput(block.outputRich)
    }
    val plainOutput = remember(block.outputLines) { block.outputLines.joinToString("\n") }
    val output = richOutput ?: AnnotatedString(plainOutput)

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Intrinsic height bounds the strip so fillMaxHeight() can match the card.
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(CARD_CORNER_RADIUS))
            .background(cardBackground),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(ACCENT_STRIP_WIDTH)
                .background(accent),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(CARD_PADDING),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = DroshPrimary)) {
                                append(if (block.prompt.isBlank()) "$" else block.prompt)
                                append(" ")
                            }
                            withStyle(SpanStyle(color = DroshText)) {
                                append(block.command)
                            }
                        },
                        fontFamily = LocalFontSet.current.mono,
                        fontSize = OUTPUT_FONT_SIZE,
                        maxLines = COMMAND_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(MENU_BUTTON_SIZE),
                ) {
                    Icon(
                        imageVector = DroshIcons.EllipsisVertical,
                        contentDescription = stringResource(R.string.block_ui_menu_cd),
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(MENU_ICON_SIZE),
                    )
                }

                DroshDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    items = listOf(
                        DroshMenuItem(
                            label = stringResource(R.string.block_ui_menu_copy_command),
                            icon = DroshIcons.Copy,
                            id = MENU_COPY_COMMAND,
                        ),
                        DroshMenuItem(
                            label = stringResource(R.string.block_ui_menu_copy_output),
                            icon = DroshIcons.Copy,
                            id = MENU_COPY_OUTPUT,
                        ),
                        DroshMenuItem(
                            label = stringResource(R.string.block_ui_menu_copy_both),
                            icon = DroshIcons.Copy,
                            id = MENU_COPY_BOTH,
                        ),
                        DroshMenuItem(
                            label = stringResource(
                                if (block.isCollapsed) {
                                    R.string.block_ui_menu_expand
                                } else {
                                    R.string.block_ui_menu_collapse
                                },
                            ),
                            icon = if (block.isCollapsed) DroshIcons.Eye else DroshIcons.EyeOff,
                            id = MENU_TOGGLE_COLLAPSE,
                            dividerBefore = true,
                        ),
                    ),
                    onItemClick = { item ->
                        when (item.id) {
                            MENU_COPY_COMMAND -> onCopy(block.command)
                            MENU_COPY_OUTPUT -> onCopy(output.text)
                            MENU_COPY_BOTH -> onCopy(
                                buildString {
                                    append(block.command)
                                    if (output.text.isNotEmpty()) {
                                        append('\n')
                                        append(output.text)
                                    }
                                },
                            )
                            MENU_TOGGLE_COLLAPSE -> onCollapse(block.id, !block.isCollapsed)
                        }
                    },
                    title = block.command.ifBlank { null },
                )
            }

            Row(
                modifier = Modifier.padding(top = META_TOP_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatElapsed(block.elapsedMs(System.currentTimeMillis())),
                    color = DroshTextMuted,
                    fontFamily = LocalFontSet.current.mono,
                    fontSize = META_FONT_SIZE,
                )
                if (exitCode != null && exitCode != 0) {
                    Spacer(Modifier.width(META_GAP))
                    Text(
                        text = stringResource(R.string.block_ui_exit_code, exitCode),
                        color = DroshError,
                        fontFamily = LocalFontSet.current.mono,
                        fontSize = META_FONT_SIZE,
                    )
                }
                val delta = block.networkDelta
                if (delta.hasTraffic) {
                    Spacer(Modifier.width(META_GAP))
                    Text(
                        text = stringResource(
                            R.string.block_ui_network_delta,
                            formatBytes(delta.rxBytes),
                            formatBytes(delta.txBytes),
                        ),
                        color = DroshTextMuted,
                        fontFamily = LocalFontSet.current.mono,
                        fontSize = META_FONT_SIZE,
                    )
                }
            }

            if (output.text.isNotEmpty()) {
                if (block.isCollapsed) {
                    Text(
                        text = stringResource(R.string.block_ui_collapsed_hint),
                        color = DroshTextMuted,
                        fontFamily = LocalFontSet.current.mono,
                        fontSize = META_FONT_SIZE,
                        modifier = Modifier.padding(top = META_TOP_PADDING),
                    )
                } else {
                    SelectionContainer {
                        LinkifiedBlockOutput(
                            base = output,
                            baseColor = if (isError) DroshTextMuted else DroshText,
                            onUrlClick = { url -> uriHandler.openUri(url) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Output text with ANSI colours from [base], plus the repo's link affordance.
 *
 * The link overlay is applied on top of the terminal spans so coloured output
 * keeps its styling everywhere else. Tapping a link paints a temporary
 * background behind it; the down event is not consumed, so the enclosing
 * [SelectionContainer] still owns long-press and drag selection — copying from
 * a selection works exactly as it does in [PromptBlock].
 */
@Composable
private fun LinkifiedBlockOutput(
    base: AnnotatedString,
    baseColor: Color,
    onUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plainText = base.text
    val urlMatches = remember(plainText) { UrlDetector.findUrls(plainText) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pressedUrl by remember { mutableStateOf<String?>(null) }

    val urlAtPosition: (Offset) -> UrlMatch? = { position ->
        val result = layout
        if (result == null || urlMatches.isEmpty()) {
            null
        } else {
            val offset = result.getOffsetForPosition(position)
            urlMatches.firstOrNull { offset >= it.start && offset < it.end }
        }
    }

    val linkColor = DroshPrimary
    val pressedLinkColor = DroshPrimary.copy(alpha = LINK_PRESSED_ALPHA)

    val annotated = remember(base, urlMatches, pressedUrl, linkColor, pressedLinkColor) {
        buildAnnotatedString {
            append(base)
            for (match in urlMatches) {
                addStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        background = if (match.url == pressedUrl) pressedLinkColor else Color.Unspecified,
                    ),
                    match.start,
                    match.end,
                )
            }
        }
    }

    Text(
        text = annotated,
        color = baseColor,
        fontFamily = LocalFontSet.current.mono,
        fontSize = OUTPUT_FONT_SIZE,
        lineHeight = OUTPUT_LINE_HEIGHT,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OUTPUT_PADDING, vertical = OUTPUT_VERTICAL_PADDING)
            .pointerInput(plainText) {
                detectTapGestures(
                    onPress = { position ->
                        val match = urlAtPosition(position)
                        pressedUrl = match?.url
                        val released = tryAwaitRelease()
                        pressedUrl = null
                        if (released) match?.let { onUrlClick(it.url) }
                    },
                )
            },
    )
}

/** Compact bar pinned to the top once a long block's own header scrolls away. */
@Composable
private fun StickyCommandBar(
    command: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = CARD_CORNER_RADIUS, bottomEnd = CARD_CORNER_RADIUS))
            .background(DroshSurfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(STICKY_BAR_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = DroshIcons.ChevronUp,
                contentDescription = stringResource(R.string.block_ui_sticky_cd),
                tint = DroshPrimary,
                modifier = Modifier.size(STICKY_ICON_SIZE),
            )
            Spacer(Modifier.width(STICKY_ICON_GAP))
            Text(
                text = command.ifBlank { "$" },
                color = DroshText,
                fontFamily = LocalFontSet.current.mono,
                fontSize = STICKY_FONT_SIZE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(STICKY_DIVIDER_HEIGHT)
                .background(DroshBorderSubtle),
        )
    }
}

/** Turn the terminal's rich lines into one styled, selectable string. */
private fun buildRichOutput(lines: List<StyledLine>): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) append('\n')
        line.spans.forEach { span ->
            withStyle(span.toSpanStyle()) { append(span.text) }
        }
    }
}

private fun StyledSpan.toSpanStyle(): SpanStyle = SpanStyle(
    color = fg?.let { Color(it) } ?: Color.Unspecified,
    background = bg?.let { Color(it) } ?: Color.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    textDecoration = if (underline) TextDecoration.Underline else null,
)

private val Block.outputLineCount: Int
    get() = if (outputRich.isNotEmpty()) outputRich.size else outputLines.size

private fun formatElapsed(ms: Long): String = when {
    ms < MS_PER_SECOND -> "${ms}ms"
    ms < SECONDS_PER_MINUTE * MS_PER_SECOND -> "${ms / MS_PER_SECOND}s"
    else -> "${ms / (SECONDS_PER_MINUTE * MS_PER_SECOND)}m " +
        "${(ms % (SECONDS_PER_MINUTE * MS_PER_SECOND)) / MS_PER_SECOND}s"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < BYTES_PER_KB -> "${bytes}B"
    bytes < BYTES_PER_MB -> "${bytes / BYTES_PER_KB}KB"
    else -> "${bytes / BYTES_PER_MB}MB"
}

private val CARD_CORNER_RADIUS = 10.dp
private val ACCENT_STRIP_WIDTH = 3.dp
private val CARD_PADDING = 12.dp
private val BLOCK_SPACING = 6.dp
private val LIST_VERTICAL_PADDING = 6.dp
private val OUTPUT_PADDING = 14.dp
private val OUTPUT_VERTICAL_PADDING = 2.dp
private val MENU_BUTTON_SIZE = 32.dp
private val MENU_ICON_SIZE = 20.dp
private val META_TOP_PADDING = 4.dp
private val META_GAP = 10.dp
private val STICKY_BAR_PADDING = 10.dp
private val STICKY_ICON_SIZE = 16.dp
private val STICKY_ICON_GAP = 8.dp
private val STICKY_DIVIDER_HEIGHT = 1.dp

private val OUTPUT_FONT_SIZE = 13.sp
private val OUTPUT_LINE_HEIGHT = 18.sp
private val META_FONT_SIZE = 11.sp
private val STICKY_FONT_SIZE = 12.sp

private const val COMMAND_MAX_LINES = 2
private const val LONG_OUTPUT_LINE_THRESHOLD = 20
private const val ERROR_CARD_TINT_ALPHA = 0.08f
private const val RUNNING_CARD_TINT_ALPHA = 0.05f
private const val LINK_PRESSED_ALPHA = 0.28f
private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val BYTES_PER_KB = 1_024L
private const val BYTES_PER_MB = BYTES_PER_KB * 1_024L

private const val MENU_COPY_COMMAND = "copy_command"
private const val MENU_COPY_OUTPUT = "copy_output"
private const val MENU_COPY_BOTH = "copy_both"
private const val MENU_TOGGLE_COLLAPSE = "toggle_collapse"
