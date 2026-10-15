package dev.drosh.ui.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * The window preferences the agent chat screen asks of the activity.
 *
 * ## Why this is not a CompositionLocal
 *
 * A composition local flows down. The thing being asked for lives *up*: the
 * navigation bar is a property of the activity window, and the activity is the
 * composable above the whole nav host. A local handed down from `MainActivity`
 * could carry a value into the chat screen, but the chat screen could not hand
 * one back up — so the flag is held in one place both of them can see, written
 * by the screen that wants it and read by the window that owns the bar.
 *
 * The alternative was an argument on `AgentChatScreen` and a route check in
 * `MainActivity`, which reads the navigation stack to decide something about a
 * window: the screen knows when it is on screen and the window does not, so the
 * screen says so.
 *
 * ## Two flags, not one
 *
 * Only the navigation bar is asked for here. The status bar is
 * [dev.drosh.ui.agent.components.DroshStatusBarVisible] and stays that way —
 * the clock is something a user reads while an agent is working, and the
 * navigation bar is a thing that points away from a screen where there is
 * nothing else to go to.
 */
object AgentWindowChrome {
    /**
     * True while a screen wants the system navigation bar hidden.
     *
     * False everywhere else, which is what `MainActivity` restores on the way
     * out: a screen that shows a bar the user had hidden everywhere else is
     * making a decision on their behalf.
     *
     * A `State`, not a delegate: the flag is read from another module, and a
     * delegated property hands its reader the value rather than the state that
     * carries it — which leaves the reader unable to use `by` over it and back to
     * a `.value` they then have to turn into state themselves.
     */
    val navigationBarHidden: State<Boolean>
        get() = hidden

    private val hidden = mutableStateOf(false)

    internal fun requestNavigationBar(hidden: Boolean) {
        this.hidden.value = hidden
    }
}

/**
 * Asks the activity window for a navigation-bar-free bottom edge.
 *
 * A side effect rather than state inside the screen's own composition tree,
 * because the bar belongs to the window and only `MainActivity` can reach it.
 *
 * Restored on the way out rather than left: this is a preference of the screen
 * being looked at, not of the app.
 *
 * The behaviour the window uses is the transient one, so a swipe from an edge
 * still brings the bars back: a screen that hides them is hiding them while it
 * is being looked at, not taking them away.
 *
 * Two flags, not one, because the two bars are different decisions. See
 * [AgentWindowChrome].
 */
@Composable
fun ChatNavBarHidden() {
    SideEffect { AgentWindowChrome.requestNavigationBar(hidden = true) }

    DisposableEffect(Unit) {
        onDispose { AgentWindowChrome.requestNavigationBar(hidden = false) }
    }
}
