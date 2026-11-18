package dev.drosh.domain.session

import kotlinx.coroutines.flow.StateFlow

/**
 * Live facts about the sessions the engine is running.
 *
 * Pure Kotlin port: the implementation samples `/proc`, `ActivityManager` and
 * the engine's frame counters on the data side, and the UI only reads these
 * flows. No Android type crosses this boundary.
 */
interface SessionTelemetry {
    val processes: StateFlow<List<SessionProcessInfo>>
    val resources: StateFlow<ResourceSnapshot>
}
