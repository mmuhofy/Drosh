package dev.drosh.data.backup

import android.content.Context
import android.os.Build
import dev.drosh.data.local.DroshDatabase
import dev.drosh.data.settings.toml.SettingsPaths
import dev.drosh.domain.backup.BACKUP_FORMAT_VERSION
import dev.drosh.domain.backup.BACKUP_MANIFEST_NAME
import dev.drosh.domain.backup.BackupManifest
import dev.drosh.domain.backup.BackupOptions
import dev.drosh.domain.backup.BackupProgress
import dev.drosh.domain.backup.BackupRepository
import dev.drosh.domain.backup.BackupSection
import dev.drosh.domain.backup.BackupSectionKind
import dev.drosh.domain.backup.RestoreSelection
import dev.drosh.domain.session.DeviceIdentityRepository
import dev.drosh.terminal.UbuntuBootstrap
import dev.drosh.terminal.backup.RootfsArchiveReader
import dev.drosh.terminal.backup.RootfsArchiveWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the rootfs writer in `:terminal` reports about the tree it just packed.
 *
 * Mirrors the result type of `RootfsArchiveWriter.writeSubtree`: the member
 * bytes as they went into the tar stream, their digest, and how many files
 * that was. The digest is what lands in the manifest, and the byte count is
 * what the restore path measures free space against.
 */
data class ArchiveWriteResult(
    val bytesWritten: Long,
    val sha256: String,
    val fileCount: Int,
)

/**
 * The rootfs tree, behind an interface.
 *
 * The work belongs to `dev.drosh.terminal.backup` (`RootfsArchiveWriter` and
 * `RootfsArchiveReader` — the walk with its permission-relax pass, the
 * excludes, and the zip-slip-guarded extract; see
 * `docs/BACKUP-RESTORE-PLAN.md` §3). This class only orchestrates, so it
 * depends on this port rather than on those classes: the two calls it needs,
 * with the result shape it can carry in a manifest.
 *
 * The indirection is also what makes this slice testable without an Android
 * device and without a rootfs on disk — the unit tests in
 * `data/src/test/kotlin/dev/drosh/data/backup` inject a port that packs and
 * unpacks a temp directory.
 */
interface RootfsArchivePort {

    /**
     * Packs the rootfs tree at [rootfsDir] into [out] as members under
     * [memberPrefix] (`drosh/rootfs` by convention).
     *
     * The writer gets the live tar stream and writes members into it; it does
     * not own the stream and must not close it, terminate the archive, or
     * write a manifest of its own.
     */
    suspend fun writeSubtree(rootfsDir: File, memberPrefix: String, out: OutputStream): ArchiveWriteResult

    /**
     * Extracts the [memberPrefix] members of the archive on [input] into
     * [stagingDir].
     *
     * [input] is a fresh stream positioned at the start of the archive: tar is
     * sequential and the rootfs members sit at the end, so reaching them means
     * reading everything before them. A damaged archive throws; the caller
     * treats that as a failed restore and rolls back.
     */
    suspend fun extractToStaging(input: InputStream, memberPrefix: String, stagingDir: File)
}

/**
 * [RootfsArchivePort] over the real `:terminal` classes.
 *
 * Thin on purpose — every rule worth having (the permission-relax pass, the
 * descriptor-based walk, the excludes, the path guard) lives in the terminal
 * slice, and a second copy of any of them here would be a second place to get
 * it wrong. The writer is built with its own default excludes, which are the
 * parts of a rootfs that must never be packed (`proc`, `sys`, the staged proot
 * binary); the tree itself is named per call, not per instance.
 */
internal class TerminalRootfsArchivePort : RootfsArchivePort {

    override suspend fun writeSubtree(
        rootfsDir: File,
        memberPrefix: String,
        out: OutputStream,
    ): ArchiveWriteResult {
        val written = RootfsArchiveWriter().writeSubtree(rootfsDir, memberPrefix, null, out)
        return ArchiveWriteResult(
            bytesWritten = written.bytesWritten,
            sha256 = written.sha256,
            fileCount = written.fileCount,
        )
    }

