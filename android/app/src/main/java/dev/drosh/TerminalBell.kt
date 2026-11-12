package dev.drosh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * The terminal's bell.
 *
 * ## Who calls it
 *
 * The engine calls it when a program rings the bell — the BEL character, or
 * one of the sequences that mean the same thing. The call arrives on the
 * session's PTY reader thread, which is why nothing here touches the screen
 * directly: the answer is posted to the main thread.
 *
 * ## What the answer is
 *
 * The terminal flashes, the phone buzzes, and when the app is not on screen
 * the bell is a notification: a program that says something while the user is
 * looking at something else has to be able to say it.
 */
object TerminalBell {

    private const val TAG = "TerminalBell"

    /** The channel the bell's notifications live in. */
    private const val CHANNEL_ID = "drosh_terminal_bell"

    /** The notification's id; one at a time is enough. */
    private const val NOTIFICATION_ID = 4101

    private val main = Handler(Looper.getMainLooper())

    /**
     * Told when a program rang the bell.
     *
     * Set by the screen, which knows the focused pane and whether the app is on
     * screen. Called on the main thread.
     */
    @Volatile
    var onBell: (() -> Unit)? = null

    /**
     * The bell, rung.
     *
     * `@JvmStatic` is what makes the method reachable as
     * `dev.drosh.TerminalBell.ring()`, the signature the native bridge calls.
     */
    @JvmStatic
    fun ring() {
        val handler = onBell ?: return
        main.post {
            runCatching { handler() }
                .onFailure { Log.w(TAG, "the bell could not be answered", it) }
        }
    }

    /**
     * Says the bell in a notification.
     *
     * Only for an app that is not on screen: a notification for a bell the
     * user just watched flash is noise.
     */
    fun notify(context: Context, title: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(dev.drosh.ui.R.string.terminal_bell_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            manager.createNotificationChannel(channel)
        }

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = open?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(dev.drosh.ui.R.string.terminal_bell_title))
            .setContentText(title)
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "the bell notification failed", it) }
    }
}
