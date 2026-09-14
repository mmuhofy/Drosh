package com.iris.irisshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.view.Gravity
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.iris.irisshell.domain.session.SessionRepository
import com.iris.irisshell.terminal.TerminalManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import kotlin.math.min

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

    /**
     * Position in the completion file — only new lines are read each poll.
     */
    private var lastFilePointer = 0L

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    override fun onCreate() {
        super.onCreate()
        setupNotificationChannel()
        setupCommandCompleteChannel()
        ensureCompletionFile()
        observeSessionCount()
        startCompletionMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                terminalManager.destroy()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            null -> {
                val notification = buildNotification(terminalManager.tabCount)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForeground = true
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        terminalManager.destroy()
        super.onDestroy()
    }

    private fun observeSessionCount() {
        lifecycleScope.launch {
            terminalManager.sessionCountFlow.collectLatest { count ->
                if (!isForeground) return@collectLatest

                val nm = getSystemService<NotificationManager>()
                nm?.notify(NOTIFICATION_ID, buildNotification(count))

                if (count == 0 && sessionRepository.shouldExit.value) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf()
                }
            }
        }
    }

    private fun ensureCompletionFile() {
        val file = File(filesDir, COMPLETION_FILE_NAME)
        if (!file.exists()) file.createNewFile()
    }

    /**
     * Polls the completion file every 500 ms for new lines written by
     * the shell's `preexec`/`precmd` hooks. Each line is formatted as:
     *   `command|elapsed_seconds|exit_code`
     */
    private fun startCompletionMonitor() {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(filesDir, COMPLETION_FILE_NAME)
            while (isActive) {
                delay(500)
                if (!isActive) break

                val currentLen = file.length()
                if (currentLen > lastFilePointer) {
                    val raf = RandomAccessFile(file, "r")
                    try {
                        raf.seek(lastFilePointer)
                        val newBytes = min(8192L, currentLen - lastFilePointer).toInt()
                        val buffer = ByteArray(newBytes)
                        raf.readFully(buffer)
                        lastFilePointer = raf.filePointer
                        val content = String(buffer, Charsets.UTF_8)
                        content.lines().filter { it.isNotBlank() }.forEach { line ->
                            parseAndNotify(line)
                        }
                    } catch (_: Exception) {
                        // File might be mid-write; try again next poll.
                    } finally {
                        raf.close()
                    }
                }
            }
        }
    }

    private fun parseAndNotify(line: String) {
        val parts = line.split("|")
        if (parts.size != 3) return

        val command = parts[0].takeIf { it.isNotBlank() } ?: return
        val elapsedSec = parts[1].toIntOrNull() ?: 0
        val exitCode = parts[2].toIntOrNull() ?: 0

        notifyCommandComplete(command, elapsedSec, exitCode)
        showCompletionToast(command, elapsedSec, exitCode)
    }

    private fun notifyCommandComplete(command: String, elapsedSec: Int, exitCode: Int) {
        val (statusText, statusRes) = when {
            exitCode == 0 -> Pair(
                getString(R.string.notification_command_completed, command),
                R.drawable.ic_notification,
            )
            else -> Pair(
                getString(R.string.notification_command_error, command, exitCode),
                R.drawable.ic_notification,
            )
        }
        val durationText = formatDuration(elapsedSec)

        val nm = getSystemService<NotificationManager>()
        nm?.notify(COMMAND_COMPLETE_ID, NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(statusText)
            .setContentText(durationText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        )
    }

    private fun showCompletionToast(command: String, elapsedSec: Int, exitCode: Int) {
        val status = if (exitCode == 0) "Tamamlandı" else "Hata"
        val duration = formatDuration(elapsedSec)
        val text = "$command — $status — $duration"

        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        val density = resources.displayMetrics.density
        toast.setGravity(
            Gravity.TOP or Gravity.END,
            (16 * density).toInt(),
            (112 * density).toInt(),
        )
        toast.show()
    }

    private fun formatDuration(sec: Int): String = when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
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
            action = ACTION_STOP
        }
        val exitPending = PendingIntent.getService(
            this, 0, exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val sessionText = if (sessionCount == 1) {
            getString(R.string.notification_one_session)
        } else {
            getString(R.string.notification_session_count, sessionCount)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
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
                CHANNEL_ID,
                getString(R.string.terminal_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService<NotificationManager>()
            nm?.createNotificationChannel(channel)
        }
    }

    private fun setupCommandCompleteChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                COMMAND_CHANNEL_ID,
                getString(R.string.command_complete_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.enableVibration(true)
            val nm = getSystemService<NotificationManager>()
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1337
        const val CHANNEL_ID = "iris_terminal_service"
        const val COMMAND_CHANNEL_ID = "iris_command_complete"
        const val COMMAND_COMPLETE_ID = 1338
        const val COMPLETION_FILE_NAME = "iris_cmd_complete"
        const val ACTION_STOP = "com.iris.irisshell.action.STOP_SERVICE"
    }
}
