package dev.drosh

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.drosh.core.LocaleHelper
import dev.drosh.data.local.irisShellDataStore
import dev.drosh.data.session.SessionManagerAdapter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

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

    private val localeKey = stringPreferencesKey("locale")

    @Inject lateinit var sessionManagerAdapter: SessionManagerAdapter

    override fun attachBaseContext(base: Context) {
        val language = runBlocking(Dispatchers.IO) {
            base.irisShellDataStore.data.map { prefs -> prefs[localeKey] ?: "" }.first()
        }
        super.attachBaseContext(LocaleHelper.applyLocale(base, language))
    }

    override fun onCreate() {
        super.onCreate()
        sessionManagerAdapter.start()
        ContextCompat.startForegroundService(this, Intent(this, TerminalService::class.java))
    }
}

