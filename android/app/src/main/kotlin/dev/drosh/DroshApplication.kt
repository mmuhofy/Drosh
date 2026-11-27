package dev.drosh

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.drosh.core.AppLocale
import dev.drosh.data.session.SessionManagerAdapter
import dev.drosh.terminal.engine.native.NativeLibraries
import dev.drosh.domain.settings.SettingsStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry — `@HiltAndroidApp` triggers Hilt's code generation for the
 * entire component tree.
 *
 * Ported from mmuhofy/IrisCode — app/src/main/kotlin/com/iris/iriscode/IrisCodeApp.kt
 * Adapted for Drosh — dev.drosh
 *
 * Phase 1+ — boots [SessionManagerAdapter] for session reconciliation,
 * then starts [TerminalService] as a foreground service so PTY sessions
 * survive process-level death by the Activity.
 */
@HiltAndroidApp
class DroshApplication : Application() {

    @Inject lateinit var sessionManagerAdapter: SessionManagerAdapter
    @Inject lateinit var settingsStore: SettingsStore
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.applyLocale(base, ""))
    }

    override fun onCreate() {
        super.onCreate()
        // The terminal's clipboard sink needs a context before any session can
        // ask it to write; attaching here is the earliest point it exists.
        ClipboardSink.attach(this)
        // The component store needs a context before the first JNI call; the
        // application is the earliest point it exists.
        NativeLibraries.appContext = this
        // The persisted setting is the authority: a session may be created
        // (and a program may write) before any terminal screen is composed, so
        // the sink's gate is seeded from the store synchronously, then kept in
        // step with it for the life of the process.
        ClipboardSink.allowWrites = settingsStore.settings.value.input.allowClipboardWrites
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            settingsStore.settings
                .map { it.input.allowClipboardWrites }
                .distinctUntilChanged()
                .collect { allowed -> ClipboardSink.allowWrites = allowed }
        }
        sessionManagerAdapter.start()
        ContextCompat.startForegroundService(this, Intent(this, TerminalService::class.java))
    }
}

