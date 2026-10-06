package dev.drosh.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import dev.drosh.MainActivity
import dev.drosh.R
import dev.drosh.core.TerminalConstants
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.terminal.TerminalManager
import javax.inject.Inject

/**
 * Shows one terminal pane in a window above every other app.
 *
 * ## Why a service, and not a Compose overlay
 *
 * A window above other apps can only be added by something the system considers
 * long-lived: an app cannot draw outside its own bounds once backgrounded. So this
 * is a foreground service holding a `TYPE_APPLICATION_OVERLAY` view.
 *
 * The obvious worry with a Compose app is that this window has no composition to
 * live in. A service window has no `LifecycleOwner` and no `ViewModelStore`, and
 * anything built on a `Recomposer` needs its frame loop torn down by hand or the
 * process leaks a frame loop for as long as it lives.
 *
 * That worry does not apply here, and avoiding it is why the overlay is built from
 * plain Views ([FloatingPaneOverlayView]). A pane's content is a `TerminalView` — an
 * ordinary Android View. The overlay needs a title bar, not a composition, so there
 * is nothing here that ever recomposes: the tree is built once and thereafter only
 * repositioned. No composition means no lifecycle to own and nothing to leak, which
 * is the difference between this being plausible and being a source of hangs.
 *
 * ## Why the pane is handed over rather than shown twice
 *
 * A `TerminalView` holds exactly one session, and attaching a session to a second
 * view resets that view's emulator — two views on one session fight, and neither
 * is right. So the pane's view is *moved*, not copied:
 * [TerminalManager.registerPaneView] installs this window's view for the slot and
 * drops whatever was there, which is the entire handover in one call. The session
 * stays owned by the terminal layer throughout; only the View changes windows.
 *
 * ## Input: tap to focus
 *
 * A pane that cannot be typed into is a wallpaper, and one that always holds the
 * keyboard stops being floating — the app underneath could not be used. So the
 * overlay does not focus itself on appearing, and takes the keyboard only when the
 * user asks. See [FloatingPaneOverlayView.setFocused].
 */
@AndroidEntryPoint
class FloatingPaneOverlayService : LifecycleService() {

    @Inject
    lateinit var terminalManager: TerminalManager

    private var overlayView: FloatingPaneOverlayView? = null
    private var slot: PaneSlot? = null

    /**
     * The window manager the overlay view lives in.
     *
     * Held in a field rather than reached through [getSystemService] at each use:
     * `addView` and `removeView` are the whole point of this service, and having
     * them be three calls away invites someone to reach for a wrong one.
     */
    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == TerminalConstants.ACTION_STOP_OVERLAY) {
            dismiss()
            return START_NOT_STICKY
        }

        // Promoted before anything else. A service asked to start has a few seconds
        // to reach the foreground before the system kills it, and the window below
        // needs the service to be foreground to be valid at all.
        startInForeground()

        val target = intent?.getStringExtra(FloatingPaneOverlayView.EXTRA_SLOT)
            ?.let { name -> PaneSlot.entries.firstOrNull { it.name == name } }

        if (target == null || !Settings.canDrawOverlays(this)) {
            // No slot means no pane to show; no permission means no window to show it
            // in. Either way the service stays foreground rather than stopping:
            // stopping silently would leave the layout claiming SYSTEM_OVERLAY with
            // nothing on screen and no way to tell why.
            return START_NOT_STICKY
        }

        show(target)
        return START_NOT_STICKY
    }

    /**
     * Adds the window and hands the pane to it.
     *
     * Idempotent: the activity can ask for the same pane again while the service is
     * already up, and being asked twice is not a reason to stack a second window
     * over the first.
     */
    private fun show(target: PaneSlot) {
        if (overlayView != null) return

        val host = FloatingPaneOverlayView(this, terminalManager, target)
        host.onClose = { dismiss() }
        host.setTitle(getString(R.string.overlay_pane_title))

        // Added before the handover. If the window cannot be added — permission
        // revoked between the check and here, or the display went away — there is no
        // view to hand the pane to, and handing it to a view that was never added
        // would move the pane off screen and out of reach.
        try {
            windowManager.addView(host, buildLayoutParams())
        } catch (e: WindowManager.BadTokenException) {
            dismiss()
            return
        } catch (e: WindowManager.InvalidDisplayException) {
            dismiss()
            return
        }

        // The handover. Replaces the activity's view for this slot; the layout now
        // says SYSTEM_OVERLAY so the activity's composition stops building one.
        terminalManager.registerPaneView(target, host.terminalView, this)
        overlayView = host
        slot = target
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(FloatingPaneOverlayView.DEFAULT_WIDTH_DP),
            dp(FloatingPaneOverlayView.DEFAULT_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Deliberately no FLAG_NOT_FOCUSABLE: without it the pane could never be
            // typed into. Not focusing on appearance is what keeps that affordable —
            // the window can be focusable and still leave the keyboard alone until
            // asked. FLAG_WATCH_OUTSIDE_TOUCH is what lets a touch outside the pane
            // dismiss it as unfocused rather than leave a stale keyboard claim on an
            // app the user has walked away from.
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    /**
     * Tears down the window and the service.
     *
     * Hands the pane back before removing the view. Otherwise the terminal layer is
     * left holding a view that no longer exists, and the activity cannot redraw the
     * pane because nothing told it to.
     */
    private fun dismiss() {
        releasePane()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePane() {
        overlayView?.let { host ->
            host.setFocused(false)
            slot?.let { terminalManager.unregisterPaneView(it) }
            runCatching { windowManager.removeView(host) }
        }
        overlayView = null
        slot = null
    }

    override fun onDestroy() {
        // Also runs when the system kills the service, so it cannot assume
        // onStartCommand already cleaned up. Tolerates following a [dismiss].
        releasePane()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        try {
            startForeground(
                TerminalConstants.OVERLAY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE arrived in API 34 and a call
            // carrying it throws below that rather than degrading. Foreground is
            // required either way, so fall back to the two-argument form.
            startForeground(TerminalConstants.OVERLAY_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, FloatingPaneOverlayService::class.java)
                .setAction(TerminalConstants.ACTION_STOP_OVERLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TerminalConstants.OVERLAY_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.overlay_notification_stop),
                stop,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TerminalConstants.OVERLAY_CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            // LOW, because the notification exists because a foreground service needs
            // one — not because the pane needs attention. Visible, silent.
            NotificationManager.IMPORTANCE_LOW,
        )
        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private companion object {
        const val REQUEST_OPEN = 0
        const val REQUEST_STOP = 1
    }
}