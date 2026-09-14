package com.iris.irisshell.ui.input

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.iris.irisshell.domain.input.ExtraKey
import com.iris.irisshell.domain.input.InputIntent

private val PILL_SHAPE = RoundedCornerShape(26.dp)

@Composable
fun ExtraKeyBar(
    ctrlStuck: Boolean,
    altStuck: Boolean,
    terminalView: View?,
    onIntent: (InputIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activePopup: ExtraKey.Special? by remember { mutableStateOf(null) }
    var moreOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = moreOpen,
            enter = fadeIn(tween(150)) + scaleIn(
                initialScale = 0.98f,
                animationSpec = tween(
                    180,
                    easing = FastOutSlowInEasing,
                ),
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
                    onComboSelected = { intents ->
                        intents.forEach(onIntent)
                    },
                    onDismiss = {
                        activePopup = null
                    },
                )
            }
        }

        if (moreOpen) {
            Spacer(
                modifier = Modifier.height(8.dp),
            )
        }

        Box(
            modifier = Modifier
                .width(390.dp)
                .height(50.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = PILL_SHAPE,
                    clip = false,
                )
                .clip(PILL_SHAPE),
        ) {
            if (terminalView != null) {
                AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = {
                        LiquidGlassBackdropView(
                            sourceView = terminalView,
                        )
                    },
                    update = {
                        it.markDirty()
                    },
                )
            }

            LiquidGlassSurface(
                modifier = Modifier.matchParentSize(),
            )

            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(
                        horizontal = 6.dp,
                        vertical = 5.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val modifierKeys = listOf(
                    ExtraKey.Navigation.ESC,
                    ExtraKey.Special.CTRL,
                    ExtraKey.Special.ALT,
                    ExtraKey.Navigation.TAB,
                )

                modifierKeys.forEach { key ->
                    val stuck = when (key) {
                        ExtraKey.Special.CTRL -> ctrlStuck
                        ExtraKey.Special.ALT -> altStuck
                        else -> false
                    }

                    ExtraKeyButton(
                        key = key,
                        stuckActive = stuck,
                        onTap = {
                            onIntent(key.toSingleIntent())
                        },
                        onLongPress = {
                            if (key is ExtraKey.Special) {
                                activePopup = key
                                moreOpen = false
                            }
                        },
                        modifier = Modifier.width(48.dp),
                    )
                }

                Spacer(
                    modifier = Modifier.width(5.dp),
                )

                val navigationKeys = listOf(
                    ExtraKey.Navigation.ARROW_UP,
                    ExtraKey.Navigation.ARROW_DOWN,
                    ExtraKey.Navigation.ARROW_LEFT,
                    ExtraKey.Navigation.ARROW_RIGHT,
                )

                navigationKeys.forEach { key ->
                    ExtraKeyButton(
                        key = key,
                        stuckActive = false,
                        onTap = {
                            onIntent(key.toSingleIntent())
                        },
                        onLongPress = {},
                        modifier = Modifier.width(37.dp),
                    )
                }

                MoreKeyButton(
                    expanded = moreOpen,
                    onClick = {
                        moreOpen = !moreOpen
                        activePopup = null
                    },
                )
            }
        }
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
        modifier = Modifier.clip(
            RoundedCornerShape(18.dp),
        ),
    ) {
        LiquidGlassSurface(
            modifier = Modifier.matchParentSize(),
        )

        Row(
            modifier = Modifier.padding(8.dp),
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
                    )
                }
            }
        }
    }
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
            text = "•••",
            fontSize = 17.sp,
        )
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