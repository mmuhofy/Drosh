package dev.drosh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.drosh.domain.session.PersistentSessionStore
import dev.drosh.domain.session.SessionRepository
import dev.drosh.core.TerminalConstants
import dev.drosh.data.di.ApplicationScope
import dev.drosh.terminal.session.TerminalSessionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.net.Uri

import dev.drosh.data.state.CommandStateBus

import dev.drosh.data.state.CommandStateProvider


@AndroidEntryPoint
class TerminalService : LifecycleService() {

    @Inject
    lateinit var terminalManager: TerminalSessionCoordinator

    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var persistentSessionStore: PersistentSessionStore

    // The service is being torn down; lifecycleScope is being destroyed with it,
    // so the sweep runs on the process-wide scope instead.
    @Inject
    @field:ApplicationScope
    lateinit var appScope: CoroutineScope

    private val binder = LocalBinder()

    /** True once [startForeground] has been called — prevents notify-before-foreground. */
    @Volatile
    private var isForeground = false

    /**
     * The wake/wifi lock pair behind the notification's toggle. Created in
     * [onCreate], not as a field initializer: a Service's context is not
     * attached until after construction, and reading services from it that
     * early would reach a null base.
     */
    private lateinit var wakeLock: TerminalWakeLock

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    override fun onCreate() {
        super.onCreate()
        createTerminalNotificationChannel(this)
        wakeLock = TerminalWakeLock(this)
        observeSessionCount()
        observeCommandRunning()
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
     *
     * Snippets piggyback on the same flow. There is no watcher and no extra
     * loop: the file is re-read here, and only a content change notifies.
     * Editing snippets means running commands (the editor exits, the file
     * changes, the next transition publishes), so the keyboard sees an edit
     * no later than the next command boundary — without any polling.
     */
    private fun publishCommandState() {
        lifecycleScope.launch {
            terminalManager.commandState.state.collectLatest { snapshot ->
                CommandStateBus.publish(
                    terminalManager.activePersistentId().orEmpty(),
                    snapshot,
                    terminalManager.commandActivity.level.value,
                    terminalManager.currentAmbientTint,
                )
                contentResolver.notifyChange(Uri.parse(CommandStateProvider.URI_COMMAND), null)
                publishSnippetsIfChanged()
            }
        }
    }

    private var lastPublishedSnippets: List<dev.drosh.terminal.session.SnippetsStore.Snippet> =
        emptyList()

    private fun publishSnippetsIfChanged() {
        val current = terminalManager.readSnippets()
        if (current == lastPublishedSnippets) return
        lastPublishedSnippets = current
        CommandStateBus.publishSnippets(current)
        contentResolver.notifyChange(Uri.parse(CommandStateProvider.URI_SNIPPETS), null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TerminalConstants.ACTION_STOP -> {
                terminalManager.destroy()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            TerminalConstants.ACTION_WAKE_LOCK -> {
                // One button, two states: the rebuild below flips the label
                // and the icon with the lock.
                if (wakeLock.isHeld) wakeLock.release() else wakeLock.acquire()
                refreshNotification()
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
        // A lock the user left on must not keep the device awake after the
        // service — and with it the sessions it was taken for — is gone.
        wakeLock.release()
        // Leave nothing stale behind: a keyboard querying afterwards would
        // otherwise see the last command of a session that no longer exists.
        CommandStateBus.clear()
        lastPublishedSnippets = emptyList()
        contentResolver.notifyChange(Uri.parse(CommandStateProvider.URI_COMMAND), null)
        contentResolver.notifyChange(Uri.parse(CommandStateProvider.URI_SNIPPETS), null)
        terminalManager.destroy()

        // The user chose the foreground service's death as "the app fully
        // closed": it is the moment the shells are gone and the process has no
        // reason to come back. A recents swipe is deliberately NOT the trigger —
        // it only removes the activity, and the service (and its shells) keeps
        // running. Sweeping there would throw away session history every time
        // the user switched apps, which is the opposite of what a terminal is
        // for. Here, unpinned metadata is removed; pinned rows and their previews
        // survive for the next launch.
        appScope.launch {
            val keep = persistentSessionStore.snapshot()
            sessionRepository.deleteNonPersistentSessions(keep)
        }
        super.onDestroy()
    }

    private fun observeSessionCount() {
        lifecycleScope.launch {
            terminalManager.sessionCountFlow.collectLatest { count ->
                if (!isForeground) return@collectLatest

                refreshNotification()

                if (count == 0 && sessionRepository.shouldExit.value) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf()
                }
            }
        }
    }

    /**
     * Keeps the "Command running" part of the text honest.
     *
     * Only the flips are collected: the snapshot changes on cwd and exit-code
     * updates too, and re-posting the notification for text that did not
     * change would be pure churn. This is the same publisher the keyboard
     * reads, so the notification and the keyboard never disagree.
     */
    private fun observeCommandRunning() {
        lifecycleScope.launch {
            terminalManager.commandState.state
                .map { it.isRunning }
                .distinctUntilChanged()
                .collectLatest { refreshNotification() }
        }
    }

    /**
     * Re-posts the foreground notification from the current session count,
     * command state and wake-lock state.
     *
     * Every input routes through here — the session-count collector, the
     * command flips and the wake-lock toggle — so one builder is the only
     * definition of what the notification says.
     */
    private fun refreshNotification() {
        if (!isForeground) return
        getSystemService<NotificationManager>()
            ?.notify(TerminalConstants.NOTIFICATION_ID, buildNotification(terminalManager.tabCount))
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this, REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val newSessionPending = PendingIntent.getActivity(
            this, REQUEST_NEW_SESSION,
            Intent(this, MainActivity::class.java).apply {
                action = TerminalConstants.ACTION_NEW_SESSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val exitIntent = Intent(this, TerminalService::class.java).apply {
            action = TerminalConstants.ACTION_STOP
        }
        val exitPending = PendingIntent.getService(
            this, REQUEST_EXIT, exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val wakeLockHeld = wakeLock.isHeld
        val wakeToggleIntent = Intent(this, TerminalService::class.java).apply {
            action = TerminalConstants.ACTION_WAKE_LOCK
        }
        val wakeTogglePending = PendingIntent.getService(
            this, REQUEST_WAKE_LOCK, wakeToggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val sessionText = resources.getQuantityString(
            R.plurals.notification_session_count,
            sessionCount,
            sessionCount,
        )
        // Termux appends its state markers to the session count the same way;
        // one line is all the compact view shows, so everything that must be
        // readable at a glance shares it.
        val text = buildString {
            append(sessionText)
            if (terminalManager.commandState.state.value.isRunning) {
                append(" · ")
                append(getString(R.string.notification_command_running))
            }
            if (wakeLockHeld) {
                append(" · ")
                append(getString(R.string.notification_wake_lock_held))
            }
        }

        return NotificationCompat.Builder(this, TerminalConstants.CHANNEL_ID)
            .setContentTitle(getString(R.string.terminal_service_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_notification_new_session,
                getString(R.string.notification_action_new_session),
                newSessionPending,
            )
            // The label and the icon are the lock's state: an open padlock to
            // let go, a closed one to take — the toggle reads at a glance.
            .addAction(
                if (wakeLockHeld) R.drawable.ic_notification_lock_open
                else R.drawable.ic_notification_lock,
                getString(
                    if (wakeLockHeld) R.string.notification_action_wake_release
                    else R.string.notification_action_wake_acquire,
                ),
                wakeTogglePending,
            )
            .addAction(R.drawable.ic_notification, getString(R.string.notification_action_exit), exitPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private companion object {
        /**
         * One PendingIntent identity per action. Sharing a code would let
         * `FLAG_UPDATE_CURRENT` rewrite a sibling's intent, so the buttons
         * could start firing each other's actions.
         */
        const val REQUEST_OPEN_APP = 0
        const val REQUEST_NEW_SESSION = 1
        const val REQUEST_EXIT = 2
        const val REQUEST_WAKE_LOCK = 3
    }
}

/**
 * Creates the foreground service's notification channel — the one place it is
 * defined.
 *
 * Both [TerminalService] and [SetupForegroundService] post on this channel and
 * either can run first, so the creation is shared rather than duplicated: a
 * channel's name and importance are fixed the first time it is created, and
 * two definitions could disagree about them with no way to tell which one won.
 */
internal fun createTerminalNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        TerminalConstants.CHANNEL_ID,
        context.getString(R.string.terminal_service_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    )
    context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
}
