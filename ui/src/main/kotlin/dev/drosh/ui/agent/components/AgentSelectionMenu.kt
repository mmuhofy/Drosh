package dev.drosh.ui.agent.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.ui.DroshIcons

/**
 * The text selection menu for the agent screens.
 *
 * ## Why it is not the terminal's `SelectionMenu`
 *
 * Two different problems, and the terminal solved the wrong one for this screen.
 *
 * `SelectionMenuBackdrop` samples the view beneath the menu into a `Bitmap` and
 * blurs that. It has to: the terminal is a `TerminalView` inside an `AndroidView`,
 * so it is not in Compose's display list at all and Haze's effect nodes find
 * nothing behind the menu. The screenshot is a workaround for the terminal's
 * architecture, and the file says so.
 *
 * The agent transcript is plain Compose, which means the backdrop can be blurred
 * directly — Haze renders it into an offscreen layer and blurs that, every frame,
 * with no rasterisation of the transcript at all. This is the real thing rather
 * than a picture of it: it stays correct while the transcript scrolls underneath,
 * which the bitmap version cannot do, since it re-samples on a 90ms timer and
 * visibly lags a fast scroll.
 *
 * ## What it is for
 *
 * Copy is the reason this menu exists. Everything an agent says is something the
 * user may want to paste into their own editor, a bug report, or a prompt, and
 * without a menu on the agent transcript the only way to get a command out of a
 * tool row is to retype it by hand.
 */
@Composable
fun AgentSelectionMenu(
    selectedText: String,
    onCopy: (String) -> Unit,
    onSelectAll: () -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalAgentGlass.current
    val shape = RoundedCornerShape(14.dp)

    // Enters at 90% and settles, matching the sheet. A menu that simply appears is
    // easy to miss when it arrives over the exact text the user just selected.
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "agentSelectionMenuScale",
    )

    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .then(
                if (glass == null) {
                    // No source to blur, e.g. in a preview. Opaque enough to read.
                    Modifier.background(DroshSurfaceHigh.copy(alpha = 0.96f))
                } else {
                    Modifier
                        .hazeEffect(glass.state, agentSelectionStyle())
                        .background(DroshSurfaceHigh.copy(alpha = 0.72f))
                }
            )
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AgentSelectionAction(
            icon = DroshIcons.Copy,
            label = "Kopyala",
            onClick = { onCopy(selectedText) },
        )
        AgentSelectionAction(
            icon = DroshIcons.Type,
            label = "Tümü",
            onClick = onSelectAll,
        )
        AgentSelectionAction(
            icon = DroshIcons.Share,
            label = "Paylaş",
            onClick = { onShare(selectedText) },
        )
    }
}

/**
 * Stronger blur than the pills use.
 *
 * The menu sits over the exact words the user just selected, so anything readable
 * through it competes with the selection they are acting on. 32dp against the
 * pills' 20dp: a pill can afford to be slightly see-through because there is
 * nothing behind it worth reading.
 */
@Composable
private fun agentSelectionStyle(): HazeStyle = HazeStyle(
    tint = HazeTint(DroshSurfaceHigh.copy(alpha = 0.26f)),
    blurRadius = 32.dp,
)

@Composable
private fun AgentSelectionAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            // No ripple, matching the terminal's selection menu: the menu itself
            // is already the transient surface, and a ripple inside it draws the
            // eye away from the selection being acted on.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DroshText,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                color = DroshTextSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Copies text to the clipboard.
 *
 * Returns Unit rather than a success flag because nothing acts on the result:
 * `setText` on the platform clipboard has no failure path to report, so a Boolean
 * here would be a value every call site ignores.
 */
@Composable
fun rememberAgentClipboard(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) {
        { text: String -> clipboard.setText(AnnotatedString(text)) }
    }
}
