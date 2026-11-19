package dev.drosh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.drosh.domain.terminal.BackgroundSetup
import dev.drosh.domain.terminal.RootfsDownloadProgress
import dev.drosh.linux.BootstrapStatePort
import dev.drosh.linux.UbuntuSetupState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the Ubuntu setup while the Activity is gone.
 *
 * A foreground service, not an Activity coroutine: on API 26+ a long download
 * started from the UI is killed with the process as soon as the app is not
 * visible, and `START_STICKY` lets the system bring this one back. It is a
 * *separate* service from [TerminalService] on purpose — that one destroys the
 * terminal manager and sweeps session metadata in `onDestroy()`, and a setup
 * restart must never do either.
 *
 * The work itself lives in the process-scoped [BootstrapStatePort], so a
 * recreated setup screen observes the same download through the same flows.
 */
@AndroidEntryPoint
class SetupForegroundService : LifecycleService() {

    @Inject lateinit var port: BootstrapStatePort

    @Volatile private var setupJob: Job? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BackgroundSetup.ACTION_CANCEL -> cancelSetup()
            else -> {
                // A null intent means the system restarted us after process
                // death. Resume: the `.part` file is still on disk and the
                // bootstrap re-HEADs before choosing where to continue.
                if (setupJob?.isActive != true) startSetup()
            }
        }
        // Setup owns no sessions, so a restart cannot sweep anything.
        return START_STICKY
    }

    private fun startSetup() {
        if (!isForeground) {
            val notification = indeterminateNotification(
                dev.drosh.ui.R.string.setup_installing,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    SETUP_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(SETUP_NOTIFICATION_ID, notification)
            }
            isForeground = true
        }
        setupJob?.cancel()
        setupJob = lifecycleScope.launch {
            // The UI reads the same flow; this collector only drives the
            // notification and uses tryEmit-style, non-blocking updates.
            val progressJob = launch {
                port.rootfsDownload.collect { sample -> publishProgress(sample) }
            }
            val state = port.runBootstrap()
            progressJob.cancel()
            when (state) {
                is UbuntuSetupState.Ready -> {
                    publishMessage(dev.drosh.ui.R.string.setup_complete)
                    delay(SETUP_COMPLETE_VISIBLE_MS)
                }
                is UbuntuSetupState.Failed -> {
                    publishMessage(dev.drosh.ui.R.string.setup_failed_short)
                    delay(SETUP_COMPLETE_VISIBLE_MS)
                }
                else -> Unit
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    private fun cancelSetup() {
        setupJob?.cancel()
        setupJob = null
        publishMessage(dev.drosh.ui.R.string.setup_cancelled)
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun publishProgress(sample: RootfsDownloadProgress) {
        if (!isForeground) return
        val text = if (sample.totalBytes > 0L) {
            getString(
                dev.drosh.ui.R.string.setup_download_progress,
                sample.downloadedBytes / BYTES_PER_MB,
                sample.totalBytes / BYTES_PER_MB,
                sample.bytesPerSecond / BYTES_PER_MB,
            )
        } else {
            getString(
                dev.drosh.ui.R.string.setup_download_progress_unknown,
                sample.downloadedBytes / BYTES_PER_MB,
                sample.bytesPerSecond / BYTES_PER_MB,
            )
        }
        val builder = baseBuilder().setContentText(text)
        if (sample.totalBytes > 0L) {
            builder.setProgress(
                sample.totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                sample.downloadedBytes.coerceAtMost(sample.totalBytes)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                false,
            )
        } else {
            builder.setProgress(0, 0, true)
        }
        notify(builder.build())
    }

    private fun publishMessage(textRes: Int) {
        if (!isForeground) return
        notify(
            baseBuilder()
                .setContentText(getString(textRes))
                .setProgress(0, 0, true)
                .build(),
        )
    }

    private fun indeterminateNotification(textRes: Int): android.app.Notification =
        baseBuilder()
            .setContentText(getString(textRes))
            .setProgress(0, 0, true)
            .build()

    private fun baseBuilder(): NotificationCompat.Builder {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TerminalConstants.CHANNEL_ID)
            .setContentTitle(getString(dev.drosh.ui.R.string.setup_service_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
    }

    private fun notify(notification: android.app.Notification) {
        getSystemService<NotificationManager>()
            ?.notify(SETUP_NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(TerminalConstants.CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                TerminalConstants.CHANNEL_ID,
                getString(R.string.terminal_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private companion object {
        const val SETUP_NOTIFICATION_ID = 4201
        const val BYTES_PER_MB = 1_000_000f
        const val SETUP_COMPLETE_VISIBLE_MS = 1_500L
    }
}