    override suspend fun extractToStaging(input: InputStream, memberPrefix: String, stagingDir: File) {
        // The reader verifies member checksums itself and refuses members that
        // would escape the staging root; its result is a count, which the
        // repository has no use for — a failure arrives as an exception.
        RootfsArchiveReader().extractToStaging(input, memberPrefix, stagingDir)
    }
}

/**
 * The two numbers a manifest carries so a restore can say where it came from.
 *
 * Read from the package manager rather than `BuildConfig`: library modules
 * have no `BuildConfig` of their own, and the version is the one property of
 * an archive that must be the app's truth rather than a constant someone
 * remembered to update.
 */
data class AppVersionInfo(val code: Int, val name: String)

/**
 * Where an archive is written and what it is called.
 *
 * An interface rather than the function-typed providers this started as:
 * Hilt cannot resolve `() -> File` as a binding key — the constructor asks
 * for `Function0<? extends File>` and no `@Provides` in Kotlin matches it,
 * which is a build failure in the app module and a confusing one. A named
 * collaborator is also the honest shape: a run names its own file, and the
 * test needs both pointed at a temp directory.
 */
interface BackupLocation {
    /** The directory archives are written into; created if missing. */
    fun archiveDir(): File

    /** A fresh archive file name, distinct per run. */
    fun archiveFileName(): String
}

