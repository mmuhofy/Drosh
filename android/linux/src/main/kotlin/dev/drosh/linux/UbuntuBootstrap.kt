package dev.drosh.linux

import android.content.Context
import android.system.Os
import android.util.Log
import dev.drosh.domain.terminal.RootfsDownloadProgress
import dev.drosh.domain.terminal.SetupPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import org.tukaani.xz.XZInputStream

// Inspired by: github.com/termux/termux-app (asset staging and rootfs layout)
class UbuntuBootstrap(private val context: Context) {

    private val baseDir: File get() = File(context.filesDir, "ubuntu")

    /**
     * The chroot-ng runtime, staged where it can be run.
     *
     * An **asset**, not a native library: the installer does not extract a
     * `lib/` entry that nothing loads, so a runtime that lives there is in the
     * APK and nowhere else, and the guest has nothing to run. The runtime is staged
     * from an asset for the same reason, and it works.
     */
    val chrootNgFile: File get() = File(baseDir, "bin/libchrootng.so")
    val libDir: File get() = File(baseDir, "lib")
    val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")

    /** Marker file written by `setup.sh` once bootstrap completes. */
    private val setupMarker: File
        get() = File(rootfsDir, "var/lib/drosh/.setup_complete")

    /**
     * The guest's login shell — the one file a usable rootfs must contain.
     *
     * `bin/bash` rather than a fixed `usr/bin/bash`: the cloud image is a
     * merged-usr layout where `/bin` is a symlink to `usr/bin`, and
     * `File.exists()` follows it, so either path answers for the same file.
     */
    private val guestShell: File
        get() = File(rootfsDir, "bin/bash")

    /**
     * Whether the guest's filesystem is installed.
     *
     * The rootfs is the guest's, and it does not belong to an engine: every
     * launcher enters the same filesystem, they only differ in how they do it.
     *
     * Asking about one launcher's binary here tied the two together, so choosing
     * chroot-ng on a device where it had never been staged said "no guest" —
     * and the terminal came up empty, with the vendored terminal quietly
     * falling back to a plain shell and hiding it.
     *
     * The apt layout is deliberately not part of this: the Ubuntu 24.04 cloud
     * image carries no `etc/apt/sources.list` at all — sources live in the
     * deb822 file `etc/apt/sources.list.d/ubuntu.sources` — so checking for
     * the old one-file layout read a correctly installed guest as missing and
     * the terminal said "no guest command". What a finished setup leaves
     * behind is the marker, and what a rootfs cannot lack is the shell; both
     * together are the installation, and neither names a package manager.
     */
    val isInstalled: Boolean
        get() = setupMarker.exists() && guestShell.exists()

    /**
     * Why the guest is not installed, or null when it is.
     *
     * The specific reason rather than a boolean: the two failures have
     * different fixes, and the terminal shows this text instead of the bare
     * "no guest command" that told the user nothing.
     */
    val installUnavailableReason: String?
        get() = when {
            !setupMarker.exists() ->
                "setup has not completed (no setup marker in the rootfs) — run setup"
            !guestShell.exists() ->
                "the rootfs has no /bin/bash — the download may be corrupt, run setup again"
            else -> null
        }

    /**
     * Whether the guest runtime can be launched.
     *
     * It is staged from an asset by the bootstrap; this is what says whether
     * it is there.
     */
    val isChrootNgAvailable: Boolean
        get() = chrootNgFile.canExecute()

    @Volatile private var lastFailedStep: String = "Unknown"

    /**
     * Stages the guest's launchers, and nothing else.
     *
     * The launchers are a few megabytes and the rootfs is a gigabyte, so they
     * are staged on their own: a device that has the rootfs but not the
     * runtime — which is every device that was set up before the runtime
     * travelled as an asset — gets the runtime without the rootfs being
     * extracted again.
     *
     * Safe to call more than once: a file that is already there is left
     * alone.
     */
    suspend fun stageRuntimes() = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        File(baseDir, "bin").mkdirs()
        libDir.mkdirs()

