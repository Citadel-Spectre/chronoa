package app.chronoa

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The countdown before the next alarm: from a few minutes before it rings (5 or 10, a setting)
 * it shows in the status bar and on the widget, the same way a running timer does. Snoozed
 * alarms count down too. [refresh] works out what's next and is called whenever alarms change.
 */
object Upcoming {
    private const val NOTIFICATION_ID = 3
    private const val REQ_WAKE = -3
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    const val ACTION_WAKE = "app.chronoa.UPCOMING"
    const val ACTION_SKIP = "app.chronoa.SKIP"

    /** The next thing to ring: an enabled alarm (its id) or the snooze (id 0), and when. */
    private fun next(c: Context): Pair<Int, Long>? {
        val now = System.currentTimeMillis()
        val alarms = Store.alarms(c).filter { it.enabled }.map { it.id to Scheduler.nextTrigger(it) }
        val snooze = Store.snoozeAt(c).takeIf { it > now }?.let { listOf(0 to it) }.orEmpty()
        return (alarms + snooze).minByOrNull { it.second }
    }

    /** Shows the countdown if the next alarm is close, otherwise sets a wake-up for when it will be. */
    fun refresh(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val wake = PendingIntent.getBroadcast(c, REQ_WAKE, Intent(c, AlarmReceiver::class.java).setAction(ACTION_WAKE), FLAGS)
        am.cancel(wake)

        val next = next(c)
        val lead = Store.alarmLeadMinutes(c) * 60_000L
        val now = System.currentTimeMillis()
        if (next == null) {
            clear(c)
            return
        }
        val (id, at) = next
        if (at - now > lead) {
            clear(c)
            // Come back when the countdown should start. Inexact is fine for this.
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - lead, wake)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at - lead, wake)
            }
            return
        }
        Store.setUpcomingAt(c, at)
        // Come back just after it's due, whatever happens at the ring, so the widget's
        // countdown can never run on past zero into "time since".
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 2_000, wake)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at + 2_000, wake)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at + 2_000, wake)
        }
        // Its name, if it has one: a snooze carries the name of the alarm it came from.
        Store.setUpcomingLabel(c, if (id == 0) Store.snoozeLabel(c) else Store.alarms(c).firstOrNull { it.id == id }?.label.orEmpty())
        TimerWidget.update(c)
        if (Store.statusCountdown(c)) show(c, id, at) else nm(c).cancel(NOTIFICATION_ID)
    }

    /** Skips the coming ring: a snooze is dropped, a one-time alarm turned off, a repeating one skips a day. */
    fun skip(c: Context, id: Int) {
        if (id == 0) {
            Scheduler.cancelSnooze(c)
        } else {
            val a = Store.alarms(c).firstOrNull { it.id == id } ?: return refresh(c)
            val at = Scheduler.nextTrigger(a)
            val day = 1 shl Scheduler.weekday(java.util.Calendar.getInstance().apply { timeInMillis = at })
            val next = when {
                !a.repeats -> a.copy(enabled = false)
                // A skipped day counts as done for a delete-after alarm.
                a.deleteAfter -> a.copy(skip = at, rung = a.rung or day)
                else -> a.copy(skip = at)
            }
            if (next.deleteAfter && (next.allDone || !next.repeats)) {
                Store.removeAlarm(c, id)
                Scheduler.cancel(c, id)
                return refresh(c)
            }
            Store.putAlarm(c, next)
            Scheduler.schedule(c, next)
        }
        refresh(c)
    }

    private fun clear(c: Context) {
        nm(c).cancel(NOTIFICATION_ID)
        if (Store.upcomingAt(c) != 0L) {
            Store.setUpcomingAt(c, 0)
            TimerWidget.update(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun show(c: Context, id: Int, at: Long) {
        Timer.ensureChannel(c)
        val open = PendingIntent.getActivity(
            c, 3,
            Intent(c, MainActivity::class.java).putExtra(Timer.EXTRA_TAB, 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            FLAGS,
        )
        val skip = PendingIntent.getBroadcast(
            c, ACTION_SKIP.hashCode(),
            Intent(c, AlarmReceiver::class.java).setAction(ACTION_SKIP).putExtra(Scheduler.EXTRA_ID, id),
            FLAGS,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, Timer.CHANNEL) else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(Store.upcomingLabel(c).ifEmpty { c.getString(if (id == 0) R.string.snoozed_alarm else R.string.alarm) })
            .setContentText(c.getString(R.string.rings_at, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setWhen(at)
            .setShowWhen(true)
            // Gone by itself once it's time; the ring takes over from there.
            .addAction(0, c.getString(R.string.skip), skip)
        if (Build.VERSION.SDK_INT >= 24) b.setUsesChronometer(true).setChronometerCountDown(true)
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(maxOf(at - System.currentTimeMillis(), 1000))
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW)
        Timer.promote(b, null)
        nm(c).notify(NOTIFICATION_ID, b.build())
    }

    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
