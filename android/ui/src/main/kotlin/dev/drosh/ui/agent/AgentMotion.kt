package dev.drosh.ui.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * The screen's one clock and one sweep.
 *
 * ## Why the elapsed seconds are shared
 *
 * Every tool row and every reasoning row used to run its own
 * `LaunchedEffect { while (true) { delay(1000); elapsed++ } }`. Twenty tool
 * calls on a screen is twenty coroutines, and each one woke its row once a
 * second for as long as the screen was open — including the rows that finished
 * minutes ago, because the loop was never cancelled for a row that stopped
 * running. Now there is one ticker for the screen, and a row subtracts the
 * value it saw when it started.
 *
 * ## Why the sweep is shared, and why it is a lambda
 *
 * The sweep was the larger half of the cost. Every row built its own
 * `rememberInfiniteTransition` and read the animated value while composing, so
 * a *finished* row — a past-tense verb, no gradient anywhere on screen — was
 * still recomposing on the animation's frame clock, sixty times a second, for
 * the whole time the transcript was open. With a long conversation that is a
 * few hundred rows doing work nobody can see, and it is most of the scroll lag.
 *
 * One transition is created for the screen here. It is handed out as a
 * **provider lambda** rather than as a `Float`, and a row only calls it when it
 * is actually running — so a finished row never reads the state, Compose never
 * marks it dirty, and the animation has nothing to invalidate. Reading
 * `progress` directly at the top of a row would have reintroduced the whole
 * problem, because the read is what subscribes.
 */
@Stable
class AgentMotion(
    /** Seconds since the screen mounted; a row subtracts its own start. */
    val elapsedSeconds: State<Int>,
    /** The sweep position in 0..1. Only call it while something is running. */
    val sweep: () -> Float,
)

private val LocalAgentMotion = staticCompositionLocalOf<AgentMotion?> { null }

/**
 * Start the screen's clock and sweep.
 *
 * @param ticking whether anything is running. The clock is not started when
 *        nothing is: an idle transcript has no elapsed time to show, and a
 *        ticker that keeps waking to recompute a number nobody reads is the
 *        same cost in a quieter form.
 */
@Composable
fun rememberAgentMotion(ticking: Boolean): AgentMotion {
    val seconds = remember { mutableIntStateOf(0) }
    LaunchedEffect(ticking) {
        if (!ticking) return@LaunchedEffect
        while (true) {
            delay(1_000)
            seconds.intValue++
        }
    }

    val transition = rememberInfiniteTransition(label = "agentSweep")
    // Not read here on purpose — see the class note. The row that is running is
    // the only thing that reads it, which is what keeps finished rows idle.
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SWEEP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "agentSweepProgress",
    )

    return remember(seconds, progress) {
        AgentMotion(
            elapsedSeconds = seconds,
            sweep = { progress.value },
        )
    }
}

/** Put the screen's motion in scope for the transcript's rows. */
@Composable
fun ProvideAgentMotion(motion: AgentMotion, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAgentMotion provides motion, content = content)
}

/**
 * Seconds since [from], or 0 when the screen has no clock running.
 *
 * Null motion — a row previewed on its own, outside the provider — answers 0,
 * so a previewed row shows `0s` rather than not composing at all.
 */
@Composable
fun elapsedSince(from: Int): Int {
    val motion = LocalAgentMotion.current ?: return 0
    return motion.elapsedSeconds.value - from
}

/** The sweep position, or 0 when there is no transition in scope. */
@Composable
fun sweepPosition(): Float = LocalAgentMotion.current?.sweep?.invoke() ?: 0f

/**
 * The clock value a running row started from.
 *
 * `produceState` rather than `remember`, because the row is composed before the
 * call is running and the start has to be the moment it *began*, not the moment
 * it first appeared on screen — a row restored from a stored transcript must
 * not come back claiming it has been running for as long as the app was open.
 */
@Composable
fun rememberRunningStart(running: Boolean): Int {
    val motion = LocalAgentMotion.current
    return produceState(initialValue = motion?.elapsedSeconds?.value ?: 0, running, motion) {
        if (running) {
            value = motion?.elapsedSeconds?.value ?: 0
        }
    }.value
}

private const val SWEEP_MS = 1200