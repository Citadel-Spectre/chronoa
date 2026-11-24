package app.chronoa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms alarms after a reboot, an app update, or a change of clock or time zone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Scheduler.restoreAll(c)
        Timer.show(c)
    }
}
