package app.chronoa

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Arms and disarms the system alarms that wake [AlarmReceiver]. */
object Scheduler {
    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val KIND_ALARM = 0
    const val KIND_TIMER = 1
    const val KIND_SNOOZE = 2


    // Alarms use their own id as request code; ids start at 1, so these never collide.
    private const val REQ_TIMER = -1
    private const val REQ_SNOOZE = -2

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canExact(c: Context) = Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    /**
     * The next time after [from] that the alarm rings: its clock time on the next of its
     * weekdays (any day for a one-time alarm), passing over a ring it has been told to skip.
     */
    fun nextTrigger(a: Alarm, from: Long = System.currentTimeMillis()): Long {
        val t = occurrence(a, from)
        return if (a.skip != 0L && t == a.skip) occurrence(a, t) else t
    }

    /** The next time after [from] that the alarm's clock time falls on one of its days. */
    fun occurrence(a: Alarm, from: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        cal.set(Calendar.HOUR_OF_DAY, a.hour)
        cal.set(Calendar.MINUTE, a.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (a.repeats) {
            while (a.days and (1 shl weekday(cal)) == 0) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Monday = 0 to Sunday = 6. */
    fun weekday(cal: Calendar) = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    private fun operation(c: Context, req: Int, kind: Int, id: Int): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(c, req, i, FLAGS)
    }

    private fun setAt(c: Context, at: Long, op: PendingIntent, alarmClock: Boolean) {
        val am = am(c)
        try {
            if (canExact(c)) {
                if (alarmClock) {
                    // Shows in the status bar and lock screen, and is never delayed by Doze.
                    val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), FLAGS)
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
                } else if (Build.VERSION.SDK_INT >= 23) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, at, op)
                }
                return
            }
        } catch (e: SecurityException) {
            // Exact alarm permission was revoked; fall through to the best we can do.
        }
        if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    fun schedule(c: Context, a: Alarm, from: Long = System.currentTimeMillis()) {
        val op = operation(c, a.id, KIND_ALARM, a.id)
        if (a.enabled) setAt(c, nextTrigger(a, from), op, true) else am(c).cancel(op)
        Upcoming.refresh(c)
    }

    fun cancel(c: Context, id: Int) {
        am(c).cancel(operation(c, id, KIND_ALARM, id))
        Upcoming.refresh(c)
    }

    fun scheduleTimer(c: Context, end: Long) =
        setAt(c, end, operation(c, REQ_TIMER, KIND_TIMER, 0), false)

    fun cancelTimer(c: Context) = am(c).cancel(operation(c, REQ_TIMER, KIND_TIMER, 0))

    fun snooze(c: Context) {
        val at = System.currentTimeMillis() + Store.snoozeMinutes(c) * 60_000L
        Store.setSnoozeAt(c, at)
        Store.setSnoozeLabel(c, Store.ringLabel(c))
        Store.setSnoozeAlarmId(c, Store.ringAlarmId(c))
        Store.setSnoozeTone(c, Store.ringTone(c))
        Store.setSnoozeCount(c, Store.snoozeCount(c) + 1)
        setAt(c, at, operation(c, REQ_SNOOZE, KIND_SNOOZE, 0), true)
        Upcoming.refresh(c)
    }

    fun cancelSnooze(c: Context) {
        Store.setSnoozeAt(c, 0)
        am(c).cancel(operation(c, REQ_SNOOZE, KIND_SNOOZE, 0))
    }

    /** Re-arms everything. The system forgets alarms on reboot, and clock changes move them. */
    fun restoreAll(c: Context) {
        Store.alarms(c).forEach { schedule(c, it) }
        // Anything that came due while the phone was off rings right away.
        val soon = System.currentTimeMillis() + 1000
        val end = Store.timerEnd(c)
        if (end > 0) scheduleTimer(c, maxOf(end, soon))
        val snooze = Store.snoozeAt(c)
        if (snooze > 0) setAt(c, maxOf(snooze, soon), operation(c, REQ_SNOOZE, KIND_SNOOZE, 0), true)
        Upcoming.refresh(c)
    }
}
