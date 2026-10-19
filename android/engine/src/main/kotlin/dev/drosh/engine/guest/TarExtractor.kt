package dev.drosh.engine.guest

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * A tar reader for the guest rootfs — deliberately not a general one.
 *
 * The Ubuntu base tarball is inspected at the block level, not through a library
 * that normalises it away: 6826 headers for 3413 entries, because **every entry
 * is preceded by a pax extended header** (`x`, named `./PaxHeaders/…`) carrying
 * `mtime`/`atime`/`ctime` with sub-second precision. That is exactly the kind of
 * detail a first version gets wrong — it did, and the device said so:
 * "pax header before './PaxHeaders/bin' is not supported". The pax records are
 * parsed now, and `path`, `linkpath` and `size` override the header when present.
 *
 * Entry types in this archive: 2562 regular files, 655 directories, 194 symlinks,
 * 2 hardlinks. Those four are implemented; anything else throws with the entry
 * name instead of being skipped, because a silently missing `/bin/sh` is worse
 * than a failed install.
 *
 * Symlink *targets* are not confined to the rootfs — a rootfs legitimately has
 * `bin -> usr/bin` and absolute links like `/usr/bin/mawk`. Entry *paths* are
 * confined: the parent is canonicalised and must still be inside the target
 * directory, so an archive cannot write outside it.
 */
internal object TarExtractor {

    private const val BLOCK = 512
    private const val NAME = 0
    private const val SIZE = 124
    private const val CHECKSUM = 148
    private const val TYPE = 156
    private const val LINK = 157
    private const val MAGIC = 257
    private const val PREFIX = 345

