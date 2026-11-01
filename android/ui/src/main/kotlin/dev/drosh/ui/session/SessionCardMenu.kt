package dev.drosh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons

/**
 * What a held card offers.
 *
 * A dialog rather than a dropdown anchored to the card, because the grid
 * scrolls: a menu pinned to a card's position stops meaning anything the moment
 * the list moves under it, and the card that opened it may not even exist after
 * a scroll.
 *
 * The split entry is the only place the grouping gesture is explained in words.
 * The drag has no label of its own, so this is also how a user finds out that
 * carrying one card onto another joins them.
 */
@Composable
fun SessionCardMenu(
    session: SessionSnapshot,
    onDismiss: () -> Unit,
    onSplit: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(268.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(DroshSurfaceHigh)
                .padding(vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(
                    text = session.name,
                    color = DroshText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Split için başka bir kart seç, ya da bu kartı başka bir karta sürükle.",
                    color = DroshTextMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }

            Spacer(Modifier.height(4.dp))

            MenuEntry(DroshIcons.SquareTerminal, "Split olarak aç", onSplit)
            MenuEntry(DroshIcons.X, "Session'ı kapat", onClose)
        }
    }
}

@Composable
private fun MenuEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DroshTextSecondary,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = label,
            color = DroshText,
            fontSize = 14.sp,
        )
    }
}
