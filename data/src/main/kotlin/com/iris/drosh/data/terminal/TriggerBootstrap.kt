package com.iris.irisshell.data.terminal

import com.iris.irisshell.data.di.ApplicationScope
import com.iris.irisshell.domain.terminal.SetupPreferences
import com.iris.irisshell.domain.terminal.TriggerBootstrapUseCase
import com.iris.irisshell.terminal.BootstrapStatePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the bootstrap pipeline via [BootstrapStatePort].
 *
 * Each public method ([start], [retry], [reDownloadRootfs], [resetEverything])
 * re-runs `UbuntuBootstrap.install`. The terminal module's port owns the
 * idempotency: if everything is already installed, it short-circuits to
 * `Ready` and emits a single line to logs.
 */
// Inspired by: github.com/termux/termux-app (proot-loader)
// Adapted for Drosh — com.iris.irisshell
@Singleton
class TriggerBootstrap @Inject constructor(
    private val port: BootstrapStatePort,
    @ApplicationScope private val scope: CoroutineScope,
) : TriggerBootstrapUseCase {

    private val _state = MutableStateFlow(TriggerBootstrapUseCase.State.NotStarted)
    override val stateFlow: Flow<TriggerBootstrapUseCase.State>
        get() = _state.asStateFlow()

    override val state: TriggerBootstrapUseCase.State
        get() = _state.value

    override fun start() = run(SetupPreferences.defaults())

    override fun start(preferences: SetupPreferences) = run(preferences)

    override fun retry() = run(SetupPreferences.defaults())

    override fun reDownloadRootfs() = run(SetupPreferences.defaults())

    override fun resetEverything() = run(SetupPreferences.defaults())

    private fun run(preferences: SetupPreferences) {
        _state.value = TriggerBootstrapUseCase.State.Running
        port.runBootstrap(scope = scope, preferences = preferences)
    }
}
