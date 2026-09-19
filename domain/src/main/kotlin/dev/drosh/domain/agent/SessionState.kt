package dev.drosh.domain.agent

/**
 * Session lifecycle state.
 */
sealed interface SessionState {
    data object Idle : SessionState
    data class Busy(val turnCount: Int = 1) : SessionState
    data class Retry(
        val attempt: Int,
        val message: String,
        val nextRetryMs: Long
    ) : SessionState
    data object Cancelled : SessionState
}

/**
 * Work mode — controls which tools are available to the agent.
 * Mirrors IrisCode's WorkMode pattern.
 */
enum class WorkMode {
    PLAN,
    BUILD,
    AUTO
}