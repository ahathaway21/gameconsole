package app.healthprofiler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fired by AlarmManager every few minutes (also while the phone is idle). */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = WatchService.instance
        if (svc != null) svc.tick()
        else try {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        } catch (_: Exception) { /* Android may refuse from the background; the app restarts it when opened. */ }
    }
}

/** Restart monitoring after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.address(context) == null || !WatchService.hasBtPermission(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        } catch (_: Exception) {}
    }
}
