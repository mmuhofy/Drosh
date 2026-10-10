package dev.drosh.domain.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * The settings surface, as one store over one file.
 *
 * The file is `<rootfs home>/.drosh/settings.toml` — inside the Linux
 * environment on purpose. A shell, a script or the agent can read it and
 * write it, and this store notices: it watches the file and re-reads it on
 * change, so `theme_mode = "dark"` from an SSH session takes effect without
 * going through the app. An app-private copy is kept as the fallback for
 * when the rootfs is not there yet (or not there any more), and it is
 * re-synced on every write.
 *
 * Writes are whole-snapshot: [update] transforms the value and the whole file
 * is re-rendered and replaced atomically. There is no per-key write to get
 * half-applied, and a reader — in-app or from the shell — never sees a
 * half-written file.
 */
interface SettingsStore {

    /** The current snapshot. Never null; seeded at construction. */
    val settings: StateFlow<DroshSettings>

    /**
     * Applies [transform] to the snapshot and persists the result.
     *
     * The flow emits the new value even if the write fails — a setting the
     * user just toggled should not snap back because the disk is full — and
     * the failure is logged, not thrown.
     */
    suspend fun update(transform: (DroshSettings) -> DroshSettings)

    /** Re-reads the file, picking up an edit made outside the app. */
    suspend fun reload()
}
