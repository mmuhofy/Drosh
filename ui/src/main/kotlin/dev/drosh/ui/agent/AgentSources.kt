package dev.drosh.ui.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.LocalFontSet
import timber.log.Timber

/**
 * One link the agent read during this conversation.
 *
 * Collected from the transcript rather than kept beside it: the tools already
 * hold the URLs, and a second list would be a second thing to fall out of step
 * with what the model actually read.
 */
data class AgentSource(
    val url: String,
    /** The page's own title when the tool gave one, else the URL. */
    val title: String,
    /** The search query that surfaced it, for the row's second line. */
    val context: String,
)

/**
 * Every URL the transcript's searches came back with, in order and deduplicated.
 *
 * Order is first-seen rather than last, because the first time the agent went
 * to a page is when the user most wants to see it; and the same URL reached
 * twice is one source, not two.
 */
fun collectSources(messages: List<ChatMessage>): List<AgentSource> {
    val seen = linkedMapOf<String, AgentSource>()
    messages.filterIsInstance<ChatMessage.ToolCall>().forEach { tool ->
        if (tool.state != ToolCallState.Succeeded) return@forEach
        val outcome = parseSearchOutcome(tool)
        if (outcome !is SearchOutcome.Results) return@forEach
        outcome.results.forEach { result ->
            seen.getOrPut(result.url) {
                AgentSource(url = result.url, title = result.title, context = outcome.query)
            }
        }
    }
    return seen.values.toList()
}

/**
 * The sources at the foot of the transcript.
 *
 * ## Why this is an item in the list and not a card pinned under it
 *
 * A pinned panel would occupy the bottom of the screen for the whole
 * conversation whether or not the agent searched anything, and it would push the
 * transcript's last message out of view. As a `LazyColumn` item it takes the
 * space only when there are sources, it scrolls away with everything else, and
 * it ends up where the answer is — which is the sentence the reader wants to
 * check against what it was based on.
 *
 * ## Why opening one asks first
 *
 * `ACTION_VIEW` leaves the app. The transcript is the thing the user is reading,
 * and a tap on a row that silently swaps it for a browser loses the context the
 * source was being read in. One dialog, and it says what is about to happen.
 */
@Composable
fun AgentSourcesSection(
    sources: List<AgentSource>,
    /** Asked to open a link; the screen owns the one confirmation dialog. */
    onOpen: (String) -> Unit,
    onShare: (AgentSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = DroshIcons.Globe,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = stringResource(R.string.agent_sources_title).uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.9.sp,
                color = DroshTextMuted,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(DroshOutline.copy(alpha = 0.35f)),
            )
        }

        sources.forEach { source ->
            SourceRow(
                source = source,
                onOpen = { onOpen(source.url) },
                onShare = { onShare(source) },
            )
        }
    }
}

@Composable
private fun SourceRow(
    source: AgentSource,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    val shareCd = stringResource(R.string.agent_sources_share_cd)
    val openCd = stringResource(R.string.agent_sources_open_cd)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onOpen)
            .padding(start = 4.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HostMark(host = source.url.substringAfter("://").substringBefore('/').removePrefix("www."))
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.title,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = DroshText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = source.url,
                fontSize = 10.5.sp,
                fontFamily = LocalFontSet.current.mono,
                color = DroshTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onShare)
                .semantics { contentDescription = shareCd },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DroshIcons.Share,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(14.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpen)
                .semantics { contentDescription = openCd },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DroshIcons.ExternalLink,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * The one thing standing between a tap and another app.
 *
 * `startActivity` on an unhandled intent throws on some devices, so the failure
 * is caught and reported rather than crashing a chat over a link. The dialog is
 * remembered by URL rather than as a boolean so a double tap cannot queue two.
 */
@Composable
internal fun LeaveAppDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_sources_leave_title)) },
        text = {
            Text(
                if (failed) {
                    stringResource(R.string.agent_sources_no_browser)
                } else {
                    stringResource(R.string.agent_sources_leave_body)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (!openExternally(context, url)) failed = true else onDismiss()
            }) {
                Text(stringResource(R.string.agent_sources_leave_yes), color = DroshText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = DroshTextSecondary)
            }
        },
    )
}

/** Hand a URL to whatever app claims it. False when nothing does. */
private fun openExternally(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (failure: ActivityNotFoundException) {
    Timber.w(failure, "no handler for %s", url)
    false
}

/**
 * Hands one source to the system share sheet.
 *
 * Suspends rather than fires and forgets, for the same reason the assistant's
 * own share does: `startActivity` from a non-activity context needs a real
 * coroutine scope, and a chooser with no handler behind it throws on some
 * devices rather than showing an empty sheet.
 */
suspend fun shareSource(context: Context, source: AgentSource) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, source.title)
        putExtra(Intent.EXTRA_TEXT, source.url)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, null), null)
    }.onFailure { Timber.w(it, "share unavailable") }
}

