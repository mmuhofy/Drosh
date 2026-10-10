package dev.drosh.data.settings.toml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.drosh.terminal.UbuntuBootstrap
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the settings file lives.
 *
 * Two paths, one truth. The **primary** is inside the Linux environment —
 * `<rootfs>/home/.drosh/settings.toml`, the file a shell or the agent reads
 * and writes — and the **backup** is app-private, `<filesDir>/.drosh/settings.toml`,
 * for the windows in which the rootfs is not there: before the first setup
 * finishes, and after a rootfs reset wipes it.
 *
 * The rootfs path is built from the bootstrap's own rootfs directory rather
 * than a second copy of the "ubuntu" literal, so the two cannot drift.
 */
@Singleton
class SettingsPaths @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bootstrap: UbuntuBootstrap,
) {

    /** The file the Linux side sees. Missing until the rootfs exists. */
    val primaryFile: File
        get() = File(File(bootstrap.rootfsDir, "home/.drosh"), FILE_NAME)

    /** The app-private copy, always written. */
    val backupFile: File
        get() = File(File(context.filesDir, DROSH_DIR), FILE_NAME)

    companion object {
        const val FILE_NAME = "settings.toml"

        /** The Linux-side directory carrying Drosh's own files. */
        const val DROSH_DIR = ".drosh"
    }
}
