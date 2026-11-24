package app.chronoa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Woken by the system when an alarm, snooze or timer is due, and by the notification buttons. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> {
                Ringer.stop(c)
                return
            }
            ACTION_SNOOZE -> {
                if (!Ringer.canSnooze(c)) return
                Ringer.stop(c, completed = false)
                Scheduler.snooze(c)
                return
            }
            Timer.ACTION_PAUSE -> return Timer.pause(c)
            Timer.ACTION_RESUME -> return Timer.resume(c)
            Timer.ACTION_RESET -> return Timer.reset(c)
            Upcoming.ACTION_WAKE -> return Upcoming.refresh(c)
            Upcoming.ACTION_SKIP -> return Upcoming.skip(c, intent.getIntExtra(Scheduler.EXTRA_ID, -1))
        }

        val kind = intent.getIntExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_ALARM)
        when (kind) {
            Scheduler.KIND_ALARM -> {
                val id = intent.getIntExtra(Scheduler.EXTRA_ID, 0)
                val a = Store.alarms(c).firstOrNull { it.id == id } ?: return
                if (!a.enabled) return
                if (a.repeats) {
                    // On to its next day; a skip that has now passed is done with. Today counts
                    // as done for a delete-after alarm.
                    val today = 1 shl Scheduler.weekday(java.util.Calendar.getInstance())
                    val next = a.copy(skip = 0, rung = if (a.deleteAfter) a.rung or today else a.rung)
                    Store.putAlarm(c, next)
                    // Look a minute ahead so this same minute isn't picked again.
                    Scheduler.schedule(c, next, System.currentTimeMillis() + 60_000)
                } else {
                    Store.putAlarm(c, a.copy(enabled = false))
                }
                Store.setRingLabel(c, a.label)
                Store.setRingAlarmId(c, a.id)
                Store.setRingTone(c, a.tone)
                // A fresh ring, so the snooze count starts over.
                Store.setSnoozeCount(c, 0)
            }
            Scheduler.KIND_TIMER -> {
                Timer.rang(c)
                Store.setRingLabel(c, "")
                Store.setRingAlarmId(c, 0)
                Store.setRingTone(c, "")
            }
            Scheduler.KIND_SNOOZE -> {
                Store.setSnoozeAt(c, 0)
                Store.setRingLabel(c, Store.snoozeLabel(c))
                Store.setRingAlarmId(c, Store.snoozeAlarmId(c))
                Store.setRingTone(c, Store.snoozeTone(c))
            }
        }
        // On to whatever rings next.
        if (kind != Scheduler.KIND_TIMER) Upcoming.refresh(c)
        // Whatever rang, the widget stops counting down to it.
        TimerWidget.update(c)
        // Keep the process awake while the ring screen is being brought up.
        val pending = goAsync()
        Ringer.ring(c, kind) { pending.finish() }
    }

    companion object {
        const val ACTION_STOP = "app.chronoa.STOP"
        const val ACTION_SNOOZE = "app.chronoa.SNOOZE"
    }
}
