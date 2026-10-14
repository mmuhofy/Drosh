package dev.drosh.data.backup

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.drosh.data.local.DroshDatabase
import dev.drosh.domain.backup.BackupManifest
import dev.drosh.domain.backup.BackupOptions
import dev.drosh.domain.backup.BackupSection
import dev.drosh.domain.backup.BackupSectionKind
import dev.drosh.domain.input.InputPreferencesRepository
import dev.drosh.terminal.SnippetsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every directory the app-data half of a backup touches, in one plain data
 * class.
 *
 * Two copies of almost everything, because that is how the app itself keeps
 * its files: the **guest** copy inside the Linux environment
 * (`<rootfs>/home/.drosh`) and the **app-private** mirror
 * (`filesDir/.drosh`), which survives a rootfs reset. The app reads the guest
 * copy when there is one and falls back to the mirror otherwise — see
 * [dev.drosh.data.settings.toml.SettingsPaths] and
 * [dev.drosh.data.theme.ThemeRepositoryImpl], which own that arrangement.
 *
 * This class exists so the backup code can be pointed at a temp directory in a
 * JVM test: the default graph (see the module in `BackupRepositoryImpl.kt`)
 * builds one from the real paths, and every other caller simply receives one.
 */
data class BackupPaths(
    /** `filesDir/ubuntu/rootfs` — the Linux environment itself. */
    val rootfsDir: File,
    /** `filesDir/.drosh` — the app-private mirror of the Drosh config dir. */
    val appDroshDir: File,
    /** `<rootfs>/home/.drosh` — the config dir a shell sees. */
    val guestDroshDir: File,
    /** `filesDir/datastore` — the preferences DataStore's own directory. */
    val dataStoreDir: File,
    /** `databases/irisshell.db` — the Room file, with `-wal`/`-shm` siblings. */
    val databaseFile: File,
) {
    /** The settings file the app always writes, whatever the rootfs is doing. */
    val appSettingsFile: File get() = File(appDroshDir, "settings.toml")

    /** The settings file a shell reads; missing until the rootfs exists. */
    val guestSettingsFile: File get() = File(guestDroshDir, "settings.toml")

    /** Installed theme files, app-private copy. */
    val appThemesDir: File get() = File(appDroshDir, "themes")

    /** Installed theme files, guest copy — the one the app actually reads. */
    val guestThemesDir: File get() = File(guestDroshDir, "themes")

    /**
     * The snippets file, in the guest home — its only home.
     *
     * Derived through [SnippetsStore] rather than written out again here: the
     * store's own [SnippetsStore.location] is what the terminal, the keyboard
     * and the command-state provider all call, so the backup cannot drift
     * from them.
     */
    val snippetsFile: File get() = SnippetsStore.location(File(rootfsDir, "home"))

    /** Room's write-ahead log; replayed over the main file on the next open. */
    val databaseWalFile: File get() = File(databaseFile.absolutePath + "-wal")

    /** Shared-memory index for [databaseWalFile]; safe to drop with the log. */
    val databaseShmFile: File get() = File(databaseFile.absolutePath + "-shm")

    /** The database's own directory, where a snapshot may be staged. */
    val databaseDir: File
        get() = databaseFile.parentFile
            ?: throw IOException("${databaseFile.path} has no parent directory")
}

/**
 * Where every member of an archive lives.
 *
 * One top-level directory — `drosh/` — holding the app-data sections, the
 * rootfs tree, and the manifest last of all. The prefix is shared with the
 * rootfs writer in `:terminal`, which receives it as its member prefix; see
 * `docs/BACKUP-RESTORE-PLAN.md` §2 for the format this mirrors.
 */
internal object ArchiveLayout {
    /** The container directory every member sits under. */
    const val ARCHIVE_PREFIX = "drosh"

    /** The app-data sections, under [ARCHIVE_PREFIX]. */
    const val APP_PREFIX = "$ARCHIVE_PREFIX/app"

    /** The rootfs tree, under [ARCHIVE_PREFIX] — written by the `:terminal` port. */
    const val ROOTFS_PREFIX = "$ARCHIVE_PREFIX/rootfs"

    /** The manifest member, written after everything else. */
    const val MANIFEST_MEMBER = "$ARCHIVE_PREFIX/manifest.json"

    /** Cap on the manifest member's size; see [BackupManifestJson.MAX_BYTES]. */
    const val MANIFEST_MAX_BYTES = BackupManifestJson.MAX_BYTES

    const val SETTINGS_DIR = "$APP_PREFIX/settings"
    const val DATASTORE_DIR = "$APP_PREFIX/datastore"
    const val DATABASE_DIR = "$APP_PREFIX/database"
    const val THEMES_DIR = "$APP_PREFIX/themes"
    const val SNIPPETS_DIR = "$APP_PREFIX/snippets"
    const val INPUT_PREFS_FILE = "$APP_PREFIX/input_prefs.json"
}

/** One member of a section, as written or as read back. */
data class SectionMember(val name: String, val byteSize: Long, val sha256: String)

/**
 * How a section's checksum is defined.
 *
 * A section is a group of members, and the manifest carries a single sha256
 * for the group — so the digest has to be a function of the group, not of a
 * stream. It is: sha256 over one line per member, sorted by member name, of
 *
 *     <member path> 0x00 <member sha256> 0x0A
 *
 * Sorting is what makes the two sides agree. The writer records the digest in
 * the manifest and the reader recomputes it from what came out of the archive;
 * the order the members happen to be visited in must not matter, and on a
 * corrupt or re-packed archive it cannot be relied on. The byte order of the
 * tar itself is untouched — this only decides how the per-member digests are
 * combined.
 */
internal object SectionDigest {

    /** Total payload bytes of [members]. */
    fun byteSize(members: List<SectionMember>): Long = members.sumOf { it.byteSize }

