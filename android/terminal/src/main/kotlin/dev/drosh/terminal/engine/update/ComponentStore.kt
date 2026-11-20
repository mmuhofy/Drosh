package dev.drosh.terminal.engine.update

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where a component lives on this device, and the choice of which one is
 * active.
 *
 * ## Layout
 *
 * ```
 * filesDir/updates/<id>/<version>/<file>   one directory per installed version
 * filesDir/updates/active.json             which version is active, atomically
 * ```
 *
 * Versioned directories mean a process killed mid-install leaves the previous
 * copy untouched, and a rollback is just a different line in `active.json`.
 * The last two versions are kept; older ones are pruned once an activation
 * succeeds, so a device does not accumulate a release per week.
 *
 * ## Trust
 *
 * A file that fails its checksum is deleted rather than trusted. A library is
 * code execution, and a file that is not what was published is not a file to
 * run.
 */
class ComponentStore(private val context: Context) {

    private val root = File(context.filesDir, "updates")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The directory a component's versions live under. */
    private fun versionsDir(id: ComponentId): File = File(root, id.key)

    /** The directory one version of a component occupies. */
    private fun versionDir(id: ComponentId, version: String): File =
        File(versionsDir(id), version)

    /** The file that records the active set. */
    private val activeFile: File get() = File(root, "active.json")

    /** Read the active set, or an empty one when it is missing or unreadable. */
    fun activeSet(): ActiveSet = synchronized(lock) {
        val file = activeFile
        if (!file.isFile) return ActiveSet()
        runCatching { json.decodeFromString<ActiveSet>(file.readText()) }
            .getOrNull() ?: ActiveSet()
    }

    /** The installed state of one component, or null when it has none. */
    fun installed(id: ComponentId): InstalledState? =
        activeSet().active[id.key]

    /**
     * Verify and store [bytes] for [release]. Returns the installed state on
     * success, or null when the checksum is wrong or the write fails.
     */
    fun install(release: ComponentRelease, bytes: ByteArray): InstalledState? {
        val actual = sha256(bytes)
        if (actual != release.sha256.lowercase()) {
            Log.w(TAG, "refusing to install ${release.id} ${release.version}: checksum mismatch")
            return null
        }
        if (release.sizeBytes > 0 && bytes.size.toLong() != release.sizeBytes) {
            Log.w(TAG, "refusing to install: size mismatch ${bytes.size} != ${release.sizeBytes}")
            return null
        }

        val dir = versionDir(ComponentId.valueOfKey(release.id), release.version)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "could not create $dir")
            return null
        }
        val target = File(dir, fileName(release.id))
        val temporary = File(dir, "${fileName(release.id)}.tmp")
        return runCatching {
            temporary.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return null
            }
            InstalledState(
                version = release.version,
                sha256 = release.sha256.lowercase(),
                path = target.absolutePath,
                source = InstallSource.DOWNLOADED,
            )
        }.getOrNull()?.also {
            prune(ComponentId.valueOfKey(release.id))
        }
    }

    /**
     * Make [state] the active copy of [id]. Atomic: the previous active set is
     * read, one entry replaced, the whole file rewritten to a temp and renamed.
     */
    fun activate(id: ComponentId, state: InstalledState): Boolean {
        synchronized(lock) {
            val current = activeSet()
            val next = current.copy(active = current.active + (id.key to state))
            return writeActiveSet(next)
        }
    }

    /** Drop the active entry for [id], so the bundled copy is used again. */
    fun rollback(id: ComponentId): Boolean = synchronized(lock) {
        val current = activeSet()
        val next = current.copy(active = current.active - id.key)
        writeActiveSet(next)
    }

    /** True when a downloaded copy is present and intact. */
    fun hasDownloaded(id: ComponentId, expectedSha256: String?): Boolean =
        downloadedLibrary(id, expectedSha256) != null

    /** The downloaded copy that passes its checksum, or null. */
    fun downloadedLibrary(id: ComponentId, expectedSha256: String?): File? {
        val state = installed(id) ?: return null
        if (state.source != InstallSource.DOWNLOADED) return null
        val file = File(state.path)
        if (!file.isFile) return null
        if (expectedSha256 != null && sha256(file) != expectedSha256.lowercase()) {
            Log.w(TAG, "downloaded $id failed its checksum; removing it")
            file.delete()
            return null
        }
        if (state.sha256.isNotEmpty() && sha256(file) != state.sha256) {
            Log.w(TAG, "downloaded $id failed its recorded checksum; removing it")
            file.delete()
            return null
        }
        return file
    }

    private fun writeActiveSet(set: ActiveSet): Boolean {
        if (!root.isDirectory && !root.mkdirs()) return false
        val temporary = File(root, "active.json.tmp")
        return runCatching {
            temporary.writeText(json.encodeToString(set))
            if (activeFile.exists()) activeFile.delete()
            temporary.renameTo(activeFile)
        }.getOrElse {
            Log.w(TAG, "could not write active.json", it)
            temporary.delete()
            false
        }
    }

    /** Keep the last two versions of [id]; remove the rest. */
    private fun prune(id: ComponentId) {
        val dir = versionsDir(id)
        if (!dir.isDirectory) return
        val kept = dir.listFiles()?.sortedByDescending { it.name }?.take(2)?.map { it.name } ?: return
        dir.listFiles()?.forEach { child ->
            if (child.name !in kept) child.deleteRecursively()
        }
    }

    private fun fileName(id: ComponentId): String = when (id) {
        ComponentId.GHOSTTY_VT -> "libghostty-vt.so"
        ComponentId.CHROOT_NG -> "libchrootng.so"
        ComponentId.APK -> "drosh.apk"
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private val lock = Any()

    companion object {
        private const val TAG = "ComponentStore"

        /** Parse a component id from its key, or null when unknown. */
        fun ComponentId.Companion.valueOfKey(key: String): ComponentId =
            entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException(key)
    }
}
