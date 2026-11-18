package dev.drosh.domain.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Use case that triggers / re-triggers the bootstrap pipeline.
 *
 * The UI calls this from "Get started" on the welcome screen, and from the
 * Retry / Re-download / Reset buttons on the recovery screen. Every route uses
 * [SetupPreferences.defaults]: setup has no choices to carry any more, so the
 * argument-taking overload was removed with the theme and package picks.
 */
interface TriggerBootstrapUseCase {
    val state: State
    fun start()
    fun retry()
    fun reDownloadRootfs()
    fun resetEverything()

    enum class State { NotStarted, Running }
    val stateFlow: Flow<State>
}
