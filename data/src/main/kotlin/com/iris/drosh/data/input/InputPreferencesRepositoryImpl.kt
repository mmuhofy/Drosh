package com.iris.irisshell.data.input

import com.iris.irisshell.domain.input.InputPreferencesRepository
import com.iris.irisshell.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delegates [InputPreferencesRepository] to the shared
 * [SettingsRepository]. Kept as its own type so the UI can depend on a
 * narrow interface (input-system concerns only) without dragging the
 * whole settings surface along.
 *
 * UNTESTED — verify before use in production.
 */
@Singleton
class InputPreferencesRepositoryImpl @Inject constructor(
    private val settings: SettingsRepository,
) : InputPreferencesRepository {

    override val extraKeysBarVisible: Flow<Boolean> = settings.extraKeysBarVisible

    override suspend fun setExtraKeysBarVisible(visible: Boolean) {
        settings.setExtraKeysBarVisible(visible)
    }
}
