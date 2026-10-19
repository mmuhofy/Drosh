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
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.zip.GZIPInputStream

/**
 * Extracts the `<prefix>/...` members of a backup archive into a staging
 * directory.
 *
 * ### What it is for
 *
 * Restore never writes into the live rootfs: it extracts into
 * `rootfs.restore-staging/`, verifies, and only then swaps the directory (see
 * `docs/BACKUP-RESTORE-PLAN.md` §6 and §3.4). This class is the extract half of
 * that, and the part of restore that has to assume the archive is hostile — it
 * is a file the user picked, and its bytes are not ours.
 *
 * So the rules here are absolute, not best-effort:
 *
 * 1. **Zip-slip guard.** Every member path is resolved against the staging
 *    directory and the *canonical* result must still be inside it. `..`
 *    components and absolute paths are refused by name before a single byte is
 *    created, and the canonical check catches a path that only sneaks out
 *    through an existing symlink. A refused member is logged and skipped: one
 *    poisoned member is not a reason to abandon a restore the user waited an
 *    hour for, and the alternative — aborting — also loses the members that
 *    *were* fine. The caller is expected to treat a skipped member as a failed
 *    restore and roll back.
 * 2. **Symlinks are never followed and never created.** A member that *is* a
 *    symlink is skipped with a warning, because restoring one would hand the
 *    next write a path out of the staging directory. Hardlink members (`1`) are
 *    skipped for the same reason — a hardlink's target is another member, and
 *    trusting that chain buys restore nothing it can use.
 * 3. **Setuid/setgid/sticky never survive.** The recorded mode is masked to the
 *    low nine bits before it is applied. A restored rootfs must not silently
 *    hand back setuid binaries proot cannot honour anyway.
 *
 * The other half of the safety story is that this writes only regular files and
 * directories, with parents created on demand, into a directory the caller
 * created for exactly this purpose.
 *
 * ### Format tolerance
 *
 * The reader accepts what [RootfsArchiveWriter] emits — ustar headers with a
 * `prefix` split, GNU `L` long names, block padding, and a two-zero-block
 * end-of-archive marker after every section (concatenated sections are legal
 * tar, so a rootfs section followed by an app-data section reads correctly). It
 * also accepts PAX `x` headers carrying a `path` record, which is how Python's
 * `tarfile` — proot-distro's own implementation — writes long names since 3.8,
 * and it verifies each header checksum. A checksum failure is a hard error: a
 * corrupt tar is not a partially-recoverable state.
 *
 * Member names are decoded as UTF-8, matching what [RootfsArchiveWriter] emits,
 * so a name round-trips byte for byte. An archive carrying raw non-UTF-8 bytes
 * restores with mangled names rather than failing — an acceptable trade against
 * failing to restore at all, and nothing the guards above depend on.
 *
 * gzip is detected from the magic bytes rather than assumed, so a mislabelled
 * plain `.tar` still restores. That is not a security decision: the guards are
 * applied to every member either way.
 */
class RootfsArchiveReader {

