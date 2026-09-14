package com.iris.irisshell.design.system

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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

enum class IrisMenuItemStyle { Default, Destructive }

private val MIN_MENU_WIDTH = 140.dp
private val MAX_MENU_WIDTH = 280.dp

data class IrisMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val style: IrisMenuItemStyle = IrisMenuItemStyle.Default,
    val dividerBefore: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
fun IrisDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<IrisMenuItem>,
    onItemClick: (IrisMenuItem) -> Unit,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismissRequest,
        offset           = offset,
        shape            = RoundedCornerShape(14.dp),
        containerColor   = IrisSurfaceVariant,
        modifier         = modifier
            .background(IrisSurfaceVariant)
            .widthIn(min = MIN_MENU_WIDTH, max = MAX_MENU_WIDTH),
    ) {
        items.forEachIndexed { index, item ->
            if (item.dividerBefore && index != 0) {
                HorizontalDivider(
                    color    = IrisOutline.copy(alpha = 0.35f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            val textColor = when {
                !item.enabled                               -> IrisTextSecondary.copy(alpha = 0.4f)
                item.style == IrisMenuItemStyle.Destructive -> IrisError
                else                                        -> IrisText
            }
            val iconTint = when {
                !item.enabled                               -> IrisTextSecondary.copy(alpha = 0.3f)
                item.style == IrisMenuItemStyle.Destructive -> IrisError.copy(alpha = 0.85f)
                else                                        -> IrisTextSecondary
            }

            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        onClick = {
                            if (item.enabled) {
                                onDismissRequest()
                                onItemClick(item)
                            }
                        },
                        enabled = item.enabled,
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.icon != null) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint               = iconTint,
                        modifier           = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text  = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
            }
        }
    }
}
