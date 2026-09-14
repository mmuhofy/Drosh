package com.iris.irisshell.domain.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Use case that triggers / re-triggers the bootstrap pipeline.
 *
 * The UI calls this from the Retry / Re-download / Reset buttons on the recovery
 * screen, and from the onboarding wizard's final "Continue" button.
 *
 * [start] without arguments uses [SetupPreferences.defaults] — this is the
 * path taken when [com.iris.irisshell.MainActivity] triggers bootstrap on a
 * warm start (preferences were saved during onboarding).
 *
 * [start] with [SetupPreferences] is the path taken from onboarding — the
 * user's choices (shell, username, packages) are forwarded to the bootstrap
 * pipeline.
 */
interface TriggerBootstrapUseCase {
    val state: State
    fun start()
    fun start(preferences: SetupPreferences)
    fun retry()
    fun reDownloadRootfs()
    fun resetEverything()

    enum class State { NotStarted, Running }
    val stateFlow: Flow<State>
}