/**
 * The one door the UI walks through, on the data side.
 *
 * Three jobs, and the order inside each one is the whole design:
 *
 *  - **[createBackup]** writes the app sections, then the rootfs (when asked
 *    for), then the manifest **last** — so an interrupted backup leaves an
 *    archive with no manifest, which restore refuses, rather than a manifest
 *    describing files that were never written.
 *  - **[inspectArchive]** reads the manifest member and nothing else. It never
 *    extracts, so asking what is inside an archive is safe on a 40 GB backup
 *    on a metered connection.
 *  - **[restore]** stages everything into a temp directory first, verifies
 *    every selected section's checksum against the manifest, and only then
 *    swaps files into place through [FileSwap] — each file replaced atomically,
 *    each previous file remembered, so a failure halfway through leaves the
 *    app exactly as it was.
 *
 * What this class owns is the archive and the app-private files. The rootfs
 * tree is packed and unpacked by [RootfsArchivePort]; the database snapshot is
 * taken by [DatabaseSnapshotter]; both are injected so this file can be
 * exercised in a JVM test against temp directories.
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val paths: BackupPaths,
    private val appData: AppDataBackup,
    private val rootfsArchive: RootfsArchivePort,
    private val deviceIdentity: DeviceIdentityRepository,
    private val appVersion: AppVersionInfo,
    private val backupLocation: BackupLocation,
) : BackupRepository {

    // ── creating ─────────────────────────────────────────────────────────────

    override fun createBackup(options: BackupOptions): Flow<BackupProgress> = flow {
        val directory = backupLocation.archiveDir().apply { mkdirs() }
        val output = File(directory, backupLocation.archiveFileName())
        val staging = File(directory, "${output.name}.staging-${System.nanoTime()}")
        val startedAt = System.currentTimeMillis()
        val sections = mutableListOf<BackupSection>()
        var completed: BackupManifest? = null

        try {
            val planned = appData.planned(options)
            // A rootfs that is not installed has nothing to pack, and asking
            // for one must not fail a backup of everything else.
            val includeRootfs = options.includeRootfs && paths.rootfsDir.isDirectory
            val totalSteps = planned.size + (if (includeRootfs) 1 else 0) + 1
            var step = 0
            emit(BackupProgress.Started(totalSteps))

            // Resolved once: the label is a settings lookup and an offline
            // table probe, and the manifest needs one value, not one per run.
            val deviceLabel = runCatching { deviceIdentity.resolveName() }.getOrNull().orEmpty()
            val manifest = BackupManifest(
                formatVersion = BACKUP_FORMAT_VERSION,
                createdAtEpochMs = startedAt,
                appVersionCode = appVersion.code,
                appVersionName = appVersion.name,
                deviceLabel = deviceLabel,
                rootfsIncluded = includeRootfs,
                sections = emptyList(),
            )

            staging.outputStream().buffered().use { buffered ->
                GZIPOutputStream(buffered).use { gzip ->
                    val writer = TarMemberWriter(gzip)
                    val sink = TarSectionSink(writer)

                    // App sections first: they are the ones with per-member
                    // checksums the manifest can prove.
                    planned.forEach { section ->
                        val written = appData.write(section, sink)
                        if (written != null) sections += written
                        step++
                        emit(BackupProgress.Step(section.stepName, fraction(step, totalSteps)))
                    }

                    // The rootfs, when asked for, and always after the app
                    // sections — its members are the archive's heaviest.
                    if (includeRootfs) {
                        val written = rootfsArchive.writeSubtree(
                            paths.rootfsDir,
                            ArchiveLayout.ROOTFS_PREFIX,
                            gzip,
                        )
                        sections += BackupSection(
                            id = BackupManifest.ID_ROOTFS,
                            kind = BackupSectionKind.ROOTFS,
                            archivePath = ArchiveLayout.ROOTFS_PREFIX,
                            byteSize = written.bytesWritten,
                            sha256 = written.sha256,
                        )
                        step++
                        emit(BackupProgress.Step("rootfs", fraction(step, totalSteps)))
                    }

                    // The manifest last, always. An archive that ends without
                    // one is an archive restore refuses, which is the plan's
                    // crash-safety rule: the file that says what a backup
                    // contains is written only once everything in it is.
                    val withSections = manifest.copy(sections = sections.toList())
                    completed = withSections
                    val bytes = BackupManifestJson.encode(withSections).toByteArray(Charsets.UTF_8)
                    sink.writeBytes(ArchiveLayout.MANIFEST_MEMBER, bytes)
                    writer.finish()
                    step++
                    emit(BackupProgress.Step("manifest", fraction(step, totalSteps)))
                }
            }

            // Moved into place only once the stream is closed and flushed, so
            // the file the caller is handed is never a partial gzip.
            moveAtomically(staging, output)
            emit(BackupProgress.Completed(output.absolutePath, completed))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            staging.delete()
            Timber.w(e, "backup: createBackup failed")
            emit(
                BackupProgress.Failed(
                    e.message ?: "the backup could not be written",
                    e::class.simpleName,
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    // ── inspecting ───────────────────────────────────────────────────────────

    override suspend fun inspectArchive(file: File): BackupManifest? =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            try {
                file.inputStream().buffered().use { raw ->
                    GZIPInputStream(raw).use { gzip ->
                        ArchiveTarReader(gzip).use { reader -> readManifest(reader) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "backup: ${file.name} could not be read as an archive")
                null
            }
        }

    /**
     * Scans members until the manifest, reading nothing else's payload.
     *
     * The manifest is the last member in an archive this app writes, so the
     * scan reaches it by stepping over every member that comes before it. On a
     * settings-only backup that is a handful of headers; on one with a rootfs
     * it is a full decompress of the archive, which is the honest price of
     * manifest-last — the only thing that is never read is a member's payload
     * being copied somewhere.
     *
     * The name is matched by suffix as well as exactly, so an archive rebuilt
     * by a tool that nests the members one directory deeper still answers, and
     * it stops at the first match: a second manifest would be a stream with
     * two endings, and the first one is the one written by this app.
     */
    private fun readManifest(reader: ArchiveTarReader): BackupManifest? {
        while (true) {
            val name = reader.nextFileEntry() ?: return null
            if (!isManifestMember(name)) continue
            val buffer = ByteArrayOutputStream()
            reader.copyTo(buffer, ArchiveLayout.MANIFEST_MAX_BYTES)
            return BackupManifestJson.decode(buffer.toString(Charsets.UTF_8.name()))
        }
    }

    private fun isManifestMember(name: String): Boolean =
        name == BACKUP_MANIFEST_NAME || name.endsWith("/$BACKUP_MANIFEST_NAME")

    /**
     * A step's fraction of the whole job.
     *
     * Clamped at one, because the last step of a job is the last thing the
     * progress bar shows before the result panel replaces it — and a bar that
     * stops short of full reads as a stalled job rather than a finished one.
     * The step names are the UI's to translate: they are short and stable, and
     * `ui/backup` maps each one it knows to a phrase.
     */
    private fun fraction(step: Int, total: Int): Float =
        if (total <= 0 || step >= total) 1f else step.toFloat() / total

    // ── restoring ────────────────────────────────────────────────────────────

    override fun restore(file: File, selection: RestoreSelection): Flow<BackupProgress> = flow {
        if (!file.isFile) {
            emit(BackupProgress.Failed("that file is not there any more"))
            return@flow
        }
        val manifest = inspectArchive(file)
        if (manifest == null) {
            emit(BackupProgress.Failed("that is not a backup this build can read"))
            return@flow
        }

        // Anything the caller asked for that the archive does not have is a
        // programming error, not a partial restore: fail before touching a
        // file rather than quietly restoring less than was asked for.
        val unknown = selection.sectionIds
            ?.minus(manifest.sections.map { it.id }.toSet())
            .orEmpty()
        if (unknown.isNotEmpty()) {
            emit(BackupProgress.Failed("this archive has no section named ${unknown.joinToString(", ")}"))
            return@flow
        }

        val rootfsSection = manifest.sections.firstOrNull {
            selection.accepts(it.id) && it.kind == BackupSectionKind.ROOTFS
        }
        val appSections = manifest.sections.filter {
            selection.accepts(it.id) && it.kind != BackupSectionKind.ROOTFS
        }
        // One unit per emitted step — the verification pass, one per section,
        // and the rootfs swap plus the settings write that follows it — so the
        // last step is also the one that reaches a full bar. The verification
        // pass announces itself at zero and is counted once it is done.
        val totalSteps = 1 + appSections.size + (if (rootfsSection != null) 2 else 0)
        var step = 0
        val backupDir = backupLocation.archiveDir().apply { mkdirs() }
        val staging = Files.createTempDirectory(backupDir.toPath(), "drosh-restore-").toFile()
        var swap: FileSwap? = null
        var committed = false

        try {
            emit(BackupProgress.Started(totalSteps))

            // Free space, before anything is written: the extracted sections
            // are a little smaller than their members (tar headers, gzip), so
            // this is a lower bound — which is the direction that matters.
            val needed = appSections.sumOf { it.byteSize } + (rootfsSection?.byteSize ?: 0L)
            if (needed > 0 && staging.usableSpace < needed) {
                throw IOException("the restore needs ${needed / 1_048_576} MB and there is ${staging.usableSpace / 1_048_576} MB free")
            }

            emit(BackupProgress.Step("verifying", 0f))
            val staged = extractAndVerify(file, appSections, staging)
            Timber.i("backup: ${staged.values.sumOf { it.size }} members staged for restore")
            step++

            val rollback = File(staging, "rollback").apply { mkdirs() }
            val fileSwap = FileSwap(rollback)
            swap = fileSwap

            appSections.forEach { section ->
                step++
                emit(BackupProgress.Step(section.id, fraction(step, totalSteps)))
                val appSection = AppDataBackup.AppSection.forId(section.id)
                    ?: throw IOException("the archive names a section this build does not know: ${section.id}")
                appData.apply(appSection, fileSwap, staging)
            }

            if (rootfsSection != null) {
                step++
                emit(BackupProgress.Step(rootfsSection.id, fraction(step, totalSteps)))
                if (restoreRootfs(file, rootfsSection)) {
                    // The fresh rootfs carries whatever settings it was packed
                    // with; the app's mirror is the newer truth, so it goes
                    // over. Only after the swap — the primary path has to exist
                    // first, and it is created by the restore that just ran.
                    step++
                    emit(BackupProgress.Step(AppDataBackup.AppSection.SETTINGS.stepName, fraction(step, totalSteps)))
                    appData.rewriteSettingsIntoRootfs(fileSwap, staging)
                }
            }

            committed = true
            emit(BackupProgress.Completed(null, manifest))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (!committed) swap?.rollback()
            Timber.w(e, "backup: restore failed")
            emit(
                BackupProgress.Failed(
                    e.message ?: "the restore could not be completed",
                    e::class.simpleName,
                ),
            )
        } finally {
            staging.deleteRecursively()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Extracts the app sections of [archive] into [staging] and verifies each
     * one against its manifest checksum.
     *
     * Verify-then-apply in one pass: the payload is streamed straight into the
     * staging directory through a digest, so a corrupt member is caught the
     * moment it has been read rather than after it has been applied. Nothing
     * outside [staging] is touched here, which is what makes the rule "any
     * failure leaves previous files untouched" true — the only thing a failed
     * restore has moved is bytes inside a temp directory.
     */
    private fun extractAndVerify(
        archive: File,
        sections: List<BackupSection>,
        staging: File,
    ): Map<String, List<SectionMember>> {
        val collected = LinkedHashMap<String, MutableList<SectionMember>>()
        sections.forEach { collected[it.id] = mutableListOf() }

        if (sections.isNotEmpty()) {
            archive.inputStream().buffered().use { raw ->
                GZIPInputStream(raw).use { gzip ->
                    ArchiveTarReader(gzip).use { reader ->
                        while (true) {
                            val name = reader.nextFileEntry() ?: break
                            // The rootfs, the manifest, and anything belonging
                            // to a section that was not selected are all
                            // stepped over here.
                            val owner = sections.firstOrNull { it.ownsMember(name) } ?: continue
                            val target = stagingPath(staging, name)
                            target.parentFile?.mkdirs()
                            val digest = MessageDigest.getInstance("SHA-256")
                            val written = DigestOutputStream(target.outputStream(), digest).use { stream ->
                                reader.copyTo(stream)
                            }
                            val member = SectionMember(name, written, digest.digest().toHexLower())
                            collected[owner.id]?.add(member)
                        }
                    }
                }
            }
        }

        sections.forEach { section ->
            val members = collected[section.id].orEmpty()
            if (members.isEmpty()) {
                throw IOException("this archive holds nothing for section ${section.id}")
            }
            val verified = SectionDigest.digest(members)
            if (verified != section.sha256 || members.sumOf { it.byteSize } != section.byteSize) {
                throw IOException("the ${section.id} section failed its checksum")
            }
        }
        return collected
    }

    /** True when [memberName] belongs to this section's members. */
    private fun BackupSection.ownsMember(memberName: String): Boolean =
        memberName == archivePath || memberName.startsWith("$archivePath/")

    /**
     * Where an archive member is staged, canonically checked.
     *
     * The guard is the zip-slip rule from the plan (§3): a member whose path
     * climbs out of the staging root is refused rather than written, and the
     * comparison is on canonical paths, so a symlink planted in the staging
     * directory cannot talk the check into agreeing.
     */
    private fun stagingPath(staging: File, memberName: String): File {
        val root = staging.canonicalPath
        val target = File(staging, memberName)
        val canonical = target.canonicalPath
        if (canonical != root && !canonical.startsWith(root + File.separator)) {
            throw IOException("member '$memberName' would escape the staging directory")
        }
        return target
    }

    /**
     * Stages the rootfs out of [archive] and swaps it into
     * [BackupPaths.rootfsDir], returning true when the tree was replaced.
     *
     * The staging directory is a sibling of the rootfs rather than a child of
     * the cache directory, for one reason: the swap is a rename, and a rename
     * only works inside one filesystem. The previous tree is moved to
     * `rootfs.pre-restore` first and put back if anything fails, so a failed
     * rootfs restore costs nothing but a rollback.
     *
     * Two things are deliberately *not* here, both because they belong to the
     * terminal slice: re-running the bootstrap scripts over the restored tree
     * (the plan's §6.3, and what turns a re-extracted rootfs into a working
     * environment), and per-member checksum verification of the tree itself.
     * The archive's rootfs section carries one digest for everything the
     * writer packed, which is only provable by re-packing it; instead the
     * reader's own path guard and the restore's failure rollback are what
     * stand between a damaged rootfs and a broken install.
     */
    private suspend fun restoreRootfs(archive: File, section: BackupSection): Boolean {
        val parent = paths.rootfsDir.parentFile
            ?: throw IOException("${paths.rootfsDir.path} has no parent directory")
        val name = paths.rootfsDir.name
        val staging = File(parent, "$name.restore-staging-${System.nanoTime()}")
        val preRestore = File(parent, "$name.pre-restore")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("could not create $staging")

        try {
            archive.inputStream().buffered().use { raw ->
                GZIPInputStream(raw).use { gzip ->
                    rootfsArchive.extractToStaging(gzip, ArchiveLayout.ROOTFS_PREFIX, staging)
                }
            }

            // The reader's staging layout is not something this side gets to
            // dictate, so both shapes are accepted: the tree rooted at the
            // staging directory, or under a `rootfs/` directory of it.
            val tree = if (File(staging, "rootfs").isDirectory) File(staging, "rootfs") else staging

            val existing = paths.rootfsDir
            val movedAside = existing.isDirectory
            if (movedAside) {
                if (preRestore.exists()) preRestore.deleteRecursively()
                Files.move(existing.toPath(), preRestore.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                if (!existing.isDirectory && !existing.mkdirs()) {
                    throw IOException("could not create $existing")
                }
                moveTreeContents(tree, existing)
            } catch (failure: Throwable) {
                if (movedAside) {
                    existing.deleteRecursively()
                    Files.move(preRestore.toPath(), existing.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                throw failure
            }

            // The old tree is kept only while the swap is in flight. A restore
            // that succeeded has nothing to roll back to, and a rootfs is
            // hundreds of megabytes.
            if (movedAside) preRestore.deleteRecursively()
            return true
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun moveTreeContents(from: File, to: File) {
        from.listFiles()?.forEach { child ->
            moveAtomically(child, File(to, child.name))
        }
    }

    // ── the graph ────────────────────────────────────────────────────────────

    /**
     * Hilt entries the backup feature needs that are not constructor-injected.
     *
     * Every directory lives here rather than in `BackupPaths`'s own callers,
     * so the derivation of an app path happens once: the settings paths come
     * from [dev.drosh.data.settings.toml.SettingsPaths] (which owns the
     * two-copy arrangement), the rootfs from [UbuntuBootstrap] (which owns
     * where the environment is unpacked), and the DataStore directory from the
     * `preferencesDataStore` delegate's own convention — `filesDir/datastore/`
     * — which is the one place `dev.drosh.data.local.DroshDataStore` declares
     * it.
     *
     * The [BackupLocation] binding exists so a backup run names its own file:
     * `archiveFileName` is read inside [createBackup], after the caller has
     * decided, rather than once per process. An interface, not a `() -> File`
     * provider — Hilt does not resolve function types as binding keys.
     */
    @Module
    @InstallIn(SingletonComponent::class)
    object BackupModule {

        @Provides
        @Singleton
        fun provideBackupPaths(
            @ApplicationContext context: Context,
            settingsPaths: SettingsPaths,
            bootstrap: UbuntuBootstrap,
        ): BackupPaths = BackupPaths(
            rootfsDir = bootstrap.rootfsDir,
            appDroshDir = settingsPaths.backupDir,
            guestDroshDir = settingsPaths.primaryDir,
            dataStoreDir = File(context.filesDir, "datastore"),
            databaseFile = context.getDatabasePath(DroshDatabase.DATABASE_NAME),
        )

        @Provides
        fun provideRootfsArchivePort(): RootfsArchivePort = TerminalRootfsArchivePort()

        /**
         * Unscoped on purpose: the snapshotter holds nothing but the Room
         * database, which is itself a singleton, so a second instance is the
         * same object's second reference.
         */
        @Provides
        fun provideDatabaseSnapshotter(database: DroshDatabase): DatabaseSnapshotter =
            RoomDatabaseSnapshot(database)

        @Provides
        @Singleton
        fun provideBackupLocation(@ApplicationContext context: Context): BackupLocation =
            DefaultBackupLocation(context)

        @Provides
        @Singleton
        fun provideAppVersionInfo(@ApplicationContext context: Context): AppVersionInfo {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") info.versionCode
            }
            return AppVersionInfo(code = code, name = info.versionName.orEmpty())
        }
    }
}

/** Archives land in the app cache, under this directory. */
private const val BACKUP_DIR_NAME = "drosh-backups"

/** The archive container: gzip tar, until zstd earns its dependency. */
private const val ARCHIVE_SUFFIX = ".tar.gz"

/** File-name stamp: one archive per second, which is one per real run. */
private const val FILE_STAMP = "yyyyMMdd-HHmmss"

/** The production [BackupLocation]: app cache, timestamped file name. */
class DefaultBackupLocation(context: Context) : BackupLocation {
    private val cacheDir: File = context.cacheDir

    override fun archiveDir(): File = File(cacheDir, BACKUP_DIR_NAME)

    override fun archiveFileName(): String =
        "drosh-backup-" + SimpleDateFormat(FILE_STAMP, Locale.US).format(Date()) + ARCHIVE_SUFFIX
}