    /** The section digest as defined above. */
    fun digest(members: List<SectionMember>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        members.sortedBy { it.name }.forEach { member ->
            digest.update(member.name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(member.sha256.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().toHexLower()
    }
}

/** Lower-case hex, without `String.format` and its locale moods. */
internal fun ByteArray.toHexLower(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        // Parenthesised on purpose: infix functions bind looser than indexing,
        // so `digits[value ushr 4]` without them would index with the unmasked
        // value and fail to compile — or worse.
        out[index * 2] = digits[(value ushr 4)]
        out[index * 2 + 1] = digits[(value and 0x0F)]
    }
    return String(out)
}

/**
 * A minimal POSIX ustar tar writer and reader — just wide enough for this
 * feature, and deliberately narrow.
 *
 * The version catalog carries no archive or compression library (see
 * `docs/BACKUP-RESTORE-PLAN.md` §2), and the members this side owns are plain
 * files with short names, so the whole format is a 512-byte header, the
 * payload, and two zero blocks. Two rules the implementation keeps:
 *
 *  - **No directory members.** Parent directories are created on extraction
 *    instead, which removes a class of ordering bugs (a directory member
 *    arriving after its children) and halves the header work.
 *  - **The size is known before the body runs.** Like every tar in existence,
 *    the header goes first — so a caller that passes a file's `length()` is
 *    promising that many bytes, and gets an exception when the body writes
 *    fewer or more. That is how a file that changes size mid-backup is caught
 *    rather than silently truncated.
 */
internal object TarFormat {
    const val BLOCK_SIZE = 512

    // Field offsets, per POSIX.1-1988 ustar.
    const val NAME = 0
    const val NAME_LENGTH = 100
    const val MODE = 100
    const val MODE_LENGTH = 8
    const val UID = 108
    const val UID_LENGTH = 8
    const val GID = 116
    const val GID_LENGTH = 8
    const val SIZE = 124
    const val SIZE_LENGTH = 12
    const val MTIME = 136
    const val MTIME_LENGTH = 12
    const val CHECKSUM = 148
    const val CHECKSUM_LENGTH = 8
    const val TYPE = 156
    const val MAGIC = 257
    const val PREFIX = 345
    const val PREFIX_LENGTH = 155

    /** Regular file, in the ustar spelling (ASCII `0`). */
    const val TYPE_REGULAR = '0'

    /** Mode bits for the members this feature writes. */
    const val MODE_FILE = 0b110_100_100 // 0644
    const val MODE_EXEC = 0b111_101_101 // 0755

    /**
     * Reads a numeric header field, octal or GNU base-256.
     *
     * Base-256 is what GNU tar writes for sizes the octal field cannot hold (a
     * member of 8 GiB and up). Nothing this side of the backup produces one,
     * but the rootfs writer in `:terminal` may, and a reader that choked on
     * the first huge member would make the archive unreadable rather than
     * merely large.
     */
    fun readNumeric(header: ByteArray, offset: Int, length: Int): Long {
        if (length > 0 && (header[offset].toInt() and 0xFF) == 0x80) {
            var value = 0L
            for (index in offset + 1 until offset + length) {
                value = (value shl 8) or (header[index].toLong() and 0xFF)
            }
            return value
        }
        var value = 0L
        var index = offset
        val end = offset + length
        while (index < end) {
            val byte = header[index].toInt() and 0xFF
            if (byte == 0 || byte == ' '.code) break
            if (byte < '0'.code || byte > '7'.code) {
                throw IOException("not a tar header: the size field is not octal")
            }
            value = (value shl 3) or (byte - '0'.code).toLong()
            index++
        }
        return value
    }

    /** The block padding a member of [size] bytes is followed by. */
    fun paddingOf(size: Long): Long =
        ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE).toLong()
}

/**
 * One member's payload, padded to the tar block size when closed.
 *
 * Closing is what writes the padding and what proves the body wrote as many
 * bytes as the header promised — the two facts that keep a truncated member
 * from being mistaken for a complete one.
 */
internal class TarMemberStream(
    private val out: OutputStream,
    private val size: Long,
) : OutputStream() {

    private var written = 0L
    private var closed = false

    override fun write(b: Int) {
        checkCapacity(1)
        out.write(b)
        written++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkCapacity(len)
        out.write(b, off, len)
        written += len
    }

    /**
     * Writes the padding, once.
     *
     * Idempotent because the member stream is closed twice on the way out:
     * the digest wrapper that wraps it is a FilterOutputStream, so *its* close
     * closes this one, and the writer's own `use` closes it again. A second
     * close that padded again wrote the padding twice, every member was
     * 512-aligned-plus-one-padding long, and every header after the first sat
     * at an offset no reader could find.
     */
    override fun close() {
        if (closed) return
        closed = true
        if (written != size) {
            throw IOException("member body wrote $written of $size bytes")
        }
        var padding = TarFormat.paddingOf(size)
        while (padding > 0) {
            out.write(0)
            padding--
        }
        out.flush()
    }

    private fun checkCapacity(len: Int) {
        if (len < 0 || written + len > size) {
            throw IOException("member body wrote more than the declared $size bytes")
        }
    }
}

/** Writes members into one tar stream. */
internal class TarMemberWriter(private val out: OutputStream) {

    /**
     * Writes the header and returns the payload stream.
     *
     * The returned stream must be closed before the next member is begun —
     * that is what writes the block padding. The tar itself is not owned
     * here: closing a member never closes the stream it belongs to.
     */
    fun beginMember(
        name: String,
        size: Long,
        modifiedAtMs: Long,
        executable: Boolean,
    ): OutputStream {
        require(size >= 0) { "a member cannot have a negative size" }
        writeHeader(name, size, modifiedAtMs, if (executable) TarFormat.MODE_EXEC else TarFormat.MODE_FILE)
        return TarMemberStream(out, size)
    }

