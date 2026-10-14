/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.terminal.backup

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.EnumSet

/**
 * What one [RootfsArchiveWriter.writeSubtree] call produced.
 *
 * [bytesWritten] and [sha256] describe the *same* bytes: the uncompressed tar
 * member bytes for this subtree — headers, data, padding. They are not the
 * number of bytes that landed on disk, which is whatever gzip made of them and
 * is smaller by however much the whole-rootfs redundancy bought.
 * [BackupSection][dev.drosh.domain.backup.BackupSection] wants a size and a
 * digest that verify against each other, so both are taken over the same stream
 * and both are pre-compression: gzip output depends on the deflate
 * implementation's level, and a checksum over compressed bytes would break the
 * moment anyone changed it.
 *
 * [fileCount] counts regular files only. Directories are archived too — they
 * carry permissions — but "how many files" is what a wants eyeballing.
 */
data class ArchiveWriteResult(
    val bytesWritten: Long,
    val sha256: String,
    val fileCount: Int,
)

/**
 * Packs a slice of the Ubuntu rootfs into a tar member tree.
 *
 * ### Why hand-rolled tar
 *
 * The version catalog carries no compression dependency, and
 * `java.util.zip.ZipOutputStream` cannot express a tar: zip has a central
 * directory, per-entry deflate options, and is not seekable, while tar is a
 * flat stream of 512-byte headers that must be written in one forward pass.
 * So the header encoding lives here, in a place with tests around it.
 *
 * The layout mirrors proot-distro's `<name>/manifest.json` +
 * `<name>/rootfs/...` tree, so members land under [memberPrefix] and are
 * addressed as `drosh/rootfs/home/user/.zshrc`.
 *
 * Headers are POSIX ustar — `ustar\0` + version `00`, octal numeric fields, and
 * the 155-byte `prefix` field at offset 345 for names between 101 and 256
 * bytes. Longer names get a GNU `L` (long name) pseudo-entry ahead of the real
 * header, because deep rootfs paths
 * (`usr/local/lib/python3.12/dist-packages/...`) blow past 256 bytes routinely
 * and this is the normal case, not the exotic one. Python's `tarfile` — what
 * proot-distro is written in — reads GNU long names natively.
 *
 * A two-zero-block end-of-archive marker follows the last member so the tar is
 * well-formed standing alone. It is *not* counted in
 * [ArchiveWriteResult.bytesWritten] and not digested. Concatenated sections
 * (rootfs, then app data, then the manifest) are legal tar, and
 * [RootfsArchiveReader] steps over the markers between them.
 *
 * ### What is deliberately not archived
 *
 * - **Symlinks.** proot rewrites guest links at runtime (`--link2symlink`), and
 *   a link can point at a host bind mount the app supplies for the session
 *   (`/proc`, `/system`, `/data`). Archiving one would let restore write
 *   through it. [RootfsArchiveReader] skips them too, so the loss is symmetric
 *   and visible rather than a restore-only surprise.
 * - **Special files.** Sockets, FIFOs and device nodes cannot be regular files
 *   in a tar, and an unprivileged restore could not recreate them anyway.
 * - **Generated proot artifacts.** `ubuntu/local/{stat,vmstat}`,
 *   `ubuntu/tmp`, `ubuntu/bin/proot` and the loader `.so` files all live
 *   *beside* the rootfs, not inside it, so a walk rooted at `rootfsDir` never
 *   sees them. See [DEFAULT_EXCLUDED_TOP_LEVEL] for the names that live inside
 *   and still do not belong in an archive.
 *
 * ### The relax pass
 *
 * A walk that meets a `chmod 000` directory silently skips everything under it —
 * proot-distro lost subtrees to exactly this before it added a relax pass. This
 * class does the same and writes it down: [relaxPermissions] walks first,
 * records every mode it touches, and adds `OWNER_READ` (plus `OWNER_EXECUTE` on
 * directories, without which a directory can be neither listed nor entered) so
 * the archive pass can read everything. [restorePermissions] then puts the
 * original modes back, including the ones that were 000. The recorded
 * originals are what the tar headers carry, so the archive describes the tree
 * the user actually had rather than the temporarily-lax one.
 *
 * ### Walking a tree we do not fully trust
 *
 * proot-distro walks by descriptor: entries are addressed as
 * `(parentFd, name)` and opened `O_NOFOLLOW`, so a guest process that swaps a
 * directory between the check and the pack has nothing to swap — the
 * descriptor still points at the inode. The JVM has no `openat`, so this is
 * approximated:
 *
 * - every visited path is canonicalised — `Path.toRealPath` resolves each
 *   symlink component and throws if anything is missing — and must still
 *   resolve inside the rootfs; and
 * - file content is read with
 *   [LinkOption.NOFOLLOW_LINKS][java.nio.file.LinkOption.NOFOLLOW_LINKS] in the
 *   open options, which is what actually turns on `O_NOFOLLOW` (see
 *   [openForRead]). minSdk is 26, so `java.nio.file` — and with it
 *   [LinkOption] — is available.
 *
 * The residual window is between `toRealPath()` returning and
 * `newByteChannel` opening. It is documented and accepted: the guest is our own
 * single-user rootfs on our own storage, nothing else on the device has a
 * reason to race a backup, and the outcome of losing the race is a skipped
 * file, not a corrupted archive.
 */
