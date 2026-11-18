package dev.drosh.linux

import android.content.Context
import android.system.Os
import android.util.Log
import dev.drosh.domain.terminal.SetupPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

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
     * Whether the guest's filesystem is installed.
     *
     * The rootfs is the guest's, and it does not belong to an engine: every
     * launcher enters the same filesystem, they only differ in how they do it.
     *
     * Asking about one launcher's binary here tied the two together, so choosing
     * chroot-ng on a device where it had never been staged said "no guest" —
     * and the terminal came up empty, with the vendored terminal quietly
     * falling back to a plain shell and hiding it.
     */
    val isInstalled: Boolean
        get() = File(rootfsDir, "etc/apt/sources.list").exists()
            && setupMarker.exists()

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
    ) {
        // The rootfs is reused when it is already there, so a missing runtime
        // costs the staging of the binary and not the download of the image.
        if (isInstalled && isChrootNgAvailable) {
            onLog("✓ Bootstrap already installed — skipping.")
            onState(UbuntuSetupState.Ready)
            return
        }
        runInstall(preferences, onState, onLog)
    }

    private suspend fun runInstall(
        preferences: SetupPreferences,
        onState: (UbuntuSetupState) -> Unit,
        onLog: (String) -> Unit,
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

                // Ubuntu rootfs (try multiple asset names, fallback to download)
                rootfsDir.mkdirs()
                val rootfsStream = try {
                    context.assets.open("ubuntu_rootfs")
                } catch (_: Exception) {
                    try {
                        context.assets.open("ubuntu-base.tar.gz")
                    } catch (_: Exception) {
                        onLog("  · Rootfs not in assets — downloading from cdimage.ubuntu.com…")
                        downloadRootfs()
                    }
                }
                rootfsStream.use { input ->
                    onLog("  · Extracting rootfs tarball…")
                    extractTarGz(GZIPInputStream(input), rootfsDir)
                }
                onLog("✓ Rootfs extracted.")

                onLog("→ Configuring guest (network, bash, completion marker)…")
                onState(UbuntuSetupState.Configuring)
                lastFailedStep = "Configure"
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                runScriptInGuest(
                    SCRIPT_SETUP,
                    onLog = onLog,
                    envExtras = mapOf(
                        "IRIS_USERNAME" to preferences.userName,
                        "ABI" to abi,
                    ),
                )
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

        val argv = mutableListOf<String>().apply {
            add(runtime)
            add("--fake-id=0:0")
            add("--link2symlink")
            add("--rewrite")
            listOf("/data", "/storage", "/sdcard")
                .filter { File(it).isDirectory }
                .forEach { add("--bind"); add("$it:$it") }
            add("--work-dir")
            add("/home")
            val environment = linkedMapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME" to "/home",
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

        val argv = mutableListOf<String>().apply {
            add(runtime)
            add("--fake-id=0:0")
            add("--link2symlink")
            add("--rewrite")
            listOf("/data", "/storage", "/sdcard")
                .filter { File(it).isDirectory }
                .forEach { add("--bind"); add("$it:$it") }
            add("--work-dir")
            add("/home")
            listOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME" to "/home",
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
     * Streams the rootfs tarball.
     *
     * The [okhttp3.Response] is deliberately not closed here: the returned
     * stream is the body, and closing the response would close it. The caller
     * wraps this in `use { }`, which is what actually releases the connection.
     */
    private fun downloadRootfs(): InputStream {
        val arch = android.os.Build.SUPPORTED_ABIS[0]
        val rootfsArch = ROOTFS_ARCH_MAP[arch] ?: "arm64"
        val url = "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_VERSION/release/ubuntu-base-$UBUNTU_VERSION-base-$rootfsArch.tar.gz"
        val request = okhttp3.Request.Builder().url(url).addHeader("User-Agent", "IrisCode/1.0").build()
        val response = downloadClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw RuntimeException("Download failed: ${response.code} for $url")
        }
        return response.body.byteStream()
    }

    fun retry() {
        baseDir.deleteRecursively()
    }

    companion object {
        private const val UBUNTU_VERSION = "24.04.4"

        /** The chroot-ng runtime, as it travels in the APK. */
        private const val CHROOT_NG_ASSET = "libchrootng.so"

        private val ROOTFS_ARCH_MAP = mapOf(
            "arm64-v8a" to "arm64",
            "armeabi-v7a" to "armhf",
            "x86_64" to "amd64",
            "x86" to "i386"
        )

        // The single setup script, shipped in the APK and streamed to the
        // guest via stdin (`bash -s`).
        private const val SCRIPT_SETUP = "setup.sh"
    }

    // ─── tar.gz extraction (no system binary dependency) ─────────────────────

    private fun extractTarGz(gz: GZIPInputStream, destDir: File) {
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        val headerBuf = ByteArray(512)
        val dataBuf = ByteArray(32768)

        while (true) {
            if (readFully(gz, headerBuf) < 0) return
            if (headerBuf.all { it == 0.toByte() }) return

            val name = parseString(headerBuf, 0, 100)
            val size = parseOctal(headerBuf, 124, 12)
            val type = headerBuf[156].toInt().toChar()
            val linkName = parseString(headerBuf, 157, 100)

            if (type == 'L') {
                pendingLongName = readStringData(gz, size, dataBuf)
                skipPadding(gz, size)
                continue
            }
            if (type == 'K') {
                pendingLongLink = readStringData(gz, size, dataBuf)
                skipPadding(gz, size)
                continue
            }

            val finalName = pendingLongName ?: name
            pendingLongName = null
            val finalLink = pendingLongLink ?: linkName
            pendingLongLink = null

            if (type == '5') {
                File(destDir, finalName).mkdirs()
                skipPadding(gz, size)
            } else if (type == '2') {
                val entry = File(destDir, finalName)
                entry.parentFile?.mkdirs()
                entry.delete()
                Os.symlink(finalLink, entry.absolutePath)
                skipPadding(gz, size)
            } else if (type == 'x' || type == 'g') {
                skipData(gz, size)
                skipPadding(gz, size)
            } else if (finalName.isEmpty() || finalName == "." || finalName == ".." || finalName.endsWith("/")) {
                skipPadding(gz, size)
            } else {
                val entry = File(destDir, finalName)
                entry.parentFile?.mkdirs()
                var remaining = size
                FileOutputStream(entry).use { out ->
                    while (remaining > 0) {
                        val toRead = minOf(dataBuf.size.toLong(), remaining).toInt()
                        val read = readFully(gz, dataBuf, toRead)
                        if (read < 0) throw RuntimeException("Unexpected EOF in $finalName")
                        out.write(dataBuf, 0, read)
                        remaining -= read
                    }
                }
                skipPadding(gz, size)

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
