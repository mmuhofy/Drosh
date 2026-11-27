package dev.drosh

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Keeps the CPU and the Wi-Fi radio awake while the user asks for it.
 *
 * Termux's foreground notification offers the same toggle, and for the same
 * reason: a shell in the background is killed by doze the moment the screen
 * goes off, so a long build cannot survive without a held lock. The two locks
 * are always taken and released together — a partial wake lock alone still
 * lets the Wi-Fi radio sleep mid-transfer.
 *
 * Owned by TerminalService: it is created in `onCreate`, toggled by the
 * notification's action, and released in `onDestroy` so nothing outlives the
 * service.
 */
internal class TerminalWakeLock(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var powerLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val lockTag = "${appContext.packageName}:service-wakelock"

    /** True while the pair is held — the notification reads this for its label. */
    val isHeld: Boolean get() = powerLock != null

    /**
     * Takes both locks with no timeout — an arbitrary deadline would end a
     * build the user meant to let run. The second acquire would leak a lock
     * no `release()` can reach, so it does nothing.
     */
    @SuppressLint("WakelockTimeout")
    fun acquire() {
        if (isHeld) return

        val power = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockTag)
        power.acquire()
        powerLock = power

        // FULL_HIGH_PERF is deprecated but is what Termux holds: it keeps the
        // radio at full throughput instead of the low-latency power trade.
        @Suppress("DEPRECATION")
        val wifi = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, lockTag)
        wifi?.acquire()
        wifiLock = wifi

        requestBatteryExemption()
    }

    /** Releases both locks. Safe to call when nothing is held. */
    fun release() {
        powerLock?.let { lock -> if (lock.isHeld) lock.release() }
        powerLock = null
        wifiLock?.let { lock -> if (lock.isHeld) lock.release() }
        wifiLock = null
    }

    /**
     * Asks the system to exempt Drosh from battery optimisation.
     *
     * A held lock only survives doze if the system is allowed to ignore the
     * app — otherwise the optimiser reclaims the CPU anyway and the lock was
     * taken for nothing. The question is the system's own dialog, asked once
     * per acquire and skipped when the exemption already stands; Termux asks
     * the same way. The dialog needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * in the manifest, and a service is not an activity, so the intent
     * carries `FLAG_ACTIVITY_NEW_TASK`.
     */
    private fun requestBatteryExemption() {
        if (powerManager.isIgnoringBatteryOptimizations(appContext.packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            appContext.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            // A ROM without the dialog must not take the service down: the
            // locks are already held, which is what the action asked for.
            Log.w(TAG, "Battery optimisation dialog unavailable: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "Drosh"
    }
}
