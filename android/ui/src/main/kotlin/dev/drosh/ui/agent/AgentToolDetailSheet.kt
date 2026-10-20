package dev.drosh.ui.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * What a tool did, in full, as a sheet.
 *
 * ## Why a sheet and not an inline expansion
 *
 * A tool row that expands inline makes the transcript taller by however much
 * output there is — a `git diff` on a big change pushes the conversation off the
 * screen, and the row the user was reading moves out from under their thumb. A
 * sheet holds the detail somewhere else entirely and the transcript stays put.
 *
 * ## Why there is no footer
 *
 * There used to be one, with a "Copy" and a "Close" written out in words. Copy
 * is worth one icon and it now sits beside the close icon in the title row,
 * where every other action on every other sheet in the agent also sits; and
 * "Close" was spending the width of a word on the one action the drag already
 * performs. A footer for two actions was a second design, and this is one
 * sheet in one language.
 *
 * The body is monospaced because most of what a tool prints is output: a diff,
 * a stack trace, a directory listing. Every column alignment the command
 * produced is destroyed by a proportional font.
 */
@Composable
fun AgentToolDetailSheet(
    visible: Boolean,
    title: String,
    subtitle: String,
    body: String,
    state: ToolCallState?,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val copyCd = stringResource(R.string.action_copy)

    AgentSheet(
        modifier = modifier,
        title = title,
        // The state is the subtitle whenever the caller has nothing better to
        // put there: what the row says it did and what happened to it are the
        // two halves of the same sentence.
        subtitle = subtitle.ifBlank { state?.let { stringResource(it.labelRes()) } },
        icon = DroshIcons.Code,
        trailing = {
            SheetIconButton(
                icon = DroshIcons.Copy,
                description = copyCd,
                onClick = { onCopy(body) },
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = body.ifBlank { stringResource(R.string.agent_tool_no_output) },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = LocalFontSet.current.mono,
                color = if (body.isBlank()) DroshTextMuted else DroshTextSecondary,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