    /**
     * Extracts members starting with [memberPrefix] from the gzip tar [input]
     * into [stagingDir]. SECURITY: every member path is canonicalised and must
     * resolve inside stagingDir (zip-slip guard); symlink entries are skipped
     * with a warning (never followed); setuid/setgid bits are cleared on
     * extraction; regular files only.
     *
     * [stagingDir] is created when missing and is never emptied first: a restore
     * resuming after a crash needs to keep what an earlier pass already wrote,
     * and it is the caller's job to hand us a fresh or resumable directory.
     *
     * Throws [IOException] for an unreadable stream, a bad header checksum, a
     * truncated entry, or a staging directory that cannot be created — all
     * "this archive is not usable" cases. Refused members (zip-slip, symlink,
     * hardlink, special file, non-member) are skipped and logged instead.
     */
    fun extractToStaging(input: InputStream, memberPrefix: String, stagingDir: File): ExtractResult {
        val staging = stagingDir.toPath()
        Files.createDirectories(staging)
        // Canonical, and before anything is written: /data/user/0 is a symlink
        // to /data/data on some Android versions, so comparing canonical child
        // paths against an unresolved staging root would refuse everything.
        val canonicalStaging = canonical(staging)
        val prefix = memberPrefix.replace(File.separatorChar, '/').trim('/')

        val buffered: InputStream = if (input.markSupported()) {
            input
        } else {
            BufferedInputStream(input, STREAM_BUFFER_BYTES)
        }
        // A caller that already unwrapped the gzip layer must not be wrapped a
        // second time: GZIPInputStream does not support mark, so the naive
        // version of this computes "the first bytes are gzip magic" on data that
        // has *already been decompressed* and decompresses plaintext a second
        // time.
        val stream: InputStream = when {
            input is GZIPInputStream -> input
            isGzip(buffered) -> GZIPInputStream(buffered, STREAM_BUFFER_BYTES)
            else -> buffered
        }
        // Deferred directory modes: applying a recorded 0500 while walking
        // through the directory in question makes every file below it unwritable,
        // so modes are stored and applied after every file is on disk — deepest
        // first, so a parent that loses x cannot strand its own children.
        val deferredDirectories = LinkedHashMap<Path, Int>()
        var filesExtracted = 0
        var bytesWritten = 0L

        // A plain try/finally rather than `use {}`: the body below uses
        // non-local `continue` to skip a member, which is clearer here than a
        // chain of guard predicates, and it must not depend on the lambda being
        // inline.
        try {
            val header = ByteArray(BLOCK_BYTES)
            var pendingLongName: String? = null
            var pendingPaxPath: String? = null
            while (readHeader(stream, header)) {
                val type = header[TYPEFLAG_OFFSET].toInt().toChar()
                val size = parseOctal(header, SIZE_OFFSET, 12)
                val mode = parseOctal(header, MODE_OFFSET, 8).toInt()
                if (!verifyChecksum(header)) {
                    throw IOException("tar header checksum mismatch for member '${headerName(header)}'")
                }

                when (type) {
                    // GNU long name: the data block *is* the name of the member
                    // that follows.
                    'L' -> {
                        pendingLongName = readData(stream, size).decodeName()
                        continue
                    }

                    // Long *link* name. Unused: link members are never restored.
                    'K' -> {
                        skipEntry(stream, size)
                        continue
                    }

                    // PAX extended headers, carrying a `path` record among
                    // others. Only the records this reader acts on are parsed.
                    'x', 'g' -> {
                        val path = paxPath(readData(stream, size))
                        if (path != null) pendingPaxPath = path
                        continue
                    }
                }

                val rawName = pendingPaxPath ?: pendingLongName ?: headerName(header)
                val prefixField = if (pendingLongName == null && pendingPaxPath == null) {
                    parseString(header, PREFIX_OFFSET, PREFIX_FIELD_BYTES)
                } else {
                    // A GNU long name or a PAX path replaces the name *and*
                    // makes the prefix field meaningless.
                    ""
                }
                val name = normalizeMemberName(rawName, prefixField)
                pendingLongName = null
                pendingPaxPath = null

                // Only this section's members; the manifest, the app-data
                // sections and any other rootfs view belong to someone else.
                if (name != prefix && !name.startsWith("$prefix/")) {
                    skipEntry(stream, size)
                    continue
                }
                val relative = name.removePrefix(prefix).removePrefix("/")
                // The member is the section root, which maps to the staging
                // directory itself — the caller's to own, not ours to chmod.
                if (relative.isEmpty() || relative == "." || relative == "..") {
                    skipEntry(stream, size)
                    continue
                }

                when (type) {
                    '5' -> {
                        val dir = resolveStrictly(staging, canonicalStaging, relative)
                        if (dir == null) {
                            Timber.w("backup: refusing directory member $name — resolves outside staging")
                            skipEntry(stream, size)
                            continue
                        }
                        Files.createDirectories(dir)
                        deferredDirectories[dir] = mode
                        skipEntry(stream, size)
                    }

                    '2', '1' -> {
                        Timber.w("backup: skipping $type member $name — links are never restored")
                        skipEntry(stream, size)
                    }

                    '3', '4', '6' -> {
                        Timber.w("backup: skipping $type member $name — devices and fifos are never restored")
                        skipEntry(stream, size)
                    }

                    '0', '\u0000', '7' -> {
                        val file = resolveStrictly(staging, canonicalStaging, relative)
                        if (file == null) {
                            Timber.w("backup: refusing member $name — resolves outside staging")
                            skipEntry(stream, size)
                            continue
                        }
                        Files.createDirectories(file.parent)
                        // Delete first: whatever is already at that path — a
                        // previous pass, a resumed restore — must end up as a
                        // regular file, and never as a write *through* a link.
                        Files.deleteIfExists(file)
                        bytesWritten += writeData(stream, file, size)
                        skipPadding(stream, size)
                        applyMode(file, mode)
                        filesExtracted++
                    }

                    else -> {
                        Timber.w("backup: skipping unknown member type '$type' for $name")
                        skipEntry(stream, size)
                    }
                }
            }
        } finally {
            // Closes the gzip layer and the caller's stream with it.
            stream.close()
        }

        applyDeferredDirectoryModes(deferredDirectories)

        return ExtractResult(filesExtracted = filesExtracted, bytesWritten = bytesWritten)
    }

