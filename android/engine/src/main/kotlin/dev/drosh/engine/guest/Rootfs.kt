package dev.drosh.engine.guest

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * The Ubuntu base rootfs the guest runs in.
 *
 * Source: the official `ubuntu-base` tarball for arm64, pinned by version and
 * SHA-256 (the hash is Ubuntu's own, from the `SHA256SUMS` file next to the
 * tarball — not one we computed from a download we trust).
 *
 * Install is staged so a failure is visible where it happened: download →
 * verify → extract into `rootfs.tmp` → rename → stamp. The stamp records the
 * version and hash, so a pin bump reinstalls instead of running a stale tree.
 */
internal object Rootfs {

    private const val TAG = "DroshLabGuest"

    const val VERSION = "24.04.5"

    private const val URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
            "ubuntu-base-24.04.5-base-arm64.tar.gz"

    /** From Ubuntu's `SHA256SUMS` for 24.04.5 (verified 2026-10-17). */
    private const val SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

    private const val ARCHIVE_BYTES = 29_936_675L

    /** The extracted tree is ~105 MB; the archive is another 30 MB. */
    private const val REQUIRED_FREE_BYTES = 250L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, "rootfs")

    private fun stamp(context: Context) = File(context.filesDir, "rootfs.stamp")

    fun isInstalled(context: Context): Boolean {
        val dir = dir(context)
        val stamp = stamp(context)
        if (!dir.isDirectory || !stamp.isFile) return false
        return stamp.readText().trim() == "$VERSION $SHA256"
    }

    /**
     * Installs the rootfs unless it is already there. Blocking: call from a
     * background thread. [onProgress] is called with a human-readable stage.
     */
    fun ensureInstalled(context: Context, onProgress: (String) -> Unit) {
        if (isInstalled(context)) {
            onProgress("rootfs $VERSION already installed")
            return
        }

        val free = context.filesDir.usableSpace
        if (free < REQUIRED_FREE_BYTES) {
            throw IOException(
                "not enough free space: ${megabytes(free)} of ${megabytes(REQUIRED_FREE_BYTES)} needed",
            )
        }

        onProgress("downloading $VERSION (${megabytes(ARCHIVE_BYTES)})")
        val archive = download(context, onProgress)

        val rootfs = dir(context)
        val staging = File(context.filesDir, "rootfs.tmp")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("cannot create ${staging.absolutePath}")

        try {
            onProgress("extracting")
            GZIPInputStream(archive.inputStream().buffered(1 shl 16)).use { input ->
                TarExtractor.extract(input, staging) { count, _ ->
                    onProgress("extracting $count entries")
                }
            }
            if (TarExtractor.permissionFailures > 0) {
                Log.w(TAG, "${TarExtractor.permissionFailures} entries kept default permissions")
            }

            if (rootfs.exists() && !rootfs.deleteRecursively()) {
                throw IOException("cannot replace ${rootfs.absolutePath}")
            }
            if (!staging.renameTo(rootfs)) {
                throw IOException("cannot move ${staging.absolutePath} to ${rootfs.absolutePath}")
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }

        archive.delete()
        stamp(context).writeText("$VERSION $SHA256\n")
        Log.i(TAG, "rootfs $VERSION installed at ${rootfs.absolutePath}")
        onProgress("installed")
    }

    /**
     * chroot-ng's argv for this rootfs, or null when the binary is not in the
     * APK. `--fake-id=0:0` presents root; `--work-dir=/root` is a *guest* path.
     */
    fun command(context: Context): Pair<String, List<String>>? {
        val binary = ChrootProbe.binary(context) ?: return null
        return binary.absolutePath to listOf(
            "--fake-id=0:0",
            "--work-dir=/root",
            dir(context).absolutePath,
            "/bin/bash",
            "-l",
        )
    }

    private fun download(context: Context, onProgress: (String) -> Unit): File {
        val archive = File(context.cacheDir, "ubuntu-base-$VERSION.tar.gz")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "drosh-terminal-lab")
        }

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code from $URL")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: ARCHIVE_BYTES
            connection.inputStream.use { input ->
                archive.outputStream().buffered(1 shl 16).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (read - reported >= 4L * 1024 * 1024) {
                            reported = read
                            onProgress("downloading ${megabytes(read)} / ${megabytes(total)}")
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        val actual = digest.digest().joinToString("") {
            String.format(Locale.ROOT, "%02x", it)
        }
        if (actual != SHA256) {
            archive.delete()
            throw IOException("checksum mismatch: expected $SHA256, got $actual")
        }
        return archive
    }

    private fun megabytes(bytes: Long): String =
        String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
