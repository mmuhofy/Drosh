package dev.drosh.ui.agent.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keeps the system status bar visible on the agent screens.
 *
 * ## Why an agent screen has to ask
 *
 * `TerminalScreen` hides the status bar outright when the immersive setting is on,
 * which is the right call there: a terminal is full-bleed text and the bar is a row
 * of pixels stolen from it.
 *
 * But it hides it through `WindowInsetsControllerCompat` on the *window*, and that
 * state outlives the composable. Nothing in the terminal screen restores it, so
 * walking from the terminal into the agent arrived with no status bar — and stayed
 * that way, which is how a hidden bar can still be recovered by a swipe but is not
 * something a user should have to discover.
 *
 * It is also simply wrong for these screens. The pill row's top padding is the
 * status bar's height, so with the bar hidden that padding became dead space above
 * the controls and the row sat lower than it should for no reason.
 *
 * Restored on the way out rather than left showing: this is a preference of the
 * screen being looked at, not of the app, and a screen that shows a bar the user
 * had hidden everywhere else is making a decision on their behalf.
 */
@Composable
fun DroshStatusBarVisible() {
    // Read outside `remember`: a composition local cannot be read inside a
    // non-composable lambda, and hoisting it here also re-resolves when the context
    // changes rather than keeping the activity it happened to have at first frame.
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    LaunchedEffect(activity) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Same escape hatch the terminal sets: a swipe from the top can still
            // summon the bars, so nobody can be locked out of them.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.statusBars())
        }
    }

    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
