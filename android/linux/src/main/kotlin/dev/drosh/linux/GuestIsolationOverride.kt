package dev.drosh.linux

import android.content.Context
import dev.drosh.domain.settings.GuestIsolation
import java.io.File

/**
 * The guest isolation engine a device test asked for, or null when it did not.
 *
 * The override is a file in the app's own storage, so it can be set from a
 * shell without a build or without touching the setup wizard:
 *
 *     adb shell run-as dev.drosh.my sh -c 'echo chroot_ng > files/guest_backend'
 *
 * It wins over the user's setting, because a test that cannot be sure which
 * engine it is testing is not a test. A file that names something else is
 * ignored rather than fatal: a typo in a test command must not take the app
 * down.
 */
object GuestIsolationOverride {

    private const val OVERRIDE_FILE = "guest_backend"

    /** The engine the override names, or null. */
    fun read(context: Context): GuestIsolation? {
        val file = File(context.filesDir, OVERRIDE_FILE)
        if (!file.isFile) return null
        val name = runCatching { file.readText().trim() }.getOrNull() ?: return null
        return GuestIsolation.fromName(name)
    }
}
