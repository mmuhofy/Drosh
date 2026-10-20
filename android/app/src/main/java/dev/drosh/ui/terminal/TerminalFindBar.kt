package dev.drosh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The terminal's find bar: a query, where it is, and a way out.
 *
 * Four controls and a counter, on one row, over the terminal. The counter is
 * the point of the whole thing — it is what tells the user whether the text
 * they typed is in the output at all, and "3/41" says more than any highlight
 * can.
 *
 * It is not draggable, deliberately. The old bar was, and it cost a
 * `pointerInput` that claimed every drag on the row: the field could not be
 * scrolled with a finger on it, the arrows could not be reached without
 * lifting, and the bar could be left somewhere off the terminal entirely.
 * A find bar sits at the top of what it is searching, full stop.
 *
 * Every target is [TOUCH_TARGET] tall even when the row is not: the icons are
 * drawn small and the touch area around them is not, because a finger does
 * not shrink to fit an icon.
 */
@Composable
fun TerminalFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The query is the one thing this bar exists for, so it takes the keyboard
    // the moment it appears. Not on focus alone — the keyboard is the user
    // asking to type, and a find bar that opened without it would make them
    // tap the field first, every time.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(FIND_BAR_SHAPE)
            .background(DroshSurfaceVariant)
            .border(1.dp, DroshBorderSubtle.copy(alpha = 0.3f), FIND_BAR_SHAPE),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOUCH_TARGET)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = DroshIcons.Search,
                contentDescription = null,
                tint = DroshTextSecondary,
                modifier = Modifier.size(16.dp),
            )

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        color = DroshText,
                        fontSize = 13.sp,
                    ),
                ),
                cursorBrush = SolidColor(DroshPrimary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    // Enter walks to the next match, the way every other find
                    // bar on every platform does. It also dismisses the
                    // keyboard, which is what makes the next Enter a Search
                    // again rather than a second one while typing.
                    onSearch = {
                        onNext()
                        keyboard?.hide()
                    },
                ),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.terminal_find_hint),
                            color = DroshTextMuted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )

            // The counter, and only once there is something to count. An empty
            // query reads "0/0", which is a lie — it has found nothing because
            // it has not been asked anything.
            if (query.isNotEmpty()) {
                val counter = if (matchCount > 0) {
                    stringResource(R.string.terminal_find_counter, currentMatch, matchCount)
                } else {
                    stringResource(R.string.terminal_find_no_matches)
                }
                Text(
                    text = counter,
                    color = if (matchCount > 0) DroshTextSecondary else DroshTextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }

            FindIconButton(
                icon = DroshIcons.ChevronUp,
                contentDescription = stringResource(R.string.terminal_find_previous),
                onClick = onPrevious,
                // With one match there is no previous to go to, and an arrow
                // that appears to do nothing is worse than one that is off.
                enabled = matchCount > 1,
            )

            FindIconButton(
                icon = DroshIcons.ChevronDown,
                contentDescription = stringResource(R.string.terminal_find_next),
                onClick = onNext,
                enabled = matchCount > 1,
            )

            FindIconButton(
                icon = DroshIcons.X,
                contentDescription = stringResource(R.string.terminal_find_close),
                onClick = onClose,
                enabled = true,
            )
        }
    }
}

/**
 * One small icon with a full [TOUCH_TARGET] around it.
 *
 * The icon stays 16dp — a row of four full-size icons would be a row of
 * four buttons and no room for the query — and the touch area around it is
 * what the platform asks for. An icon smaller than the finger that has to
 * hit it is how the old bar's arrows missed.
 */
@Composable
private fun FindIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val tint = if (enabled) DroshTextSecondary else DroshTextMuted.copy(alpha = 0.4f)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(TOUCH_TARGET),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The find bar's shape: a capsule, matching the pills it sits under.
 */
private val FIND_BAR_SHAPE = RoundedCornerShape(20.dp)

/**
 * The touch target of every control on the row, and the row's own height.
 *
 * 44dp is the platform's minimum, and a find bar is a row of small targets:
 * two arrows, a close, and a field between them.
 */
private val TOUCH_TARGET = 44.dp
