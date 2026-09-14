package com.iris.irisshell.domain.terminal

/**
 * Rich progress snapshot for the bootstrap pipeline.
 *
 * Carries enough information to drive:
 *   - the Material 3 stacked stepper (currentStep + stepStates)
 *   - the determinate progress bar (percent)
 *   - the ETA label (estimatedRemainingMs)
 *   - the live log drawer (liveLog) — a bounded Flow of recently-emitted lines
 *   - the recovery screen (error)
 */
data class BootstrapProgress(
    val currentStep: BootstrapStep,
    val stepStates: Map<BootstrapStep, StepState>,
    val percent: Int,
    val estimatedRemainingMs: Long?,
    val currentMessage: String,
    val error: BootstrapError? = null,
) {
    val isInProgress: Boolean
        get() = currentStep !in setOf(BootstrapStep.Idle, BootstrapStep.Ready, BootstrapStep.Failed)

    val isReady: Boolean
        get() = currentStep == BootstrapStep.Ready

    val isFailed: Boolean
        get() = currentStep == BootstrapStep.Failed

    companion object {
        fun initial(): BootstrapProgress = BootstrapProgress(
            currentStep = BootstrapStep.Idle,
            stepStates = emptyMap(),
            percent = 0,
            estimatedRemainingMs = null,
            currentMessage = "Preparing…",
            error = null,
        )
    }
}

enum class StepState { Pending, Active, Done, Failed }

/**
 * Information about why a bootstrap run failed and how to recover.
 *
 * recoveryActions are ordered from cheapest to most destructive. The UI binds
 * them directly to buttons in `SetupRecoveryScreen`.
 */
data class BootstrapError(
    val step: BootstrapStep,
    val shortMessage: String,
    val lastLogLines: List<String>,
    val recoveryActions: List<RecoveryAction>,
)

enum class RecoveryAction {
    Retry,
    ReDownloadRootfs,
    ResetEverything,
    ReportIssue,
}