    /** How many regular files were written, and how many bytes they hold. */
    data class ExtractResult(val filesExtracted: Int, val bytesWritten: Long)

    // ─── the zip-slip guard ─────────────────────────────────────────────────

    /**
     * Resolves [relative] under [staging], or returns null when the result would
     * land outside it.
     *
     * Two layers, because they catch different things. The textual layer refuses
     * an absolute path and any `..` component before a single byte is created.
     * The canonical layer then resolves the destination through whatever already
     * exists on disk — `toRealPath` on the file if it is there, or on its parent
     * plus the file name if it is not — so a member that reaches out through a
     * symlinked intermediate directory is caught even though its own components
     * look entirely innocent.
     */
    private fun resolveStrictly(staging: Path, canonicalStaging: String, relative: String): Path? {
        if (relative.isEmpty() || relative == "." || relative == "..") return null
        if (relative.startsWith("/")) return null
        if (relative.indexOf('\u0000') >= 0) return null
        if (relative.split('/').any { it == ".." }) return null
        val resolved = staging.resolve(relative)
        // `File(parent, child)` with a rooted child discards the parent instead
        // of joining it. resolve() does the same, so check it back explicitly
        // rather than trusting the overload's behaviour on hostile input.
        if (!resolved.startsWith(staging)) return null
        val canonical = canonicalOf(resolved)
        return if (canonical == canonicalStaging ||
            canonical.startsWith("$canonicalStaging${File.separator}")
        ) {
            resolved
        } else {
            null
        }
    }

    private fun canonicalOf(path: Path): String = try {
        // Resolves every symlink component, including ones an earlier member
        // of this very entry created.
        path.toRealPath().toString()
    } catch (_: IOException) {
        // Does not exist yet: resolve the deepest part that does exist and
        // append the remaining name, which is the same path with a
        // guaranteed-real prefix.
        try {
            path.parent.toRealPath().resolve(path.fileName).toString()
        } catch (_: IOException) {
            path.toAbsolutePath().normalize().toString()
        }
    }

    private fun canonical(path: Path): String = try {
        path.toRealPath().toString()
    } catch (e: IOException) {
        throw IOException("staging directory cannot be resolved: $path", e)
    }

    /**
     * Restores the recorded directory modes, deepest first.
     *
     * Deepest first matters, and descending path length is a valid topological
     * order for it: a child's path is always its parent's plus a separator and a
     * component, so it is strictly longer, so no parent is restored before the
     * children it would lock out.
     */
    private fun applyDeferredDirectoryModes(directories: Map<Path, Int>) {
        directories.keys
            .sortedByDescending { it.toString().length }
            .forEach { dir ->
                try {
                    applyMode(dir, directories.getValue(dir))
                } catch (e: IOException) {
                    Timber.w(e, "backup: could not restore the mode of $dir")
                }
            }
    }

    // ─── tar parsing ────────────────────────────────────────────────────────

    /**
     * True when the first two bytes are the gzip magic. Non-destructive: the
     * stream is buffered and mark-supported by the time this is called.
     */
    private fun isGzip(input: InputStream): Boolean {
        if (!input.markSupported()) return false
        input.mark(2)
        val first = input.read()
        val second = input.read()
        input.reset()
        return first == 0x1f && second == 0x8b
    }

    /**
     * Reads one header block, or returns false at the end of the archive.
     *
     * A zero block is the end-of-archive marker, and [RootfsArchiveWriter]
     * emits one after every section — so the block after it is read to tell
     * "the archive ends here" from "another section follows". Concatenated tar
     * is legal, and it is exactly what a rootfs-then-app-data archive looks
     * like on disk.
     */
    private fun readHeader(tar: InputStream, header: ByteArray): Boolean {
        if (!readExactly(tar, header, header.size)) return false
        if (!header.all { it == 0.toByte() }) return true
        if (!readExactly(tar, header, header.size)) return false
        return !header.all { it == 0.toByte() }
    }

    /** Fills exactly [count] bytes of [buffer], false only at EOF mid-block. */
    private fun readExactly(tar: InputStream, buffer: ByteArray, count: Int): Boolean {
        var offset = 0
        while (offset < count) {
            val read = tar.read(buffer, offset, count - offset)
            if (read < 0) return offset == 0
            offset += read
        }
        return true
    }

    /** Reads [size] bytes of entry data and the block padding after it. */
    private fun readData(tar: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        if (!readExactly(tar, data, data.size)) throw IOException("unexpected end of tar entry data")
        skipPadding(tar, size)
        return data
    }