    /** The end-of-archive marker: two zero blocks. */
    fun finish() {
        out.write(ByteArray(TarFormat.BLOCK_SIZE * 2))
        out.flush()
    }

    private fun writeHeader(name: String, size: Long, modifiedAtMs: Long, mode: Int) {
        val header = ByteArray(TarFormat.BLOCK_SIZE)
        putName(header, name)
        putNumeric(header, TarFormat.MODE, TarFormat.MODE_LENGTH, mode.toLong())
        putNumeric(header, TarFormat.UID, TarFormat.UID_LENGTH, 0L)
        putNumeric(header, TarFormat.GID, TarFormat.GID_LENGTH, 0L)
        putNumeric(header, TarFormat.SIZE, TarFormat.SIZE_LENGTH, size)
        putNumeric(header, TarFormat.MTIME, TarFormat.MTIME_LENGTH, (modifiedAtMs / 1000L).coerceAtLeast(0L))
        header[TarFormat.TYPE] = TarFormat.TYPE_REGULAR.code.toByte()
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(header, TarFormat.MAGIC)
        header[TarFormat.MAGIC + 5] = 0
        header[TarFormat.MAGIC + 6] = '0'.code.toByte()
        header[TarFormat.MAGIC + 7] = '0'.code.toByte()
        // Sum with the checksum field read as eight spaces, then write the
        // digits back — the same dance every tar writer does, because the
        // checksum covers its own field.
        for (index in 0 until TarFormat.CHECKSUM_LENGTH) {
            header[TarFormat.CHECKSUM + index] = ' '.code.toByte()
        }
        var sum = 0L
        for (byte in header) sum += (byte.toLong() and 0xFF)
        putDigits(header, TarFormat.CHECKSUM, 6, sum)
        header[TarFormat.CHECKSUM + 6] = 0
        header[TarFormat.CHECKSUM + 7] = ' '.code.toByte()
        out.write(header)
    }

    /** The name field, spilling into the prefix field when it is long. */
    private fun putName(header: ByteArray, name: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size <= TarFormat.NAME_LENGTH) {
            nameBytes.copyInto(header, TarFormat.NAME)
            return
        }
        // ustar splits a long name at a '/' boundary across the name and
        // prefix fields. Nothing this feature writes gets close; the branch
        // exists so a long theme or DataStore file name degrades to a clear
        // error instead of a silently corrupt header.
        val split = splitPoint(nameBytes)
            ?: throw IOException("member name too long for the tar format: $name")
        nameBytes.copyInto(header, TarFormat.NAME, split + 1, nameBytes.size)
        nameBytes.copyInto(header, TarFormat.PREFIX, 0, split)
    }

    /** The index of the '/' to split [nameBytes] at, or null when none fits. */
    private fun splitPoint(nameBytes: ByteArray): Int? {
        for (index in nameBytes.size - 1 downTo 0) {
            if (nameBytes[index] != '/'.code.toByte()) continue
            val prefixBytes = index
            val suffixBytes = nameBytes.size - index - 1
            if (prefixBytes in 1..TarFormat.PREFIX_LENGTH && suffixBytes in 1..TarFormat.NAME_LENGTH) {
                return index
            }
        }
        return null
    }

    /** Exactly [digits] octal characters, left-padded with zeroes. */
    private fun putDigits(target: ByteArray, offset: Int, digits: Int, value: Long) {
        var remaining = value
        for (index in digits - 1 downTo 0) {
            target[offset + index] = ('0'.code + (remaining and 0x7).toInt()).toByte()
            remaining = remaining shr 3
        }
        // Anything left over means the field was too small — an 11-digit size
        // field holds 8 GiB. Dropping the high digits would write a header
        // that describes a smaller member than the body that follows, which is
        // a corrupt archive, and a clear exception is a better ending.
        if (remaining != 0L) {
            throw IOException("$value does not fit a $digits-digit octal field")
        }
    }

    /** `length - 1` octal digits and a NUL — the shape tar's numeric fields use. */
    private fun putNumeric(target: ByteArray, offset: Int, length: Int, value: Long) {
        putDigits(target, offset, length - 1, value)
        target[offset + length - 1] = 0
    }
}

/**
 * Reads a tar stream member by member.
 *
 * Fussy about damage, forgiving about shape. A header whose size field is not
 * octal is a damaged stream (an exception, which the repository turns into
 * `BackupProgress.Failed`); directory, link, GNU long-name and pax members are
 * simply stepped over, because a stream may legitimately be written by a
 * different tar implementation — the rootfs members are written by
 * `:terminal`, not by this class.
 *
 * A tar stream is sequential and the rootfs sits at the end of the archive, so
 * a caller that only wants the app sections reads the whole thing and skips
 * what is not theirs. That is the cost of the manifest-last rule, and it buys
 * the crash-safety property in `docs/BACKUP-RESTORE-PLAN.md` §2.
 *
 * One shape is peculiar to this feature and worth knowing about: the zero
 * blocks the rootfs writer leaves mid-stream. See [nextFileEntry].
 *
 * The header checksum is not verified here, on purpose. A corrupt header
 * shows up as a member whose content no longer digests to what the manifest
 * says it should, and that check covers the payload too — which the header
 * checksum does not. The rootfs reader in `:terminal` does verify both,
 * because it is the one reading a tree this class cannot checksum member by
 * member.
 */
internal class ArchiveTarReader(private val input: InputStream) : Closeable {

    private val header = ByteArray(TarFormat.BLOCK_SIZE)
    private val scratch = ByteArray(64 * 1024)
    private var pendingLongName: String? = null
    private var payloadLeft = 0L
    private var padLeft = 0L

