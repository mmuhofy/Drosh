package dev.drosh.data.backup

import dev.drosh.domain.backup.BACKUP_FORMAT_VERSION
import dev.drosh.domain.backup.BackupManifest
import dev.drosh.domain.backup.BackupSection
import dev.drosh.domain.backup.BackupSectionKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `manifest.json`, the one file whose name restore trusts.
 *
 * The manifest is a separate shape from the domain's [BackupManifest] on
 * purpose: the domain type is what the UI and the repository speak, and this
 * one is the *wire format*, with `snake_case` keys that survive a round trip
 * through a text editor. Everything the archive claims to contain is here —
 * per-section `archive_path`, `byte_size` and `sha256` — so the manifest alone
 * decides what may be extracted and whether what came out is what went in.
 *
 * Reading is deliberately forgiving about *extra* keys (a manifest written by a
 * newer build of the same format may carry fields this one does not know) and
 * unforgiving about *bad* ones: a `format_version` this build cannot explain,
 * an unknown section kind, a missing checksum or a duplicated section id all
 * answer `null`, which is the repository's cue to refuse the archive rather
 * than half-restore it. See `docs/BACKUP-RESTORE-PLAN.md` §2 for the format
 * and §3 for the trust rules it exists to enforce.
 */
internal object BackupManifestJson {

    /**
     * The largest manifest this build will even look at.
     *
     * A real one is a few kilobytes — a handful of sections, one line each.
     * Anything past a megabyte is either a corrupted stream or something that
     * is not a Drosh backup at all, and reading it into memory would be a free
     * denial of service on the restore path. The plan's "oversized manifest"
     * refusal lives here.
     */
    const val MAX_BYTES: Long = 1L shl 20

    /**
     * `snake_case` keys, and lenient on the way in: an archive that has been
     * through a hand-edit, a different JSON pretty-printer or a newer writer
     * must still load rather than be reported as foreign.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** Renders [manifest] as the text of the last member of an archive. */
    fun encode(manifest: BackupManifest): String =
        json.encodeToString(ManifestFile.serializer(), manifest.toFile())

    /**
     * Parses manifest text, or answers null when it is not a manifest this
     * build can act on.
     *
     * Null is the only honest answer for a bad manifest: every caller asks
     * this question before it touches a file, and an archive that cannot
     * describe itself must not be restored from.
     */
    fun decode(text: String): BackupManifest? {
        val file = runCatching {
            json.decodeFromString(ManifestFile.serializer(), text)
        }.getOrNull() ?: return null

        // A newer format is refused rather than guessed at: the archive may
        // mean something this build cannot read, and restoring the parts it
        // does understand would silently drop the rest.
        if (file.formatVersion <= 0 || file.formatVersion > BACKUP_FORMAT_VERSION) return null

        val sections = file.sections.map { section ->
            val kind = runCatching { BackupSectionKind.valueOf(section.kind.trim().uppercase()) }
                .getOrNull()
                ?: return null
            // A section with nowhere to live, or nothing to verify, cannot be
            // extracted or checked — the archive is not usable as written.
            if (section.archivePath.isBlank() || section.sha256.isBlank()) return null
            BackupSection(
                id = section.id,
                kind = kind,
                archivePath = section.archivePath,
                byteSize = section.byteSize,
                sha256 = section.sha256,
            )
        }

        // Duplicate ids would make manifest.section(id) a coin flip, and a
        // selection by id a restore of whichever entry came first.
        if (sections.map { it.id }.distinct().size != sections.size) return null

        return BackupManifest(
            formatVersion = file.formatVersion,
            createdAtEpochMs = file.createdAtEpochMs,
            appVersionCode = file.appVersionCode,
            appVersionName = file.appVersionName,
            deviceLabel = file.deviceLabel,
            rootfsIncluded = file.rootfsIncluded,
            sections = sections,
        )
    }

    // ── the wire format ──────────────────────────────────────────────────────

    @Serializable
    data class ManifestFile(
        @SerialName("format_version") val formatVersion: Int,
        @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
        @SerialName("app_version_code") val appVersionCode: Int,
        @SerialName("app_version_name") val appVersionName: String,
        @SerialName("device_label") val deviceLabel: String,
        @SerialName("rootfs_included") val rootfsIncluded: Boolean,
        val sections: List<SectionFile> = emptyList(),
    )

    @Serializable
    data class SectionFile(
        val id: String,
        /** [BackupSectionKind] name, upper case — the enum's own spelling. */
        val kind: String,
        @SerialName("archive_path") val archivePath: String,
        @SerialName("byte_size") val byteSize: Long,
        @SerialName("sha256") val sha256: String,
    )

    private fun BackupManifest.toFile(): ManifestFile = ManifestFile(
        formatVersion = formatVersion,
        createdAtEpochMs = createdAtEpochMs,
        appVersionCode = appVersionCode,
        appVersionName = appVersionName,
        deviceLabel = deviceLabel,
        rootfsIncluded = rootfsIncluded,
        sections = sections.map { section ->
            SectionFile(
                id = section.id,
                kind = section.kind.name,
                archivePath = section.archivePath,
                byteSize = section.byteSize,
                sha256 = section.sha256,
            )
        },
    )
}
