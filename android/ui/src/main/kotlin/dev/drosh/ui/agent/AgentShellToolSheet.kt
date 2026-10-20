package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshTilePressed
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * One shell call, in full: the command, where it ran, and what it printed.
 *
 * ## Why the command is its own section
 *
 * The transcript row shows a command truncated to one line, because a row is one
 * line. The same command here is the whole of it — a pipeline that was cut halfway
 * is a pipeline the user cannot reason about, and this is the sheet whose entire
 * job is reasoning about it.
 *
 * ## Why the working directory is shown
 *
 * A relative path means something different in every directory. `./build` in
 * /home/me/app and /home/me/other are two different outputs, and the sheet that
 * shows the output without the directory has shown the user half of what happened.
 *
 * ## Why the output is monospaced
 *
 * It is a terminal's output. Every column alignment the command produced — a
 * `ls -l` table, a `git diff` hunk header, a stack trace's carets — is destroyed
 * by a proportional font, and a diff read in the wrong font is a diff read wrong.
 */
@Composable
fun AgentShellToolSheet(
    visible: Boolean,
    command: String,
    workdir: String?,
    output: String,
    state: ToolCallState?,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyCd = stringResource(R.string.action_copy)

    AgentSheet(
        modifier = modifier,
        visible = visible,
        title = stringResource(R.string.agent_sheet_shell_title),
        subtitle = state?.let { stringResource(it.labelRes()) },
        icon = DroshIcons.SquareTerminal,
        trailing = {
            SheetIconButton(
                icon = DroshIcons.Copy,
                description = copyCd,
                onClick = { onCopy(command + "\n\n" + output) },
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetSectionLabel(stringResource(R.string.agent_sheet_shell_command))
            SheetMonoBlock(text = command.ifBlank { stringResource(R.string.agent_tool_no_output) })

            workdir?.takeIf { it.isNotBlank() }?.let { where ->
                SheetSectionLabel(stringResource(R.string.agent_sheet_shell_workdir))
                SheetMonoBlock(text = where)
            }

            SheetSectionLabel(stringResource(R.string.agent_sheet_shell_output))
            SheetMonoBlock(
                text = output.ifBlank { stringResource(R.string.agent_tool_no_output) },
                mutedWhenBlank = true,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * The reasoning a model did before it answered, as a sheet.
 *
 * The transcript row is one line and has to be: a reasoning block is longer than
 * the answer it produced, and putting it in the conversation turns the
 * conversation into a log of the model's homework. So the row says it is there
 * and this sheet says what it is.
 *
 * Nothing here changes what the model is told — it is the same text the chat
 * surface already holds, read from a different place.
 */
@Composable
fun AgentReasoningSheet(
    visible: Boolean,
    text: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val copyCd = stringResource(R.string.action_copy)

    AgentSheet(
        modifier = modifier,
        visible = visible,
        title = stringResource(R.string.agent_sheet_reasoning_title),
        subtitle = subtitle ?: stringResource(R.string.agent_sheet_reasoning_body),
        icon = DroshIcons.Brain,
        trailing = {
            SheetIconButton(
                icon = DroshIcons.Copy,
                description = copyCd,
                onClick = { onCopy(text) },
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
                text = text.ifBlank { stringResource(R.string.agent_sheet_reasoning_empty) },
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                fontFamily = LocalFontSet.current.mono,
                color = if (text.isBlank()) DroshTextMuted else DroshTextSecondary,
            )
        }
    }
}

/**
 * A block of output on its own tile.
 *
 * One rounded tile rather than the bare background the tool detail sheet used:
 * the sheet's own surface is now the same colour as the transcript, and a block
 * that only differs by having no background around it is a block nobody can find
 * the edge of.
 */
@Composable
fun SheetMonoBlock(
    text: String,
    modifier: Modifier = Modifier,
    mutedWhenBlank: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DroshTilePressed)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = LocalFontSet.current.mono,
            color = if (mutedWhenBlank && text.isBlank()) DroshTextMuted else DroshTextSecondary,
        )
    }
}

/**
 * The state's own words, rather than a colour the user has to read.
 *
 * A sealed interface, so the branches are its six members and there is no `else`
 * to leave a new state silently unlabelled the day one is added.
 */
internal fun ToolCallState.labelRes(): Int = when (this) {
    ToolCallState.Running -> R.string.agent_tool_running
    ToolCallState.Succeeded -> R.string.agent_tool_succeeded
    ToolCallState.Failed -> R.string.agent_tool_failed
    ToolCallState.Cancelled -> R.string.agent_tool_cancelled
    ToolCallState.AwaitingApproval -> R.string.agent_tool_awaiting
    ToolCallState.Pending -> R.string.agent_tool_pending
}