    /** Writes exactly [size] bytes of entry data into a new [file]. */
    private fun writeData(tar: InputStream, file: Path, size: Long): Long {
        val scratch = ByteArray(minOf(COPY_BUFFER_BYTES.toLong(), size).toInt())
        var written = 0L
        Channels.newOutputStream(
            Files.newByteChannel(
                file,
                EnumSet.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ),
            ),
        ).use { out ->
            var remaining = size
            while (remaining > 0) {
                val chunk = minOf(scratch.size.toLong(), remaining).toInt()
                if (!readExactly(tar, scratch, chunk)) {
                    throw IOException("unexpected end of tar entry data")
                }
                out.write(scratch, 0, chunk)
                written += chunk
                remaining -= chunk
            }
        }
        return written
    }

    /** Consumes [size] bytes of entry data, leaving the padding in place. */
    private fun skipData(tar: InputStream, size: Long) {
        val scratch = ByteArray(minOf(COPY_BUFFER_BYTES.toLong(), size).toInt())
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(scratch.size.toLong(), remaining).toInt()
            if (!readExactly(tar, scratch, chunk)) throw IOException("unexpected end of tar entry data")
            remaining -= chunk
        }
    }

    private fun skipPadding(tar: InputStream, dataSize: Long) {
        val padding = ((BLOCK_BYTES - (dataSize % BLOCK_BYTES)) % BLOCK_BYTES).toInt()
        if (padding <= 0) return
        val block = ByteArray(padding)
        if (!readExactly(tar, block, padding)) throw IOException("unexpected end of tar padding")
    }

    /** Skips a member's data *and* its padding, landing on the next header. */
    private fun skipEntry(tar: InputStream, size: Long) {
        skipData(tar, size)
        skipPadding(tar, size)
    }

    /**
     * The header checksum, checked unsigned *and* signed: historical writers
     * genuinely disagree about whether the header bytes are summed as `char` or
     * as `unsigned char`, and the field is stored as six octal digits + NUL +
     * space either way.
     */
    private fun verifyChecksum(header: ByteArray): Boolean {
        val stored = parseOctal(header, CHECKSUM_OFFSET, CHECKSUM_BYTES)
        var unsigned = 0L
        var signed = 0L
        for (i in header.indices) {
            val b = if (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_BYTES) {
                // The checksum field counts as spaces while it is being summed.
                CHECKSUM_SPACE
            } else {
                header[i]
            }
            unsigned += b.toInt() and 0xFF
            signed += b.toLong()
        }
        return stored == unsigned || stored == signed
    }

    private fun headerName(header: ByteArray): String =
        parseString(header, NAME_OFFSET, NAME_FIELD_BYTES)

    /** The bytes of a GNU long-name entry as a String, NULs trimmed. */
    private fun ByteArray.decodeName(): String {
        val end = indexOfFirst { it == 0.toByte() }.let { if (it < 0) size else it }
        return String(this, 0, end, StandardCharsets.UTF_8)
    }

    /**
     * Pulls the `path` record out of a PAX extended header.
     *
     * PAX records are `<length> <key>=<value>\n`, where the length counts the
     * whole record including its own leading digits and the trailing newline —
     * the part everyone gets wrong on the first attempt. Everything this reader
     * does not act on is walked past: a PAX header can carry `mtime`, `size`
     * and more, and none of them change what restore does here.
     */
    private fun paxPath(data: ByteArray): String? {
        var index = 0
        while (index < data.size) {
            var space = index
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) return null
            val length = try {
                String(data, index, space - index, StandardCharsets.US_ASCII).trim().toInt()
            } catch (_: NumberFormatException) {
                return null
            }
            val recordEnd = index + length
            if (length <= 0 || recordEnd > data.size) return null
            val body = String(data, space + 1, recordEnd - space - 1, StandardCharsets.UTF_8)
            if (body.startsWith("$PAX_PATH=")) {
                return body.removePrefix("$PAX_PATH=").trimEnd('\n')
            }
            index = recordEnd
        }
        return null
    }

    /**
     * Normalises a member name into a path relative to the archive root.
     *
     * Both slashes are accepted (some archives use `\`), runs of `/` are
     * collapsed, and a leading `./` or `/` is stripped. Anything hostile that
     * normalisation cannot fix is caught by [resolveStrictly] rather than here:
     * normalisation is about reading the archive, and [resolveStrictly] is
     * about safety.
     */
    private fun normalizeMemberName(raw: String, prefixField: String): String {
        var name = raw.replace('\\', '/')
        var prefix = prefixField.replace('\\', '/')
        while (prefix.startsWith("./") || prefix.startsWith("/")) prefix = prefix.substring(1)
        if (prefix.isNotEmpty()) name = "$prefix/$name"
        while (name.startsWith("./")) name = name.substring(2)
        while (name.startsWith("/")) name = name.substring(1)
        while (name.contains("//")) name = name.replace("//", "/")
        return name.trimEnd('/')
    }

    // ─── permissions ────────────────────────────────────────────────────────

    /**
     * Applies the recorded mode, masking off setuid, setgid and sticky.
     *
     * A restored rootfs must not gain a setuid binary that was never meant to
     * survive the round trip: proot translates the guest uid and cannot honour
     * a host setuid bit anyway, so the only thing dropping it can do is remove a
     * way for a copied-in binary to do something the user did not ask for.
     * Falls back to the plain [java.io.File] API on a filesystem that reports no
     * POSIX permissions.
     */
    private fun applyMode(path: Path, mode: Int) {
        val masked = mode and MODE_MASK
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(formatMode(masked)))
        } catch (_: IOException) {
            val file = path.toFile()
            file.setReadable(masked and OWNER_READ != 0, true)
            file.setWritable(masked and OWNER_WRITE != 0, true)
            file.setExecutable(masked and OWNER_EXECUTE != 0, true)
        }
    }

    /** `rwxr-xr-x` for [mode] — the string [PosixFilePermissions.fromString] wants. */
    private fun formatMode(mode: Int): String = buildString(9) {
        append(if (mode and OWNER_READ != 0) 'r' else '-')
        append(if (mode and OWNER_WRITE != 0) 'w' else '-')
        append(if (mode and OWNER_EXECUTE != 0) 'x' else '-')
        append(if (mode and GROUP_READ != 0) 'r' else '-')
        append(if (mode and GROUP_WRITE != 0) 'w' else '-')
        append(if (mode and GROUP_EXECUTE != 0) 'x' else '-')
        append(if (mode and OTHERS_READ != 0) 'r' else '-')
        append(if (mode and OTHERS_WRITE != 0) 'w' else '-')
        append(if (mode and OTHERS_EXECUTE != 0) 'x' else '-')
    }

    private fun parseString(field: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, field.size)
        return String(field, offset, end - offset, StandardCharsets.UTF_8).trimEnd('\u0000')
    }

    /**
     * An octal field: leading spaces, zeros and NULs skipped, the run read until
     * the next space or NUL. Also understands GNU base-256, which is how a tar
     * says "this entry is bigger than eight gigabytes".
     */
    private fun parseOctal(field: ByteArray, offset: Int, length: Int): Long {
        val end = minOf(offset + length, field.size)
        if (offset < end && (field[offset].toInt() and 0xFF) == BASE_256_FLAG) {
            var value = 0L
            for (i in offset + 1 until end) {
                value = (value shl 8) or (field[i].toLong() and 0xFF)
            }
            return value
        }
        var i = offset
        while (i < end && (field[i] == 0x20.toByte() || field[i] == 0x30.toByte() || field[i] == 0.toByte())) i++
        if (i >= end) return 0
        var j = i
        while (j < end && field[j] != 0x20.toByte() && field[j] != 0.toByte()) j++
        val text = String(field, i, j - i, StandardCharsets.US_ASCII)
        return text.toLongOrNull(8) ?: 0
    }

    private companion object {
        private const val BLOCK_BYTES = 512
        private const val NAME_OFFSET = 0
        private const val NAME_FIELD_BYTES = 100
        private const val MODE_OFFSET = 100
        private const val SIZE_OFFSET = 124
        private const val CHECKSUM_OFFSET = 148
        private const val CHECKSUM_BYTES = 8
        private const val TYPEFLAG_OFFSET = 156
        private const val PREFIX_OFFSET = 345
        private const val PREFIX_FIELD_BYTES = 155
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val MODE_MASK = 0b111_111_111
        private const val OWNER_READ = 0b100_000_000
        private const val OWNER_WRITE = 0b010_000_000
        private const val OWNER_EXECUTE = 0b001_000_000
        private const val GROUP_READ = 0b000_100_000
        private const val GROUP_WRITE = 0b000_010_000
        private const val GROUP_EXECUTE = 0b000_001_000
        private const val OTHERS_READ = 0b000_000_100
        private const val OTHERS_WRITE = 0b000_000_010
        private const val OTHERS_EXECUTE = 0b000_000_001
        private const val BASE_256_FLAG = 0x80
        private const val PAX_PATH = "path"
        private const val CHECKSUM_SPACE = 0x20.toByte()
    }
}
