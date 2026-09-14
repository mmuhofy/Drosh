package com.iris.irisshell.ui.util

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Blur the background window behind the current Compose tree.
 *
 * Used by [com.iris.irisshell.ui.session.SessionSwitcherSheet] to apply
 * an iOS-style scrim to the terminal while the session-switcher popup
 * is open. Blur targets the **dialog's parent window** so the terminal
 * underneath stays composited — much cheaper than blurring the
 * foreground content, and matches the way Apple's sheet overlays
 * work on iOS.
 *
 * On API < 31 there is no [RenderEffect]; we fall through silently —
 * the host dialog's default scrim still darkens the background.
 *
 * @param radiusDp blur radius in density-independent pixels.
 *   Larger = more blur. Caller typically passes 18–24dp.
 * @param enabled when false the effect is cleared. Defaults to true.
 *   Use this if the popup animates open/closed and you only want the
 *   blur applied while visible.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun BlurDialogWindow(
    radiusDp: Float = 20f,
    enabled: Boolean = true,
) {
    val view: View = LocalView.current
    val dialogWindow = remember(view) { view.context.findActivityWindow() }

    DisposableEffect(dialogWindow, enabled, radiusDp) {
        if (dialogWindow == null || !enabled) {
            return@DisposableEffect onDispose { }
        }
        val px = (radiusDp * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val effect = RenderEffect.createBlurEffect(px.toFloat(), px.toFloat(), Shader.TileMode.CLAMP)
        val decor = dialogWindow.decorView
        decor.setRenderEffect(effect)
        onDispose {
            decor.setRenderEffect(null)
        }
    }
}

private fun android.content.Context.findActivityWindow(): android.view.Window? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}
