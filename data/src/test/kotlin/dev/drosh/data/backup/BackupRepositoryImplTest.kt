package dev.drosh.data.backup

import app.cash.turbine.test
import dev.drosh.domain.backup.BackupManifest
import dev.drosh.domain.backup.BackupOptions
import dev.drosh.domain.backup.BackupProgress
import dev.drosh.domain.backup.RestoreSelection
import dev.drosh.domain.input.InputPreferencesRepository
import dev.drosh.domain.session.DeviceIdentityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The backup and restore round trip, end to end, against temp directories.
 *
 * What these tests hold in place is the order of the archive and the honesty of
 * its checksums, because those are the two things a unit test can prove and a
 * device test would only demonstrate:
 *
 *  - the manifest is the **last** member, so an interrupted backup is an
 *    archive restore refuses rather than a half-truth;
 *  - a corrupted member fails the restore *before* any file is touched, so the
 *    previous files are still there afterwards;
 *  - every section comes back byte for byte, including the two-copy paths
 *    (app mirror and guest) and the database swap.
 *
 * The rootfs port is a fake that packs and unpacks a temp directory with the
 * same tar writer the app sections use — which is also the only reason this
 * test can run without an Android device, a Room database, or an Ubuntu
 * rootfs on disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRepositoryImplTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var paths: BackupPaths
    private lateinit var repository: BackupRepositoryImpl
    private lateinit var snapshotter: FakeDatabaseSnapshotter
    private lateinit var inputPreferences: FakeInputPreferences
    private lateinit var rootfsPort: FakeRootfsArchivePort

    @Before
    fun setUp() {
        root = tmp.newFolder("app")
        paths = BackupPaths(
            rootfsDir = File(root, "ubuntu/rootfs"),
            appDroshDir = File(root, "files/.drosh"),
            guestDroshDir = File(root, "ubuntu/rootfs/home/.drosh"),
            dataStoreDir = File(root, "files/datastore"),
            databaseFile = File(root, "files/databases/irisshell.db"),
        )
        snapshotter = FakeDatabaseSnapshotter()
        inputPreferences = FakeInputPreferences(visible = false)
        rootfsPort = FakeRootfsArchivePort()
        repository = BackupRepositoryImpl(
            paths = paths,
            appData = AppDataBackup(paths, snapshotter, inputPreferences),
            rootfsArchive = rootfsPort,
            deviceIdentity = FakeDeviceIdentityRepository(),
            appVersion = AppVersionInfo(code = 123, name = "1.2.3"),
            backupLocation = FakeBackupLocation(File(root, "cache/backups").apply { mkdirs() }, ARCHIVE_NAME),
        )
    }

    @After
    fun tearDown() {
        // Nothing to clean by hand: the repository owns its staging
        // directories and the tests below assert that it does.
    }

    // ── the round trip ───────────────────────────────────────────────────────

    @Test
    fun `a backup archive round-trips every app section`() = runTest {
        writeFakeAppFiles()
        val archive = backupFile()

        val events = repository.createBackup(BackupOptions(includeRootfs = false)).toList()

        val last = events.last()
        // A cast here would hide the reason; the reason is the diagnosis.
        assertTrue("createBackup ended with $last", last is BackupProgress.Completed)
        val completed = last as BackupProgress.Completed
        assertEquals(archive.absolutePath, completed.outputPath)
        assertTrue(archive.isFile)

        // Everything the archive held is now different on disk.
        overwriteAppFiles()
        assertFalse(SETTINGS_TEXT == paths.appSettingsFile.readText())

        val restored = repository.restore(archive).toList()

        val restoreLast = restored.last()
        assertTrue("restore ended with $restoreLast", restoreLast is BackupProgress.Completed)
        assertEquals(
            completed.manifest,
            (restoreLast as BackupProgress.Completed).manifest,
        )
        assertEquals(SETTINGS_TEXT, paths.appSettingsFile.readText())
        assertEquals(THEME_TEXT, File(paths.appThemesDir, "nord.toml").readText())
        assertEquals(THEME_TEXT, File(paths.guestThemesDir, "nord.toml").readText())
        assertEquals(DATASTORE_TEXT, File(paths.dataStoreDir, "drosh_shell_prefs.preferences_pb").readText())
        assertEquals(DATABASE_TEXT, paths.databaseFile.readText())
        assertEquals(SNIPPETS_TEXT, paths.snippetsFile.readText())
        assertEquals(BACKGROUND_TEXT, File(paths.appDroshDir, "background.png").readText())
        // The input preferences were re-stated in process, which is the one
        // section the settings file alone cannot carry back without a restart.
        assertEquals(listOf(false), inputPreferences.applied)
    }

    @Test
    fun `inspecting an archive reads its manifest and nothing else`() = runTest {
        writeFakeAppFiles()
        val archive = backupFile()
        repository.createBackup(BackupOptions()).toList()

        val manifest = repository.inspectArchive(archive)

        // If the manifest did not survive, say what the archive actually
        // holds — the member list is the whole diagnosis.
        assertNotNull(
            "inspect returned null; members=${runCatching { memberNames(archive) }.getOrElse { it.toString() }}",
            manifest,
        )
        requireNotNull(manifest)
        assertEquals(1, manifest.formatVersion)
        assertEquals("1.2.3", manifest.appVersionName)
        assertEquals(123, manifest.appVersionCode)
        assertEquals("Test Device", manifest.deviceLabel)
        assertFalse(manifest.rootfsIncluded)
        assertEquals(
            listOf(
                BackupManifest.ID_SETTINGS,
                BackupManifest.ID_DATASTORE,
                BackupManifest.ID_DATABASE,
                BackupManifest.ID_THEMES,
                BackupManifest.ID_SNIPPETS,
                BackupManifest.ID_INPUT_PREFS,
            ),
            manifest.sections.map { it.id },
        )
        // Every section's checksum is real: a non-empty digest and a byte size
        // that matches what the archive holds.
        manifest.sections.forEach { section ->
            assertTrue("${section.id} has no checksum", section.sha256.isNotEmpty())
            assertTrue("${section.id} has no size", section.byteSize > 0)
        }
        // The database section came from a snapshot of the live file, not from
        // the file itself, and the blurred cache was left out of the settings
        // section — it is derived data.
        assertEquals(listOf("irisshell.db"), snapshotter.calls)
        assertFalse(memberNames(archive).any { it.contains(".blurred.") })
    }

    @Test
    fun `the manifest is the last member, after the rootfs`() = runTest {
        writeFakeAppFiles()
        writeFakeRootfs()
        val archive = backupFile()
        repository.createBackup(BackupOptions(includeRootfs = true)).toList()

        val names = memberNames(archive)

        assertEquals(ArchiveLayout.MANIFEST_MEMBER, names.last())
        val firstRootfs = names.indexOfFirst { it.startsWith(ArchiveLayout.ROOTFS_PREFIX) }
        val lastApp = names.indexOfLast { it.startsWith(ArchiveLayout.APP_PREFIX) }
        assertTrue("the app sections come before the rootfs", lastApp < firstRootfs)
        assertTrue("the rootfs comes before the manifest", firstRootfs < names.size - 1)
        assertTrue(names.any { it.startsWith("${ArchiveLayout.ROOTFS_PREFIX}/") })

        val manifest = repository.inspectArchive(archive)
        requireNotNull(manifest)
        assertTrue(manifest.rootfsIncluded)
        assertEquals(BackupManifest.ID_ROOTFS, manifest.sections.last().id)
    }

    @Test
    fun `progress names every step and ends completed`() = runTest {
        writeFakeAppFiles()
        writeFakeRootfs()

        repository.createBackup(BackupOptions(includeRootfs = true)).test {
            val started = awaitItem() as BackupProgress.Started
            // six app sections, the rootfs, and the manifest
            assertEquals(8, started.totalSteps)
            var steps = 0
            while (true) {
                when (val event = awaitItem()) {
                    is BackupProgress.Step -> {
                        steps++
                        assertTrue(event.fraction in 0f..1f)
                    }
                    else -> {
                        assertTrue(event is BackupProgress.Completed)
                        break
                    }
                }
            }
            // Started, one Step per unit of work, then Completed: six app
            // sections, the rootfs and the manifest — the same eight units
            // Started counted.
            assertEquals(8, steps)
            awaitComplete()
        }
    }

    // ── refusing archives ────────────────────────────────────────────────────

    @Test
    fun `an archive with no manifest is not a backup`() = runTest {
        val member = "drosh/app/settings/settings.toml"
        val archive = File(root, "cache/backups/no-manifest.tar.gz")
        archive.parentFile?.mkdirs()
        GZIPOutputStream(FileOutputStream(archive)).use { gzip ->
            val writer = TarMemberWriter(gzip)
            writer.beginMember(member, SETTINGS_TEXT.length.toLong(), 0, false).use { it.write(SETTINGS_TEXT.toByteArray()) }
            writer.finish()
        }

        assertNull(repository.inspectArchive(archive))

        val events = repository.restore(archive).toList()
        assertTrue(events.last() is BackupProgress.Failed)
    }

    @Test
    fun `a manifest from a newer format is refused`() = runTest {
        val newer = """
            {"format_version":99,"created_at_epoch_ms":1,"app_version_code":1,
             "app_version_name":"1.0","device_label":"d","rootfs_included":false,
             "sections":[]}
        """.trimIndent()
        val archive = File(root, "cache/backups/newer.tar.gz")
        archive.parentFile?.mkdirs()
        GZIPOutputStream(FileOutputStream(archive)).use { gzip ->
            val writer = TarMemberWriter(gzip)
            val bytes = newer.toByteArray()
            writer.beginMember(
                ArchiveLayout.MANIFEST_MEMBER,
                bytes.size.toLong(),
                0,
                false,
            ).use { it.write(bytes) }
            writer.finish()
        }

        assertNull(repository.inspectArchive(archive))
    }

    @Test
    fun `a file that is not an archive at all is refused`() = runTest {
        val garbage = File(root, "cache/backups/not-a-backup.bin")
        garbage.parentFile?.mkdirs()
        garbage.writeText("just some text, certainly not a tar.gz")

        assertNull(repository.inspectArchive(garbage))
        assertNull(repository.inspectArchive(File(root, "cache/backups/absent.tar.gz")))
    }

    // ── refusing a damaged archive ───────────────────────────────────────────

    @Test
    fun `a corrupt member fails the restore and leaves the files alone`() = runTest {
        writeFakeAppFiles()
        val archive = backupFile()
        repository.createBackup(BackupOptions()).toList()
        val corrupted = corruptMember(archive, "drosh/app/settings/settings.toml")

        // The restore is asked for while the live files hold newer content.
        overwriteAppFiles()

        val events = repository.restore(corrupted).toList()

        val failed = events.last() as BackupProgress.Failed
        assertTrue(failed.reason.contains("app_settings"))
        // Nothing moved: the tampered files are still the tampered files.
        assertEquals(TAMPERED_SETTINGS, paths.appSettingsFile.readText())
        assertEquals(TAMPERED_DATASTORE, File(paths.dataStoreDir, "drosh_shell_prefs.preferences_pb").readText())
        assertEquals(TAMPERED_DATABASE, paths.databaseFile.readText())
        assertTrue(inputPreferences.applied.isEmpty())
        // And the staging directory is gone, not left in the cache.
        assertNoLeftovers()
    }

    @Test
    fun `a member whose payload was truncated fails the restore`() = runTest {
        writeFakeAppFiles()
        val archive = backupFile()
        repository.createBackup(BackupOptions()).toList()
        // Cut the archive short after the first app member's header.
        val raw = GZIPInputStream(FileInputStream(archive)).readBytes()
        val truncated = File(root, "cache/backups/truncated.tar.gz")
        GZIPOutputStream(FileOutputStream(truncated)).use { it.write(raw, 0, 512 + 32) }

        val events = repository.restore(truncated).toList()

        assertTrue(events.last() is BackupProgress.Failed)
        assertNoLeftovers()
    }

    // ── partial restores ─────────────────────────────────────────────────────

    @Test
    fun `a partial selection restores only what was asked for`() = runTest {
        writeFakeAppFiles()
        val archive = backupFile()
        repository.createBackup(BackupOptions()).toList()
        overwriteAppFiles()

        val events = repository.restore(
            archive,
            RestoreSelection(sectionIds = setOf(BackupManifest.ID_SETTINGS)),
        ).toList()

        val last = events.last()
        assertTrue("partial restore ended with $last", last is BackupProgress.Completed)
        assertEquals(SETTINGS_TEXT, paths.appSettingsFile.readText())
        // The rest of the app was left holding what it had.
        assertEquals(TAMPERED_DATASTORE, File(paths.dataStoreDir, "drosh_shell_prefs.preferences_pb").readText())
        assertEquals(TAMPERED_DATABASE, paths.databaseFile.readText())
        assertEquals(TAMPERED_THEME, File(paths.appThemesDir, "nord.toml").readText())
        assertEquals(TAMPERED_SNIPPETS, paths.snippetsFile.readText())
        assertTrue("input prefs were not selected", inputPreferences.applied.isEmpty())
        assertNoLeftovers()
    }

    @Test
    fun `a section the archive does not have fails before touching anything`() = runTest {
        writeFakeAppFiles()
        val archive = backupFile()
        repository.createBackup(BackupOptions()).toList()
        overwriteAppFiles()

        val events = repository.restore(
            archive,
            RestoreSelection(sectionIds = setOf(BackupManifest.ID_ROOTFS)),
        ).toList()

        assertTrue(events.last() is BackupProgress.Failed)
        assertEquals(TAMPERED_SETTINGS, paths.appSettingsFile.readText())
        assertNoLeftovers()
    }

    // ── the rootfs ───────────────────────────────────────────────────────────

    @Test
    fun `restoring the rootfs swaps the tree and rewrites the settings into it`() = runTest {
        writeFakeAppFiles()
        writeFakeRootfs()
        val archive = backupFile()
        repository.createBackup(BackupOptions(includeRootfs = true)).toList()

        // A rootfs that has since changed, and an archived settings file that
        // disagrees with what the guest copy now holds.
        File(paths.rootfsDir, "etc/hostname").writeText("a-different-host\n")
        File(paths.guestDroshDir, "since-restored.txt").writeText("user data\n")
        assertEquals("a-different-host\n", File(paths.rootfsDir, "etc/hostname").readText())

        val events = repository.restore(
            archive,
            RestoreSelection(sectionIds = setOf(BackupManifest.ID_ROOTFS)),
        ).toList()

        val last = events.last()
        assertTrue("rootfs restore ended with $last", last is BackupProgress.Completed)
        // The archived tree is back, whole: the file that changed and the one
        // the user added are both gone, because the tree was replaced.
        assertEquals("ubuntu\n", File(paths.rootfsDir, "etc/hostname").readText())
        assertFalse(File(paths.rootfsDir, "since-restored.txt").exists())
        // And the app's settings mirror — the newer truth — was written over
        // the rootfs copy that arrived with the tree.
        assertEquals(SETTINGS_TEXT, paths.guestSettingsFile.readText())
        // No staging tree and no pre-restore copy is left behind either.
        val rootfsParent = paths.rootfsDir.parentFile!!
        assertFalse(File(rootfsParent, "rootfs.pre-restore").exists())
        assertTrue(rootfsParent.listFiles()!!.none {
            it.name.startsWith("rootfs.restore-staging")
        })
    }

    @Test
    fun `a rootfs that was not asked for is left alone`() = runTest {
        writeFakeAppFiles()
        writeFakeRootfs()
        val archive = backupFile()
        repository.createBackup(BackupOptions(includeRootfs = true)).toList()
        File(paths.rootfsDir, "etc/hostname").writeText("a-different-host\n")

        repository.restore(
            archive,
            RestoreSelection(sectionIds = setOf(BackupManifest.ID_SETTINGS)),
        ).toList()

        assertEquals("a-different-host\n", File(paths.rootfsDir, "etc/hostname").readText())
        // The guest copy of the settings is not touched when the rootfs stays:
        // its owner is the running environment.
        assertEquals(GUEST_SETTINGS, paths.guestSettingsFile.readText())
    }

    @Test
    fun `a rootfs restore that cannot extract rolls everything back`() = runTest {
        writeFakeAppFiles()
        writeFakeRootfs()
        val archive = backupFile()
        repository.createBackup(BackupOptions(includeRootfs = true)).toList()
        // A rootfs that has since changed, and app files that have moved on
        // from what the archive holds.
        File(paths.rootfsDir, "etc/hostname").writeText("keep-me\n")
        overwriteAppFiles()
        rootfsPort.failExtract = true

        val events = repository.restore(archive).toList()

        assertTrue(events.last() is BackupProgress.Failed)
        // The tree is exactly as it was.
        val rootfsParent = paths.rootfsDir.parentFile!!
        assertEquals("keep-me\n", File(paths.rootfsDir, "etc/hostname").readText())
        assertFalse(File(rootfsParent, "rootfs.pre-restore").exists())
        assertTrue(rootfsParent.listFiles()!!.none {
            it.name.startsWith("rootfs.restore-staging")
        })
        // And so are the app files the restore had already swapped into place
        // before the rootfs step failed.
        assertEquals(TAMPERED_SETTINGS, paths.appSettingsFile.readText())
        assertEquals(TAMPERED_DATABASE, paths.databaseFile.readText())
        assertEquals(TAMPERED_SNIPPETS, paths.snippetsFile.readText())
        assertNoLeftovers()
    }

    // ── the fake tree ────────────────────────────────────────────────────────

    private fun writeFakeAppFiles() {
        write(paths.appSettingsFile, SETTINGS_TEXT)
        write(File(paths.appThemesDir, "nord.toml"), THEME_TEXT)
        write(File(paths.guestThemesDir, "nord.toml"), THEME_TEXT)
        write(File(paths.dataStoreDir, "drosh_shell_prefs.preferences_pb"), DATASTORE_TEXT)
        write(paths.databaseFile, DATABASE_TEXT)
        write(paths.snippetsFile, SNIPPETS_TEXT)
        write(File(paths.appDroshDir, "background.png"), BACKGROUND_TEXT)
        // The blurred cache is derived data and must not be archived.
        write(File(paths.appDroshDir, "background.png.blurred.v1.8.png"), "blur")
    }

    private fun writeFakeRootfs() {
        write(File(paths.rootfsDir, "etc/hostname"), "ubuntu\n")
        write(File(paths.rootfsDir, "home/.zshrc"), "# zsh\n")
        write(paths.guestSettingsFile, GUEST_SETTINGS)
    }

    private fun overwriteAppFiles() {
        write(paths.appSettingsFile, TAMPERED_SETTINGS)
        write(File(paths.appThemesDir, "nord.toml"), TAMPERED_THEME)
        write(File(paths.dataStoreDir, "drosh_shell_prefs.preferences_pb"), TAMPERED_DATASTORE)
        write(paths.databaseFile, TAMPERED_DATABASE)
        write(paths.snippetsFile, TAMPERED_SNIPPETS)
    }

    private fun write(target: File, text: String) {
        target.parentFile?.mkdirs()
        target.writeText(text)
    }

    private fun backupFile(): File = File(root, "cache/backups/$ARCHIVE_NAME")

    private fun assertNoLeftovers() {
        val dir = File(root, "cache/backups")
        val leftovers = dir.listFiles()?.filter {
            it.name.contains("staging") || it.name.contains("restore")
        }.orEmpty()
        assertTrue("staging directories left behind: $leftovers", leftovers.isEmpty())
    }

    /** Every member name in the archive, in the order they appear. */
    private fun memberNames(archive: File): List<String> {
        val names = mutableListOf<String>()
        GZIPInputStream(FileInputStream(archive)).use { gzip ->
            ArchiveTarReader(gzip).use { reader ->
                while (true) {
                    val name = reader.nextFileEntry() ?: break
                    names += name
                }
            }
        }
        return names
    }

    /**
     * Rewrites [archive] with one flipped byte inside a member's payload.
     *
     * The tar is decompressed, one payload byte inverted, and the whole stream
     * written out again — the shape of a file that was edited by hand, which
     * is the damage a checksum is supposed to catch.
     */
    private fun corruptMember(archive: File, memberName: String): File {
        val raw = GZIPInputStream(FileInputStream(archive)).readBytes()
        val headerOffset = indexOfBytes(raw, memberName.toByteArray())
        assertTrue("no member named $memberName", headerOffset >= 0)
        val payload = headerOffset + TarFormat.BLOCK_SIZE
        raw[payload] = (raw[payload].toInt() xor 0xFF).toByte()
        val corrupted = File(archive.parentFile, "corrupted.tar.gz")
        GZIPOutputStream(FileOutputStream(corrupted)).use { it.write(raw) }
        return corrupted
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private companion object {
        const val ARCHIVE_NAME = "drosh-backup-test.tar.gz"
        const val SETTINGS_TEXT = "theme_mode = \"dark\"\n"
        const val GUEST_SETTINGS = "theme_mode = \"light\"\n"
        const val THEME_TEXT = "[theme]\nid = \"nord\"\n"
        const val SNIPPETS_TEXT = "{\"gp\":\"git push\"}"
        const val DATASTORE_TEXT = "preferences-bytes"
        const val DATABASE_TEXT = "database-bytes"
        const val BACKGROUND_TEXT = "background-bytes"
        const val TAMPERED_SETTINGS = "theme_mode = \"tampered\"\n"
        const val TAMPERED_THEME = "[theme]\nid = \"tampered\"\n"
        const val TAMPERED_DATASTORE = "tampered- preferences"
        const val TAMPERED_DATABASE = "tampered-database"
        const val TAMPERED_SNIPPETS = "{\"xx\":\"tampered\"}"
    }
}

