package dev.drosh.ui.session

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * A transparent search pill inside the screen.
 *
 * It filters session cards by the user's name, the program's title or a rename
 * made in this screen. No filled background: on the dark theme a hairline
 * border and the search glyph are enough to say "this is an input".
 */
@Composable
internal fun SessionSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, bottom = 4.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(percent = 50))
            .border(1.dp, DroshBorderSubtle.copy(alpha = 0.6f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = DroshIcons.Search,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(16.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.session_screen_search_hint),
                    color = DroshTextMuted,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = DroshText, fontSize = 13.sp),
                cursorBrush = SolidColor(DroshPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = { onQueryChange("") },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DroshIcons.X,
                    contentDescription = stringResource(R.string.session_screen_search_clear),
                    tint = DroshTextMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * The transparent + button at the bottom, beside the existing new-session
 * pill. It has no fill at all — just a hairline ring around a plus glyph.
 */
@Composable
internal fun TransparentAddButton(onNewSession: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(1.dp, DroshBorderSubtle, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onNewSession,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DroshIcons.Plus,
            contentDescription = stringResource(R.string.session_screen_add),
            tint = DroshTextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}
