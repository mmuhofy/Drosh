package dev.drosh.terminal.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The rootfs archive engine: writer and reader, round-tripped.
 *
 * Plain JVM tests on purpose — no Robolectric, no Android framework classes.
 * The tar format, the relax pass and the path guards are all `java.nio.file`
 * and arithmetic, so they run as fast unit tests on CI, and the one thing that
 * would need Android is expressed through `Files.createSymbolicLink` instead.
 *
 * The hand-built tars in the security tests are deliberately *not* produced by
 * the writer under test: a zip-slip fixture built by the code it attacks proves
 * nothing, so [tarOf] below writes its own 512-byte headers.
 */
class RootfsArchiveTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    private lateinit var rootfs: File

    /** A sibling of the staging directory: somewhere an archive must not write. */
    private lateinit var outside: File

    /** Deeply nested, so `../evil.txt` still lands inside the JUnit temp root. */
    private lateinit var staging: File

    private val writer = RootfsArchiveWriter()

    private val reader = RootfsArchiveReader()

    @Before
    fun setUp() {
        rootfs = folder.newFolder("rootfs")
        outside = folder.newFolder("outside")
        staging = folder.newFolder("staging", "a", "b")
    }

    @After
    fun releaseModes() {
        // The writer is required to leave a mode-000 file mode-000 after a
        // successful write, and TemporaryFolder cannot delete such a tree. Every
        // assertion has already run, so relax the whole fixture now.
        folder.root.walkTopDown().forEach { file ->
            file.setReadable(true, true)
            file.setExecutable(true, true)
            file.setWritable(true, true)
        }
    }

    // ─── round trip ─────────────────────────────────────────────────────────

    @Test
    fun `round trip restores nested directories contents and permissions`() {
        // A >256-byte path needs a GNU long-name entry, and one between 101 and
        // 256 needs the ustar prefix field — both built by hand into the member
        // path, both asserted below.
        val files = mapOf(
            "etc/apt/sources.list" to "deb http://archive.ubuntu.com/ubuntu noble main\n",
            "etc/hostname" to "drosh\n",
            "home/user/.zshrc" to "export ZSH_THEME=agnoster\n",
            "home/user/.local/share/note.txt" to "a note\n",
            "usr/local/bin/tool.sh" to "#!/bin/sh\necho hi\n",
            "var/lib/dpkg/status" to "Package: zsh\nStatus: install ok installed\n",
            "long/${"a".repeat(100)}/${"b".repeat(60)}.txt" to "prefix field\n",
            "deeper/${List(6) { "c".repeat(60) }.joinToString("/")}/file.txt" to "long name\n",
            "home/dark/hidden.txt" to "secret\n",
            "home/locked/secret.txt" to "locked\n",
        )
        // home/dark is mode 000 on purpose (see the relax-pass test) and
        // home/locked is unreadable by group/other.
        val directories = mapOf(
            "etc" to MODE_755,
            "etc/apt" to MODE_755,
            "home" to MODE_755,
            "home/user" to MODE_755,
            "home/user/.local" to MODE_700,
            "home/user/.local/share" to MODE_700,
            "usr/local/bin" to MODE_755,
            "var/lib/dpkg" to MODE_755,
            "long/${"a".repeat(100)}" to MODE_755,
            "home/dark" to MODE_000,
            "home/locked" to MODE_500,
        )
        files.forEach { (relative, content) -> writeToRootfs(relative, content, MODE_644) }
        directories.forEach { (relative, mode) ->
            if (relative.isNotEmpty()) Files.setPosixFilePermissions(File(rootfs, relative).toPath(), permissionsFor(mode))
        }
        Files.setPosixFilePermissions(File(rootfs, "usr/local/bin/tool.sh").toPath(), permissionsFor(MODE_755))

        val archive = ByteArrayOutputStream()
        GZIPOutputStream(archive).use { gzip ->
            writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = null, out = gzip)
        }

        val result = reader.extractToStaging(
            GZIPInputStream(ByteArrayInputStream(archive.toByteArray())),
            MEMBER_PREFIX,
            staging,
        )

        assertEquals(files.size, result.fileCount)
        // bytesWritten counts file bytes, so it is exactly the payload.
        assertEquals(files.values.sumOf { it.length.toLong() }, result.bytesWritten)

        // Modes first: home/dark comes back mode 000, and reading through a
        // directory with no permissions at all is not something to rely on.
        assertMode(File(staging, "etc/hostname"), MODE_644)
        assertMode(File(staging, "usr/local/bin/tool.sh"), MODE_755)
        assertMode(File(staging, "home/user/.local"), MODE_700)
        assertMode(File(staging, "home/dark"), MODE_000)
        assertMode(File(staging, "home/locked"), MODE_500)

        // Now the contents, one directory relaxed for the purpose.
        Files.setPosixFilePermissions(File(staging, "home/dark").toPath(), permissionsFor(MODE_700))
        files.forEach { (relative, text) ->
            val extracted = File(staging, relative)
            assertTrue("missing $relative", extracted.isFile)
            assertEquals("content of $relative", text, extracted.readText())
        }
        // The deep names round-tripped byte for byte, which is the part a naive
        // writer gets wrong.
        assertTrue(File(staging, "long/${"a".repeat(100)}").isDirectory)
        assertTrue(File(staging, "deeper/${"c".repeat(60)}").isDirectory)
    }

    @Test
    fun `the relax pass archives unreadable entries and restores their modes`() {
        writeToRootfs("home/dark/hidden.txt", "secret\n", MODE_644)
        Files.setPosixFilePermissions(
            File(rootfs, "home/dark").toPath(),
            PosixFilePermissions.fromString("---------"),
        )
        writeToRootfs("home/locked/secret.txt", "locked\n", MODE_000)

        val archive = ByteArrayOutputStream()
        GZIPOutputStream(archive).use { gzip ->
            writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = null, out = gzip)
        }

        // The relax pass is temporary: the modes the user had are back.
        assertEquals(
            PosixFilePermissions.fromString("---------"),
            Files.getPosixFilePermissions(File(rootfs, "home/dark").toPath()),
        )
        assertEquals(
            PosixFilePermissions.fromString("---------"),
            Files.getPosixFilePermissions(File(rootfs, "home/locked/secret.txt").toPath()),
        )

        val result = reader.extractToStaging(
            GZIPInputStream(ByteArrayInputStream(archive.toByteArray())),
            MEMBER_PREFIX,
            staging,
        )

        // Both unreadable entries were archived, not silently skipped.
        assertEquals(2, result.fileCount)
        // The directory's 000 mode made it back, so its content is unreachable
        // until the mode is relaxed — assert the modes before touching anything.
        assertMode(File(staging, "home/dark"), MODE_000)
        assertMode(File(staging, "home/locked/secret.txt"), MODE_000)
        Files.setPosixFilePermissions(File(staging, "home/dark").toPath(), permissionsFor(MODE_700))
        Files.setPosixFilePermissions(
            File(staging, "home/locked/secret.txt").toPath(),
            permissionsFor(MODE_600),
        )
        assertEquals("secret\n", File(staging, "home/dark/hidden.txt").readText())
        assertEquals("locked\n", File(staging, "home/locked/secret.txt").readText())
    }

    @Test
    fun `excluded top level directories and excluded relative paths are not archived`() {
        writeToRootfs("proc/self/cmdline", "rootfs\n", MODE_644)
        writeToRootfs("tmp/junk.txt", "junk\n", MODE_644)
        writeToRootfs("home/.drosh/settings-cache.tmp", "cache\n", MODE_644)

        val archive = ByteArrayOutputStream()
        GZIPOutputStream(archive).use { gzip ->
            writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = null, out = gzip)
        }

        // Not excluded by the defaults, so a second writer with the relative
        // exclusion configured is what drops it.
        val archiveWithRelativeExclusions = ByteArrayOutputStream()
        GZIPOutputStream(archiveWithRelativeExclusions).use { gzip ->
            RootfsArchiveWriter(excludedRelativePaths = setOf("home/.drosh/settings-cache.tmp"))
                .writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = null, out = gzip)
        }

        val result = reader.extractToStaging(
            GZIPInputStream(ByteArrayInputStream(archiveWithRelativeExclusions.toByteArray())),
            MEMBER_PREFIX,
            staging,
        )

        assertTrue(RootfsArchiveWriter.DEFAULT_EXCLUDED_TOP_LEVEL.contains("proc"))
        assertTrue(RootfsArchiveWriter.DEFAULT_EXCLUDED_TOP_LEVEL.contains("tmp"))
        assertTrue(RootfsArchiveWriter.DEFAULT_EXCLUDED_TOP_LEVEL.contains("dev"))
        assertTrue(RootfsArchiveWriter.DEFAULT_EXCLUDED_TOP_LEVEL.contains("run"))
        assertEquals("no file was archived", 0, result.fileCount)
        // Whole subtrees pruned: nothing at all under them.
        assertFalse(File(staging, "proc").exists())
        assertFalse(File(staging, "tmp").exists())
        // The exact relative path is gone but its parent directory is a real
        // directory in the tree, so the directory member is legitimate.
        assertFalse(File(staging, "home/.drosh/settings-cache.tmp").exists())
        assertTrue(File(staging, "home/.drosh").isDirectory)
        // The default writer does archive it, which is what makes the exclusion
        // meaningful rather than a no-op.
        val fromDefaults = reader.extractToStaging(
            GZIPInputStream(ByteArrayInputStream(archive.toByteArray())),
            MEMBER_PREFIX,
            folder.newFolder("staging-defaults"),
        )
        assertEquals(1, fromDefaults.filesExtracted)
    }

    @Test
    fun `a symlink in the tree is skipped by the writer`() {
        writeToRootfs("home/user/.zshrc", "the real file\n", MODE_644)
        val target = File(outside, "settings.toml")
        target.writeText("outside\n")
        Files.createSymbolicLink(File(rootfs, "home/user/alias").toPath(), target.toPath())

        val archive = ByteArrayOutputStream()
        val result = GZIPOutputStream(archive).use { gzip ->
            writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = null, out = gzip)
        }

        assertEquals(1, result.fileCount)
        val extracted = reader.extractToStaging(
            GZIPInputStream(ByteArrayInputStream(archive.toByteArray())),
            MEMBER_PREFIX,
            staging,
        )
        assertEquals(1, extracted.filesExtracted)
        assertEquals("the real file\n", File(staging, "home/user/.zshrc").readText())
        // The link is not in the archive, so it cannot come back, and nothing
        // was written through it either.
        assertFalse(File(staging, "home/user/alias").exists())
        assertFalse(Files.isSymbolicLink(File(staging, "home/user/alias").toPath()))
        assertEquals("outside\n", target.readText())
    }

    // ─── determinism and the digest ─────────────────────────────────────────

    @Test
    fun `sha256 is non empty stable across identical writes and covers exactly the member bytes`() {
        writeToRootfs("etc/hostname", "drosh\n", MODE_644)
        writeToRootfs("home/user/.zshrc", "export ZSH_THEME=agnoster\n", MODE_644)

        val firstRaw = ByteArrayOutputStream().also {
            writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = null, out = it)
        }.toByteArray()
        val first = writer.writeSubtree(rootfs, MEMBER_PREFIX, null, ByteArrayOutputStream())
        val secondRaw = ByteArrayOutputStream().also {
            writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = null, out = it)
        }.toByteArray()
        val second = writer.writeSubtree(rootfs, MEMBER_PREFIX, null, ByteArrayOutputStream())

        assertTrue(first.sha256.isNotEmpty())
        assertEquals(first.sha256, second.sha256)
        assertEquals(first.bytesWritten, second.bytesWritten)
        assertEquals(first.fileCount, second.fileCount)
        // Two walks of an unchanged tree give the same bytes, so a manifest
        // written from this result is reproducible.
        assertTrue("identical writes produced different bytes", firstRaw.contentEquals(secondRaw))

        // The digest is over the uncompressed member bytes and nothing else —
        // the trailing end-of-archive marker is excluded — so it can be verified
        // independently, exactly as a caller checking a checksum would.
        val independent = MessageDigest.getInstance("SHA-256")
            .digest(firstRaw.copyOf(first.bytesWritten.toInt()))
            .joinToString("") { "%02x".format(it) }
        assertEquals(independent, first.sha256)
        assertTrue(first.bytesWritten < firstRaw.size.toLong())
    }

    @Test
    fun `an empty subtree writes no members`() {
        Files.createDirectories(File(rootfs, "emptydir").toPath())

        val raw = ByteArrayOutputStream()
        val result = writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = "emptydir", out = raw)

        assertEquals(0, result.fileCount)
        assertEquals(0L, result.bytesWritten)
        // The digest of nothing, and a well-formed tar with no members in it.
        assertEquals(EMPTY_SHA256, result.sha256)
        assertEquals("two zero blocks of end-of-archive marker", 1024, raw.size())

        val archive = ByteArrayOutputStream()
        GZIPOutputStream(archive).use { gzip ->
            writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = "emptydir", out = gzip)
        }
        val extracted = reader.extractToStaging(
            GZIPInputStream(ByteArrayInputStream(archive.toByteArray())),
            MEMBER_PREFIX,
            staging,
        )
        assertEquals(0, extracted.filesExtracted)
        assertEquals(0L, extracted.bytesWritten)
    }

    // ─── the header layout the reader has to cope with ──────────────────────

    @Test
    fun `names between 101 and 256 bytes are split across the ustar prefix field`() {
        val medium = "long/${"a".repeat(100)}/${"b".repeat(60)}.txt"
        assertTrue("fixture must need the prefix field", medium.length in 101..256)
        writeToRootfs(medium, "prefix field\n", MODE_644)

        val raw = ByteArrayOutputStream().also {
            writer.writeSubtree(rootfs, MEMBER_PREFIX, null, it)
        }.toByteArray()

        val members = parseTar(raw)
        val member = members.single { it.name.endsWith(".txt") }
        assertEquals("$MEMBER_PREFIX/long/${"a".repeat(100)}", member.prefix)
        assertEquals("b".repeat(60) + ".txt", member.name)
        assertEquals('0', member.type)

        // And it still round-trips through the reader.
        reader.extractToStaging(ByteArrayInputStream(gzip(raw)), MEMBER_PREFIX, staging)
        assertEquals("prefix field\n", File(staging, medium).readText())
    }

    @Test
    fun `names over 256 bytes use a gnu long name entry`() {
        val deep = "deeper/${List(6) { "c".repeat(60) }.joinToString("/")}/file.txt"
        assertTrue("fixture must exceed the ustar prefix", deep.length > 256)
        writeToRootfs(deep, "long name\n", MODE_644)

        val raw = ByteArrayOutputStream().also {
            writer.writeSubtree(rootfs, MEMBER_PREFIX, null, it)
        }.toByteArray()

        val members = parseTar(raw)
        val index = members.indexOfFirst { it.type == 'L' }
        assertTrue("no GNU long name entry written", index >= 0)
        assertEquals(
            MEMBER_PREFIX + "/" + deep,
            members[index].data.decodeToString().trimEnd('\u0000'),
        )
        // The real member follows, carrying the truncated name GNU tar writes.
        val member = members[index + 1]
        assertTrue(member.name.isNotEmpty())
        assertTrue("name field must stay at 100 bytes", member.name.length <= 100)
        assertTrue("name field is a prefix of the member", "$MEMBER_PREFIX/$deep".startsWith(member.name))

        reader.extractToStaging(ByteArrayInputStream(gzip(raw)), MEMBER_PREFIX, staging)
        assertEquals("long name\n", File(staging, deep).readText())
    }

    @Test
    fun `headers are ustar with the checksum the reader expects`() {
        writeToRootfs("etc/hostname", "drosh\n", MODE_644)
        val raw = ByteArrayOutputStream().also {
            writer.writeSubtree(rootfs, MEMBER_PREFIX, null, it)
        }.toByteArray()
        assertTrue("tar is block aligned", raw.size % 512 == 0)
        assertEquals("ustar\u0000", raw.decodeToString(257, 263))
        assertEquals("00", raw.decodeToString(263, 265))
        // The end-of-archive marker is two zero blocks.
        assertTrue(raw.copyOfRange(raw.size - 1024, raw.size).all { it == 0.toByte() })
    }

    @Test
    fun `a pax path record names the member`() {
        // Python's tarfile — proot-distro's own implementation — writes long
        // names this way since 3.8, so the reader has to understand them.
        val path = "$MEMBER_PREFIX/pax/${"d".repeat(120)}.txt"
        val raw = tarOf(
            FakeEntry(name = "placeholder.txt", type = 'x', content = paxRecord("path", path)),
            FakeEntry(name = "short.txt", type = '0', content = "pax name\n"),
        )
        val extracted = reader.extractToStaging(ByteArrayInputStream(gzip(raw)), MEMBER_PREFIX, staging)
        assertEquals(1, extracted.filesExtracted)
        assertEquals("pax name\n", File(staging, "pax/${"d".repeat(120)}.txt").readText())
    }

    // ─── security ───────────────────────────────────────────────────────────

    @Test
    fun `zip slip member paths are refused and nothing escapes the staging directory`() {
        // Four flavours of the same attack: a relative escape under the member
        // prefix, a deeper relative escape, a bare `../` member and an absolute
        // member. Each must be refused; the innocent member must still extract.
        val raw = tarOf(
            FakeEntry(name = "$MEMBER_PREFIX/../../evil.txt", type = '0', content = "evil\n"),
            FakeEntry(name = "$MEMBER_PREFIX/../../../evil2.txt", type = '0', content = "evil\n"),
            FakeEntry(name = "../evil3.txt", type = '0', content = "evil\n"),
            FakeEntry(name = "/etc/evil4", type = '0', content = "evil\n"),
            FakeEntry(name = "$MEMBER_PREFIX/home/user/ok.txt", type = '0', content = "good\n"),
        )

        val extracted = reader.extractToStaging(ByteArrayInputStream(gzip(raw)), MEMBER_PREFIX, staging)

        assertEquals("only the innocent member is extracted", 1, extracted.filesExtracted)
        assertEquals("good\n", File(staging, "home/user/ok.txt").readText())
        // Missing parents are created on demand: the fixture has no directory
        // entries for this path at all.
        assertTrue(File(staging, "home/user").isDirectory)

        val escaped = mutableListOf<String>()
        folder.root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.name.contains("evil")) escaped.add(file.absolutePath)
        }
        assertEquals("files escaped the staging directory: $escaped", emptyList<String>(), escaped)
        assertFalse(File(staging, "etc/evil4").exists())
    }

    @Test
    fun `a symlink member pointing outside the staging directory is skipped`() {
        val outsideFile = File(outside, "settings.toml")
        outsideFile.writeText("outside\n")
        val raw = tarOf(
            FakeEntry(name = "$MEMBER_PREFIX/link", type = '2', linkName = "../../outside/settings.toml"),
            FakeEntry(name = "$MEMBER_PREFIX/real.txt", type = '0', content = "real\n"),
        )

        val extracted = reader.extractToStaging(ByteArrayInputStream(gzip(raw)), MEMBER_PREFIX, staging)

        assertEquals(1, extracted.filesExtracted)
        assertEquals("real\n", File(staging, "real.txt").readText())
        // The link was not recreated, so nothing in the staging tree can reach
        // out of it, and nothing was written through the link either.
        assertFalse(Files.exists(File(staging, "link").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("outside\n", outsideFile.readText())
        assertEquals(1, outside.listFiles()!!.size)
    }

    @Test
    fun `a directory member that escapes the staging directory is refused`() {
        val raw = tarOf(
            FakeEntry(name = "$MEMBER_PREFIX/../../escaped", type = '5'),
            FakeEntry(name = "$MEMBER_PREFIX/ok", type = '5'),
        )
        reader.extractToStaging(ByteArrayInputStream(gzip(raw)), MEMBER_PREFIX, staging)
        assertTrue(File(staging, "ok").isDirectory)
        // The escape would have landed here, inside the staging directory itself.
        assertFalse(File(staging, "escaped").exists())
        assertFalse(File(folder.root, "escaped").exists())
    }

    @Test
    fun `setuid setgid and sticky bits do not survive extraction`() {
        val raw = tarOf(
            FakeEntry(name = "$MEMBER_PREFIX/bin/tool", type = '0', content = "binary\n", mode = MODE_4755),
            FakeEntry(name = "$MEMBER_PREFIX/bin/dir", type = '5', mode = MODE_1777),
        )
        reader.extractToStaging(ByteArrayInputStream(gzip(raw)), MEMBER_PREFIX, staging)
        assertEquals(MODE_755, unixMode(File(staging, "bin/tool")) and MODE_7777)
        assertEquals(MODE_777, unixMode(File(staging, "bin/dir")) and MODE_7777)
    }

    @Test
    fun `a corrupt header checksum is a hard error`() {
        writeToRootfs("etc/hostname", "drosh\n", MODE_644)
        val corrupt = ByteArrayOutputStream().also {
            writer.writeSubtree(rootfs, MEMBER_PREFIX, null, it)
        }.toByteArray().copyOf()
        // The last checksum digit is always significant, so flipping it cannot
        // accidentally leave the stored value unchanged.
        corrupt[153] = (if (corrupt[153] == '0'.code.toByte()) '1' else '0').code.toByte()

        try {
            reader.extractToStaging(ByteArrayInputStream(gzip(corrupt)), MEMBER_PREFIX, staging)
            fail("a header with a bad checksum must be refused")
        } catch (expected: Exception) {
            assertTrue("unexpected error: $expected", expected.toString().contains("checksum"))
        }
        assertEquals("nothing was extracted", emptyList<String>(), staging.list()!!.toList())
    }

    @Test
    fun `a subtree that escapes the rootfs is refused rather than clamped`() {
        try {
            writer.writeSubtree(rootfs, MEMBER_PREFIX, "../../etc", ByteArrayOutputStream())
            fail("a subtree outside the rootfs must throw")
        } catch (expected: Exception) {
            assertTrue("unexpected error: $expected", expected.toString().contains("escapes"))
        }
    }

    @Test
    fun `a subtree selection archives only that subtree`() {
        writeToRootfs("home/user/.zshrc", "home\n", MODE_644)
        writeToRootfs("etc/hostname", "etc\n", MODE_644)

        val archive = ByteArrayOutputStream()
        GZIPOutputStream(archive).use { gzip ->
            writer.writeSubtree(rootfs, MEMBER_PREFIX, relativeSubtree = "home", out = gzip)
        }
        val result = reader.extractToStaging(
            GZIPInputStream(ByteArrayInputStream(archive.toByteArray())),
            MEMBER_PREFIX,
            staging,
        )
        assertEquals(1, result.fileCount)
        assertEquals("home\n", File(staging, "user/.zshrc").readText())
        assertFalse(File(staging, "etc").exists())
    }

    // ─── fixtures ─────────────────────────────────────────────────────────

    private fun writeToRootfs(relative: String, content: String, mode: Int) {
        val file = File(rootfs, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
        Files.setPosixFilePermissions(file.toPath(), permissionsFor(mode))
    }

    private fun assertMode(file: File, expected: Int) {
        assertEquals("mode of ${file.name}", expected, unixMode(file) and MODE_7777)
    }

    /** The raw `unix:mode`, file-type bits included, so setuid can be asserted. */
    private fun unixMode(file: File): Int =
        (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt()

    private fun permissionsFor(mode: Int): Set<PosixFilePermission> {
        val text = buildString(9) {
            append(if (mode and MODE_400 != 0) 'r' else '-')
            append(if (mode and MODE_200 != 0) 'w' else '-')
            append(if (mode and MODE_100 != 0) 'x' else '-')
            append(if (mode and MODE_040 != 0) 'r' else '-')
            append(if (mode and MODE_020 != 0) 'w' else '-')
            append(if (mode and MODE_010 != 0) 'x' else '-')
            append(if (mode and MODE_004 != 0) 'r' else '-')
            append(if (mode and MODE_002 != 0) 'w' else '-')
            append(if (mode and MODE_001 != 0) 'x' else '-')
        }
        return PosixFilePermissions.fromString(text)
    }

    private fun gzip(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(raw) }
    }.toByteArray()

    /**
     * One hand-built tar header plus its data, written from the POSIX spec
     * rather than by the writer under test.
     */
    private data class FakeEntry(
        val name: String,
        val type: Char,
        val content: String = "",
        val linkName: String = "",
        val mode: Long = MODE_644,
        val prefix: String = "",
    )

    private fun tarOf(vararg entries: FakeEntry): ByteArray {
        val out = ByteArrayOutputStream()
        entries.forEach { entry ->
            val block = ByteArray(512)
            putField(block, 0, 100, entry.name)
            putOctal(block, 100, 8, entry.mode)
            putOctal(block, 108, 8, 0)
            putOctal(block, 116, 8, 0)
            putOctal(block, 124, 12, entry.content.length.toLong())
            putOctal(block, 136, 12, 0)
            block[156] = entry.type.code.toByte()
            if (entry.linkName.isNotEmpty()) putField(block, 157, 100, entry.linkName)
            putField(block, 257, 6, "ustar\u0000")
            putField(block, 263, 2, "00")
            if (entry.prefix.isNotEmpty()) putField(block, 345, 155, entry.prefix)
            // The checksum is the sum of every byte with this field as spaces.
            for (i in 148 until 156) block[i] = ' '.code.toByte()
            val digits = block.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0')
            digits.forEachIndexed { i, c -> block[148 + i] = c.code.toByte() }
            block[154] = 0
            block[155] = ' '.code.toByte()
            out.write(block)
            if (entry.content.isNotEmpty()) {
                val data = entry.content.toByteArray()
                out.write(data)
                out.write(ByteArray((512 - (data.size % 512)) % 512))
            }
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun putField(block: ByteArray, offset: Int, length: Int, value: String) {
        value.toByteArray(Charsets.UTF_8)
            .copyInto(block, offset, 0, minOf(length, value.toByteArray(Charsets.UTF_8).size))
    }

    private fun putOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        value.toString(8).padStart(length - 1, '0').forEachIndexed { i, c ->
            block[offset + i] = c.code.toByte()
        }
        block[offset + length - 1] = 0
    }

    /**
     * A minimal tar reader for the header-layout assertions. A second
     * implementation of the format is the point: if the writer and this one
     * agree, the writer is not merely self-consistent.
     */
    private class RawMember(
        val type: Char,
        val prefix: String,
        val name: String,
        val size: Long,
        val data: ByteArray,
    )

    private fun parseTar(raw: ByteArray): List<RawMember> = buildList {
        var offset = 0
        while (offset + 512 <= raw.size) {
            val header = raw.copyOfRange(offset, offset + 512)
            offset += 512
            if (header.all { it == 0.toByte() }) continue
            val name = field(header, 0, 100)
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val prefix = field(header, 345, 155)
            val data = raw.copyOfRange(offset, offset + size.toInt())
            offset += ((512 - (size % 512)) % 512)
            add(RawMember(type, prefix, name, size, data))
        }
    }

    private fun field(block: ByteArray, offset: Int, length: Int): String =
        String(block, offset, length, Charsets.UTF_8).trimEnd('\u0000')

    private fun octal(block: ByteArray, offset: Int, length: Int): Long {
        var i = offset
        val end = offset + length
        while (i < end && (block[i] == ' '.code.toByte() || block[i] == '0'.code.toByte() || block[i] == 0.toByte())) i++
        if (i >= end) return 0
        var j = i
        while (j < end && block[j] != ' '.code.toByte() && block[j] != 0.toByte()) j++
        return String(block, i, j - i, Charsets.US_ASCII).toLong(8)
    }

    /**
     * A PAX record with the self-referencing length everyone gets wrong on the
     * first attempt: the length counts the whole record, including its own
     * leading digits and the trailing newline.
     */
    private fun paxRecord(key: String, value: String): String {
        val body = "$key=$value\n"
        var digits = 1
        while (true) {
            val total = body.length + digits + 1
            if (total.toString().length == digits) return "$total $body"
            digits = total.toString().length
        }
    }

    private companion object {
        const val MEMBER_PREFIX = "drosh/rootfs"

        // Kotlin has no octal literal, so the modes are spelled in
        // binary with the octal they stand for beside them.
        const val MODE_000 = 0b000 // 0o000
        const val MODE_001 = 0b001 // 0o001
        const val MODE_002 = 0b010 // 0o002
        const val MODE_004 = 0b100 // 0o004
        const val MODE_010 = 0b001_000 // 0o010
        const val MODE_020 = 0b010_000 // 0o020
        const val MODE_040 = 0b100_000 // 0o040
        const val MODE_100 = 0b001_000_000 // 0o100
        const val MODE_200 = 0b010_000_000 // 0o200
        const val MODE_400 = 0b100_000_000 // 0o400
        const val MODE_500 = 0b101_000_000 // 0o500
        const val MODE_600 = 0b110_000_000 // 0o600
        const val MODE_644 = 0b110_100_100 // 0o644
        const val MODE_700 = 0b111_000_000 // 0o700
        const val MODE_755 = 0b111_101_101 // 0o755
        const val MODE_777 = 0b111_111_111 // 0o777
        const val MODE_1777 = 0b001_111_111_111 // 0o1777
        const val MODE_4755 = 0b100_111_101_101 // 0o4755
        const val MODE_7777 = 0b111_111_111_111 // 0o7777

        /** SHA-256 of zero bytes. */
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