    /** The member the last [nextFileEntry] returned. */
    var entryName: String = ""
        private set

    /**
     * Advances to the next regular-file member and returns its name, or null
     * at the end of the archive. Anything left of the previous member's
     * payload is skipped first, so a caller that stopped reading early is not
     * punished for it.
     */
    fun nextFileEntry(): String? {
        skipRemaining()
        while (true) {
            if (!readFully(header)) return null
            // A zero block is the end-of-archive marker, and it is skipped
            // rather than obeyed. The rootfs writer in `:terminal` terminates
            // its own stream — two zero blocks, written before it returns —
            // and this archive's manifest is written *after* the rootfs, by the
            // manifest-last rule. So a marker inside the stream means "a
            // section ended", not "the archive ended", and the only honest end
            // is the end of the stream itself. Concatenated tar is legal, and
            // rootfs-then-manifest is what one looks like here.
            if (header.all { it == 0.toByte() }) continue
            val rawName = readString(TarFormat.NAME, TarFormat.NAME_LENGTH)
            val prefix = readString(TarFormat.PREFIX, TarFormat.PREFIX_LENGTH)
            val size = TarFormat.readNumeric(header, TarFormat.SIZE, TarFormat.SIZE_LENGTH)
            val type = header[TarFormat.TYPE].toInt().toChar()
            when (type) {
                // GNU long name: the payload is the next header's name.
                'L' -> {
                    pendingLongName = readPayloadAsString(size)
                    continue
                }
                // pax headers, directories, links and specials hold nothing this
                // feature archives, but their payloads still occupy the stream.
                'x', 'g', '5', '1', '2', '3', '4', '6', '7' -> {
                    skipPayload(size)
                    continue
                }
                else -> Unit
            }
            val name = pendingLongName ?: if (prefix.isEmpty()) rawName else "$prefix/$rawName"
            pendingLongName = null
            if (name.isEmpty()) {
                skipPayload(size)
                continue
            }
            payloadLeft = size
            padLeft = TarFormat.paddingOf(size)
            entryName = name
            return name
        }
    }

