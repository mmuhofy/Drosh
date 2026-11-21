package dev.drosh.terminal.engine.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One published build of a component.
 *
 * The checksum is not optional: an artifact downloaded over the network and
 * loaded into the process is code execution, and a build that skips the check
 * is a build that trusts whatever answered the request.
 *
 * @property builtAgainst the component versions this build was produced with.
 *        `libdrosh.so` is not a component (it ships in the APK), but a VT
 *        update that disagrees with the APK's expected VT version is refused —
 *        which is what makes "I only updated the engine" safe.
 */
@Serializable
data class ComponentRelease(
    val id: String,
    val version: String,
    val abi: String,
    @SerialName("sha256") val sha256: String,
    val sizeBytes: Long,
    val url: String,
    val publishedAt: String,
    val builtAgainst: Map<String, String> = emptyMap(),
)

/**
 * A record that a component is installed, kept in `active.json`.
 *
 * The path is absolute. A missing file is not an error: it means the record is
 * stale, and the store falls back to the bundled copy.
 */
@Serializable
data class InstalledState(
    val version: String,
    val sha256: String,
    val path: String,
    val source: InstallSource,
)

/**
 * The set of components that are active right now.
 *
 * One file, written atomically: a process killed mid-write leaves the previous
 * set — or nothing — rather than a half-applied update.
 */
@Serializable
data class ActiveSet(val active: Map<String, InstalledState> = emptyMap())
