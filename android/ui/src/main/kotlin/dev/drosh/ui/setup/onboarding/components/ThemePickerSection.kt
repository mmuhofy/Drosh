package dev.drosh.ui.setup.onboarding.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The theme picker, as a section rather than a page.
 *
 * It used to be a screen of its own, between About and Packages — a preference
 * given a whole page of a three-question setup. Collapsed into the welcome
 * page, it keeps the part that did the work: the grid of palettes, two to a
 * row, so comparing two of them is side by side at the same size. The chosen
 * palette is announced through the chrome — the caller wraps this in
 * [AccentPreview] — rather than through a summary card, because the fastest
 * way to know whether you want a theme is to watch the screen agree with you
 * about it.
 *
 * The grid takes a bounded height from its caller (`weight(1f)` inside a
 * column): a lazy grid without one has no rows to lay out.
 */
@Composable
fun ThemePickerSection(
    choices: List<DroshTheme>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.setup_theme_title),
            style = TextStyle(
                fontFamily = LocalFontSet.current.sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = 0.5.sp,
            ),
            color = DroshText,
        )

        Text(
            text = stringResource(R.string.setup_theme_subtitle),
            style = TextStyle(
                fontFamily = LocalFontSet.current.sans,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            ),
            color = DroshTextMuted,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(choices, key = { it.id }) { theme ->
                ThemeChoiceCard(
                    theme = theme,
                    selected = theme.id == selectedId,
                    onSelect = { onSelect(theme.id) },
                )
            }
        }
    }
}

/** One palette on the shelf: its cover, its name, and whether it is the choice. */
@Composable
private fun ThemeChoiceCard(
    theme: DroshTheme,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) DroshPrimary.copy(alpha = 0.08f) else DroshSurfaceVariant,
        animationSpec = tween(220),
        label = "themeCardContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) DroshPrimary else DroshBorderSubtle,
        animationSpec = tween(220),
        label = "themeCardBorder",
    )

    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            ThemeTerminalPreview(
                palette = theme.palette,
                cornerRadius = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = theme.name,
                    style = TextStyle(
                        fontFamily = LocalFontSet.current.sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = if (selected) DroshPrimary else DroshText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = DroshIcons.Check,
                        contentDescription = null,
                        tint = DroshPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Text(
                text = theme.author.ifBlank { stringResource(R.string.setup_theme_builtin) },
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 11.sp,
                ),
                color = DroshTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
