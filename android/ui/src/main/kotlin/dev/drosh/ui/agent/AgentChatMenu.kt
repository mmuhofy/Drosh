package dev.drosh.ui.agent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drosh.domain.agent.TerminalLine
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.AgentTerminalPane

/**
 * The chat's terminal history, as a bottom sheet.
 *
 * The menu that used to open this is gone: it moved to the drawer's own
 * `DroshDropdownMenu`, anchored to the overflow pill, so one screen no longer
 * carries two dropdowns. What is left is the sheet itself — the one thing here
 * that was worth keeping.
 *
 * ## One chrome
 *
 * This sheet used to be its own shape with its own grabber and a "Close"
 * written in the corner, and it was the only sheet in the agent whose scrim
 * animated. It is now `AgentSheet`: the 28dp corner and the 38x4 grabber the
 * model picker already used, a drag that follows the finger, and a close that
 * is an icon with a 44dp target. Four sheets that looked like four apps are one
 * sheet that looks like one app.
 *
 * ## Why there is no blur
 *
 * It used to blur whatever was behind it, which on a scrolling transcript
 * meant a second render of everything the user was reading at a moment when
 * the device could least afford it — and a sheet full of text over blurred
 * text is harder to read than one over a flat surface. One opaque surface,
 * one thing to look at.
 */
@Composable
fun TerminalHistorySheet(
    lines: List<TerminalLine>,
    sheetVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!sheetVisible) return

    AgentSheet(
        modifier = modifier,
        title = stringResource(R.string.agent_terminal_history),
        subtitle = if (lines.isEmpty()) null else stringResource(R.string.session_log_line_count, lines.size),
        icon = DroshIcons.SquareTerminal,
        heightFraction = 0.76f,
        onDismiss = onDismiss,
    ) {
        if (lines.isEmpty()) {
            SheetEmptyNote(
                text = stringResource(R.string.agent_no_commands_yet),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 22.dp),
            )
        } else {
            // The commands are cards: one per command, with the output under
            // it. A flat surface, because that is what every other card on
            // this screen is.
            AgentTerminalPane(
                lines = lines,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}
