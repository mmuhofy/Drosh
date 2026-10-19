package dev.drosh.domain.backup

/**
 * The backup contract every layer codes against.
 *
 * The archive on disk mirrors proot-distro's format — `<name>/manifest.json`
 * plus member trees — so the parts that describe *what is inside* are data
 * classes here, and the parts that *do* the work live behind interfaces in
 * `:data` (app data) and `:terminal` (rootfs). See
 * `docs/BACKUP-RESTORE-PLAN.md` for the whole design; this file is the part
 * of it that must compile for everyone at once.
 */

/** Current archive layout version. Restore refuses a larger number. */
const val BACKUP_FORMAT_VERSION: Int = 1

/** The member name the manifest always lives under. */
const val BACKUP_MANIFEST_NAME: String = "manifest.json"

/** What a section of the archive holds. */
enum class BackupSectionKind {
    SETTINGS,
    DATASTORE,
    ROOM_DATABASE,
    THEMES,
    SNIPPETS,
    INPUT_PREFS,
    ROOTFS,
}

/**
 * One addressable part of the archive. [archivePath] is the member path
 * inside the tar; [sha256] is the hex digest of the member's bytes, so a
 * truncated or edited archive fails verification instead of half-restoring.
 */
data class BackupSection(
    val id: String,
    val kind: BackupSectionKind,
    val archivePath: String,
    val byteSize: Long,
    val sha256: String,
)

/**
 * The manifest: what the archive claims to contain. Written last, read first,
 * and the only decision input restore trusts.
 */
data class BackupManifest(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val createdAtEpochMs: Long,
    val appVersionCode: Int,
    val appVersionName: String,
    val deviceLabel: String,
    val rootfsIncluded: Boolean,
    val sections: List<BackupSection>,
) {
    fun section(id: String): BackupSection? = sections.firstOrNull { it.id == id }

    companion object {
        /** Well-known section ids, so restore can select by meaning. */
        const val ID_SETTINGS = "app_settings"
        const val ID_DATASTORE = "app_datastore"
        const val ID_DATABASE = "app_database"
        const val ID_THEMES = "app_themes"
        const val ID_SNIPPETS = "app_snippets"
        const val ID_INPUT_PREFS = "app_input_prefs"
        const val ID_ROOTFS = "rootfs"
    }
}

/** What the user asked to include. Tiers T0–T3 from the plan. */
data class BackupOptions(
    val includeSettings: Boolean = true,
    val includeDataStore: Boolean = true,
    val includeDatabase: Boolean = true,
    val includeThemesAndSnippets: Boolean = true,
    val includeRootfs: Boolean = false,
)

/**
 * Restore selection: every section by default, or an explicit set of
 * [BackupManifest] id's for a partial restore.
 */
data class RestoreSelection(
    val sectionIds: Set<String>? = null,
) {
    fun accepts(id: String): Boolean = sectionIds == null || id in sectionIds
}

/** Progress of a long job; the UI renders [fraction] and the current step. */
sealed interface BackupProgress {
    data class Started(val totalSteps: Int) : BackupProgress
    data class Step(val step: String, val fraction: Float) : BackupProgress
    data class Completed(val outputPath: String?, val manifest: BackupManifest?) : BackupProgress
    data class Failed(val reason: String, val cause: String? = null) : BackupProgress
}

/**
 * The one door the UI walks through.
 *
 * [createBackup] writes the archive to a file the caller owns (app cache) and
 * reports progress; moving it to the user's chosen SAF location is the UI's
 * job. [inspectArchive] reads the manifest without extracting anything.
 * [restore] extracts into a staging area and swaps atomically; it never
 * promises success mid-flight — failures roll back or leave the previous tree
 * untouched.
 */
interface BackupRepository {
    /** Emits at least one terminal event: [BackupProgress.Completed] or [BackupProgress.Failed]. */
    fun createBackup(options: BackupOptions): kotlinx.coroutines.flow.Flow<BackupProgress>

    /** The archive's manifest, or null when the file is not a usable backup. */
    suspend fun inspectArchive(file: java.io.File): BackupManifest?

    /** Restores the selected sections; emits progress, then one terminal event. */
    fun restore(
        file: java.io.File,
        selection: RestoreSelection = RestoreSelection(),
    ): kotlinx.coroutines.flow.Flow<BackupProgress>
}
