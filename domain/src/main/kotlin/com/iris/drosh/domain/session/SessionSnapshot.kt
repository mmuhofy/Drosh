package com.iris.irisshell.domain.session

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