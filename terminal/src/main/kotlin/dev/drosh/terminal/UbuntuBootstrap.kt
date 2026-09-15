package dev.drosh.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import dev.drosh.domain.terminal.SetupPreferences
import dev.drosh.domain.terminal.ShellChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

// Inspired by: github.com/Xed-Editor/Karbon-PackagesX (proot binary for Android NDK)
// and github.com/termux/termux-app (proot-loader)
class UbuntuBootstrap(private val context: Context) {

    private val baseDir: File get() = File(context.filesDir, "ubuntu")
    val prootFile: File get() = File(baseDir, "bin/proot")
    val libDir: File get() = File(baseDir, "lib")
    val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")

    /** Marker file written by `rootfs-optimize.sh` once bootstrap completes. */
    private val setupMarker: File
        get() = File(rootfsDir, "var/lib/drosh/.setup_complete")

    /** Rootfs identity check — used to early-exit when bootstrap is already done. */
    val isInstalled: Boolean
        get() = prootFile.canExecute()
            && File(libDir, "libtalloc.so.2").canRead()
            && File(rootfsDir, "etc/apt/sources.list").exists()
            && setupMarker.exists()

    @Volatile private var lastFailedStep: String = "Unknown"

    suspend fun install(
        preferences: SetupPreferences = SetupPreferences.defaults(),
        onState: (UbuntuSetupState) -> Unit,
        onLog: (String) -> Unit = {},
    ) {
        if (isInstalled) {
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

                onLog("→ Extracting PRoot binary and Ubuntu rootfs…")
                onState(UbuntuSetupState.Extracting)

                // proot binary
                context.assets.open("proot").use { input ->
                    prootFile.parentFile!!.mkdirs()
                    FileOutputStream(prootFile).use { out ->
                        input.copyTo(out)
                    }
                }
                prootFile.setExecutable(true, false)
                onLog("  · PRoot binary staged.")

                // libtalloc.so.2
                context.assets.open("libtalloc.so.2").use { input ->
                    libDir.mkdirs()
                    FileOutputStream(File(libDir, "libtalloc.so.2")).use { out ->
                        input.copyTo(out)
                    }
                }
                onLog("  · libtalloc.so.2 staged.")

                // Ubuntu rootfs (try multiple asset names, fallback to download)
                rootfsDir.mkdirs()
                lastFailedStep = "Rootfs"
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

                onLog("→ Configuring rootfs (apt sources, resolv.conf, shells)…")
                onState(UbuntuSetupState.Configuring)
                lastFailedStep = "Configure"
                runScriptInProot(SCRIPTS_CONFIGURE, onLog = onLog)
                onLog("✓ Rootfs configured.")

                // Always install base packages — ensures zsh is available
                // regardless of PackageProfile (Minimal / Developer / Custom).
                onLog("→ Installing base packages…")
                onState(UbuntuSetupState.InstallingPackages("apt", "Installing packages..."))
                lastFailedStep = "Packages"
                runScriptInProot(
                    SCRIPTS_PACKAGES,
                    onLog = onLog,
                    envExtras = mapOf(
                        "IRIS_CUSTOM_PACKAGES" to preferences.customPackages.joinToString(","),
                    ),
                )
                onLog("✓ Base packages installed.")

                val shellChoice = preferences.shellChoice
                if (shellChoice == ShellChoice.Zsh) {
                    onLog("→ Installing Oh My Zsh + plugins…")
                    onState(UbuntuSetupState.InstallingOhMyZsh("Installing Oh My Zsh..."))
                    lastFailedStep = "OhMyZsh"
                    var omzSuccess = false
                    try {
                        val rc1 = runScriptInProot(SCRIPTS_SET_DEFAULT_SHELL, onLog = onLog)
                        if (rc1 != 0) throw Exception("set-default-shell exited $rc1")
                        val rc2 = runScriptInProot(SCRIPTS_OMZ, onLog = onLog)
                        if (rc2 != 0) throw Exception("omz-install exited $rc2")
                        val rc3 = runScriptInProot(SCRIPTS_ZSHRC, onLog = onLog)
                        if (rc3 != 0) throw Exception("zshrc-write exited $rc3")
                        onLog("✓ Oh My Zsh ready.")
                        omzSuccess = true
                    } catch (e: Exception) {
                        onLog("⚠ Oh My Zsh install failed (${e.message}); falling back to bash.")
                    }
                    // If OMZ failed, reset default shell to bash so /etc/passwd
                    // is consistent with the bash .bashrc we write next.
                    if (!omzSuccess) {
                        runScriptInProot(SCRIPTS_RESET_SHELL_TO_BASH, onLog = onLog)
                        runScriptInProot(SCRIPTS_BASHRC, onLog = onLog)
                        onLog("✓ Fallback to bash configured for ${preferences.userName}.")
                    }
                } else {
                    onLog("→ Bash selected — skipping Oh My Zsh and set-default-shell.")
                    onState(UbuntuSetupState.Optimizing)
                    lastFailedStep = "Bash"
                    runScriptInProot(SCRIPTS_BASHRC, onLog = onLog)
                    onLog("✓ .bashrc configured for ${preferences.userName}.")
                }

                onLog("→ Optimizing rootfs (deb cache purge, marker write)…")
                onState(UbuntuSetupState.Optimizing)
                lastFailedStep = "Optimize"
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                runScriptInProot(
                    SCRIPTS_OPTIMIZE,
                    onLog = onLog,
                    envExtras = mapOf(
                        "ABI" to abi,
                        "IRIS_USERNAME" to preferences.userName,
                        "IRIS_CUSTOM_PACKAGES" to preferences.customPackages.joinToString(","),
                    ),
                )
                onLog("✓ Rootfs optimized.")

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


    // All installation / configuration logic is now in shell scripts under
    // terminal/src/main/assets/shell-scripts/setup/. Kotlin only orchestrates
    // the pipeline and forwards logs — see `runScriptInProot`.

    // ─── Proot script loading & execution ──────────────────────────

    private fun loadAssetScript(scriptName: String): String {
        val path = "shell-scripts/setup/$scriptName"
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    private fun pipeScriptInProot(
        scriptBody: String,
        onLog: (String) -> Unit,
        envExtras: Map<String, String> = emptyMap(),
    ): Int {
        val prootExe = prootFile.absolutePath
        val rootfs = rootfsDir.absolutePath
        val lib = libDir.absolutePath
        val tmp = tmpDir.absolutePath

        val argv = listOf(
            linkerPath, prootExe,
            "--kill-on-exit", "-0", "--link2symlink",
            "-r", rootfs,
            "-w", "/home",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/system", "-b", "/data",
            "-b", "${lib}:/hostlib",
            "/bin/bash", "-s",
        )

        val env = mapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/home",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "LD_LIBRARY_PATH" to lib,
            "PROOT_TMP_DIR" to tmp,
        ) + envExtras

        val pb = ProcessBuilder(*argv.toTypedArray())
        pb.environment().clear()
        pb.environment().putAll(env)
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

    private fun runScriptInProot(
        scriptName: String,
        onLog: (String) -> Unit,
        envExtras: Map<String, String> = emptyMap(),
    ): Int {
        val body = loadAssetScript(scriptName)
        return pipeScriptInProot(body, onLog, envExtras)
    }


    // ─── Proot command execution (for setup steps) ─────────────────

    private val linkerPath: String
        get() = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"

    private fun runInProot(
        command: String,
        onLog: (String) -> Unit = {},
    ): Int {
        val prootExe = prootFile.absolutePath
        val rootfs = rootfsDir.absolutePath
        val lib = libDir.absolutePath
        val tmp = tmpDir.absolutePath

        val argv = listOf(
            linkerPath, prootExe,
            "--kill-on-exit", "-0", "--link2symlink",
            "-r", rootfs,
            "-w", "/home",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/system", "-b", "/data",
            "-b", "${lib}:/hostlib",
            "/bin/bash", "-c", command
        )

        val env = mapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/home",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "LD_LIBRARY_PATH" to lib,
            "PROOT_TMP_DIR" to tmp
        )

        val pb = ProcessBuilder(*argv.toTypedArray())
        pb.environment().clear()
        pb.environment().putAll(env)
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

    private fun downloadRootfs(): InputStream {
        val arch = android.os.Build.SUPPORTED_ABIS[0]
        val rootfsArch = ROOTFS_ARCH_MAP[arch] ?: "arm64"
        val url = "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_VERSION/release/ubuntu-base-$UBUNTU_VERSION-base-$rootfsArch.tar.gz"
        val request = okhttp3.Request.Builder().url(url).addHeader("User-Agent", "IrisCode/1.0").build()
        val response = okhttp3.OkHttpClient.Builder().followRedirects(true).build().newCall(request).execute()
        if (!response.isSuccessful) throw RuntimeException("Download failed: ${response.code} for $url")
        return response.body!!.byteStream()
    }

    fun retry() {
        baseDir.deleteRecursively()
    }

    companion object {
        private const val UBUNTU_VERSION = "24.04.4"
        private val ROOTFS_ARCH_MAP = mapOf(
            "arm64-v8a" to "arm64",
            "armeabi-v7a" to "armhf",
            "x86_64" to "amd64",
            "x86" to "i386"
        )

        // Asset script filenames under terminal/src/main/assets/shell-scripts/setup/.
        // Each is shipped in the APK and streamed to proot via stdin (`bash -s`).
        private const val SCRIPTS_CONFIGURE = "rootfs-configure.sh"
        private const val SCRIPTS_PACKAGES = "packages-install.sh"
        private const val SCRIPTS_SET_DEFAULT_SHELL = "set-default-shell.sh"
        private const val SCRIPTS_RESET_SHELL_TO_BASH = "reset-shell-to-bash.sh"
        private const val SCRIPTS_OMZ = "omz-install.sh"
        private const val SCRIPTS_ZSHRC = "zshrc-write.sh"
        private const val SCRIPTS_BASHRC = "bashrc-write.sh"
        private const val SCRIPTS_OPTIMIZE = "rootfs-optimize.sh"
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
