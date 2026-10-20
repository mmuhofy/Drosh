package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.glassRow

// ── parsing what the search tool printed ───────────────────────────────────

/**
 * One result the agent found, as a thing the user can look at.
 *
 * The tool's output is prose for a model: an index, a title, a URL and then
 * however many passages Exa thought were relevant. Rendering that as the same
 * text is rendering the model-facing format at the user, so it is parsed into
 * this instead.
 */
data class SearchResult(
    val title: String,
    val url: String,
    /** The first passage, which is the one that answers the question. */
    val snippet: String,
) {
    /** The host, for the row's mark. */
    val host: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}

/**
 * Everything one `web_search` call came back with.
 *
 * A sealed shape because the three cases need genuinely different screens: a
 * list, a line saying nothing came back, and a line saying the search failed.
 * Rendering all three through one list with an empty-state branch is how a
 * failed search ends up looking like a search with no results.
 */
sealed interface SearchOutcome {

    data class Results(
        val query: String,
        val results: List<SearchResult>,
    ) : SearchOutcome {
        /**
         * The domain the results came from, for the sheet's heading.
         *
         * The most common host among them, not the first: one search routinely
         * returns three pages of the same site and two from elsewhere, and the
         * site the search was *about* is the one that repeats.
         */
        val domain: String
            get() = results
                .groupingBy { it.host }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                .orEmpty()
    }

    /** The call succeeded and there was nothing. */
    data class Empty(val query: String) : SearchOutcome

    /** The call failed, or printed something unrecognisable. */
    data class Failed(val reason: String) : SearchOutcome
}

/**
 * Read one tool row's output as search results.
 *
 * A line scan rather than a regex over the whole blob, because the format is
 * positional — index line, title, URL, optional date, then indented passages —
 * and the passages are free text that a regex would happily eat into. Returns
 * [SearchOutcome.Failed] for a call that did not succeed, so a network error is
 * never shown as "no results".
 */
fun parseSearchOutcome(message: ChatMessage.ToolCall): SearchOutcome {
    val query = message.summary.trim()

    if (message.state == ToolCallState.Failed || message.state == ToolCallState.Cancelled) {
        return SearchOutcome.Failed(
            message.error ?: message.finalOutput?.takeLast(400).orEmpty().ifBlank { query },
        )
    }

    val body = message.finalOutput ?: message.output.joinToString("\n")
    if (body.isBlank()) return SearchOutcome.Empty(query)

    val results = parseResultBlocks(body)
    return when {
        results.isNotEmpty() -> SearchOutcome.Results(query, results)
        body.contains("No results", ignoreCase = true) -> SearchOutcome.Empty(query)
        else -> SearchOutcome.Failed(body.takeLast(400).trim())
    }
}

/**
 * Pull `[n] title / url / …` blocks out of the tool's output.
 *
 * Each block runs from its `[n]` marker to the next one, or to the end. Within
 * a block the second line is the URL and everything after it, once the leading
 * two spaces are stripped, is passage text — joined back into one snippet
 * because three paragraphs of a quote are one thing to read, not three.
 */
private fun parseResultBlocks(body: String): List<SearchResult> {
    val lines = body.lines()
    val results = mutableListOf<SearchResult>()
    var current: MutableList<String>? = null

    fun flush() {
        val block = current ?: return
        current = null
        val url = block.getOrNull(1)?.trim().orEmpty()
        if (!url.startsWith("http")) return
        results += SearchResult(
            title = block.first().trim().ifBlank { url },
            url = url,
            snippet = block.drop(2)
                .joinToString(" ") { it.trim() }
                .replace(NO_PASSAGE, "")
                .trim()
                .take(SNIPPET_CHARS),
        )
    }

    lines.forEach { line ->
        val trimmed = line.trim()
        when {
            // `[1] Title` opens a block. The index has to be *digits only*, or a
            // line that merely starts with a bracket — a citation like "[1]" in a
            // passage, a Markdown link target — is read as a new result.
            isResultMarker(trimmed) -> {
                flush()
                current = mutableListOf(trimmed.substringAfter(']').trim())
            }

            trimmed.isNotEmpty() -> current?.add(trimmed)
            // A blank line is the tool's own separator between blocks, so the
            // block is over and whatever comes next starts something new.
            else -> flush()
        }
    }
    flush()
    return results
}