        if (!chrootNgFile.canExecute()) {
            context.assets.open(CHROOT_NG_ASSET).use { input ->
                chrootNgFile.parentFile!!.mkdirs()
                FileOutputStream(chrootNgFile).use { out ->
                    input.copyTo(out)
                }
            }
            chrootNgFile.setExecutable(true, false)
        }
    }

    suspend fun install(
        preferences: SetupPreferences = SetupPreferences.defaults(),
        onState: (UbuntuSetupState) -> Unit,
        onLog: (String) -> Unit = {},
        onDownloadProgress: (RootfsDownloadProgress) -> Unit = {},
    ) {
        // The rootfs is reused when it is already there, so a missing runtime
        // costs the staging of the binary and not the download of the image.
        if (isInstalled && isChrootNgAvailable) {
            onLog("✓ Bootstrap already installed — skipping.")
            onState(UbuntuSetupState.Ready)
            return
        }
        runInstall(preferences, onState, onLog, onDownloadProgress)
    }

    private suspend fun runInstall(
        preferences: SetupPreferences,
        onState: (UbuntuSetupState) -> Unit,
        onLog: (String) -> Unit,
        onDownloadProgress: (RootfsDownloadProgress) -> Unit,
    ) {
        try {
            withContext(Dispatchers.IO) {
                baseDir.mkdirs()
                File(baseDir, "bin").mkdirs()
                libDir.mkdirs()
                tmpDir.mkdirs()

                onLog("→ Extracting Ubuntu rootfs…")
                onState(UbuntuSetupState.Extracting)

                // The launchers first, on their own: a device that has the
                // rootfs but not the runtime gets it without the rootfs being
                // extracted again.
                lastFailedStep = "Rootfs"
                stageRuntimes()
                onLog("  · Guest launchers staged.")

                // Full Ubuntu Server root filesystem, downloaded and verified
                // before a single entry is written. There are no bundled rootfs
                // assets in this build, so the download is the only path — and
                // keeping it the only path is what makes "never extract
                // unverified bytes" true rather than a convention.
                rootfsDir.mkdirs()
                val rootfsArch = rootfsArchForAbi()
                val archive = downloadRootfs(rootfsArch, onLog, onDownloadProgress)
                try {
                    onLog("  · Extracting verified root filesystem…")
                    FileInputStream(archive).use { raw ->
                        XZInputStream(raw).use { xz ->
                            extractTar(xz, rootfsDir)
                        }
                    }
                } finally {
                    archive.delete()
                    partFile(rootfsArch).delete()
                    partMetaFile(rootfsArch).delete()
                }
                onLog("✓ Rootfs extracted.")

                onLog("→ Configuring guest (network, bash, completion marker)…")
                onState(UbuntuSetupState.Configuring)
                lastFailedStep = "Configure"
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                val setupExit = runScriptInGuest(
                    SCRIPT_SETUP,
                    onLog = onLog,
                    envExtras = mapOf(
                        "IRIS_USERNAME" to preferences.userName,
                        "ABI" to abi,
                    ),
                )
                // UNTESTED — verify before use: chroot-ng's exit code for a
                // script that failed inside the guest. Ready used to be emitted
                // unconditionally, so a setup that died mid-way still reported
                // success — the loading gate trusted it, the terminal opened,
                // and there was no guest behind it. Both checks are required:
                // a non-zero exit, or a zero exit that never wrote the marker
                // `setup.sh` writes last, means the guest is not set up.
                if (setupExit != 0) {
                    throw IllegalStateException(
                        "setup.sh failed in the guest with exit code $setupExit",
                    )
                }
                if (!setupMarker.exists()) {
                    throw IllegalStateException(
                        "setup.sh exited 0 but never wrote ${setupMarker.path} — the guest is not set up",
                    )
                }
                onLog("✓ Guest configured for ${preferences.userName}.")

                onLog("✓ Bootstrap complete. Ready.")
                onState(UbuntuSetupState.Ready)
            }
        } catch (e: Exception) {
            Log.e("UbuntuBootstrap", "Setup failed", e)
            onLog("✗ FAILED at [$lastFailedStep]: ${e::class.simpleName}: ${e.message ?: "Unknown error"}")
            onState(UbuntuSetupState.Failed(
                error = "${e::class.simpleName}: ${e.message ?: "Unknown error"}",
                failedStep = lastFailedStep,
            ))
        }
    }


    // One script does all guest configuration. It lives under
    // linux/src/main/assets/shell-scripts/setup/ and is streamed to the guest
    // via stdin (`bash -s`) — see `runScriptInGuest`.

    // ─── Guest script loading & execution ─────────────────────────

    private fun loadAssetScript(scriptName: String): String {
        val path = "shell-scripts/setup/$scriptName"
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    /**
     * The guest directory chroot-ng works in: `/home` when the rootfs has one.
     *
     * UNTESTED — verify before use: a rootfs without `/home` would make
     * `--work-dir /home` point at a directory that is not there, and `/root`
     * is the fallback every Ubuntu rootfs does have. HOME follows the same
     * directory so the shell never starts with a home that does not exist.
     */
    private fun workDirIn(rootfs: String): String =
        if (File(rootfs, "home").isDirectory) "/home" else "/root"

    /**
     * Runs a shell script inside the guest, streaming its output.
     *
     * The script arrives on the guest shell's stdin (`bash -s`), which is what
     * keeps the setup steps out of the argv: a script with a `$` in it would
     * otherwise be expanded by the host shell on the way in.
     */
    private fun pipeScriptInGuest(
        scriptBody: String,
        onLog: (String) -> Unit,
        envExtras: Map<String, String> = emptyMap(),
    ): Int {
        val rootfs = rootfsDir.absolutePath
        val runtime = chrootNgFile.absolutePath
        val workDir = workDirIn(rootfs)

        val argv = mutableListOf<String>().apply {
            add(runtime)
            add("--fake-id=0:0")
            add("--link2symlink")
            add("--rewrite")
            listOf("/data", "/storage", "/sdcard")
                .filter { File(it).isDirectory }
                .forEach { add("--bind"); add("$it:$it") }
            add("--work-dir")
            add(workDir)
            val environment = linkedMapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME" to workDir,
                "LANG" to "C.UTF-8",
                "TMPDIR" to "/tmp",
            )
            environment.putAll(envExtras)
            environment.forEach { (name, value) -> add("-E"); add("$name=$value") }
            add(rootfs)
            add("/bin/bash")
            add("-s")
        }

        val pb = ProcessBuilder(argv)
        pb.environment().clear()
        pb.environment()["TERM"] = "xterm-256color"
        pb.directory(File(rootfs))
        pb.redirectErrorStream(true)

        val process = pb.start()

        // Feed script body via stdin (bash -s reads the script from stdin).
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(scriptBody)
            writer.flush()
        }

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            onLog(line ?: "")
        }
        return process.waitFor()
    }

    private fun runScriptInGuest(
        scriptName: String,
        onLog: (String) -> Unit,
        envExtras: Map<String, String> = emptyMap(),
    ): Int {
        val body = loadAssetScript(scriptName)
        return pipeScriptInGuest(body, onLog, envExtras)
    }


    // ─── Guest command execution (for setup steps) ─────────────────

    /**
     * Runs one shell command inside the guest and streams its output.
     *
     * The same argv shape [ChrootNgRunner] builds for a session, kept here
     * because the bootstrap runs before a session exists.
     */
    private fun runInGuest(
        command: String,
        onLog: (String) -> Unit = {},
    ): Int {
        val rootfs = rootfsDir.absolutePath
        val runtime = chrootNgFile.absolutePath
        val workDir = workDirIn(rootfs)

        val argv = mutableListOf<String>().apply {
            add(runtime)
            add("--fake-id=0:0")
            add("--link2symlink")
            add("--rewrite")
            listOf("/data", "/storage", "/sdcard")
                .filter { File(it).isDirectory }
                .forEach { add("--bind"); add("$it:$it") }
            add("--work-dir")
            add(workDir)
            listOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME" to workDir,
                "LANG" to "C.UTF-8",
                "TMPDIR" to "/tmp",
            ).forEach { (name, value) -> add("-E"); add("$name=$value") }
            add(rootfs)
            add("/bin/bash")
            add("-c")
            add(command)
        }

        val pb = ProcessBuilder(argv)
        pb.environment().clear()
        pb.environment()["TERM"] = "xterm-256color"
        pb.directory(File(rootfs))
        pb.redirectErrorStream(true)

        val process = pb.start()
        // Consume + forward output to prevent buffer deadlock and surface logs.
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            onLog(line ?: "")
        }
        return process.waitFor()
    }

    // ─── Download ──────────────────────────────────────────────────

    // One client for the class, not one per download. Building a client
    // allocates a Dispatcher, a ConnectionPool and a thread pool, and a
    // throwaway one gets garbage collected with its idle sockets still open.
    // Downloads are rare and large; a per-download client was pure overhead.
    private val downloadClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder().followRedirects(true).build()
    }

    /**
     * The device ABI this build can install a guest on.
     *
     * Ubuntu publishes cloud images for several architectures, but Drosh ships a
     * single arm64 rootfs and the chroot-ng runtime for it. Guessing "arm64" for
     * an unknown ABI used to produce a URL that 404'd with no explanation; say
     * so here instead.
     */
    private fun rootfsArchForAbi(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return ROOTFS_ARCH_MAP[abi] ?: throw IllegalStateException(
            "Unsupported ABI '$abi': Drosh's Ubuntu Server rootfs is arm64-only (arm64-v8a). " +
                "This device cannot install the guest."
        )
    }

    private fun rootfsFileName(rootfsArch: String): String =
        "ubuntu-$UBUNTU_VERSION-server-cloudimg-$rootfsArch-root.tar.xz"

    private fun releaseUrl(fileName: String): String =
        "https://cloud-images.ubuntu.com/releases/$UBUNTU_VERSION/release/$fileName"

    /**
     * Downloads the full Ubuntu Server root filesystem, verifies it against
     * SHA256SUMS from the same release, and returns the verified archive.
     *
     * The download is split into [DOWNLOAD_CHUNKS] parallel HTTP ranges written
     * into one pre-sized `.part` file. A `.part.total` sidecar records the length
     * HEAD saw: if the process dies mid-download the next run compares it with a
     * fresh HEAD and resumes the remaining ranges instead of starting over.
     * The final SHA-256 is still checked over the whole file, so a bad resume
     * cannot slip through. A corrupt or length-changed `.part` is deleted.
     */
    private suspend fun downloadRootfs(
        rootfsArch: String,
        onLog: (String) -> Unit,
        onProgress: (RootfsDownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val fileName = rootfsFileName(rootfsArch)
        val url = releaseUrl(fileName)
        val archive = File(tmpDir, fileName)
        val part = partFile(rootfsArch)
        val meta = partMetaFile(rootfsArch)
        if (archive.exists()) {
            // A process that died during extraction left a complete archive.
            // Verify it before reusing; a corrupt one is deleted by the check.
            try {
                verifyRootfsChecksum(archive, fileName)
                onLog("  · Reusing the downloaded archive from the previous attempt.")
                return@withContext archive
            } catch (e: Exception) {
                onLog("  · Previous archive was unusable (${e.message}) — downloading again.")
            }
        }
        onLog("  · Downloading full Ubuntu Server $UBUNTU_VERSION root filesystem (~216 MB)…")
        onLog("  · cloud-images.ubuntu.com — nano and vim are already in the image.")

        val head = try {
            probe(url)
        } catch (e: Exception) {
            onLog("  · HEAD failed (${e.message}) — using a single stream with progress.")
            HeadInfo(totalBytes = -1L, supportsRanges = false)
        }
        var resumeFrom = 0L
        if (part.exists()) {
            val recordedTotal = meta.readTextOrNull()?.toLongOrNull() ?: -1L
            resumeFrom = when {
                recordedTotal != head.totalBytes || head.totalBytes <= 0L -> {
                    part.delete(); meta.delete(); 0L
                }
                part.length() >= head.totalBytes -> {
                    part.delete(); meta.delete(); 0L
                }
                else -> part.length()
            }
        }
        if (head.totalBytes > 0L && !meta.exists()) meta.writeText(head.totalBytes.toString())
        if (resumeFrom > 0L) onLog("  · Resuming download at $resumeFrom of ${head.totalBytes} bytes.")

        if (head.supportsRanges && head.totalBytes > 0L) {
            downloadRanges(url, part, head.totalBytes, resumeFrom, onProgress)
        } else {
            downloadSingle(url, part, head.totalBytes, resumeFrom, onProgress)
        }
        // A checksum mismatch deletes the partial; a network failure leaves it
        // alone on purpose, so the next launch can resume it.
        verifyRootfsChecksum(part, fileName)
        if (!part.renameTo(archive)) {
            part.copyTo(archive, overwrite = true)
            part.delete()
        }
        meta.delete()
        archive
    }

    /** HEAD the final URL (after redirects) for length and range support. */
    private fun probe(url: String): HeadInfo {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", DOWNLOAD_USER_AGENT)
            .head()
            .build()
        downloadClient.newCall(request).execute().use { response ->
            val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val ranges = response.code == 206 ||
                response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
            return HeadInfo(length, ranges)
        }
    }

    /** Eight ranges into one pre-sized file, with retried chunks and a shared progress meter. */
    private suspend fun downloadRanges(
        url: String,
        part: File,
        total: Long,
        resumeFrom: Long,
        onProgress: (RootfsDownloadProgress) -> Unit,
    ) = coroutineScope {
        val remaining = total - resumeFrom
        val chunkSize = (remaining + DOWNLOAD_CHUNKS - 1) / DOWNLOAD_CHUNKS
        val reporter = DownloadReporter(total, resumeFrom, PROGRESS_INTERVAL_MS, onProgress)
        val file = RandomAccessFile(part, "rw")
        file.setLength(total)
        val sink = RandomAccessSink(file)
        try {
            val jobs = (0 until DOWNLOAD_CHUNKS).map { slot ->
                async(Dispatchers.IO) {
                    val start = resumeFrom + slot * chunkSize
                    if (start >= total) return@async
                    val end = minOf(start + chunkSize - 1, total - 1)
                    downloadRange(url, start, end, slot, sink, reporter)
                }
            }
            jobs.awaitAll()
        } finally {
            file.close()
        }
        reporter.finish()
    }

    /** One ranged GET, retried up to [CHUNK_RETRIES] times with linear backoff. */
    private suspend fun downloadRange(
        url: String,
        start: Long,
        end: Long,
        slot: Int,
        sink: RandomAccessSink,
        reporter: DownloadReporter,
    ) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < CHUNK_RETRIES && currentCoroutineContext().isActive) {
            attempt += 1
            var written = 0L
            reporter.set(slot, 0L)
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", DOWNLOAD_USER_AGENT)
                    .header("Range", "bytes=$start-$end")
                    .build()
                downloadClient.newCall(request).execute().use { response ->
                    if (response.code != 206) {
                        throw RuntimeException("Expected 206 for chunk $start-$end, got ${response.code}")
                    }
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var position = start
                        while (currentCoroutineContext().isActive) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                sink.writeAt(position, buffer, read)
                                position += read
                                written += read
                                reporter.set(slot, written)
                            }
                        }
                    }
                    if (start + written <= end) {
                        throw RuntimeException("Chunk $start-$end truncated at ${start + written}")
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < CHUNK_RETRIES) delay(CHUNK_RETRY_DELAY_MS * attempt)
            }
        }
        throw RuntimeException(
            "Chunk $start-$end failed after $CHUNK_RETRIES attempts: ${lastError?.message}",
            lastError,
        )
    }

    /** Single-stream fallback when HEAD or ranges are unavailable. Still reports progress. */
    private suspend fun downloadSingle(
        url: String,
        part: File,
        knownTotal: Long,
        resumeFrom: Long,
        onProgress: (RootfsDownloadProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", DOWNLOAD_USER_AGENT)
            .apply { if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-") }
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw RuntimeException("Download failed: ${response.code} for $url")
            }
            // A 200 means the server ignored Range: overwrite from zero.
            val append = response.code == 206 && resumeFrom > 0L
            val offset = if (append) resumeFrom else 0L
            val body = response.body
            val total = if (knownTotal > 0L) {
                knownTotal
            } else {
                body.contentLength().let { if (it > 0L) offset + it else -1L }
            }
            val reporter = DownloadReporter(total, offset, PROGRESS_INTERVAL_MS, onProgress)
            RandomAccessFile(part, "rw").use { file ->
                file.seek(offset)
                body.byteStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var written = 0L
                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            file.write(buffer, 0, read)
                            written += read
                            reporter.set(0, written)
                        }
                    }
                    if (total > 0L && offset + written < total) {
                        throw RuntimeException("Download truncated at ${offset + written} of $total")
                    }
                }
            }
            reporter.finish()
        }
    }

    private fun partFile(rootfsArch: String): File =
        File(tmpDir, rootfsFileName(rootfsArch) + ".part")

    private fun partMetaFile(rootfsArch: String): File =
        File(tmpDir, rootfsFileName(rootfsArch) + ".part.total")

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private data class HeadInfo(val totalBytes: Long, val supportsRanges: Boolean)

    /**
     * One atomic byte counter shared by the chunk coroutines.
     *
     * Each coroutine owns one slot and writes only its own cumulative count;
     * the sum is an AtomicLong. Emits are throttled to [intervalMs] and the rate
     * is a one-second moving window. The callback is non-blocking by contract —
     * the foreground service uses `tryEmit` — so a reader never stalls a writer.
     */
    private class DownloadReporter(
        private val totalBytes: Long,
        initialBytes: Long,
        private val intervalMs: Long,
        private val onProgress: (RootfsDownloadProgress) -> Unit,
    ) {
        private val lock = Any()
        // Slots count only bytes fetched in this process; [initialBytes] is the
        // already-on-disk `.part` baseline and is never attributed to a slot,
        // so resetting a retried chunk cannot erase resumed bytes.
        private val slots = LongArray(DOWNLOAD_CHUNKS)
        private val sum = AtomicLong(initialBytes)
        private val samples = ArrayDeque<Pair<Long, Long>>()
        private var lastEmitAt = 0L

        init {
            samples.addLast(android.os.SystemClock.elapsedRealtime() to initialBytes)
        }

        fun set(slot: Int, bytes: Long) {
            val now = android.os.SystemClock.elapsedRealtime()
            var sample: RootfsDownloadProgress? = null
            synchronized(lock) {
                val delta = bytes - slots[slot]
                if (delta != 0L) {
                    slots[slot] = bytes
                    val current = sum.addAndGet(delta)
                    samples.addLast(now to current)
                    while (samples.isNotEmpty() && now - samples.first().first > RATE_WINDOW_MS) {
                        samples.removeFirst()
                    }
                    if (now - lastEmitAt >= intervalMs) {
                        lastEmitAt = now
                        sample = RootfsDownloadProgress(current, totalBytes, rate(now, current))
                    }
                }
            }
            sample?.let(onProgress)
        }

        fun finish() {
            val now = android.os.SystemClock.elapsedRealtime()
            val current = sum.get()
            onProgress(
                RootfsDownloadProgress(
                    downloadedBytes = current,
                    totalBytes = if (totalBytes > 0L) totalBytes else current,
                    bytesPerSecond = rate(now, current),
                ),
            )
        }

        private fun rate(now: Long, current: Long): Long {
            val oldest = samples.firstOrNull() ?: return 0L
            val elapsed = now - oldest.first
            if (elapsed <= 0L) return 0L
            return ((current - oldest.second) * 1000L / elapsed).coerceAtLeast(0L)
        }
    }

    /** Serialises seek+write so the eight chunk coroutines cannot interleave them. */
    private class RandomAccessSink(private val file: RandomAccessFile) {
        private val lock = Any()

        fun writeAt(position: Long, buffer: ByteArray, length: Int) = synchronized(lock) {
            file.seek(position)
            file.write(buffer, 0, length)
        }
    }

    /**
     * Checks the archive against the release's own SHA256SUMS, then deletes it
     * on any failure. A missing entry is a failure too: an unchecked archive is
     * never handed to the extractor.
     */
    private fun verifyRootfsChecksum(archive: File, fileName: String) {
        val url = releaseUrl(SHA256SUMS_NAME)
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", DOWNLOAD_USER_AGENT)
            .build()
        val expected = downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Checksum download failed: ${response.code} for $url")
            }
            val manifest = response.body.byteStream().bufferedReader().use { it.readText() }
            sha256For(manifest, fileName)
                ?: throw RuntimeException("SHA256SUMS has no entry for $fileName — refusing to extract")
        }
        val actual = FileInputStream(archive).use { sha256Of(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            archive.delete()
            File(archive.parentFile, archive.name + ".total").delete()
            throw RuntimeException(
                "Rootfs SHA-256 mismatch for $fileName: expected $expected, got $actual. " +
                    "The download was deleted; nothing was extracted."
            )
        }
    }

    /** The 64-hex digest for [fileName] in a SHA256SUMS file, if it is listed. */
    private fun sha256For(manifest: String, fileName: String): String? =
        manifest.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.substringAfterLast(' ').removePrefix("*").trim() == fileName
            }
            ?.substringBefore(' ')
            ?.takeIf { it.matches(SHA256_PATTERN) }

    private fun sha256Of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun retry() {
        baseDir.deleteRecursively()
    }

    companion object {
        // cloud-images.ubuntu.com publishes by release *series*, so this is
        // "24.04" even when the directory currently holds a later point release.
        private const val UBUNTU_VERSION = "24.04"
        private const val DOWNLOAD_USER_AGENT = "Drosh/1.0"
        private const val SHA256SUMS_NAME = "SHA256SUMS"
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        private const val DOWNLOAD_CHUNKS = 8
        private const val CHUNK_RETRIES = 3
        private const val CHUNK_RETRY_DELAY_MS = 200L
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val RATE_WINDOW_MS = 1_000L
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

        /** The chroot-ng runtime, as it travels in the APK. */
        private const val CHROOT_NG_ASSET = "libchrootng.so"

        /** One ABI, one rootfs: a guess here would only 404 later. */
        private val ROOTFS_ARCH_MAP = mapOf("arm64-v8a" to "arm64")

        // The single setup script, shipped in the APK and streamed to the
        // guest via stdin (`bash -s`).
        private const val SCRIPT_SETUP = "setup.sh"
    }

    // ─── tar extraction from the XZ-decoded stream ───────────────────────────

    private fun extractTar(input: InputStream, destDir: File) {
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        val headerBuf = ByteArray(512)
        val dataBuf = ByteArray(32768)

        while (true) {
            if (readFully(input, headerBuf) < 0) return
            if (headerBuf.all { it == 0.toByte() }) return

            val name = parseString(headerBuf, 0, 100)
            val size = parseOctal(headerBuf, 124, 12)
            val type = headerBuf[156].toInt().toChar()
            val linkName = parseString(headerBuf, 157, 100)

            if (type == 'L') {
                pendingLongName = readStringData(input, size, dataBuf)
                skipPadding(input, size)
                continue
            }
            if (type == 'K') {
                pendingLongLink = readStringData(input, size, dataBuf)
                skipPadding(input, size)
                continue
            }

            val finalName = pendingLongName ?: name
            pendingLongName = null
            val finalLink = pendingLongLink ?: linkName
            pendingLongLink = null

            if (type == '5') {
                File(destDir, finalName).mkdirs()
                skipPadding(input, size)
            } else if (type == '2') {
                val entry = File(destDir, finalName)
                entry.parentFile?.mkdirs()
                entry.delete()
                Os.symlink(finalLink, entry.absolutePath)
                skipPadding(input, size)
            } else if (type == 'x' || type == 'g') {
                skipData(input, size)
                skipPadding(input, size)
            } else if (finalName.isEmpty() || finalName == "." || finalName == ".." || finalName.endsWith("/")) {
                skipPadding(input, size)
            } else {
                val entry = File(destDir, finalName)
                entry.parentFile?.mkdirs()
                var remaining = size
                FileOutputStream(entry).use { out ->
                    while (remaining > 0) {
                        val toRead = minOf(dataBuf.size.toLong(), remaining).toInt()
                        val read = readFully(input, dataBuf, toRead)
                        if (read < 0) throw RuntimeException("Unexpected EOF in $finalName")
                        out.write(dataBuf, 0, read)
                        remaining -= read
                    }
                }
                skipPadding(input, size)

                val mode = parseOctal(headerBuf, 100, 8).toInt()
                if (mode and 64 != 0) entry.setExecutable(true, false)
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray, length: Int = buf.size): Int {
        var offset = 0
        while (offset < length) {
            val read = input.read(buf, offset, length - offset)
            if (read < 0) return if (offset == 0) -1 else offset
            offset += read
        }
        return offset
    }

    private fun skipData(input: java.io.InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(4096)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            readFully(input, buf, toRead)
            remaining -= toRead
        }
    }

    private fun skipPadding(input: java.io.InputStream, dataSize: Long) {
        val padding = (512 - (dataSize % 512)) % 512
        var skipped = 0L
        while (skipped < padding) {
            val toSkip = minOf(padding - skipped, 4096L)
            val n = input.skip(toSkip)
            if (n <= 0) {
                val buf = ByteArray(minOf(padding - skipped, 4096L).toInt())
                readFully(input, buf, buf.size)
                skipped += buf.size
            } else {
                skipped += n
            }
        }
    }

    private fun readStringData(input: java.io.InputStream, size: Long, buf: ByteArray): String {
        val toRead = minOf(buf.size.toLong(), size).toInt()
        readFully(input, buf, toRead)
        if (size > buf.size) {
            var remaining = size - buf.size
            val discardBuf = ByteArray(4096)
            while (remaining > 0) {
                val chunk = minOf(discardBuf.size.toLong(), remaining).toInt()
                readFully(input, discardBuf, chunk)
                remaining -= chunk
            }
        }
        val end = buf.indexOfFirst { it == 0.toByte() }.let { if (it < 0) toRead else it }
        return buf.copyOfRange(0, end).decodeToString()
    }

    private fun parseOctal(data: ByteArray, offset: Int, length: Int): Long {
        val end = minOf(offset + length, data.size)
        var i = offset
        while (i < end && (data[i] == 0x20.toByte() || data[i] == 0x30.toByte() || data[i] == 0.toByte())) i++
        if (i >= end) return 0
        var j = i
        while (j < end && data[j] != 0x20.toByte() && data[j] != 0.toByte()) j++
        if (i == j) return 0
        val str = data.copyOfRange(i, j).decodeToString()
        return str.toLong(8)
    }

    private fun parseString(data: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, data.size)
        return data.copyOfRange(offset, end)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .decodeToString()
    }
}
