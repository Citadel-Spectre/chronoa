package app.chronoa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rings: [RingService] plays the sound, a high-priority notification brings the ring screen up
 * over the lock screen through its full-screen intent, and [takeOverScreen] opens it over
 * whatever app is in use.
 */
object Ringer {
    private const val ALERT_CHANNEL = "ring_alert"
    private const val QUIET_CHANNEL = "ringing_quiet"
    private const val MISSED_CHANNEL = "missed"
    private const val NOTIFICATION_ID = 1
    const val SERVICE_ID = 4
    private const val MISSED_ID = 6
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = nm(c)
        // The first versions rang through a channel with its own sound; the app plays it now.
        if (nm.getNotificationChannel("ringing") != null) nm.deleteNotificationChannel("ringing")
        if (nm.getNotificationChannel(QUIET_CHANNEL) == null) {
            val quiet = NotificationChannel(QUIET_CHANNEL, c.getString(R.string.channel_ringing_quiet), NotificationManager.IMPORTANCE_LOW)
            quiet.setSound(null, null)
            quiet.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(quiet)
        }
        if (nm.getNotificationChannel(MISSED_CHANNEL) == null) {
            val missed = NotificationChannel(MISSED_CHANNEL, c.getString(R.string.channel_missed), NotificationManager.IMPORTANCE_LOW)
            missed.setSound(null, null)
            nm.createNotificationChannel(missed)
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL) != null) return
        val ch = NotificationChannel(ALERT_CHANNEL, c.getString(R.string.channel_ringing), NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.setBypassDnd(true)
        nm.createNotificationChannel(ch)
    }

    /** False when the user has blocked notifications, which would keep alarms from showing. */
    fun canRing(c: Context): Boolean {
        val nm = nm(c)
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = nm.getNotificationChannel(ALERT_CHANNEL)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    const val EXTRA_RANG_AT = "rang_at"
    const val EXTRA_LABEL = "label"

    private var rangAt = 0L
    private var ringing = false

    private fun ringIntent(c: Context, kind: Int) = Intent(c, RingActivity::class.java)
        .putExtra(Scheduler.EXTRA_KIND, kind)
        .putExtra(EXTRA_RANG_AT, rangAt)
        .putExtra(EXTRA_LABEL, Store.ringLabel(c))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    fun ring(c: Context, kind: Int, done: () -> Unit = {}) {
        ensureChannel(c)
        rangAt = System.currentTimeMillis()
        ringing = true
        nm(c).cancel(MISSED_ID)
        nm(c).notify(NOTIFICATION_ID, alert(c, kind))
        if (!RingService.start(c, kind)) fallbackSound(c)
        // The full-screen intent only takes over a locked or sleeping phone; while it's in use
        // Android shows a heads-up instead. Starting the screen directly covers that case.
        takeOverScreen(c, ringIntent(c, kind), done)
    }

    /**
     * Called once the ring screen is showing. Android keeps an alarm's notification pinned over
     * everything, covering the top of the screen, so take it away. The service's own quiet
     * notification stays, to get back to the screen.
     */
    fun quiet(c: Context) {
        if (ringing) nm(c).cancel(NOTIFICATION_ID)
    }

    /** The heads-up / full-screen notification that brings the ring screen up. Silent: the service plays the sound. */
    @Suppress("DEPRECATION")
    private fun alert(c: Context, kind: Int): Notification {
        val b = base(c, kind, if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ALERT_CHANNEL) else Notification.Builder(c))
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MAX)
        val screen = PendingIntent.getActivity(c, 0, ringIntent(c, kind), FLAGS)
        b.setFullScreenIntent(screen, true)
        return b.build()
    }

    /** The service's ongoing notification: quiet, with Stop / Snooze. */
    @Suppress("DEPRECATION")
    fun serviceNotification(c: Context, kind: Int): Notification {
        ensureChannel(c)
        val b = base(c, kind, if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, QUIET_CHANNEL) else Notification.Builder(c))
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW)
        return b.build()
    }

    private fun base(c: Context, kind: Int, b: Notification.Builder): Notification.Builder {
        val isTimer = kind == Scheduler.KIND_TIMER
        val label = Store.ringLabel(c)
        val title = when {
            isTimer -> c.getString(R.string.times_up)
            label.isNotEmpty() -> label
            else -> c.getString(R.string.alarm)
        }
        b.setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(if (rangAt > 0) rangAt else System.currentTimeMillis())))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(c, 0, ringIntent(c, kind), FLAGS))
        if (!isTimer && canSnooze(c)) b.addAction(0, c.getString(R.string.snooze), action(c, AlarmReceiver.ACTION_SNOOZE))
        // With Hold to dismiss on, an alarm can only be stopped from its screen, by holding.
        if (isTimer || !Store.holdToDismiss(c)) b.addAction(0, c.getString(R.string.stop), action(c, AlarmReceiver.ACTION_STOP))
        return b
    }

    /** Rang for the maximum time without an answer: stop, and leave a note for an alarm. */
    fun timedOut(c: Context, kind: Int) {
        stop(c)
        if (kind == Scheduler.KIND_TIMER) return
        ensureChannel(c)
        val label = Store.ringLabel(c)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, MISSED_CHANNEL) else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(c.getString(R.string.missed_alarm))
            .setContentText(
                listOf(label, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(rangAt)))
                    .filter { it.isNotEmpty() }.joinToString(" · "),
            )
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).putExtra(Timer.EXTRA_TAB, 0), FLAGS),
            )
        nm(c).notify(MISSED_ID, b.build())
    }

    // Only if the service can't start: play from here instead, for as long as the app lives.
    private var fallback: android.media.Ringtone? = null

    @Suppress("DEPRECATION")
    private fun fallbackSound(c: Context) {
        fallback = RingtoneManager.getRingtone(c.applicationContext, RingService.tone(c))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
    }

    /** True when Android lets us open the ring screen over whatever app is in use. */
    fun canTakeOver(c: Context) = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c)

    /**
     * Since Android 10 an app can't open a screen from the background unless it may draw over
     * other apps, and since Android 15 only while one of its overlay windows is showing. So this
     * shows a 1-pixel invisible overlay just long enough to start the ring screen.
     */
    private fun takeOverScreen(c: Context, intent: Intent, done: () -> Unit) {
        fun start() = try {
            c.startActivity(intent)
        } catch (e: RuntimeException) {
            // Blocked: the notification still rings.
        }
        if (Build.VERSION.SDK_INT < 29) {
            start()
            done()
            return
        }
        if (!Settings.canDrawOverlays(c)) {
            done()
            return
        }
        val wm = c.getSystemService(WindowManager::class.java)
        val dot = View(c)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(dot, lp)
        } catch (e: RuntimeException) {
            start()
            done()
            return
        }
        val main = Handler(Looper.getMainLooper())
        // Give the overlay a moment to be drawn, since only a visible one counts.
        main.postDelayed({ start() }, 300)
        main.postDelayed({
            try {
                wm.removeView(dot)
            } catch (e: RuntimeException) {
            }
            // Some phones still refuse to open the screen while another app is in use. Then
            // draw it straight over everything instead. (When locked, the full-screen
            // notification already covers it.)
            val km = c.getSystemService(KeyguardManager::class.java)
            if (ringing && RingActivity.showing == null && !km.isKeyguardLocked) {
                OverlayRing.show(c, intent.getIntExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_ALARM), rangAt)
            }
            done()
        }, 2500)
    }

    /**
     * Stops the ringing. [completed] means it was answered for good (dismissed, stopped or
     * timed out, not snoozed): a delete-after alarm that's now done is removed.
     */
    fun stop(c: Context, completed: Boolean = true) {
        if (completed) finishAlarm(c)
        ringing = false
        OverlayRing.hide(c)
        RingService.stop(c)
        fallback?.stop()
        fallback = null
        nm(c).cancel(NOTIFICATION_ID)
        RingActivity.showing?.finish()
    }

    /** False once the snooze limit in settings has been used up for this ring. */
    fun canSnooze(c: Context): Boolean {
        val limit = Store.snoozeLimit(c)
        return limit == 0 || Store.snoozeCount(c) < limit
    }

    private fun finishAlarm(c: Context) {
        val id = Store.ringAlarmId(c)
        if (id == 0) return
        Store.setRingAlarmId(c, 0)
        val a = Store.alarms(c).firstOrNull { it.id == id } ?: return
        if (a.deleteAfter && (!a.repeats || a.allDone)) {
            Store.removeAlarm(c, id)
            Scheduler.cancel(c, id)
        }
    }

    private fun action(c: Context, action: String) =
        PendingIntent.getBroadcast(c, action.hashCode(), Intent(c, AlarmReceiver::class.java).setAction(action), FLAGS)
}