class RootfsArchiveWriter(
    /** Top-level directory names never archived (proc/sys/dev/tmp/run/mnt style, plus app staging dirs). */
    private val excludedTopLevelNames: Set<String> = DEFAULT_EXCLUDED_TOP_LEVEL,
    /** Exact relative paths never archived, e.g. "home/.drosh/settings-cache.tmp". */
    private val excludedRelativePaths: Set<String> = emptySet(),
) {

    /**
     * Writes [relativeSubtree] (null = whole tree) of [rootfsDir] into the gzip
     * tar stream [out], under member prefix [memberPrefix] (e.g. "drosh/rootfs").
     *
     * [out] is the compressed stream and it stays open: this class writes
     * through it and never closes it, so the caller can append the app-data
     * sections and the manifest to the same archive. [relativeSubtree] is
     * interpreted relative to [rootfsDir] — `home` and `./home` behave alike,
     * `../etc` throws — and is canonicalised, so a symlinked subtree has to
     * resolve back inside the rootfs to be accepted.
     *
     * Throws [IOException] when [rootfsDir] or the requested subtree is not a
     * readable directory, when the path fails to canonicalise, or when the
     * underlying stream fails mid-member. Unreadable individual files are
     * skipped with a warning: one file the user cannot read either is not worth
     * losing a multi-gigabyte backup over. The only exception is a read that
     * fails *after* the member header is on the wire, which aborts — a member
     * whose declared length does not match its body is a corrupt archive, and a
     * failed backup is recoverable where a half-true one is not.
     */
    fun writeSubtree(
        rootfsDir: File,
        memberPrefix: String,
        relativeSubtree: String?,
        out: OutputStream,
    ): ArchiveWriteResult {
        val root = requireDirectory(rootfsDir)
        val start = resolveSubtree(root, relativeSubtree)
        val prefix = normalizePrefix(memberPrefix)
        // Canonical, because filesDir is reachable both as /data/user/0/<pkg>
        // and /data/data/<pkg> on some Android versions and those are symlinks
        // to each other. Comparing a resolved child against an unresolved root
        // would reject the entire tree.
        val canonicalRoot = canonical(start) ?: throw IOException("cannot resolve $start")

        val counting = DigestingOutputStream(out)
        // Recorded even for the walk root: relax has to make it listable before
        // walkFileTree can enter it, so its original mode has to come back too.
        val originalModes = relaxPermissions(root, start)
        var fileCount = 0
        try {
            Files.walkFileTree(
                start,
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {

                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val relative = relativeTo(root, dir)
                        // The walk root itself maps to the staging directory on
                        // restore; archiving it as a member would ask the reader
                        // to chmod the staging root, which is not ours. Also the
                        // one place there is no exclusion to apply, since it is
                        // what the caller asked for.
                        if (relative.isEmpty()) return FileVisitResult.CONTINUE
                        if (isExcluded(relative)) return FileVisitResult.SKIP_SUBTREE
                        // A directory swapped for a symlink (or moved out from
                        // under us) must not be archived.
                        if (!isInside(canonicalRoot, dir)) {
                            Timber.w("rootfs: $dir resolves outside $canonicalRoot — skipped")
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        writeMemberHeader(
                            counting,
                            memberPath(prefix, relative),
                            TypeFlag.DIRECTORY,
                            originalModes[dir] ?: permissionsOrNeutral(dir),
                            size = 0L,
                            mtime = attrs.lastModifiedTime(),
                        )
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        // walkFileTree does not follow links, so a symlink
                        // arrives here as a file whose attributes say it is a
                        // link. Skipping before any mode work matters:
                        // setPosixFilePermissions follows symlinks, and a guest
                        // link into /system must not be relaxed through.
                        if (attrs.isSymbolicLink) {
                            Timber.w("rootfs: symlink ${relativeTo(root, file)} not archived")
                            return FileVisitResult.CONTINUE
                        }
                        if (!attrs.isRegularFile) {
                            Timber.w("rootfs: special file ${relativeTo(root, file)} not archived")
                            return FileVisitResult.CONTINUE
                        }
                        val relative = relativeTo(root, file)
                        if (isExcluded(relative)) return FileVisitResult.CONTINUE
                        if (!isInside(canonicalRoot, file)) {
                            Timber.w("rootfs: $file resolves outside $canonicalRoot — skipped")
                            return FileVisitResult.CONTINUE
                        }
                        val permissions = originalModes[file] ?: permissionsOrNeutral(file)
                        if (writeFileMember(counting, file, memberPath(prefix, relative), permissions)) {
                            fileCount++
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        // Nothing was recorded for the file in the relax pass
                        // either, so there is nothing to restore and nothing to
                        // archive. Report and keep going.
                        Timber.w(exc, "rootfs: cannot read $file — skipped")
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } finally {
            restorePermissions(originalModes)
            counting.flush()
        }

        // End-of-archive marker, written after the digest is frozen so the
        // checksum covers the members and nothing else.
        counting.write(ByteArray(END_OF_ARCHIVE_BYTES))
        counting.flush()

        return ArchiveWriteResult(
            bytesWritten = counting.bytesWritten,
            sha256 = counting.sha256(),
            fileCount = fileCount,
        )
    }

    // ─── the pass that cannot read a 000-mode directory ─────────────────────

    /**
     * Records current permissions and relaxes them so the archive pass can read
     * everything.
     *
     * Returns the originals keyed by path — including the walk root, which is
     * relaxed so `walkFileTree` can list it. Only entries whose mode actually
     * changed are stored, so the return value is normally tiny (the handful of
     * `chmod 000` files a user or a package script left behind) rather than one
     * map entry per file in the rootfs.
     *
     * The relax happens in [preVisitDirectory], before the walker opens the
     * directory stream: `newDirectoryStream` needs `r` and `x` on the directory
     * and is what would otherwise fail first.
     */
    private fun relaxPermissions(root: Path, start: Path): Map<Path, Set<PosixFilePermission>> {
        val touched = LinkedHashMap<Path, Set<PosixFilePermission>>()
        Files.walkFileTree(
            start,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // Excluded subtrees are never read, so there is no reason to
                    // touch their modes and risk leaving one relaxed.
                    val relative = relativeTo(root, dir)
                    if (relative.isNotEmpty() && isExcluded(relative)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    relax(dir, isDirectory = true, touched)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attrs.isSymbolicLink && !isExcluded(relativeTo(root, file))) {
                        relax(file, isDirectory = false, touched)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    // Same policy as the archive pass: a file we cannot read is
                    // not a reason to lose the backup. Nothing was recorded for
                    // it, so there is nothing to restore.
                    Timber.w(exc, "rootfs: cannot relax $file — skipped")
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return touched
    }

    /** Adds owner read (plus owner execute on a directory) to [path]. */
    private fun relax(
        path: Path,
        isDirectory: Boolean,
        touched: MutableMap<Path, Set<PosixFilePermission>>,
    ) {
        val original = permissions(path) ?: return
        val readable = original.contains(PosixFilePermission.OWNER_READ)
        val enterable = !isDirectory || original.contains(PosixFilePermission.OWNER_EXECUTE)
        if (readable && enterable) return
        // A new EnumSet with the original added, not EnumSet.copyOf: copyOf
        // throws IllegalArgumentException on an empty collection, and a mode-000
        // file's permission set is exactly that (remembered by hand, not
        // guessed).
        val relaxed = EnumSet.noneOf(PosixFilePermission::class.java).apply { addAll(original) }
        relaxed.add(PosixFilePermission.OWNER_READ)
        if (isDirectory) relaxed.add(PosixFilePermission.OWNER_EXECUTE)
        try {
            Files.setPosixFilePermissions(path, relaxed)
            touched[path] = original
        } catch (e: IOException) {
            Timber.w(e, "rootfs: could not relax $path")
        }
    }

    /**
     * Puts back every mode [relaxPermissions] changed, in reverse insertion
     * order so a parent that loses `x` before its children are restored cannot
     * strand them. Best effort by construction: if a restore fails the tree is
     * left more permissive, never less, and the failure is logged.
     */
    private fun restorePermissions(touched: Map<Path, Set<PosixFilePermission>>) {
        touched.entries.toList().asReversed().forEach { (path, original) ->
            try {
                Files.setPosixFilePermissions(path, original)
            } catch (e: IOException) {
                Timber.w(e, "rootfs: could not restore the mode of $path")
            }
        }
    }

    // ─── tar member emission ────────────────────────────────────────────────

    /**
     * Streams one regular file into the tar. Returns false (writing nothing)
     * when the file could not be opened without following a symlink, so the
     * abort case — a member header on the wire and no content behind it — never
     * happens for a file that simply cannot be read.
     */
    private fun writeFileMember(
        out: DigestingOutputStream,
        file: Path,
        memberName: String,
        permissions: Set<PosixFilePermission>,
    ): Boolean {
        val size = try {
            Files.size(file)
        } catch (e: IOException) {
            Timber.w(e, "rootfs: cannot stat $file — skipped")
            return false
        }
        val channel = openForRead(file) ?: return false
        try {
            writeMemberHeader(
                out,
                memberName,
                TypeFlag.REGULAR,
                permissions,
                size,
                Files.getLastModifiedTime(file),
            )
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var remaining = size
            while (remaining > 0) {
                val chunk = minOf(buffer.size.toLong(), remaining).toInt()
                // read() fills at most once per call, so loop until the chunk is
                // complete — exactly what InputStream.readNBytes does, and it
                // does not treat EOF as zero bytes.
                var filled = 0
                while (filled < chunk) {
                    val read = channel.read(ByteBuffer.wrap(buffer, filled, chunk - filled))
                    if (read < 0) break
                    filled += read
                }
                // A file that shrank between size() and read(): pad with zeroes
                // so the tar stays parseable rather than trailing garbage.
                if (filled < chunk) {
                    buffer.fill(0, filled, chunk)
                    filled = chunk
                }
                out.write(buffer, 0, filled)
                remaining -= chunk
            }
        } finally {
            channel.close()
        }
        writePadding(out, size)
        return true
    }

    private fun writeMemberHeader(
        out: DigestingOutputStream,
        memberName: String,
        type: TypeFlag,
        permissions: Set<PosixFilePermission>,
        size: Long,
        mtime: FileTime,
    ) {
        val bytes = memberName.toByteArray(Charsets.UTF_8)
        val header = ByteArray(BLOCK_BYTES)
        if (bytes.size <= NAME_FIELD_BYTES) {
            bytes.copyInto(header, NAME_OFFSET)
        } else {
            when (val split = splitName(memberName)) {
                null -> {
                    writeGnuLongName(out, memberName)
                    // GNU tar repeats a truncated name here; readers must prefer
                    // the 'L' entry, and [RootfsArchiveReader] does.
                    bytes.copyInto(header, NAME_OFFSET, 0, NAME_FIELD_BYTES)
                }
                else -> {
                    split.prefix.toByteArray(Charsets.UTF_8).copyInto(header, PREFIX_OFFSET)
                    split.name.toByteArray(Charsets.UTF_8).copyInto(header, NAME_OFFSET)
                }
            }
        }

        writeOctal(header, MODE_OFFSET, 8, modeBits(permissions).toLong())
        writeOctal(header, UID_OFFSET, 8, 0)
        writeOctal(header, GID_OFFSET, 8, 0)
        writeOctal(header, SIZE_OFFSET, 12, size)
        writeOctal(header, MTIME_OFFSET, 12, seconds(mtime))
        header[TYPEFLAG_OFFSET] = type.code.code.toByte()
        // "ustar\0" + "00": POSIX ustar readers honour the prefix field on
        // exactly this pair, which is the whole point of splitting long names.
        writeString(header, MAGIC_OFFSET, USTAR_MAGIC)
        writeString(header, VERSION_OFFSET, USTAR_VERSION)
        // Restore runs unprivileged and chowns nothing, so the numeric ids stay
        // zero. A blank owner field makes some readers report a "damaged"
        // archive, though, and these two cost nothing.
        writeString(header, UNAME_OFFSET, "root")
        writeString(header, GNAME_OFFSET, "root")

        writeChecksum(header)
        out.write(header)
    }

    /**
     * GNU long-name pseudo-entry: an extra header whose typeflag is `L` and
     * whose data is the full member path, NUL-terminated and block-padded.
     */
    private fun writeGnuLongName(out: DigestingOutputStream, memberName: String) {
        val nameBytes = memberName.toByteArray(Charsets.UTF_8)
        val header = ByteArray(BLOCK_BYTES)
        writeString(header, NAME_OFFSET, GNU_LONG_NAME)
        writeOctal(header, MODE_OFFSET, 8, 0)
        writeOctal(header, UID_OFFSET, 8, 0)
        writeOctal(header, GID_OFFSET, 8, 0)
        writeOctal(header, SIZE_OFFSET, 12, (nameBytes.size + 1).toLong())
        writeOctal(header, MTIME_OFFSET, 12, 0)
        header[TYPEFLAG_OFFSET] = TypeFlag.GNU_LONG_NAME.code.code.toByte()
        writeString(header, MAGIC_OFFSET, USTAR_MAGIC)
        writeString(header, VERSION_OFFSET, USTAR_VERSION)
        writeChecksum(header)
        out.write(header)

        val data = ByteArray(nameBytes.size + 1)
        nameBytes.copyInto(data, 0)
        out.write(data)
        writePadding(out, data.size.toLong())
    }

    /**
     * The header checksum: the sum of all 512 bytes with the checksum field
     * itself taken as ASCII spaces, stored as six octal digits + NUL + space.
     * Computed both ways (unsigned and signed chars) and accepted on read,
     * because historical writers disagree.
     */
    private fun writeChecksum(header: ByteArray) {
        header.fill(CHECKSUM_SPACE, CHECKSUM_OFFSET, CHECKSUM_OFFSET + CHECKSUM_BYTES)
        var sum = 0L
        for (b in header) sum += (b.toInt() and 0xFF)
        val digits = sum.toString(8).padStart(6, '0')
        for (i in 0 until CHECKSUM_DIGITS) {
            header[CHECKSUM_OFFSET + i] = digits[i].code.toByte()
        }
        header[CHECKSUM_OFFSET + CHECKSUM_DIGITS] = 0
        header[CHECKSUM_OFFSET + CHECKSUM_DIGITS + 1] = 0x20
    }

    private fun writeString(field: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.UTF_8).copyInto(field, offset)
    }

    private fun writePadding(out: DigestingOutputStream, dataSize: Long) {
        val padding = ((BLOCK_BYTES - (dataSize % BLOCK_BYTES)) % BLOCK_BYTES).toInt()
        if (padding > 0) out.write(ByteArray(padding))
    }

    /** Writes a [length]-byte field as `length - 1` octal digits plus a NUL. */
    private fun writeOctal(field: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = value.toString(8)
        require(digits.length <= length - 1) { "tar field at $offset overflows for $value" }
        digits.padStart(length - 1, '0').forEachIndexed { i, c ->
            field[offset + i] = c.code.toByte()
        }
        field[offset + length - 1] = 0
    }

    // ─── exclusion filters ──────────────────────────────────────────────────

    /** True for the top-level names and exact relative paths never archived. */
    private fun isExcluded(relativeFromRoot: String): Boolean {
        if (relativeFromRoot.isEmpty()) return false
        if (relativeFromRoot.substringBefore('/') in excludedTopLevelNames) return true
        // An exact match on a directory prunes the subtree below it: the
        // children would otherwise be archived with a parent that never made
        // it, which is a tree restore cannot rebuild.
        return relativeFromRoot in excludedRelativePaths
    }

    /**
     * The member path for [relativeFromRoot]: the caller's prefix plus the guest
     * path with `/` separators — never a leading slash, never a `./`. Tar
     * members are portable paths, and a leading slash is one of the two ways a
     * restore ends up writing outside its staging directory.
     */
    private fun memberPath(prefix: String, relativeFromRoot: String): String {
        val relative = relativeFromRoot.replace(File.separatorChar, '/')
        return if (prefix.isEmpty()) relative else "$prefix/$relative"
    }

    private fun normalizePrefix(memberPrefix: String): String =
        memberPrefix.replace(File.separatorChar, '/').trim('/')

    private fun requireDirectory(dir: File): Path {
        // NOFOLLOW_LINKS: a rootfs that is itself a symlink is a
        // misconfiguration — rootfsDir is derived from filesDir — and archiving
        // through one would silently follow the caller out of the storage being
        // backed up.
        val path = dir.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("not a directory: $dir")
        }
        return path
    }

    /**
     * Resolves [relativeSubtree] against the rootfs, or returns the rootfs
     * itself for null or blank. A subtree that points outside the rootfs throws
     * rather than being clamped: silently archiving the parent instead of what
     * was asked for is how a "settings only" backup becomes a full rootfs
     * backup.
     */
    private fun resolveSubtree(root: Path, relativeSubtree: String?): Path {
        if (relativeSubtree.isNullOrBlank()) return root
        val requested = relativeSubtree.replace(File.separatorChar, '/').trim('/')
        if (requested.isEmpty() || requested.split('/').contains("..")) {
            throw IOException("subtree escapes the rootfs: $relativeSubtree")
        }
        if (!Files.isDirectory(root.resolve(requested), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("not a directory under the rootfs: $relativeSubtree")
        }
        val canonicalRoot = canonical(root) ?: throw IOException("cannot resolve $root")
        val canonicalStart = canonical(root.resolve(requested))
            ?: throw IOException("cannot resolve $relativeSubtree")
        if (canonicalStart != canonicalRoot &&
            !canonicalStart.startsWith("$canonicalRoot${File.separator}")
        ) {
            throw IOException("subtree resolves outside the rootfs: $relativeSubtree")
        }
        return root.resolve(requested)
    }

    /** [path] relative to [root], with `/` separators, or "" when they are the same. */
    private fun relativeTo(root: Path, path: Path): String =
        root.relativize(path).toString().replace(File.separatorChar, '/')

    private fun isInside(canonicalRoot: String, candidate: Path): Boolean {
        val resolved = canonical(candidate) ?: return false
        return resolved == canonicalRoot || resolved.startsWith("$canonicalRoot${File.separator}")
    }

    /** The fully resolved path, or null when it cannot be resolved. */
    private fun canonical(path: Path): String? =
        try {
            path.toRealPath().toString()
        } catch (e: IOException) {
            Timber.w(e, "rootfs: cannot canonicalise $path")
            null
        }

    private fun permissions(path: Path): Set<PosixFilePermission>? =
        try {
            Files.getPosixFilePermissions(path)
        } catch (e: IOException) {
            Timber.w(e, "rootfs: no POSIX permissions for $path")
            null
        }

    /**
     * Fallback for a filesystem that reports no POSIX permissions (a card
     * mounted without them, say): archived as owner-only, which is the safe
     * default for anything we could not introspect — the guest is single-user.
     */
    private fun permissionsOrNeutral(path: Path): Set<PosixFilePermission> {
        permissions(path)?.let { return it }
        val file = path.toFile()
        val readable = setOfNotNull(
            if (file.canRead()) PosixFilePermission.OWNER_READ else null,
            PosixFilePermission.OWNER_WRITE,
        )
        return if (file.isDirectory) readable + PosixFilePermission.OWNER_EXECUTE else readable
    }

    /**
     * The low nine bits of the mode word.
     *
     * An empty permission set archives as mode 000, which is literal: the file
     * really is unreadable and unwritable, and a restore that quietly turns it
     * into 0600 has rewritten the user's tree behind their back. The three high
     * bits — setuid, setgid, sticky — have no [PosixFilePermission] and are
     * dropped, because restore clears them anyway and a guest setuid bit never
     * meant anything outside the guest.
     */
    private fun modeBits(permissions: Set<PosixFilePermission>): Int {
        var bits = 0
        if (permissions.contains(PosixFilePermission.OWNER_READ)) bits = bits or 0b100_000_000
        if (permissions.contains(PosixFilePermission.OWNER_WRITE)) bits = bits or 0b010_000_000
        if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) bits = bits or 0b001_000_000
        if (permissions.contains(PosixFilePermission.GROUP_READ)) bits = bits or 0b000_100_000
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)) bits = bits or 0b000_010_000
        if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) bits = bits or 0b000_001_000
        if (permissions.contains(PosixFilePermission.OTHERS_READ)) bits = bits or 0b000_000_100
        if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) bits = bits or 0b000_000_010
        if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) bits = bits or 0b000_000_001
        return bits
    }

    private fun seconds(time: FileTime): Long = maxOf(0L, time.toMillis() / 1000L)

    /**
     * Opens [path] for reading with `O_NOFOLLOW` on.
     *
     * [LinkOption.NOFOLLOW_LINKS] is a [java.nio.file.OpenOption] in its own
     * right — not a [StandardOpenOption], which has no such constant, on either
     * the JVM or Android — and the provider turns it into `O_NOFOLLOW`. So a
     * final component that became a symlink after the canonical-path check fails
     * with `ELOOP` instead of being followed, and the caller skips the file.
     * (Measured on this JDK; AOSP's `UnixChannelFactory` handles the option the
     * same way, and Android's `java.nio.file` needs API 26, which is our
     * minSdk.)
     *
     * Returns null when the file cannot be opened, which the caller treats as
     * "skip this file". `ELOOP` is never retried: retrying would follow the link
     * this open exists to refuse. A provider that says it does not know the
     * option at all ([UnsupportedOperationException]) is retried without it,
     * because a backup that cannot run beats a backup that follows a symlink
     * through an already-canonicalised path — and no shipping filesystem here
     * hits that branch.
     */
    private fun openForRead(path: Path): SeekableByteChannel? = try {
        Files.newByteChannel(path, openReadOptions)
    } catch (_: UnsupportedOperationException) {
        try {
            Files.newByteChannel(path, EnumSet.of(StandardOpenOption.READ))
        } catch (e: IOException) {
            Timber.w(e, "rootfs: cannot open $path — skipped")
            null
        }
    } catch (e: IOException) {
        Timber.w(e, "rootfs: cannot open $path without following symlinks — skipped")
        null
    }

    companion object {
        /**
         * The top-level guest directories a backup never wants.
         *
         * `proc`, `sys`, `dev`, `run`, `tmp`, `mnt` and `media` are kernel
         * pseudofilesystems proot binds at runtime or scratch space that is
         * meaningless to archive; `lost+found` is ext4 bookkeeping that can be
         * hundreds of megabytes of zeroes on some images. The staging names are
         * belt-and-braces for a caller that points the writer at `ubuntu/`
         * rather than `ubuntu/rootfs`: the real staging directories
         * (`rootfs.restore-staging/`, `rootfs.pre-restore/`) live beside the
         * rootfs, not inside it, so a rooted walk would never reach them.
         */
        val DEFAULT_EXCLUDED_TOP_LEVEL: Set<String> = setOf(
            "proc",
            "sys",
            "dev",
            "run",
            "tmp",
            "mnt",
            "media",
            "lost+found",
            "rootfs.restore-staging",
            "rootfs.pre-restore",
            "drosh-backup-staging",
            "drosh-restore-staging",
        )

        private const val BLOCK_BYTES = 512
        private const val NAME_OFFSET = 0
        private const val NAME_FIELD_BYTES = 100
        private const val MODE_OFFSET = 100
        private const val UID_OFFSET = 108
        private const val GID_OFFSET = 116
        private const val SIZE_OFFSET = 124
        private const val MTIME_OFFSET = 136
        private const val CHECKSUM_OFFSET = 148
        private const val CHECKSUM_BYTES = 8
        private const val CHECKSUM_DIGITS = 6
        private const val TYPEFLAG_OFFSET = 156
        private const val MAGIC_OFFSET = 257
        private const val VERSION_OFFSET = 263
        private const val UNAME_OFFSET = 265
        private const val GNAME_OFFSET = 297
        private const val PREFIX_OFFSET = 345
        private const val PREFIX_FIELD_BYTES = 155
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val END_OF_ARCHIVE_BYTES = 1024
        private const val USTAR_MAGIC = "ustar\u0000"
        private const val USTAR_VERSION = "00"
        private const val GNU_LONG_NAME = "././@LongLink"
        private const val CHECKSUM_SPACE = 0x20.toByte()

        /**
         * `READ` plus [LinkOption.NOFOLLOW_LINKS], as the mixed-type
         * `Set<OpenOption>` the open APIs want — the two options are different
         * enums, so an [EnumSet] cannot hold both.
         */
        private val openReadOptions: Set<java.nio.file.OpenOption> =
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }

    /** The typeflags this writer emits. The reader knows a wider set. */
    private enum class TypeFlag(val code: Char) {
        REGULAR('0'),
        DIRECTORY('5'),
        GNU_LONG_NAME('L'),
    }

    /**
     * Tees every byte into a SHA-256 [MessageDigest] and counts them, so the
     * checksum is over exactly the bytes that went into the gzip stream and the
     * count is of exactly those bytes. Uncompressed by design: see
     * [ArchiveWriteResult].
     */
    private class DigestingOutputStream(private val delegate: OutputStream) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")

        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            digest.update(b.toByte())
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            digest.update(b, off, len)
            bytesWritten += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }

        fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Splits [name] across the ustar `prefix` and `name` fields, or returns null
     * when no split works and a GNU `L` entry is needed.
     *
     * The split has to fall on a `/` boundary, leave at most 100 bytes of path
     * after it and at most 155 before it, and the last split that satisfies
     * both wins. Component counts rather than character counts, because the
     * fields are counted in bytes and a path with non-ASCII components would
     * otherwise overflow silently. A single component longer than 100 bytes can
     * never be split, so it is rejected here and handled by the long-name
     * entry.
     */
    private fun splitName(name: String): SplitName? {
        val components = name.split('/')
        if (components.size < 2) return null
        val byteLens = components.map { it.toByteArray(Charsets.UTF_8).size }
        // Bytes of components[0..cut - 1], separators excluded, kept running:
        // it starts on the first component because the loop starts at cut = 1.
        var prefixSum = byteLens[0]
        var bestCut = -1
        for (cut in 1 until components.size) {
            val prefixBytes = prefixSum + cut - 1
            val tailBytes = byteLens.subList(cut, components.size).sum() +
                components.size - cut - 1
            if (prefixBytes in 1..PREFIX_FIELD_BYTES && tailBytes in 1..NAME_FIELD_BYTES) {
                bestCut = cut
            }
            prefixSum += byteLens[cut]
        }
        if (bestCut < 0) return null
        return SplitName(
            prefix = components.subList(0, bestCut).joinToString("/"),
            name = components.subList(bestCut, components.size).joinToString("/"),
        )
    }

    private data class SplitName(val prefix: String, val name: String)
}
