package com.iris.irisshell.domain.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Use case for the user-controlled terminal font size (sp).
 *
 * Stored in DataStore (see `:data` layer) so the choice survives app
 * restarts. Pinch and the right-side slider both write through this single
 * flow — no second source of truth.
 *
 * Default: 14sp. Range: 10..32sp, linear.
 *
 * The repository is exposed via Hilt via `:data`'s DroshPrefsModule.
 */
interface SetTerminalFontSizeUseCase {
    /** Hot stream of the persisted font size in sp, clamped to 10..32. */
    fun observe(): Flow<Float>

    /** Persists [sp]. Caller is responsible for clamping; the data layer
     *  stores whatever the use case gives it. */
    suspend fun set(sp: Float)
}
