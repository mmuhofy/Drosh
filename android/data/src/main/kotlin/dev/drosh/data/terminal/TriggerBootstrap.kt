package dev.drosh.data.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.drosh.domain.terminal.BackgroundSetup
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
import dev.drosh.linux.BootstrapStatePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands setup to the app's foreground service instead of running it in an
 * Activity-scoped coroutine.
 *
 * The service is named by component rather than by class so `:data` does not
 * depend on `:app`. The work itself lives in the process-scoped
 * [BootstrapStatePort], so a recreated setup screen re-binds to the same
 * download instead of starting a second one.
 */
// Inspired by: github.com/termux/termux-app (asset staging)
// Adapted for Drosh — dev.drosh
@Singleton
class TriggerBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val port: BootstrapStatePort,
) : TriggerBootstrapUseCase {

    private val _state = MutableStateFlow(TriggerBootstrapUseCase.State.NotStarted)
    override val stateFlow: Flow<TriggerBootstrapUseCase.State>
        get() = _state.asStateFlow()

    override val state: TriggerBootstrapUseCase.State
        get() = _state.value

    override fun isInstalled(): Boolean = port.isInstalled()

    override fun start() = send(BackgroundSetup.ACTION_START)

    override fun retry() = send(BackgroundSetup.ACTION_START)

    override fun reDownloadRootfs() = send(BackgroundSetup.ACTION_START)

    override fun resetEverything() = send(BackgroundSetup.ACTION_START)

    override fun cancelSetup() = send(BackgroundSetup.ACTION_CANCEL)

    private fun send(action: String) {
        _state.value = TriggerBootstrapUseCase.State.Running
        val intent = Intent(action)
            .setComponent(ComponentName(context, BackgroundSetup.SERVICE_CLASS))
        context.startForegroundService(intent)
    }
}
