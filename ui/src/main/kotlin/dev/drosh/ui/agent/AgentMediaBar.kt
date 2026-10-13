package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.AgentWarning
import dev.drosh.domain.agent.InputModality
import dev.drosh.domain.agent.MessageAttachment
import dev.drosh.ui.R

/**
 * What the composer is carrying, above the box.
 *
 * ## Why the bytes are not in the draft
 *
 * A photo is a few hundred kilobytes of base64 characters. As the value of a
 * `BasicTextField` that is carried by every keystroke, every re-measure and
 * every recomposition of the composer, with the transcript recomposing beside
 * it. So the draft stays the words and the files are values held next to it,
 * rendered here and turned into real content parts at the moment of sending.
 *
 * ## Wiring
 *
 * `AgentChatScreen` is not edited by this change. To adopt this:
 *
 * ```kotlin
 * val attachments by viewModel.attachments.collectAsStateWithLifecycle()
 * val warning by viewModel.mediaWarning.collectAsStateWithLifecycle()
 *
 * AgentMediaBar(
 *     attachments = attachments,
 *     warning = warning,
 *     onRemove = viewModel::removeAttachment,
 *     onAdd = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
 *     onDismissWarning = viewModel::dismissMediaWarning,
 * )
 * ```
 *
 * and have `onSend` keep the text when it returns false:
 *
 * ```kotlin
 * onSend = { text -> if (viewModel.send(text)) composerDraft = "" }
 * ```
 *
 * [AgentChatViewModel.blockedDraft] is that text when the send was refused,
 * which is the one case where the composer must not clear it.
 */
@Composable
fun AgentMediaBar(
    attachments: List<MessageAttachment>,
    warning: AgentWarning?,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onDismissWarning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty() && warning == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (attachments.isNotEmpty()) {
            AgentMediaChips(attachments = attachments, onRemove = onRemove, onAdd = onAdd)
        }
        if (warning != null) {
            AgentMediaWarningRow(warning = warning, onDismiss = onDismissWarning)
        }
    }
}

/**
 * One row of chips, scrolling sideways.
 *
 * A row rather than a wrap: eight attachments on a phone is a second line of
 * composer, and a wrapping row makes the composer grow without the user having
 * done anything. The add button rides at the end so it is reachable from the
 * same gesture that gets to the chips.
 */
@Composable
private fun AgentMediaChips(
    attachments: List<MessageAttachment>,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(attachments, key = { it.id }) { attachment ->
            AgentMediaChip(attachment = attachment, onRemove = { onRemove(attachment.id) })
        }
        item(key = AGENT_MEDIA_ADD_KEY) {
            AgentMediaAddButton(onClick = onAdd)
        }
    }
}

/**
 * One attachment, with the file drawn inside it when there is one to draw.
 *
 * A video previews as its own first frame — a few hundred kilobytes of JPEG
 * rather than the whole clip — because a chip that says "clip.mp4" tells the
 * user nothing about whether they picked the right one.
 */
@Composable
private fun AgentMediaChip(
    attachment: MessageAttachment,
    onRemove: () -> Unit,
) {
    val name = attachment.fileName.ifBlank {
        when (InputModality.of(attachment.mediaType)) {
            InputModality.IMAGE -> stringResource(R.string.agent_media_kind_image_title)
            InputModality.VIDEO -> stringResource(R.string.agent_attachment_badge_video)
            InputModality.AUDIO -> stringResource(R.string.agent_media_kind_audio)
            InputModality.TEXT -> stringResource(R.string.agent_media_kind_text)
        }
    }
    // After the name, not before it: the label the remove button announces is
    // the file's name, and a name that has not been computed cannot be read.
    val removeCd = stringResource(R.string.agent_media_remove_cd, name)
    val label = stringResource(R.string.agent_attachment_chip, name, formatBytes(attachment.byteSize))
    val preview = attachment.previewDataUrl

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DroshSurfaceHigh)
            .clickable(onClick = onRemove)
            .semantics { contentDescription = removeCd }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (preview != null) {
            AsyncImage(
                model = preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            // Nothing to draw. A blank square reads as a broken image, so it
            // gets the one word that describes what is attached.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DroshSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.agent_attachment_badge_video),
                    style = MaterialTheme.typography.labelSmall,
                    color = DroshTextSecondary,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = DroshText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgentMediaAddButton(onClick: () -> Unit) {
    val addCd = stringResource(R.string.agent_media_attach_cd)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DroshSurfaceHigh)
            .clickable(onClick = onClick)
            .semantics { contentDescription = addCd },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.agent_media_attach_label),
            style = MaterialTheme.typography.labelMedium,
            color = DroshTextMuted,
        )
    }
}

/**
 * Why the last file went nowhere.
 *
 * A refusal the user cannot read is a refusal they will make again, so this is
 * a row rather than a transient snack: it stays until the next attachment or
 * the next send, and it is dismissible once it has been read.
 *
 * The text comes out of [describe] rather than a `stringResource` here because
 * the ViewModel writes the same sentence into the transcript; one function, so
 * the two places cannot say different things.
 */
@Composable
private fun AgentMediaWarningRow(
    warning: AgentWarning,
    onDismiss: () -> Unit,
) {
    // The activity already carries the app locale, and an empty tag means
    // "do not override it" — see `localizedContext`.
    val text = warning.describe(context = LocalContext.current, languageTag = "")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DroshSurfaceHigh)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = DroshError,
        )
    }
}

/** Stable key for the add tile, so it does not renumber as chips come and go. */
private const val AGENT_MEDIA_ADD_KEY = "agent-media-add"