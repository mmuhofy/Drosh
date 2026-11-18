package dev.drosh.ui.setup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.setup.components.PillButtonFull

/**
 * The first of two onboarding screens: what Drosh is, and the one action that
 * installs it.
 *
 * No palette is picked here. The app draws in its own tokens, and setup starts
 * with the defaults — the theme is a Settings concern, not a prerequisite for
 * getting a shell. Every claim on this screen names something that ships: the
 * Ghostty VT core and our OpenGL renderer, the eight agent tools and four
 * provider protocols, SSH, the editor, split panes, backup, the Ubuntu bash
 * rootfs and the ten bundled locales.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val appear by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "welcome-appear",
    )
    val rise by animateFloatAsState(
        targetValue = if (entered) 0f else 12f,
        animationSpec = tween(durationMillis = 260),
        label = "welcome-rise",
    )

    Box(modifier = modifier.fillMaxSize().background(DroshBackground)) {
        // A single quiet halo behind the wordmark. It gives the page a centre
        // without introducing a second accent colour the theme does not own.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-140).dp)
                .size(420.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(DroshPrimary.copy(alpha = 0.18f), Color.Transparent),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .graphicsLayer {
                    alpha = appear
                    translationY = rise
                }
                .padding(horizontal = 24.dp)
                .padding(top = 52.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark()

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.welcome_headline),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 29.sp,
                    lineHeight = 35.sp,
                    letterSpacing = 0.4.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshText,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.welcome_headline_accent),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshPrimary,
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshTextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.welcome_features_title),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                ),
                color = DroshPrimary,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            FeatureGrid()

            Spacer(modifier = Modifier.height(28.dp))

            PillButtonFull(
                text = stringResource(R.string.welcome_get_started),
                onClick = onGetStarted,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.welcome_setup_note),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshTextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DroshPrimary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DroshIcons.Terminal,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.welcome_brand),
            style = TextStyle(
                fontFamily = LocalFontSet.current.sans,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                letterSpacing = 3.sp,
            ),
            color = DroshText,
        )
    }
}

@Composable
private fun FeatureGrid(modifier: Modifier = Modifier) {
    val features = listOf(
        WelcomeFeature(
            icon = DroshIcons.Terminal,
            title = stringResource(R.string.welcome_feature_terminal_title),
            detail = stringResource(R.string.welcome_feature_terminal_detail),
        ),
        WelcomeFeature(
            icon = DroshIcons.Brain,
            title = stringResource(R.string.welcome_feature_agent_title),
            detail = stringResource(R.string.welcome_feature_agent_detail),
        ),
        WelcomeFeature(
            icon = DroshIcons.Globe,
            title = stringResource(R.string.welcome_feature_ssh_title),
            detail = stringResource(R.string.welcome_feature_ssh_detail),
        ),
        WelcomeFeature(
            icon = DroshIcons.Pencil,
            title = stringResource(R.string.welcome_feature_editor_title),
            detail = stringResource(R.string.welcome_feature_editor_detail),
        ),
        WelcomeFeature(
            icon = DroshIcons.PanelLeft,
            title = stringResource(R.string.welcome_feature_panes_title),
            detail = stringResource(R.string.welcome_feature_panes_detail),
        ),
        WelcomeFeature(
            icon = DroshIcons.Archive,
            title = stringResource(R.string.welcome_feature_backup_title),
            detail = stringResource(R.string.welcome_feature_backup_detail),
        ),
        WelcomeFeature(
            icon = DroshIcons.ShieldCheck,
            title = stringResource(R.string.welcome_feature_shell_title),
            detail = stringResource(R.string.welcome_feature_shell_detail),
        ),
        WelcomeFeature(
            icon = DroshIcons.Languages,
            title = stringResource(R.string.welcome_feature_locales_title),
            detail = stringResource(R.string.welcome_feature_locales_detail),
        ),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        features.chunked(2).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(modifier = Modifier.height(8.dp))
            Row {
                row.forEachIndexed { columnIndex, feature ->
                    if (columnIndex > 0) Spacer(modifier = Modifier.width(8.dp))
                    FeatureCard(
                        feature = feature,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private data class WelcomeFeature(
    val icon: ImageVector,
    val title: String,
    val detail: String,
)

@Composable
private fun FeatureCard(
    feature: WelcomeFeature,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(DroshPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = feature.title,
            style = TextStyle(
                fontFamily = LocalFontSet.current.sans,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = DroshText,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = feature.detail,
            style = TextStyle(
                fontFamily = LocalFontSet.current.sans,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            color = DroshTextMuted,
            minLines = 2,
            maxLines = 2,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    WelcomeScreen(onGetStarted = {})
}
