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

    /** How long the beep lasts, in milliseconds. */
    private const val BELL_MILLIS = 120

    /** How loud the beep is. */
    private const val BELL_VOLUME = 80

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
            runCatching {
                handler()
                beep()
            }.onFailure { Log.w(TAG, "the bell could not be answered", it) }
        }
    }

    /**
     * The sound of the bell.
     *
     * A terminal's bell is a beep: a short, plain tone that says "something
     * happened" over whatever the program is playing. The system's own tone
     * generator is that sound, and it does not need a file of our own.
     */
    private fun beep() {
        runCatching {
            val tone = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_NOTIFICATION,
                BELL_VOLUME,
            )
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, BELL_MILLIS)
            // The tone generator holds an audio resource; it is released a
            // moment after the tone, not immediately, or the sound is cut.
            main.postDelayed({ runCatching { tone.release() } }, BELL_MILLIS + 200L)
        }.onFailure { Log.w(TAG, "the bell could not beep", it) }
    }

    /**
     * A program asked for a notification.
     *
     * `OSC 9` and `OSC 777`: the program has a title and a body, and the app
     * puts them in a notification. The program is not the system's voice — it
     * is a program on the other side of an ssh connection — so the request is
     * shown as it is.
     */
    @JvmStatic
    fun notifyRequest(title: String, body: String) {
        val handler = onNotify ?: return
        main.post {
            runCatching { handler(title, body) }
                .onFailure { Log.w(TAG, "the notification could not be shown", it) }
        }
    }

    /**
     * Told when a program asks for a notification.
     *
     * Set by the screen, which has the activity the notification is posted
     * with. Called on the main thread.
     */
    @Volatile
    var onNotify: ((title: String, body: String) -> Unit)? = null

    /**
     * Says the bell in a notification.
     *
     * Only for an app that is not on screen: a notification for a bell the
     * user just watched flash is noise.
     */
    fun notify(context: Context, title: String) = notifyWith(context, null, title)

    /**
     * The notification itself.
     *
     * The title is the program's own and the body is what it said; the bell's
     * own title is used when the program gave none.
     */
    fun notifyWith(context: Context, title: String?, body: String) {
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
            .apply {
                if (!title.isNullOrBlank()) {
                    setContentTitle(title)
                    setContentText(body)
                } else {
                    setContentTitle(context.getString(dev.drosh.ui.R.string.terminal_bell_title))
                    setContentText(body)
                }
            }
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "the bell notification failed", it) }
    }
}
