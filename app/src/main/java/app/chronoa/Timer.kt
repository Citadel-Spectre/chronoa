package app.chronoa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Starting, pausing and resetting the countdown, from the Timer tab, the ring screen or the
 * notification. While a timer runs or is paused it keeps a silent notification counting down;
 * on Android 16+ that is promoted to a Live Update, which shows as a chip in the status bar.
 */
object Timer {
    const val CHANNEL = "timer_running"
    private const val NOTIFICATION_ID = 2
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    const val ACTION_PAUSE = "app.chronoa.TIMER_PAUSE"
    const val ACTION_RESUME = "app.chronoa.TIMER_RESUME"
    const val ACTION_RESET = "app.chronoa.TIMER_RESET"
    const val EXTRA_TAB = "tab"

    fun start(c: Context, ms: Long) {
        if (ms <= 0) return
        val end = System.currentTimeMillis() + ms
        Store.setTimer(c, end, 0)
        Scheduler.scheduleTimer(c, end)
        show(c)
    }

    fun pause(c: Context) {
        val end = Store.timerEnd(c)
        if (end <= 0) return
        Store.setTimer(c, 0, maxOf(end - System.currentTimeMillis(), 1000))
        Scheduler.cancelTimer(c)
        show(c)
    }

    fun resume(c: Context) {
        val left = Store.timerLeft(c)
        if (left > 0) start(c, left)
    }

    fun reset(c: Context) {
        Store.setTimer(c, 0, 0)
        Scheduler.cancelTimer(c)
        hide(c)
    }

    /** The timer went off: it's no longer running, and the ring screen takes over. */
    fun rang(c: Context) {
        Store.setTimer(c, 0, 0)
        hide(c)
    }

    fun hide(c: Context) {
        nm(c).cancel(NOTIFICATION_ID)
        TimerWidget.update(c)
    }

    /** Posts or refreshes the countdown notification to match the stored state. */
    @Suppress("DEPRECATION")
    fun show(c: Context) {
        val end = Store.timerEnd(c)
        val left = Store.timerLeft(c)
        if (end <= 0 && left <= 0 || !Store.statusCountdown(c)) return hide(c)
        TimerWidget.update(c)
        ensureChannel(c)

        val open = PendingIntent.getActivity(
            c, 1,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_TAB, 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            FLAGS,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(c.getString(R.string.timer))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW)

        if (end > 0) {
            // Running: the system counts down to `end` by itself, no updates needed.
            b.setWhen(end).setShowWhen(true)
            if (Build.VERSION.SDK_INT >= 24) {
                b.setUsesChronometer(true).setChronometerCountDown(true)
            }
            b.setContentText(c.getString(R.string.timer_ends_at, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(end))))
            b.addAction(0, c.getString(R.string.pause), action(c, ACTION_PAUSE))
        } else {
            b.setShowWhen(false)
            b.setContentText(c.getString(R.string.timer_paused, formatLeft(left)))
            b.addAction(0, c.getString(R.string.resume), action(c, ACTION_RESUME))
        }
        b.addAction(0, c.getString(R.string.reset), action(c, ACTION_RESET))
        promote(b, if (end > 0) null else formatLeft(left))
        nm(c).notify(NOTIFICATION_ID, b.build())
    }

    /**
     * Android 16+: ask for a Live Update, shown as a status-bar chip. The chip counts down by
     * itself while running; when paused it shows the time left. These calls are made by name so
     * the app still builds against an older SDK, and are skipped where they don't exist.
     */
    fun promote(b: Notification.Builder, chipText: String?) {
        if (Build.VERSION.SDK_INT < 36) return
        try {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(b, true)
            if (chipText != null) {
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", String::class.java)
                    .invoke(b, chipText)
            }
        } catch (e: ReflectiveOperationException) {
            // Not available on this build: the regular notification still counts down.
        }
    }

    private fun formatLeft(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) {
            String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        } else {
            String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }
    }

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = nm(c)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, c.getString(R.string.channel_timer), NotificationManager.IMPORTANCE_LOW)
        ch.setSound(null, null)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun action(c: Context, action: String) =
        PendingIntent.getBroadcast(c, action.hashCode(), Intent(c, AlarmReceiver::class.java).setAction(action), FLAGS)
}
