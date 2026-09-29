package dev.drosh.domain.session

data class SessionSnapshot(
    val id: String,
    val name: String,
    val state: SessionState,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val liveSnapshotLines: List<String> = emptyList(),
)

enum class SessionState {
    Idle,
    Running,
    Closed,
}
/**
 * Name given to the session a launch creates when there is nothing to bring
 * back. Shared so the recovery path and the UI cannot drift apart on it.
 */
const val DEFAULT_SESSION_NAME = "Default"
