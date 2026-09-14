package com.iris.irisshell.data.terminal

import com.iris.irisshell.data.di.ApplicationScope
import com.iris.irisshell.domain.terminal.BootstrapError
import com.iris.irisshell.domain.terminal.BootstrapProgress
import com.iris.irisshell.domain.terminal.BootstrapStep
import com.iris.irisshell.domain.terminal.ObserveBootstrapUseCase
import com.iris.irisshell.domain.terminal.RecoveryAction
import com.iris.irisshell.domain.terminal.StepState
import com.iris.irisshell.terminal.BootstrapStatePort
import com.iris.irisshell.terminal.UbuntuSetupState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:data` implementation of [ObserveBootstrapUseCase].
 *
 * Pulls raw state from the terminal module's [BootstrapStatePort] and
 * translates each [UbuntuSetupState] into the `:domain` [BootstrapProgress]
 * shape the UI consumes. Forwarding log lines from the port into a SharedFlow
 * ring buffer gives the UI a clean `Flow<String>` without ever importing
 * `:terminal` types.
 *
 * Per AGENT.md §125-128 this is the only sanctioned seam between `ui/` and
 * `terminal/`.
 */
@Singleton
class BootstrapObserver @Inject constructor(
    private val port: BootstrapStatePort,
    @ApplicationScope private val scope: CoroutineScope,
) : ObserveBootstrapUseCase {

    /** Bounded ring buffer of recent log lines for the UI live-log drawer. */
    private val _ring = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = LOG_BUFFER_SIZE,
    )

    init {
        scope.launchCollect(port)
    }

    private fun CoroutineScope.launchCollect(port: BootstrapStatePort) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            port.logs.collect { line ->
                _ring.tryEmit(line)
            }
        }
    }

    override fun progress(): Flow<BootstrapProgress> = port.state
        .map { state -> translate(state) }
        .stateIn(scope, SharingStarted.Eagerly, translate(port.state.value))

    override fun liveLogs(): Flow<String> = _ring.asSharedFlow()

    private fun translate(state: UbuntuSetupState): BootstrapProgress {
        val stepStates = stepStatesFor(state)
        val (percent, remaining) = percentAndEtaFor(state)
        val currentMessage = messageFor(state)
        val error = (state as? UbuntuSetupState.Failed)?.let { f ->
            BootstrapError(
                step = inferFailedStep(),
                shortMessage = f.error,
                lastLogLines = emptyList(),
                recoveryActions = listOf(
                    RecoveryAction.Retry,
                    RecoveryAction.ReDownloadRootfs,
                    RecoveryAction.ResetEverything,
                    RecoveryAction.ReportIssue,
                ),
            )
        }
        return BootstrapProgress(
            currentStep = mapCurrent(state),
            stepStates = stepStates,
            percent = percent,
            estimatedRemainingMs = remaining,
            currentMessage = currentMessage,
            error = error,
        )
    }

    private fun mapCurrent(state: UbuntuSetupState): BootstrapStep = when (state) {
        UbuntuSetupState.Idle -> BootstrapStep.Idle
        UbuntuSetupState.Extracting -> BootstrapStep.Extracting
        UbuntuSetupState.Configuring -> BootstrapStep.Configuring
        is UbuntuSetupState.InstallingPackages -> BootstrapStep.InstallingPackages
        is UbuntuSetupState.InstallingOhMyZsh -> BootstrapStep.InstallingOhMyZsh
        UbuntuSetupState.Optimizing -> BootstrapStep.Optimizing
        UbuntuSetupState.Ready -> BootstrapStep.Ready
        is UbuntuSetupState.Failed -> BootstrapStep.Failed
    }

    private fun inferFailedStep(): BootstrapStep = BootstrapStep.Configuring

    private fun stepStatesFor(state: UbuntuSetupState): Map<BootstrapStep, StepState> {
        val ordered = listOf(
            BootstrapStep.Extracting,
            BootstrapStep.Configuring,
            BootstrapStep.InstallingPackages,
            BootstrapStep.InstallingOhMyZsh,
            BootstrapStep.Optimizing,
        )
        val currentIdx = mapCurrent(state).ordinalHint
        return ordered.associateWith { step ->
            when {
                currentIdx < 0 -> StepState.Pending
                step.ordinalHint < currentIdx -> StepState.Done
                step.ordinalHint == currentIdx -> StepState.Active
                else -> StepState.Pending
            }
        }
    }

    private fun percentAndEtaFor(state: UbuntuSetupState): Pair<Int, Long?> {
        // Rough budget: ~40s total. Per-step ETA scales inversely with percent.
        val pct = when (state) {
            UbuntuSetupState.Idle -> 0
            UbuntuSetupState.Extracting -> 15
            UbuntuSetupState.Configuring -> 35
            is UbuntuSetupState.InstallingPackages -> 55
            is UbuntuSetupState.InstallingOhMyZsh -> 80
            UbuntuSetupState.Optimizing -> 92
            UbuntuSetupState.Ready -> 100
            is UbuntuSetupState.Failed -> 0
        }
        val remaining = if (pct in 1..99) ((100 - pct).toLong() * 400L) else null
        return pct to remaining
    }

    private fun messageFor(state: UbuntuSetupState): String = when (state) {
        UbuntuSetupState.Idle -> "Preparing…"
        UbuntuSetupState.Extracting -> "Extracting PRoot + Ubuntu rootfs…"
        UbuntuSetupState.Configuring -> "Configuring rootfs (apt, resolv.conf, shells)…"
        is UbuntuSetupState.InstallingPackages -> state.message
        is UbuntuSetupState.InstallingOhMyZsh -> state.message
        UbuntuSetupState.Optimizing -> "Optimizing rootfs…"
        UbuntuSetupState.Ready -> "Ready."
        is UbuntuSetupState.Failed -> "Setup failed: ${state.error}"
    }

    private companion object {
        const val LOG_BUFFER_SIZE = 256
    }
}
