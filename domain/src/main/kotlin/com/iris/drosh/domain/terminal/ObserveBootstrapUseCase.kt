package com.iris.irisshell.domain.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Use case interface used by the UI to subscribe to bootstrap progress.
 *
 * The implementation in `:data` translates the terminal module's raw state into
 * a `BootstrapProgress` snapshot and pipes log lines through a `Flow` so the UI
 * can render a live log drawer without knowing about `UbuntuSetupState`.
 *
 * Architecture note: the UI consumes ONLY this interface. Per AGENT.md §125-128
 * the `ui/` module never imports from `terminal/` directly.
 */
interface ObserveBootstrapUseCase {
    fun progress(): Flow<BootstrapProgress>
    fun liveLogs(): Flow<String>
}