    /**
     * Streams the current member's payload into [out] and returns how many
     * bytes went through.
     *
     * [limit] caps the copy — the manifest is read through this with a
     * megabyte budget, and a stream that claims to be a manifest while
     * delivering gigabytes is refused instead of buffered.
     */
    fun copyTo(out: OutputStream, limit: Long = Long.MAX_VALUE): Long {
        var copied = 0L
        while (payloadLeft > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), payloadLeft).toInt())
            if (read < 0) throw IOException("the archive ends inside member '$entryName'")
            copied += read
            if (copied > limit) throw IOException("member '$entryName' is larger than $limit bytes")
            out.write(scratch, 0, read)
            payloadLeft -= read
        }
        return copied
    }

    override fun close() {
        input.close()
    }

    private fun skipRemaining() {
        var left = payloadLeft + padLeft
        payloadLeft = 0
        padLeft = 0
        while (left > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (read < 0) throw IOException("the archive ends inside member '$entryName'")
            left -= read
        }
    }

    private fun skipPayload(size: Long) {
        var left = size + TarFormat.paddingOf(size)
        while (left > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (read < 0) throw IOException("the archive ends inside a member")
            left -= read
        }
        payloadLeft = 0
        padLeft = 0
    }

    private fun readPayloadAsString(size: Long): String {
        if (size > 4096) throw IOException("a long-name member of $size bytes is not a tar long name")
        val buffer = ByteArrayOutputStream()
        payloadLeft = size
        padLeft = TarFormat.paddingOf(size)
        copyTo(buffer)
        skipRemaining()
        return buffer.toString(Charsets.UTF_8.name()).trimEnd('\u0000')
    }

    private fun readFully(buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    private fun readString(offset: Int, length: Int): String {
        var end = offset
        val stop = offset + length
        while (end < stop && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }
}

/**
 * Where a section's members go.
 *
 * The repository owns the tar stream and hands this to [AppDataBackup.write];
 * every member the app-data half of a backup produces goes through here, which
 * is what makes the per-member digests — and so the section checksums — exist
 * at all.
 */
interface SectionSink {

    /** Writes [source]'s bytes as a member at [name]. */
    fun writeFile(name: String, source: File): SectionMember

    /** Writes [bytes] as a member at [name]. */
    fun writeBytes(name: String, bytes: ByteArray): SectionMember
}

/** [SectionSink] over a tar stream, digesting each member as it is written. */
internal class TarSectionSink(private val writer: TarMemberWriter) : SectionSink {

    override fun writeFile(name: String, source: File): SectionMember {
        val size = source.length()
        return write(name, size, source.lastModified(), source.canExecute()) { member ->
            source.inputStream().use { input -> input.copyTo(member) }
        }
    }

    override fun writeBytes(name: String, bytes: ByteArray): SectionMember =
        write(name, bytes.size.toLong(), System.currentTimeMillis(), false) { member ->
            member.write(bytes)
        }

    private fun write(
        name: String,
        size: Long,
        modifiedAtMs: Long,
        executable: Boolean,
        body: (OutputStream) -> Unit,
    ): SectionMember {
        val digest = MessageDigest.getInstance("SHA-256")
        // One close, on purpose. DigestOutputStream is a FilterOutputStream,
        // so wrapping the member in a nested `use` closes it twice — once by
        // the digest's close and once by this one — and TarMemberStream pads
        // on close. The padding is now idempotent, but the shape was wrong:
        // the member is opened once, filled once, and closed once.
        val member = writer.beginMember(name, size, modifiedAtMs, executable)
        try {
            body(DigestOutputStream(member, digest))
            member.flush()
        } finally {
            member.close()
        }
        return SectionMember(name, size, digest.digest().toHexLower())
    }
}

/**
 * A consistent copy of the Room database, taken without closing it.
 *
 * The database is a singleton other components hold DAOs into, so there is no
 * window in which it can be closed for a plain file copy — and copying a live
 * WAL would produce a torn snapshot that restores as a mystery. Two
 * strategies, in order of preference:
 *
 *  1. **`VACUUM INTO`** (SQLite 3.27+, i.e. Android 11 and later). SQLite
 *     writes a compacted, transactionally consistent copy of the whole
 *     database to the target while the source stays open. This is the plan's
 *     chosen mechanism (`docs/BACKUP-RESTORE-PLAN.md` §4).
 *  2. **Checkpoint, then copy `db` + `-wal` + `-shm`.** Older Androids have no
 *     `VACUUM INTO`; a checkpoint folds the log into the main file so the copy
 *     is coherent except for writes that land between the two steps. The
 *     window is milliseconds, and it is the one thing the fallback costs: a
 *     best-effort snapshot on devices that cannot do better, not a
 *     transactional one.
 *
 * Either way the caller stages the snapshot beside the live database — same
 * filesystem, so the copy is cheap — and deletes it once the member is in the
 * archive.
 */
interface DatabaseSnapshotter {

    /**
     * Writes a snapshot of the database at [source] into [target], which must
     * not exist yet.
     */
    fun snapshot(source: File, target: File)
}

@Singleton
class RoomDatabaseSnapshot @Inject constructor(
    private val database: DroshDatabase,
) : DatabaseSnapshotter {

    override fun snapshot(source: File, target: File) {
        target.parentFile?.mkdirs()
        // VACUUM INTO refuses to overwrite, and a leftover from a previous run
        // would turn a good snapshot into a failed backup.
        target.delete()
        val sqlite: SupportSQLiteDatabase = try {
            database.openHelper.writableDatabase
        } catch (e: Exception) {
            throw IOException("the database could not be opened for a snapshot", e)
        }

        try {
            // The path is quoted for SQLite, and embedded quotes doubled, so a
            // path with an apostrophe in it is still a literal string.
            sqlite.execSQL("VACUUM INTO '${target.absolutePath.replace("'", "''")}'")
            Timber.i("backup: database snapshot taken with VACUUM INTO")
            return
        } catch (vacuum: Exception) {
            Timber.w(vacuum, "backup: VACUUM INTO unavailable, falling back to a checkpointed copy")
        }

        runCatching { sqlite.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        // The log and its index travel with the file: where the checkpoint
        // could not run they are what makes the copy readable at all, and
        // where it did they are empty.
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(source.absolutePath + suffix)
            if (sidecar.isFile) {
                Files.copy(
                    sidecar.toPath(),
                    File(target.absolutePath + suffix).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        Timber.i("backup: database snapshot taken by checkpointed copy")
    }
}

/**
 * Atomic file replacement with a rollback, for the restore path.
 *
 * Every file the restore swaps goes through here: the bytes are copied to a
 * sibling `<target>.staging-<n>` first and then moved over the target, so a
 * reader either sees the old file or the new one and never a half-written one.
 * The *previous* bytes are copied aside before the swap, which is what turns a
 * failure halfway through applying sections into "the previous files are still
 * there" instead of "some of them are".
 *
 * The rollback is best effort, in reverse order: the alternative — leaving a
 * restore half applied — is worse than a rollback that could not put the last
 * file back.
 */
internal class FileSwap(private val rollbackDir: File) {

    private class Entry(val target: File, val backup: File?)

    private val entries = mutableListOf<Entry>()

    /** Replaces [target] with [replacement], remembering what was there. */
    fun replace(target: File, replacement: File) {
        val parent = target.parentFile
            ?: throw IOException("${target.path} has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("could not create $parent")
        }
        val backup = remember(target)
        val staging = File(parent, "${target.name}.staging-${entries.size}-${System.nanoTime()}")
        Files.copy(replacement.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            moveAtomically(staging, target)
        } catch (e: Exception) {
            staging.delete()
            throw e
        }
        entries += Entry(target, backup)
    }

    /** Deletes [target] if it is there, remembering its bytes. */
    fun remove(target: File) {
        if (!target.exists()) return
        val backup = remember(target)
        if (!target.delete()) throw IOException("could not delete ${target.path}")
        entries += Entry(target, backup)
    }

    /** Puts every remembered file back, newest swap first. */
    fun rollback() {
        entries.asReversed().forEach { entry ->
            runCatching {
                if (entry.backup != null) forceReplace(entry.target, entry.backup) else entry.target.delete()
            }
        }
        entries.clear()
    }

    private fun remember(target: File): File? {
        if (!target.isFile) return null
        if (!rollbackDir.isDirectory) rollbackDir.mkdirs()
        val backup = File(rollbackDir, "${entries.size}-${target.name}")
        Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return backup
    }

    private fun forceReplace(target: File, source: File) {
        val staging = File(target.parentFile, "${target.name}.rollback-${System.nanoTime()}")
        Files.copy(source.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        moveAtomically(staging, target)
    }
}

/** Moves [from] onto [to] atomically where the filesystem allows it. */
internal fun moveAtomically(from: File, to: File) {
    try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (e: AtomicMoveNotSupportedException) {
        // The staging file is a sibling of the target, so this is the exotic
        // case — a filesystem that cannot rename over a live file. A
        // non-atomic move still beats leaving the restore half applied.
        Timber.w(e, "backup: atomic move unavailable for ${to.path}")
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Every file under this directory, symlinks not followed, in a stable order. */
internal fun File.filesUnder(): List<File> =
    walkTopDown().maxDepth(8).filter { it.isFile }.sortedBy { it.path }.toList()

/**
 * The app-private half of a backup.
 *
 * Six sections, in the order they are written: settings (the app mirror plus
 * any background images), the DataStore directory, a Room snapshot, themes,
 * snippets, and the input preferences. Almost all of it is plain file copying
 * — the exceptions are the database, which needs a consistent snapshot, and
 * the input preferences, which are read from a live flow.
 *
 * The rootfs is deliberately not here: that tree belongs to the `:terminal`
 * slice, and this class only knows the paths it must write settings and
 * snippets back into once that tree has been restored.
 */
@Singleton
class AppDataBackup @Inject constructor(
    private val paths: BackupPaths,
    private val database: DatabaseSnapshotter,
    private val inputPreferences: InputPreferencesRepository,
) {

    /** The sections this class writes, in archive order. */
    enum class AppSection(val kind: BackupSectionKind) {
        SETTINGS(BackupSectionKind.SETTINGS),
        DATASTORE(BackupSectionKind.DATASTORE),
        DATABASE(BackupSectionKind.ROOM_DATABASE),
        THEMES(BackupSectionKind.THEMES),
        SNIPPETS(BackupSectionKind.SNIPPETS),
        INPUT_PREFS(BackupSectionKind.INPUT_PREFS),
        ;

        /** The well-known manifest id, so restore can select by meaning. */
        val id: String
            get() = when (this) {
                SETTINGS -> BackupManifest.ID_SETTINGS
                DATASTORE -> BackupManifest.ID_DATASTORE
                DATABASE -> BackupManifest.ID_DATABASE
                THEMES -> BackupManifest.ID_THEMES
                SNIPPETS -> BackupManifest.ID_SNIPPETS
                INPUT_PREFS -> BackupManifest.ID_INPUT_PREFS
            }

        /**
         * The name a progress step carries: short, stable, and a thing the UI
         * may map to a phrase. The manifest [id] is deliberately not reused
         * — it is an archive key, and this is a sentence fragment.
         */
        val stepName: String
            get() = when (this) {
                SETTINGS -> "settings"
                DATASTORE -> "app data"
                DATABASE -> "database"
                THEMES -> "themes"
                SNIPPETS -> "snippets"
                INPUT_PREFS -> "input preferences"
            }

        companion object {
            /** The section a manifest id names, or null when it is not ours. */
            fun forId(id: String): AppSection? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * The sections that will actually be written, in order.
     *
     * Existence is checked here rather than discovered while writing, for two
     * reasons: the progress bar needs a step count before the first byte
     * moves, and a section with nothing in it must not reach the manifest — an
     * empty `app_themes` would be a row the restore UI offers and the archive
     * cannot honour.
     */
    suspend fun planned(options: BackupOptions): List<AppSection> = withContext(Dispatchers.IO) {
        buildList {
            if (options.includeSettings && hasSettingsSources()) add(AppSection.SETTINGS)
            if (options.includeDataStore && paths.dataStoreDir.isDirectory) add(AppSection.DATASTORE)
            if (options.includeDatabase) add(AppSection.DATABASE)
            if (options.includeThemesAndSnippets && themeFiles().isNotEmpty()) add(AppSection.THEMES)
            if (options.includeThemesAndSnippets && paths.snippetsFile.isFile) add(AppSection.SNIPPETS)
            if (options.includeSettings) add(AppSection.INPUT_PREFS)
        }
    }

    /**
     * Writes [section] into [sink] and returns its manifest entry, or null when
     * there is nothing to archive — only possible for a section the caller
     * asked for speculatively; [planned] does not do that.
     *
     * Each method runs on [Dispatchers.IO]: the database snapshot is a
     * synchronous SQLite call and the copies are plain streams.
     */
    suspend fun write(section: AppSection, sink: SectionSink): BackupSection? = when (section) {
        AppSection.SETTINGS -> writeSettings(sink)
        AppSection.DATASTORE -> writeDataStore(sink)
        AppSection.DATABASE -> writeDatabase(sink)
        AppSection.THEMES -> writeThemes(sink)
        AppSection.SNIPPETS -> writeSnippets(sink)
        AppSection.INPUT_PREFS -> writeInputPrefs(sink)
    }

    // ── writing ──────────────────────────────────────────────────────────────

    /**
     * The app-private settings mirror, and the background images with it.
     *
     * The mirror is `filesDir/.drosh/settings.toml` — the copy the app keeps so
     * a rootfs reset does not take the settings with it. It is archived rather
     * than the primary because the primary lives inside the rootfs and is
     * either already in the `rootfs` section or absent altogether.
     *
     * Background images ride along in this section because the domain
     * contract has no section of their own (`BackupSectionKind` has no
     * BACKGROUND) and they are appearance state exactly like the settings they
     * are set from. Their blurred caches are left behind: they are regenerated
     * on demand by `BackgroundImageStoreImpl`, and megabytes of derived pixels
     * have no business in a backup.
     */
    private suspend fun writeSettings(sink: SectionSink): BackupSection? = withContext(Dispatchers.IO) {
        val members = mutableListOf<SectionMember>()
        val settings = paths.appSettingsFile
        if (settings.isFile) {
            members += sink.writeFile("${ArchiveLayout.SETTINGS_DIR}/settings.toml", settings)
        }
        backgroundFiles().forEach { image ->
            members += sink.writeFile("${ArchiveLayout.SETTINGS_DIR}/backgrounds/${image.name}", image)
        }
        manifestSection(AppSection.SETTINGS, ArchiveLayout.SETTINGS_DIR, members)
    }

    /**
     * The DataStore directory as it stands.
     *
     * A full directory copy, because preferences are only meaningful whole:
     * one `drosh_shell_prefs.preferences_pb` holding every key the legacy
     * store ever wrote. The plan asks for a barrier so no write is in flight
     * while the copy runs (§4); v1 relies on the copy being short and the
     * store being idle, which is what it is during a backup the user started
     * deliberately.
     */
    private suspend fun writeDataStore(sink: SectionSink): BackupSection? = withContext(Dispatchers.IO) {
        val members = mutableListOf<SectionMember>()
        paths.dataStoreDir.filesUnder().forEach { file ->
            val relative = file.relativeTo(paths.dataStoreDir).path
            members += sink.writeFile("${ArchiveLayout.DATASTORE_DIR}/$relative", file)
        }
        manifestSection(AppSection.DATASTORE, ArchiveLayout.DATASTORE_DIR, members)
    }

    /**
     * A consistent snapshot of the Room database.
     *
     * The snapshot is staged beside the live database and streamed into the
     * archive, so a large database never exists twice in the cache. A failed
     * snapshot fails the backup: a database that cannot be copied
     * consistently must not be archived inconsistently.
     */
    private suspend fun writeDatabase(sink: SectionSink): BackupSection = withContext(Dispatchers.IO) {
        val snapshot = Files.createTempFile(paths.databaseDir.toPath(), "vacuum-snapshot", ".db").toFile()
        try {
            database.snapshot(paths.databaseFile, snapshot)
            val members = listOf(
                sink.writeFile("${ArchiveLayout.DATABASE_DIR}/${paths.databaseFile.name}", snapshot),
            )
            manifestSection(AppSection.DATABASE, ArchiveLayout.DATABASE_DIR, members)
                ?: throw IOException("the database snapshot was empty")
        } finally {
            // The checkpointed fallback leaves its sidecars next to the live
            // database under the snapshot's name; all three are ours to take.
            snapshot.delete()
            File(snapshot.absolutePath + "-wal").delete()
            File(snapshot.absolutePath + "-shm").delete()
        }
    }

    /**
     * Installed theme files, from both copies.
     *
     * The guest copy wins per file name, matching the precedence
     * `ThemeRepositoryImpl.refreshInstalled()` applies when it reads the two
     * directories — so the archive holds what the app would actually load. A
     * backup that also includes the rootfs therefore holds each theme file
     * twice (once here, once inside the rootfs member), which is a few
     * kilobytes and buys the case where the rootfs was never archived.
     */
    private suspend fun writeThemes(sink: SectionSink): BackupSection? = withContext(Dispatchers.IO) {
        val members = themeFiles().map { (name, file) ->
            sink.writeFile("${ArchiveLayout.THEMES_DIR}/$name", file)
        }
        manifestSection(AppSection.THEMES, ArchiveLayout.THEMES_DIR, members)
    }

    /**
     * The snippets file.
     *
     * Snippets live in the guest home and nowhere else —
     * `~/.drosh/snippets.json` — so this section is the copy of them that
     * survives a rootfs that was never archived. There is no app-private
     * mirror to fall back on.
     */
    private suspend fun writeSnippets(sink: SectionSink): BackupSection? = withContext(Dispatchers.IO) {
        val file = paths.snippetsFile
        if (!file.isFile) return@withContext null
        val members = listOf(sink.writeFile("${ArchiveLayout.SNIPPETS_DIR}/${file.name}", file))
        manifestSection(AppSection.SNIPPETS, ArchiveLayout.SNIPPETS_DIR, members)
    }

    /**
     * The input preferences, as a small JSON member.
     *
     * The values themselves live in `settings.toml` (the extra-keys bar flag is
     * part of `DroshSettings.input`), so this section exists for its *apply*
     * half: on restore it re-states the flag through the repository, which is
     * what makes the choice visible without waiting for the restart the
     * database swap requires.
     */
    private suspend fun writeInputPrefs(sink: SectionSink): BackupSection? {
        val visible = inputPreferences.extraKeysBarVisible.first()
        val text = inputPrefsJson.encodeToString(InputPrefsFile.serializer(), InputPrefsFile(visible))
        val bytes = text.toByteArray(Charsets.UTF_8)
        val members = listOf(sink.writeBytes(ArchiveLayout.INPUT_PREFS_FILE, bytes))
        return manifestSection(AppSection.INPUT_PREFS, ArchiveLayout.INPUT_PREFS_FILE, members)
    }

    // ── restoring ────────────────────────────────────────────────────────────

    /**
     * Copies [section]'s staged files into place, each one through [swap].
     *
     * [staging] is the restore's temp directory; the staged files sit under
     * their archive member paths, so a section's staged root is exactly its
     * `archive_path`. A member the archive never held is simply not there and
     * is skipped — a section is the union of what was written, and an
     * `app_settings` that carried only backgrounds has no settings file to put
     * back.
     */
    internal suspend fun apply(section: AppSection, swap: FileSwap, staging: File) {
        when (section) {
            AppSection.SETTINGS -> applySettings(swap, staging)
            AppSection.DATASTORE -> applyDataStore(swap, staging)
            AppSection.DATABASE -> applyDatabase(swap, staging)
            AppSection.THEMES -> applyThemes(swap, staging)
            AppSection.SNIPPETS -> applySnippets(swap, staging)
            AppSection.INPUT_PREFS -> applyInputPrefs(staging)
        }
    }

    /**
     * Writes the restored settings file into the *guest* copy as well.
     *
     * Called only when a rootfs restore just happened. The primary path is the
     * one a shell reads, and a freshly restored rootfs carries whatever
     * settings its own copy of `settings.toml` had; the app's mirror is the
     * newer truth here, so it is written over. When no rootfs restore happened
     * the guest file is left alone on purpose — its owner is the running
     * environment, and half-restoring it would leave the two copies
     * disagreeing about which one is being read.
     */
    internal suspend fun rewriteSettingsIntoRootfs(swap: FileSwap, staging: File) {
        val staged = File(File(staging, ArchiveLayout.SETTINGS_DIR), "settings.toml")
        if (!staged.isFile) return
        swap.replace(paths.guestSettingsFile, staged)
    }

    private suspend fun applySettings(swap: FileSwap, staging: File) = withContext(Dispatchers.IO) {
        val staged = File(staging, ArchiveLayout.SETTINGS_DIR)
        val settings = File(staged, "settings.toml")
        if (settings.isFile) swap.replace(paths.appSettingsFile, settings)
        val backgrounds = File(staged, "backgrounds")
        if (backgrounds.isDirectory) {
            backgrounds.filesUnder().forEach { image ->
                swap.replace(File(paths.appDroshDir, image.name), image)
            }
        }
    }

    private suspend fun applyDataStore(swap: FileSwap, staging: File) = withContext(Dispatchers.IO) {
        val staged = File(staging, ArchiveLayout.DATASTORE_DIR)
        staged.filesUnder().forEach { file ->
            val relative = file.relativeTo(staged).path
            swap.replace(File(paths.dataStoreDir, relative), file)
        }
    }

    /**
     * Replaces the database file, and nothing else.
     *
     * Only the main file moves: the snapshot was taken with `VACUUM INTO` (or
     * a checkpoint), so it is a complete database with no log to replay. A
     * stale `-wal`/`-shm` pair left over from the *previous* database would be
     * replayed on top of the restored file at the next open and undo the
     * restore, so both go first — through the same [FileSwap], so a rollback
     * puts them back.
     *
     * **v1 requires the app process to be restarted afterwards.** Room keeps
     * the database open for the life of the process, so the swap takes effect
     * on the next launch and not a moment before; the prompt that says so is
     * the UI's job (`docs/BACKUP-RESTORE-PLAN.md` §6.6). A restore that raced
     * the process's own open handle would otherwise leave SQLite reading
     * through a file descriptor that no longer names the file it opened.
     */
    private suspend fun applyDatabase(swap: FileSwap, staging: File) = withContext(Dispatchers.IO) {
        val staged = File(staging, "${ArchiveLayout.DATABASE_DIR}/${paths.databaseFile.name}")
        if (!staged.isFile) throw IOException("the archive's database snapshot is missing")
        swap.remove(paths.databaseWalFile)
        swap.remove(paths.databaseShmFile)
        swap.replace(paths.databaseFile, staged)
    }

    /**
     * Theme files into the app-private mirror, and into the guest copy when
     * there is a rootfs to hold them.
     *
     * Unlike the settings file, theme files are per-id files rather than one
     * document the whole app reads, and the guest copy is the one
     * `ThemeRepositoryImpl` consults first — so a user who restores themes
     * while keeping their rootfs gets the archived themes in both places, not
     * only in the mirror the app falls back to.
     */
    private suspend fun applyThemes(swap: FileSwap, staging: File) = withContext(Dispatchers.IO) {
        val staged = File(staging, ArchiveLayout.THEMES_DIR)
        val guest = paths.guestThemesDir
        staged.filesUnder().forEach { file ->
            val relative = file.relativeTo(staged).path
            swap.replace(File(paths.appThemesDir, relative), file)
            if (guest.isDirectory) swap.replace(File(guest, relative), file)
        }
    }

    /**
     * The snippets file, back into the guest home.
     *
     * That is its only home, so a restore onto an install whose rootfs has
     * never been set up still writes it: the file then waits, and the
     * bootstrap that lays the rootfs down does not clear `home/`.
     */
    private suspend fun applySnippets(swap: FileSwap, staging: File) = withContext(Dispatchers.IO) {
        val staged = File(File(staging, ArchiveLayout.SNIPPETS_DIR), paths.snippetsFile.name)
        if (!staged.isFile) return@withContext
        swap.replace(paths.snippetsFile, staged)
    }

    private suspend fun applyInputPrefs(staging: File) = withContext(Dispatchers.IO) {
        val staged = File(staging, ArchiveLayout.INPUT_PREFS_FILE)
        if (!staged.isFile) return@withContext
        val parsed = runCatching {
            inputPrefsJson.decodeFromString(InputPrefsFile.serializer(), staged.readText())
        }.getOrNull() ?: return@withContext
        // The settings file carries the same flag; this is the in-process
        // statement of it, so the choice is live without a restart.
        inputPreferences.setExtraKeysBarVisible(parsed.extraKeysBarVisible)
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    private fun hasSettingsSources(): Boolean =
        paths.appSettingsFile.isFile || backgroundFiles().isNotEmpty()

    /** `background.*` in the app-private mirror, blurred caches excluded. */
    private fun backgroundFiles(): List<File> =
        paths.appDroshDir.listFiles { file -> file.isFile && file.name.startsWith("background.") }
            ?.filterNot { it.name.contains(".blurred.") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Theme files from both copies, the guest copy winning per file name. */
    private fun themeFiles(): List<Pair<String, File>> {
        val byName = LinkedHashMap<String, File>()
        listOf(paths.appThemesDir, paths.guestThemesDir).forEach { dir ->
            dir.listFiles { file -> file.isFile && file.name.endsWith(".toml") }
                ?.forEach { file -> byName[file.name] = file }
        }
        return byName.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    private fun manifestSection(
        section: AppSection,
        archivePath: String,
        members: List<SectionMember>,
    ): BackupSection? {
        if (members.isEmpty()) return null
        return BackupSection(
            id = section.id,
            kind = section.kind,
            archivePath = archivePath,
            byteSize = SectionDigest.byteSize(members),
            sha256 = SectionDigest.digest(members),
        )
    }

    @Serializable
    private data class InputPrefsFile(
        @SerialName("extra_keys_bar_visible") val extraKeysBarVisible: Boolean,
    )

    private companion object {
        val inputPrefsJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