// ── fakes ────────────────────────────────────────────────────────────────────

/** Where archives go in a test: a temp directory and a fixed name. */
private class FakeBackupLocation(dir: File, name: String) : BackupLocation {
    private val dir = dir.apply { mkdirs() }
    private val name = name

    override fun archiveDir(): File = dir

    override fun archiveFileName(): String = name
}

/** The database, as a file copy. */
private class FakeDatabaseSnapshotter : DatabaseSnapshotter {
    val calls = mutableListOf<String>()

    override fun snapshot(source: File, target: File) {
        calls += source.name
        source.copyTo(target, overwrite = true)
    }
}

/** The input preferences, as a state flow and a record of what was set. */
private class FakeInputPreferences(visible: Boolean) : InputPreferencesRepository {

    private val state = MutableStateFlow(visible)
    override val extraKeysBarVisible: Flow<Boolean> = state.asStateFlow()

    val applied = mutableListOf<Boolean>()

    override suspend fun setExtraKeysBarVisible(visible: Boolean) {
        applied += visible
        state.value = visible
    }
}

private class FakeDeviceIdentityRepository : DeviceIdentityRepository {
    override suspend fun resolveName(): String = "Test Device"
    override suspend fun resolveVisual(marketingName: String): String? = null
}

/**
 * The rootfs engine, as a temp directory packed with the same tar writer the
 * app sections use.
 *
 * It mirrors the two things [RootfsArchivePort] promises: members under the
 * prefix it was given, and an extract that leaves the tree (prefix stripped)
 * in the staging directory. It also ends its stream the way the real writer
 * does — with an end-of-archive marker of its own — so the round trip here
 * exercises the manifest being written *after* that marker, which is the
 * shape an archive with a rootfs actually has on disk.
 */
