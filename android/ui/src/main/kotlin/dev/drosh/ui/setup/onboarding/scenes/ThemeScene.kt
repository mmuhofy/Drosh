package dev.drosh.ui.setup.onboarding.scenes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.LocalFontSet
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.setup.SetupWizardViewModel
import dev.drosh.ui.setup.components.PillButton
import dev.drosh.ui.setup.onboarding.components.AccentPreview
import dev.drosh.ui.setup.onboarding.components.ThemePalettePreview
import dev.drosh.ui.setup.onboarding.components.ThemeTerminalPreview
import dev.drosh.ui.setup.onboarding.components.previewColor

/**
 * The theme page: a grid of palettes, two to a row.
 *
 * Placed after About and before Packages. After, because a palette is a
 * preference and a preference is not the first thing anyone is asked during a
 * setup; before Packages, because the packages page is a long column of choices
 * with a Continue at the bottom, and the page after a long column is the one
 * nobody reads.
 *
 * Two per row rather than one: at one per row the grid is a list with extra
 * steps, and comparing two palettes is the entire job of this screen — which
 * needs them side by side, at the same size, next to each other.
 *
 * The chrome redraws in the selected accent while the finger is still
 * down. That is [SetupWizardViewModel.previewPalette] reaching the composition
 * through [AccentPreview], and it is not decoration: a theme is mostly one
 * colour, and the fastest way to know whether you want it is to see whether the
 * app agrees with you about it.
 */
@Composable
fun ThemeScene(
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val choices by viewModel.themeChoices.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedThemeId.collectAsStateWithLifecycle()
    val palette by viewModel.previewPalette.collectAsStateWithLifecycle()
    val applied by viewModel.themeApplied.collectAsStateWithLifecycle()

    AccentPreview(accent = previewColor(palette.accent)) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(DroshBackground)
                .padding(horizontal = 28.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.setup_theme_title),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    letterSpacing = 0.5.sp,
                ),
                color = DroshText,
            )

            Text(
                text = stringResource(R.string.setup_theme_subtitle),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshTextMuted,
            )

            Spacer(modifier = Modifier.height(20.dp))

            SelectedThemeSummary(
                theme = DroshTheme.find(selectedId, choices),
                applied = applied,
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(choices, key = { it.id }) { theme ->
                    ThemeChoiceCard(
                        theme = theme,
                        selected = theme.id == selectedId,
                        onSelect = { viewModel.selectTheme(theme.id) },
                    )
                }
            }

            PillButton(
                text = stringResource(R.string.setup_theme_continue),
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            )
        }
    }
}

/**
 * The chosen palette, in full, with the line that says it is not just a picture.
 *
 * The grid below is a comparison surface — two to a row, small, best for telling
 * neighbours apart. This is the other half: what the one under the finger looks
 * like on its own terms, at the size it will actually be.
 *
 * The "saved" line is absent until the bootstrap has finished and the palette is
 * in the file the terminal reads, because that is when it becomes true. A check
 * shown earlier would be a promise the app had not kept yet — and this feature
 * shipped once as exactly that promise.
 */
@Composable
private fun SelectedThemeSummary(
    theme: DroshTheme?,
    applied: Boolean,
) {
    if (theme == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DroshSurfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemePalettePreview(
            palette = theme.palette,
            cornerRadius = 8.dp,
            modifier = Modifier.size(56.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.name,
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                ),
                color = DroshText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (applied) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = DroshIcons.Check,
                        contentDescription = null,
                        tint = DroshPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(R.string.setup_theme_applied),
                        style = TextStyle(
                            fontFamily = LocalFontSet.current.sans,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = DroshPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
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