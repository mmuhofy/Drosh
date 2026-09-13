package com.iris.irisshell.ui.input

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.iris.irisshell.design.system.IrisBackground
import com.iris.irisshell.design.system.IrisBorderSubtle
import com.iris.irisshell.design.system.IrisPrimary
import com.iris.irisshell.design.system.IrisSurface
import com.iris.irisshell.design.system.IrisText
import com.iris.irisshell.design.system.IrisTextMuted
import com.iris.irisshell.domain.input.ExtraKey
import com.iris.irisshell.domain.input.InputIntent

private val KEY_CORNER = RoundedCornerShape(4.dp)
private val BAR_CORNER = RoundedCornerShape(0.dp)

/**
 * Flat key bar — text-only keys with blur behind, no surface background.
 *
 * Inspired by the HTML mockup `html/irisshell_keybar_flat_mockup.html`.
 * Keys are text-only (transparent background), positioned above the
 * terminal at the bottom of the screen. On Android 12+ the bar uses
 * RenderEffect blur sampling whatever is behind it; on API 26-30 a
 * semi-transparent fallback is used instead.
 *
 * Blur level can be controlled via [blurLevel] parameter (in pixels).
 * Default: 8px (matches HTML mockup).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlatKeyBar(
    ctrlStuck: Boolean,
    altStuck: Boolean,
    terminalView: View?,
    onIntent: (InputIntent) -> Unit,
    blurLevel: Float = 8f,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        // Blurred frosted-glass background — semi-opaque so the surface
        // is clearly visible while the blur + terminal beneath shows through.
        Box(
            modifier = Modifier
                .matchParentSize()
                .let { bg ->
                    if (canBlur) {
                        val effect = RenderEffect.createBlurEffect(
                            blurLevel, blurLevel, Shader.TileMode.MIRROR,
                        )
                        bg
                            .background(
                                IrisSurface.copy(alpha = 0.75f),
                            )
                            .graphicsLayer {
                                renderEffect = effect.asComposeRenderEffect()
                            }
                    } else {
                        bg
                            .background(
                                IrisSurface.copy(alpha = 0.85f),
                            )
                    }
                }
                .clip(BAR_CORNER),
        )

        // Foreground content — keys rendered sharp, no blur applied.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            KeyGroupSection(
                keys = listOf(
                    FlatKeySpec.Text("Esc", ExtraKey.Navigation.ESC),
                    FlatKeySpec.Primary("Ctrl", ExtraKey.Special.CTRL, ctrlStuck),
                    FlatKeySpec.Primary("Alt", ExtraKey.Special.ALT, altStuck),
                    FlatKeySpec.Primary("Tab", ExtraKey.Navigation.TAB, false),
                ),
                onIntent = onIntent,
                onLongPressPrimary = { key, open ->
                    if (key is ExtraKey.Special) open(key)
                },
            )

            KeyDivider()

            KeyGroupSection(
                keys = listOf(
                    FlatKeySpec.Text("↑", ExtraKey.Navigation.ARROW_UP),
                    FlatKeySpec.Text("←", ExtraKey.Navigation.ARROW_LEFT),
                    FlatKeySpec.Text("↓", ExtraKey.Navigation.ARROW_DOWN),
                    FlatKeySpec.Text("→", ExtraKey.Navigation.ARROW_RIGHT),
                ),
                onIntent = onIntent,
                onLongPressPrimary = { _, _ -> },
            )

            KeyDivider()

            KeyGroupSection(
                keys = listOf(
                    FlatKeySpec.Text("Home", ExtraKey.Navigation.HOME),
                    FlatKeySpec.Text("End", ExtraKey.Navigation.END),
                    FlatKeySpec.Text("PgUp", ExtraKey.Navigation.PAGE_UP),
                    FlatKeySpec.Text("PgDn", ExtraKey.Navigation.PAGE_DOWN),
                ),
                onIntent = onIntent,
                onLongPressPrimary = { _, _ -> },
            )

            KeyDivider()

            KeyGroupSection(
                keys = listOf(
                    FlatKeySpec.Text("BkSPC", ExtraKey.Navigation.HOME),
                    FlatKeySpec.Text("Enter", ExtraKey.Text("\n")),
                    FlatKeySpec.Text("Space", ExtraKey.Text(" ")),
                ),
                onIntent = onIntent,
                onLongPressPrimary = { _, _ -> },
            )

            KeyDivider()

            KeyGroupSection(
                keys = (1..12).map { n ->
                    FlatKeySpec.Fn("F$n", ExtraKey.Text("F$n"))
                },
                onIntent = onIntent,
                onLongPressPrimary = { _, _ -> },
            )
        }
    }
}

private data class FlatKeySpec(
    val label: String,
    val key: ExtraKey,
    val style: Style,
) {
    sealed interface Style
    object Default : Style
    object Primary : Style
    object Fn : Style

    companion object {
        fun Text(label: String, key: ExtraKey) = FlatKeySpec(label, key, Default)
        fun Primary(label: String, key: ExtraKey, stuck: Boolean) =
            FlatKeySpec(label, key, if (stuck) Primary else Default)
        fun Fn(label: String, key: ExtraKey) = FlatKeySpec(label, key, Fn)
    }
}

@Composable
private fun KeyGroupSection(
    keys: List<FlatKeySpec>,
    onIntent: (InputIntent) -> Unit,
    onLongPressPrimary: (ExtraKey, (ExtraKey) -> Unit) -> Unit,
) {
    var activePopup: ExtraKey.Special? by remember { mutableStateOf(null) }
    var moreOpen by remember { mutableStateOf(false) }

    Row {
        keys.forEach { spec ->
            FlatKeyButton(
                spec = spec,
                onClick = { onIntent(spec.key.toSingleIntent()) },
                onLongPress = {
                    if (spec.key is ExtraKey.Special && spec.style == FlatKeySpec.Primary) {
                        activePopup = spec.key
                    }
                },
            )
        }
    }

    AnimatedVisibility(
        visible = moreOpen,
        enter = fadeIn(tween(150)) + scaleIn(
            initialScale = 0.98f,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
        ),
        exit = fadeOut(tween(110)) + scaleOut(
            targetScale = 0.98f,
            animationSpec = tween(120),
        ),
    ) {
        MoreKeysPanel(
            onIntent = onIntent,
            onDismiss = { moreOpen = false },
        )
    }

    AnimatedVisibility(
        visible = activePopup != null && !moreOpen,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(100)),
    ) {
        activePopup?.let { modifierKey ->
            ModifierPopup(
                modifier = modifierKey,
                onComboSelected = { intents -> intents.forEach(onIntent) },
                onDismiss = { activePopup = null },
            )
        }
    }
}

@Composable
private fun FlatKeyButton(
    spec: FlatKeySpec,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val textColor = when {
        spec.style == FlatKeySpec.Primary -> IrisPrimary
        hovered || pressed -> IrisText
        else -> IrisTextMuted
    }

    val fontSize = when (spec.style) {
        is FlatKeySpec.Fn -> 11.sp
        else -> 13.sp
    }

    val fontWeight = when (spec.style) {
        is FlatKeySpec.Primary -> FontWeight.SemiBold
        else -> FontWeight.Normal
    }

    val keyWidth = when (spec.style) {
        is FlatKeySpec.Fn -> 36.dp
        else -> 44.dp
    }

    Box(
        modifier = Modifier
            .width(keyWidth)
            .height(36.dp)
            .clip(KEY_CORNER)
            .hoverable(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress.takeIf { it != {} },
            ),
    ) {
        if (hovered || pressed) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawRoundRect(
                    color = if (spec.style == FlatKeySpec.Primary)
                        IrisPrimary.copy(alpha = 0.10f)
                    else
                        IrisText.copy(alpha = 0.06f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        4.dp.toPx(),
                        4.dp.toPx(),
                    ),
                )
            }
        }

        Text(
            text = spec.label,
            color = textColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun KeyDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        IrisBackground,
                        IrisBorderSubtle,
                        IrisBackground,
                    ),
                ),
            ),
    )
}

@Composable
private fun MoreKeyButton(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(38.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (expanded) "▲" else "•••",
            fontSize = 17.sp,
            color = IrisTextMuted,
        )
    }
}

@Composable
private fun MoreKeysPanel(
    onIntent: (InputIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val keys = listOf(
        "HOME" to InputIntent.Navigate(ExtraKey.Navigation.HOME),
        "END" to InputIntent.Navigate(ExtraKey.Navigation.END),
        "PGUP" to InputIntent.Navigate(ExtraKey.Navigation.PAGE_UP),
        "PGDN" to InputIntent.Navigate(ExtraKey.Navigation.PAGE_DOWN),
        "|" to InputIntent.TypeChar('|'),
        "~" to InputIntent.TypeChar('~'),
        "/" to InputIntent.TypeChar('/'),
        "\\" to InputIntent.TypeChar('\\'),
        "_" to InputIntent.TypeChar('_'),
        "-" to InputIntent.TypeChar('-'),
    )

    Box(
        modifier = Modifier.clip(RoundedCornerShape(18.dp)),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(IrisSurface.copy(alpha = 0.85f)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            keys.forEach { (label, intent) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onIntent(intent)
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        color = IrisTextMuted,
                    )
                }
            }
        }
    }
}

private fun ExtraKey.toSingleIntent(): InputIntent = when (this) {
    is ExtraKey.Special ->
        InputIntent.ArmModifier(this)
    is ExtraKey.Text ->
        InputIntent.TypeChar(glyph.first())
    is ExtraKey.Navigation ->
        InputIntent.Navigate(this)
}
