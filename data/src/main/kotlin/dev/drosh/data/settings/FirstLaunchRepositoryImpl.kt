package dev.drosh.data.settings

import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TOML-backed [ObserveFirstLaunchUseCase].
 *
 * The flag is a key in the settings file's `[meta]` section like any other —
 * it used to be a lone DataStore key, which meant the first-launch decision
 * and the user's preferences could not be migrated together.
 */
@Singleton
class FirstLaunchRepositoryImpl @Inject constructor(
    private val store: SettingsStore,
) : ObserveFirstLaunchUseCase {

    override fun isCompleted(): Flow<Boolean> =
        store.settings.map { it.meta.firstLaunchCompleted }

    override suspend fun markCompleted() {
        store.update { it.copy(meta = it.meta.copy(firstLaunchCompleted = true)) }
    }
}