    /**
     * Extracts every entry of [input] under [target].
     *
     * [onProgress] gets the entry count and its name every 100 entries.
     */
    fun extract(input: InputStream, target: File, onProgress: (Int, String) -> Unit) {
        if (!target.isDirectory && !target.mkdirs()) {
            throw IOException("cannot create ${target.absolutePath}")
        }
        val root = target.canonicalFile
        permissionFailures = 0

        var longName: String? = null
        var longLink: String? = null
        var nextPax: Map<String, String> = emptyMap()
        var globalPax: Map<String, String> = emptyMap()
        var count = 0
        val header = ByteArray(BLOCK)

        while (true) {
            val read = readBlock(input, header)
            if (read == 0) break
            if (read < BLOCK) throw EOFException("truncated tar header after $count entries")
            if (isZero(header)) break // end-of-archive marker

            verifyChecksum(header, count)

            var type = header[TYPE].toInt().toChar()
            if (type == '\u0000') type = '0'

            var name = text(header, NAME, 100)
            val prefix = text(header, PREFIX, 155)
            if (prefix.isNotEmpty() && text(header, MAGIC, 6).startsWith("ustar")) {
                name = "$prefix/$name"
            }
            longName?.let {
                name = it
                longName = null
            }
            var link = text(header, LINK, 100)
            longLink?.let {
                link = it
                longLink = null
            }
            var size = octal(header, SIZE, 12)

            when (type) {
                // GNU long name / long link: the body is the string, the entry
                // it belongs to follows.
                'L' -> {
                    longName = body(input, size).toString(US_ASCII).trimEnd('\u0000')
                    continue
                }
                'K' -> {
                    longLink = body(input, size).toString(US_ASCII).trimEnd('\u0000')
                    continue
                }
                // pax extended headers: `x` applies to the next entry, `g` to
                // everything after it. Ubuntu's tarball writes one `x` before
                // every entry, carrying mtime/atime/ctime.
                'x' -> {
                    nextPax = paxRecords(body(input, size))
                    continue
                }
                'g' -> {
                    globalPax = globalPax + paxRecords(body(input, size))
                    continue
                }
            }

            // A pax record overrides the fixed-width header field when it is
            // present — `path` for long names, `size` for large files.
            if (nextPax.isNotEmpty() || globalPax.isNotEmpty()) {
                val pax = if (globalPax.isEmpty()) nextPax else globalPax + nextPax
                name = pax["path"] ?: name
                link = pax["linkpath"] ?: link
                size = pax["size"]?.toLongOrNull() ?: size
                nextPax = emptyMap()
            }

            val entry = entryFile(root, name)
            when (type) {
                '0' -> {
                    entry.parentFile?.mkdirs()
                    entry.outputStream().use { copy(input, it, size) }
                    skipPadding(input, size)
                    setOwnerBits(entry, mode(header), executable = false)
                }
                '5' -> {
                    if (!entry.isDirectory && !entry.mkdirs()) {
                        throw IOException("cannot create directory ${entry.absolutePath}")
                    }
                    setOwnerBits(entry, mode(header), executable = true)
                }
                '2' -> {
                    entry.parentFile?.mkdirs()
                    if (entry.exists() || Files.isSymbolicLink(entry.toPath())) entry.delete()
                    try {
                        Files.createSymbolicLink(entry.toPath(), Paths.get(link))
                    } catch (e: Exception) {
                        throw IOException("symlink ${entry.absolutePath} -> $link failed: ${e.message}", e)
                    }
                }
                '1' -> {
                    entry.parentFile?.mkdirs()
                    val source = entryFile(root, link)
                    if (entry.exists()) entry.delete()
                    try {
                        Files.createLink(entry.toPath(), source.toPath())
                    } catch (e: Exception) {
                        // Same filesystem but the host may refuse link(2); a copy
                        // is equivalent for a rootfs and keeps the install going.
                        Files.copy(
                            source.toPath(),
                            entry.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                }
                else -> throw IOException("unsupported tar entry type '$type' for '$name'")
            }

            count++
            if (count % 100 == 0) onProgress(count, name)
        }
        onProgress(count, "")
    }

    /**
     * Parses pax extended-header records: `<len> <key>=<value>\n`, where `<len>`
     * is the byte length of the whole record, length field and newline included.
     * Values are UTF-8. Keys this reader does not need (`mtime`, `atime`,
     * `ctime`) are parsed and ignored rather than treated as errors.
     */
    private fun paxRecords(data: ByteArray): Map<String, String> {
        val records = HashMap<String, String>()
        var at = 0
        while (at < data.size) {
            var space = at
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) break
            val length = String(data, at, space - at, US_ASCII).toIntOrNull() ?: break
            val valueLength = at + length - space - 2 // drop " " and the newline
            if (length <= 0 || at + length > data.size || valueLength < 0) break
            val record = String(data, space + 1, valueLength, Charsets.UTF_8)
            val equals = record.indexOf('=')
            if (equals > 0) {
                records[record.substring(0, equals)] = record.substring(equals + 1)
            }
            at += length
        }
        return records
    }

    /** Resolves [name] under [root] and refuses anything that escapes it. */
    private fun entryFile(root: File, name: String): File {
        if (name.isEmpty()) throw IOException("tar entry with an empty name")
        if (name.startsWith("/")) throw IOException("absolute path in archive: $name")
        val file = File(root, name)
        val parent = (file.parentFile ?: root).canonicalFile
        if (parent != root && !parent.path.startsWith(root.path + File.separator)) {
            throw IOException("entry escapes the rootfs: $name")
        }
        return file
    }

    private fun readBlock(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun body(input: InputStream, size: Long): ByteArray {
        if (size > Int.MAX_VALUE) throw IOException("tar entry too large: $size bytes")
        val data = ByteArray(size.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n < 0) throw EOFException("truncated tar entry")
            read += n
        }
        skipPadding(input, size)
        return data
    }

    private fun copy(input: InputStream, output: OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated tar entry")
            output.write(buffer, 0, n)
            left -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        if (padding == 0) return
        var left = padding
        val buffer = ByteArray(padding)
        while (left > 0) {
            val n = input.read(buffer, 0, left)
            if (n < 0) throw EOFException("truncated tar padding")
            left -= n
        }
    }

    private fun isZero(block: ByteArray): Boolean = block.all { it == 0.toByte() }

    /** Header checksum: the field itself counts as spaces (POSIX). */
    private fun verifyChecksum(header: ByteArray, index: Int) {
        val expected = octal(header, CHECKSUM, 8)
        var sum = 0L
        for (i in header.indices) {
            val value = if (i in CHECKSUM until CHECKSUM + 8) ' '.code else header[i].toInt() and 0xff
            sum += value
        }
        if (sum != expected) {
            throw IOException("tar header $index checksum mismatch (got $sum, expected $expected)")
        }
    }

    private fun mode(header: ByteArray): Int = octal(header, 100, 8).toInt()

    private fun text(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: offset + length
        return String(header, offset, end - offset, US_ASCII)
    }

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var seen = false
        for (i in offset until offset + length) {
            val byte = header[i].toInt() and 0xff
            if (byte == 0 || byte == ' '.code) {
                if (seen) break else continue
            }
            if (byte < '0'.code || byte > '7'.code) {
                // GNU base-256 for very large values; not used by a rootfs.
                throw IOException("unsupported tar numeric field at offset $offset")
            }
            value = value * 8 + (byte - '0'.code)
            seen = true
        }
        return value
    }

    /**
     * Android ignores group/other bits for app-private files, so only the owner
     * bits are applied — enough for the guest to see a sane `ls -l`.
     */
    private fun setOwnerBits(file: File, mode: Int, executable: Boolean) {
        val permissions = mutableSetOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        if (executable || mode and 0b001_000_000 != 0) {
            permissions += PosixFilePermission.OWNER_EXECUTE
        }
        try {
            Files.setPosixFilePermissions(file.toPath(), permissions)
        } catch (e: Exception) {
            // Not fatal: chroot-ng loads ELF images itself, so the exec bit is
            // cosmetic here. Logged by the caller as a count, not per file.
            permissionFailures++
        }
    }

    /** Files whose mode could not be applied; read by the installer's log line. */
    var permissionFailures = 0
        private set
}
