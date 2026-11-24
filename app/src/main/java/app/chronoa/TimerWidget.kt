package app.chronoa

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * One-cell home-screen widget in the glowing clock style: the current time, and under it,
 * while a timer runs or an alarm is about to ring, a countdown the launcher's Chronometer
 * keeps by itself (paused: the time left).
 */
class TimerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) = update(c)

    companion object {
        /** Updates both widgets: the one-cell one and the wide one. */
        fun update(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, TimerWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(c, wide = false))
            val wideIds = manager.getAppWidgetIds(ComponentName(c, WideWidget::class.java))
            if (wideIds.isNotEmpty()) manager.updateAppWidget(wideIds, views(c, wide = true))
        }

        fun installed(c: Context) = AppWidgetManager.getInstance(c)
            .getAppWidgetIds(ComponentName(c, TimerWidget::class.java)).isNotEmpty()

        /** Asks the launcher to add the widget, or the wide one, if it supports that (Android 8+). */
        fun requestPin(c: Context, wide: Boolean = false): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val manager = AppWidgetManager.getInstance(c)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val provider = if (wide) WideWidget::class.java else TimerWidget::class.java
            return manager.requestPinAppWidget(ComponentName(c, provider), null, null)
        }

        private fun views(c: Context, wide: Boolean): RemoteViews {
            val v = RemoteViews(c.packageName, if (wide) R.layout.widget_wide else R.layout.widget_timer)

            val now = System.currentTimeMillis()
            val end = Store.timerEnd(c)
            val left = Store.timerLeft(c)
            val alarm = Store.upcomingAt(c).takeIf { it > now } ?: 0L
            val live = Store.widgetCountdown(c)
            // Whichever comes sooner: the running timer, or an alarm about to ring. The
            // Chronometer shows `base - elapsedRealtime` when counting down. With nothing
            // counting, the widget is a clock (TextClock ticks by itself).
            val (shown, running, label) = when {
                live && end > 0 && (alarm == 0L || end <= alarm) -> Triple(end - now, true, R.string.widget_running)
                live && alarm > 0 -> Triple(alarm - now, true, R.string.alarm)
                live && left > 0 -> Triple(left, false, R.string.widget_paused)
                else -> Triple(0L, false, 0)
            }
            val counting = label != 0
            // A tap opens the Alarm tab when it's an alarm counting down (to skip it, say),
            // otherwise the Timer tab.
            val open = PendingIntent.getActivity(
                c, 2,
                Intent(c, MainActivity::class.java).putExtra(Timer.EXTRA_TAB, if (label == R.string.alarm) 0 else 1)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            // Show only the chosen theme's views (see widget_timer.xml).
            val chosen = Theme.widgetIndex(c).coerceIn(0, Theme.ALL.size - 1)
            fun id(name: String, i: Int) = c.resources.getIdentifier("${name}_$i", "id", c.packageName)
            val clock = Store.widgetClock(c)
            // With nothing else to show, the Chronoa logo.
            v.setViewVisibility(R.id.widget_logo, if (!clock && !counting) View.VISIBLE else View.GONE)
            v.setInt(R.id.widget_panel, "setImageAlpha", Store.widgetOpacity(c) * 255 / 100)
            if (wide) {
                wide(c, v, chosen, clock, counting, shown, running, label)
                return v
            }
            for (i in Theme.ALL.indices) {
                val on = i == chosen
                v.setViewVisibility(id("widget_time", i), if (on && counting) View.VISIBLE else View.GONE)
                // The time at the top (unless turned off); a countdown goes under it.
                v.setViewVisibility(id("widget_clock", i), if (on && clock) View.VISIBLE else View.GONE)
                v.setViewVisibility(id("widget_label", i), if (on && counting) View.VISIBLE else View.GONE)
            }
            val time = id("widget_time", chosen)
            if (counting) {
                if (Build.VERSION.SDK_INT >= 24) {
                    v.setChronometerCountDown(time, true)
                    v.setChronometer(time, SystemClock.elapsedRealtime() + shown + 999, null, running)
                } else {
                    // No countdown before Android 7: show the time standing still.
                    v.setChronometer(time, SystemClock.elapsedRealtime() - shown, null, false)
                }
                // An alarm with a name shows its name.
                val name = if (label == R.string.alarm) Store.upcomingLabel(c) else ""
                v.setTextViewText(id("widget_label", chosen), name.ifEmpty { c.getString(label) })
            } else {
                v.setChronometer(time, SystemClock.elapsedRealtime(), null, false)
            }
            return v
        }

        /**
         * The wide widget: idle, one big clock; counting down, the time on the left, a line in
         * the theme's color, and the countdown on the right (just the countdown if the clock is off).
         */
        private fun wide(c: Context, v: RemoteViews, chosen: Int, clock: Boolean, counting: Boolean, shown: Long, running: Boolean, label: Int) {
            fun id(name: String, i: Int) = c.resources.getIdentifier("${name}_$i", "id", c.packageName)
            v.setViewVisibility(R.id.widget_idle, if (!counting && clock) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.widget_row, if (counting) View.VISIBLE else View.GONE)
            for (i in Theme.ALL.indices) {
                val on = i == chosen
                v.setViewVisibility(id("widget_big", i), if (on) View.VISIBLE else View.GONE)
                v.setViewVisibility(id("widget_wclock", i), if (on && clock) View.VISIBLE else View.GONE)
                v.setViewVisibility(id("widget_line", i), if (on && clock) View.VISIBLE else View.GONE)
                v.setViewVisibility(id("widget_wright", i), if (on) View.VISIBLE else View.GONE)
            }
            val time = id("widget_wtime", chosen)
            if (counting) {
                if (Build.VERSION.SDK_INT >= 24) {
                    v.setChronometerCountDown(time, true)
                    v.setChronometer(time, SystemClock.elapsedRealtime() + shown + 999, null, running)
                } else {
                    v.setChronometer(time, SystemClock.elapsedRealtime() - shown, null, false)
                }
                val name = if (label == R.string.alarm) Store.upcomingLabel(c) else ""
                v.setTextViewText(id("widget_wlabel", chosen), name.ifEmpty { c.getString(label) })
            } else {
                v.setChronometer(time, SystemClock.elapsedRealtime(), null, false)
            }
        }
    }
}

/** The same widget two cells wide, with a bigger clock (see [TimerWidget]). */
class WideWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) = TimerWidget.update(c)
}
