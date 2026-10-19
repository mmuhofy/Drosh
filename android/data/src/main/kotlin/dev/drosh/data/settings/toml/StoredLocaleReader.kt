package dev.drosh.data.settings.toml

import android.content.Context
import dev.drosh.core.toml.TomlDocument
import java.io.File
import java.io.IOException

/**
 * The stored UI language, read before Hilt exists.
 *
 * [android.app.Application.attachBaseContext] runs before injection, and the
 * locale has to be applied to the base context right there — a frame with the
 * wrong locale is the entire reason this function is blocking rather than a
 * flow. The file is a couple of kilobytes, so a synchronous read of it is
 * cheaper than the machinery that would avoid one.
 *
 * The backup copy is read first and the rootfs copy second: both hold the
 * same bytes after any write, and the backup is the one that exists before
 * the first setup finishes — which is exactly when the launch is happening.
 *
 * An unreadable or absent file answers "" (follow the system) rather than
 * throwing: the locale is applied again from the store's first emission, so
 * the worst case is one frame in the system language.
 */
fun readStoredLocaleTag(context: Context): String {
    val backup = File(File(context.filesDir, SettingsPaths.DROSH_DIR), SettingsPaths.FILE_NAME)
    val rootfs = File(File(context.filesDir, "ubuntu/rootfs/home/${SettingsPaths.DROSH_DIR}"), SettingsPaths.FILE_NAME)
    for (candidate in listOf(backup, rootfs)) {
        val text = try {
            if (candidate.exists()) candidate.readText() else continue
        } catch (e: IOException) {
            continue
        }
        val parsed = runCatching { TomlDocument.parse(text) }.getOrNull() ?: continue
        return parsed.getString("appearance", "language", "")
    }
    return ""
}
