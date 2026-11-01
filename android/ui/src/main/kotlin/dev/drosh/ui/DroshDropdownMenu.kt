package dev.drosh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextSecondary

enum class DroshMenuItemStyle { Default, Destructive }

/**
 * How much room each entry gets, and what shape it presses in.
 *
 * [Compact] is the menu as a context list: it sits next to the thing it acts on
 * and should not push it around. [Slim] is the row's overflow menu — narrower
 * and taller, with a pill press shape, so it takes as little of the terminal as
 * it can while a finger still has a full-height target.
 */
enum class DroshMenuDensity { Compact, Slim }

private val MIN_MENU_WIDTH = 140.dp
private val MAX_MENU_WIDTH = 280.dp

/**
 * The row's overflow menu, as a slim card.
 *
 * Narrower than the default card and taller per entry: the row's menu sits over
 * a terminal the user is reading, so it takes as little of it as it can, and a
 * finger still has a full-height target.
 */
private val SLIM_MENU_MIN_WIDTH = 196.dp
private val SLIM_MENU_MAX_WIDTH = 206.dp

/**
 * The menu's own corners.
 *
 * Soft on purpose: a 14dp popup reads as a tooltip, and these menus are cards
 * that float over the content rather than labels pinned to a control.
 */
private val MENU_SHAPE = RoundedCornerShape(22.dp)

/** The slim card's corners. A step in from [MENU_SHAPE] for its smaller frame. */
private val SLIM_MENU_SHAPE = RoundedCornerShape(16.dp)

/**
 * An entry's press shape.
 *
 * Rounded rather than the default rectangle: the ripple is the only feedback an
 * entry has, and a square ripple inside a soft card reads as a bug.
 */
private val MENU_ITEM_SHAPE = RoundedCornerShape(14.dp)

/** An entry's press shape in the slim card: a pill, the full height of its row. */
private val SLIM_MENU_ITEM_SHAPE = RoundedCornerShape(percent = 50)

data class DroshMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val style: DroshMenuItemStyle = DroshMenuItemStyle.Default,
    val dividerBefore: Boolean = false,
    val enabled: Boolean = true,
    /**
     * Stable identity for click dispatch.
     *
     * A translated label is a display string, not a routing key: a German menu
     * that dispatches on `when (item.label)` silently stops matching the menu
     * it draws. Callers that translate their labels pass an English id here;
     * everything else keeps matching on the label.
     */
    val id: String = label,
)

@Composable
fun DroshDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<DroshMenuItem>,
    onItemClick: (DroshMenuItem) -> Unit,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    modifier: Modifier = Modifier,
    width: Dp? = null,
    /**
     * A heading above the entries, or null for none.
     *
     * A menu that acts on one named thing says which thing at the top rather than
     * leaving the user to remember what they tapped. It is a label, not an entry:
     * it is not clickable and it is separated from the list by a divider.
     */
    title: String? = null,
    /**
     * How much room each entry gets. See [DroshMenuDensity].
     *
     * Defaults to [DroshMenuDensity.Compact] so every existing caller keeps the
     * menu it had; a screen that wants the slim card asks for it.
     */
    density: DroshMenuDensity = DroshMenuDensity.Compact,
) {
    val slim = density == DroshMenuDensity.Slim
    // The slim card's own numbers; the compact menu keeps the padding it had.
    val horizontalPadding = 14.dp
    val verticalPadding = if (slim) 9.dp else 11.dp
    val iconSize = 17.dp
    val iconGap = if (slim) 11.dp else 12.dp
    val labelStyle = MaterialTheme.typography.bodyMedium
    val itemShape = if (slim) SLIM_MENU_ITEM_SHAPE else MENU_ITEM_SHAPE
    val itemMargin = if (slim) 5.dp else 0.dp

    DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismissRequest,
        offset           = offset,
        shape            = if (slim) SLIM_MENU_SHAPE else MENU_SHAPE,
        containerColor   = DroshSurfaceVariant,
        modifier         = modifier
            .background(DroshSurfaceVariant)
            .widthIn(
                min = if (slim) SLIM_MENU_MIN_WIDTH else MIN_MENU_WIDTH,
                max = if (slim) SLIM_MENU_MAX_WIDTH else MAX_MENU_WIDTH,
            )
            // Applied last so a caller pinning a width wins over the min/max range.
            .then(if (width != null) Modifier.width(width) else Modifier),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text     = title,
                style    = MaterialTheme.typography.labelMedium,
                color    = DroshTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 6.dp),
            )
            // The slim card has no rule under its title: its first entry is a
            // pill with a margin, which already separates the two.
            if (!slim) {
                HorizontalDivider(
                    color    = DroshOutline.copy(alpha = 0.35f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        items.forEachIndexed { index, item ->
            if (item.dividerBefore && index != 0) {
                HorizontalDivider(
                    color    = DroshOutline.copy(alpha = 0.35f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            val textColor = when {
                !item.enabled                               -> DroshTextSecondary.copy(alpha = 0.4f)
                item.style == DroshMenuItemStyle.Destructive -> DroshError
                else                                        -> DroshText
            }
            val iconTint = when {
                !item.enabled                               -> DroshTextSecondary.copy(alpha = 0.3f)
                item.style == DroshMenuItemStyle.Destructive -> DroshError.copy(alpha = 0.85f)
                else                                        -> DroshTextSecondary
            }

            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = itemMargin)
                    .clip(itemShape)
                    .clickable(
                        onClick = {
                            if (item.enabled) {
                                onDismissRequest()
                                onItemClick(item)
                            }
                        },
                        enabled = item.enabled,
                    )
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.icon != null) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint               = iconTint,
                        modifier           = Modifier.size(iconSize),
                    )
                    Spacer(Modifier.width(iconGap))
                }
                Text(
                    text  = item.label,
                    style = labelStyle,
                    color = textColor,
                )
            }
        }
    }
}
