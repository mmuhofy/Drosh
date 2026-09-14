package com.iris.irisshell.domain.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Use case for the first-launch detection.
 *
 * The implementation reads from DataStore. `true` means the user has either
 * completed (or skipped) onboarding. `null` means "unknown, still loading".
 */
interface ObserveFirstLaunchUseCase {
    fun isCompleted(): Flow<Boolean>
    suspend fun markCompleted()
}