/**
 * Whether [line] is a result marker — `[1]`, `[12]` and nothing longer.
 *
 * Two digits at most because the tool asks for at most ten results, and a longer
 * run of digits is a citation inside a passage rather than an index.
 */
private fun isResultMarker(line: String): Boolean {
    if (!line.startsWith('[')) return false
    val close = line.indexOf(']')
    return close in 2..3 && line.substring(1, close).all { it.isDigit() }
}

/** The tool's own note that a page yielded no extractable passage. */
private const val NO_PASSAGE = "(no passage extracted — fetch the page if you need it)"

private const val SNIPPET_CHARS = 240

/** Must match `WebSearchTool.NAME`. */
private const val WEB_SEARCH = "web_search"

// ── the sheet ──────────────────────────────────────────────────────────────

/**
 * The results of one search, as a sheet.
 *
 * ## Why a sheet and not an inline expansion
 *
 * The same reasoning as the tool detail sheet: three results with passages are
 * taller than the space the transcript has, and expanding them in place pushes
 * the conversation off the screen and moves the row the user tapped out from
 * under their thumb.
 *
 * ## One chrome
 *
 * The header used to be this sheet's own — its own icon, its own corner, its
 * own grabber, and a "Close" spelled out in the corner. It is now
 * `AgentSheet`, which is the same set of numbers the model picker already used
 * and the only place they are written down. The close is an icon with a 44dp
 * target, like every other close in the agent.
 */
@Composable
fun AgentSearchSheet(
    outcome: SearchOutcome,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = (outcome as? SearchOutcome.Results)?.results?.size
    val query = (outcome as? SearchOutcome.Results)?.query.orEmpty()

    AgentSheet(
        modifier = modifier,
        title = stringResource(R.string.agent_search_title),
        // The query and how many came back, because a list of results with no
        // words above it is a list the user cannot check against what they
        // asked. One line, ellipsised, the way the model picker keeps one.
        subtitle = when {
            count != null && count > 0 -> stringResource(
                if (count == 1) R.string.agent_search_count_one else R.string.agent_search_count,
                count,
            ) + " · " + query.ifBlank { stringResource(R.string.agent_search_title) }

            outcome is SearchOutcome.Empty -> query
            else -> null
        },
        icon = DroshIcons.Globe,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            when (outcome) {
                is SearchOutcome.Results -> outcome.results.forEach { result ->
                    SearchResultRow(result = result, onClick = { onOpen(result.url) })
                }

                is SearchOutcome.Empty -> SheetEmptyNote(stringResource(R.string.agent_search_empty))

                is SearchOutcome.Failed -> {
                    SheetEmptyNote(stringResource(R.string.agent_search_error))
                    SheetMonoBlock(text = outcome.reason, mutedWhenBlank = true)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassRow(RoundedCornerShape(12.dp), raised = true)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        HostMark(host = result.host)
        Spacer(Modifier.width(11.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                color = DroshText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.url,
                fontSize = 11.sp,
                fontFamily = LocalFontSet.current.mono,
                color = DroshTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.snippet.isNotBlank()) {
                Text(
                    text = result.snippet,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = DroshTextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }

        Icon(
            imageVector = DroshIcons.ChevronRight,
            contentDescription = stringResource(R.string.agent_sources_open_cd),
            tint = DroshTextMuted,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * The site's initial in a circle.
 *
 * A letter rather than a fetched favicon: loading five remote icons to decorate
 * a results list costs five requests the user did not ask for, on a connection
 * that may be the reason the search was slow. The host is spelled out beside it
 * anyway, so the mark is identification and not information.
 */
@Composable
fun HostMark(host: String, modifier: Modifier = Modifier) {
    val initial = host.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(DroshSurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = DroshTextSecondary,
        )
    }
}


/**
 * The search this tool row stands for, or null when it is not a search.
 *
 * Null rather than a failed outcome so the caller can fall through to the raw
 * output sheet: a row that is not a search has nothing to parse, and giving it
 * an empty result list would replace a working tool detail sheet with a sheet
 * saying there was nothing there.
 */
fun searchOutcomeOf(message: ChatMessage.ToolCall): SearchOutcome? =
    if (message.name == WEB_SEARCH) parseSearchOutcome(message) else null
