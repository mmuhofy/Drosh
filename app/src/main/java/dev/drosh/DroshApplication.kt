package dev.drosh

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.drosh.data.session.SessionManagerAdapter
import dagger.hilt.android.HiltAndroidApp
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

    override fun onCreate() {
        super.onCreate()
        sessionManagerAdapter.start()
        ContextCompat.startForegroundService(this, Intent(this, TerminalService::class.java))
    }
}

