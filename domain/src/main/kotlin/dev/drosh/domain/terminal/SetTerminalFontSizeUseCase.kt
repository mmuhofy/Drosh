package dev.drosh.domain.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Use case for the user-controlled terminal font size (sp).
 *
 * Stored in DataStore (see `:data` layer) so the choice survives app
 * restarts. Pinch and the right-side slider both write through this single
 * flow — no second source of truth.
 *
 * Fractional, because a pinch follows the fingers and the size it lands on is
 * whatever the gesture produced — 14.3sp is a real value the user chose by
 * stopping there, not noise to be rounded away on the way to disk. Limits live
 * in [TerminalZoom].
 *
 * The repository is exposed via Hilt via `:data`'s DroshPrefsModule.
 */
interface SetTerminalFontSizeUseCase {
    /** Hot stream of the persisted font size in sp, clamped to [TerminalZoom]'s limits. */
    fun observe(): Flow<Float>

    /** Persists [sp]. Caller is responsible for clamping; the data layer
     *  stores whatever the use case gives it. */
    suspend fun set(sp: Float)
}
