package dev.drosh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.drosh.domain.session.SessionRepository
import dev.drosh.core.TerminalConstants
import dev.drosh.terminal.TerminalManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.net.Uri

import dev.drosh.data.state.CommandStateBus

import dev.drosh.data.state.CommandStateProvider


@AndroidEntryPoint
class TerminalService : LifecycleService() {

    @Inject
    lateinit var terminalManager: TerminalManager

    @Inject
    lateinit var sessionRepository: SessionRepository

    private val binder = LocalBinder()

    /** True once [startForeground] has been called — prevents notify-before-foreground. */
    @Volatile
    private var isForeground = false

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    override fun onCreate() {
        super.onCreate()
        setupNotificationChannel()
        observeSessionCount()
        publishCommandState()
    }

    /**
     * Feeds the command lifecycle to [CommandStateBus], which
     * `CommandStateProvider` serves to Drosh Keyboard.
     *
     * Written here rather than collected by the provider because the provider
     * is created by the framework and has no access to the terminal. The
     * notification on every change is what lets the keyboard observe instead
     * of polling; it fires at most a few times per command.
     */
    private fun publishCommandState() {
        lifecycleScope.launch {
            terminalManager.commandState.state.collectLatest { snapshot ->
                CommandStateBus.publish(
                    terminalManager.activePersistentId().orEmpty(),
                    snapshot,
                )
                contentResolver.notifyChange(Uri.parse(CommandStateProvider.URI_COMMAND), null)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TerminalConstants.ACTION_STOP -> {
                terminalManager.destroy()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            null -> {
                val notification = buildNotification(terminalManager.tabCount)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        TerminalConstants.NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } else {
                    startForeground(TerminalConstants.NOTIFICATION_ID, notification)
                }
                isForeground = true
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        // Leave nothing stale behind: a keyboard querying afterwards would
        // otherwise see the last command of a session that no longer exists.
        CommandStateBus.clear()
        contentResolver.notifyChange(Uri.parse(CommandStateProvider.URI_COMMAND), null)
        terminalManager.destroy()
        super.onDestroy()
    }

    private fun observeSessionCount() {
        lifecycleScope.launch {
            terminalManager.sessionCountFlow.collectLatest { count ->
                if (!isForeground) return@collectLatest

                val nm = getSystemService<NotificationManager>()
                nm?.notify(TerminalConstants.NOTIFICATION_ID, buildNotification(count))

                if (count == 0 && sessionRepository.shouldExit.value) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val exitIntent = Intent(this, TerminalService::class.java).apply {
            action = TerminalConstants.ACTION_STOP
        }
        val exitPending = PendingIntent.getService(
            this, 0, exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val sessionText = resources.getQuantityString(
            R.plurals.notification_session_count,
            sessionCount,
            sessionCount,
        )

        return NotificationCompat.Builder(this, TerminalConstants.CHANNEL_ID)
            .setContentTitle(getString(R.string.terminal_service_name))
            .setContentText(sessionText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.notification_action_exit), exitPending)
            .setOngoing(true)
            .build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TerminalConstants.CHANNEL_ID,
                getString(R.string.terminal_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService<NotificationManager>()
            nm?.createNotificationChannel(channel)
        }
    }


}