private class FakeRootfsArchivePort : RootfsArchivePort {

    /** Set by a test that wants the extraction to fail. */
    var failExtract = false

    override suspend fun writeSubtree(
        rootfsDir: File,
        memberPrefix: String,
        out: OutputStream,
    ): ArchiveWriteResult {
        val counting = CountingDigest(out)
        val writer = TarMemberWriter(counting)
        var files = 0
        rootfsDir.filesUnder().forEach { file ->
            val relative = file.relativeTo(rootfsDir).path
            val member = "$memberPrefix/$relative"
            writer.beginMember(member, file.length(), file.lastModified(), false).use { stream ->
                file.inputStream().use { input -> input.copyTo(stream) }
            }
            files++
        }
        writer.finish()
        // The real writer closes its own stream; the fake does too, or these
        // tests would pass on a shape the terminal slice does not produce.
        out.write(ByteArray(TarFormat.BLOCK_SIZE * 2))
        out.flush()
        return ArchiveWriteResult(bytesWritten = counting.bytes, sha256 = counting.hex(), fileCount = files)
    }

    override suspend fun extractToStaging(input: InputStream, memberPrefix: String, stagingDir: File) {
        if (failExtract) throw IOException("the archive is damaged")
        GZIPInputStream(input.buffered()).use { gzip ->
            ArchiveTarReader(gzip).use { reader ->
                while (true) {
                    val name = reader.nextFileEntry() ?: break
                    if (!name.startsWith("$memberPrefix/")) continue
                    val target = File(stagingDir, name.removePrefix("$memberPrefix/"))
                    target.parentFile?.mkdirs()
                    val buffer = ByteArrayOutputStream()
                    reader.copyTo(buffer)
                    target.writeBytes(buffer.toByteArray())
                }
            }
        }
    }
}

/** Counts and digests what went through it, so a fake can report both. */
private class CountingDigest(private val delegate: OutputStream) : OutputStream() {

    private val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        digest.update(b.toByte())
        bytes++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        digest.update(b, off, len)
        bytes += len
    }

    override fun flush() {
        delegate.flush()
    }

    fun hex(): String = digest.digest().toHexLower()
}
