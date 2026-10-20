package dev.drosh.terminal.engine.guest

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase C, Gate 3 — the device half of it.
 *
 * chroot-ng ships in the APK as `lib/arm64-v8a/libchrootng.so`, fetched by CI
 * from its own release: `nativeLibraryDir` is the one directory an app
 * may exec from on Android 10+, and the `lib*.so` name is what makes the
 * installer extract it there. It is a static freestanding binary, not a shared
 * object — the name is a packaging requirement, not a link.
 *
 * `--probe` answers the four questions that decide whether a guest runtime can
 * exist on this kernel at all: seccomp filter availability, execmem (anon
 * RW→RX), per-mount `noexec`, and the identity/auxv the app really has. Its
 * output is the gate artefact, so it is returned verbatim and logged line by
 * line under one tag — a device run can be read back with
 * `adb logcat -s DroshLabChroot`.
 */
internal object ChrootProbe {

    private const val TAG = "DroshLabChroot"
    private const val BINARY = "libchrootng.so"
    private const val TIMEOUT_SECONDS = 10L

    /** The packaged binary, or null when the build did not carry it. */
    fun binary(context: Context): File? {
        val file = File(context.applicationInfo.nativeLibraryDir, BINARY)
        return if (file.isFile) file else null
    }

    /** Runs chroot-ng with [args] and returns its combined output. */
    fun run(context: Context, vararg args: String): String {
        val binary = binary(context)
            ?: return "probe: $BINARY is missing from ${context.applicationInfo.nativeLibraryDir}"
        val output = try {
            val process = ProcessBuilder(listOf(binary.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return text.trimEnd() + "\nprobe: timed out after $TIMEOUT_SECONDS s"
            }
            "exit=${process.exitValue()}\n" + text.trimEnd()
        } catch (e: Exception) {
            // IOException here means the file exists but cannot be executed —
            // that is the failure this whole packaging choice exists to avoid,
            // so it is reported, not swallowed.
            val message = "probe: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, message)
            return message
        }
        output.lineSequence().forEach { Log.i(TAG, it) }
        return output
    }
}
