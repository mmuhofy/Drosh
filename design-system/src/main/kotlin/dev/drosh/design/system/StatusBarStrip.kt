package dev.drosh.design.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Paints the status bar strip in a given colour.
 *
 * The app draws edge to edge, so a screen whose content is inset with
 * statusBarsPadding leaves the strip showing whatever happens to be behind it.
 * On the terminal that is the terminal's own backdrop, on the drawer it is
 * whatever the terminal was showing, which reads as the wrong screen bleeding
 * through the system bar.
 *
 * Every screen that insets its content should paint this underneath its own
 * background colour, so the strip always belongs to the screen you are looking
 * at rather than to the one underneath it.
 */
@Composable
fun StatusBarStrip(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val strip = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (strip.value <= 0f) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(strip)
            .background(color),
    )
}
