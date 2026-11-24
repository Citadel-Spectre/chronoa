package app.chronoa

import android.content.Context
import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One alarm. [days] is a bit set of the weekdays it repeats on, Monday = bit 0 to Sunday =
 * bit 6; 0 means it rings once. [skip] is the time of one coming ring to leave out (0: none).
 * With [deleteAfter], it goes away once done: a one-time alarm after it rings, a repeating one
 * once each of its days has rung (or been skipped) — [rung] tracks those days.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val days: Int = 0,
    val label: String = "",
    val skip: Long = 0,
    val deleteAfter: Boolean = false,
    val rung: Int = 0,
    /** This alarm's own sound, or "" to use the one in settings. */
    val tone: String = "",
) {
    /** A repeating delete-after alarm whose days have all been done. */
    val allDone get() = repeats && days and rung == days

    val repeats get() = days != 0

    companion object {
        const val EVERY_DAY = 0b1111111
    }
}

/** Everything the app remembers, kept in one small SharedPreferences file. */
object Store {
    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("chronoa", Context.MODE_PRIVATE)

    fun alarms(c: Context): List<Alarm> =
        prefs(c).getString("alarms", "").orEmpty()
            .split(';')
            .mapNotNull(::parse)
            .sortedBy { it.hour * 60 + it.minute }

    // Each alarm is "id,hour,minute,on,days,skip,deleteAfter,rung,label,tone" with label and
    // tone URL-encoded. Older versions stored the same without the tone, or
    // "id,hour,minute,on,days,skip,label", or first of all "id,hour,minute,on,daily"; a daily one
    // of those now repeats every day.
    private fun parse(s: String): Alarm? {
        val p = s.split(',')
        return try {
            when (p.size) {
                5 -> Alarm(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3] == "1", if (p[4] == "1") Alarm.EVERY_DAY else 0)
                7 -> Alarm(
                    p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3] == "1", p[4].toInt(), p[6].let { URLDecoder.decode(it, "UTF-8") },
                    p[5].toLong(),
                )
                9, 10 -> Alarm(
                    p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3] == "1", p[4].toInt(), URLDecoder.decode(p[8], "UTF-8"),
                    p[5].toLong(), p[6] == "1", p[7].toInt(), if (p.size == 10) URLDecoder.decode(p[9], "UTF-8") else "",
                )
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun saveAlarms(c: Context, alarms: List<Alarm>) {
        val s = alarms.joinToString(";") {
            "${it.id},${it.hour},${it.minute},${if (it.enabled) 1 else 0},${it.days},${it.skip}," +
                "${if (it.deleteAfter) 1 else 0},${it.rung}," + URLEncoder.encode(it.label, "UTF-8") + "," +
                URLEncoder.encode(it.tone, "UTF-8")
        }
        prefs(c).edit().putString("alarms", s).apply()
    }

    fun putAlarm(c: Context, a: Alarm) = saveAlarms(c, alarms(c).filter { it.id != a.id } + a)

    fun removeAlarm(c: Context, id: Int) = saveAlarms(c, alarms(c).filter { it.id != id })

    fun newAlarmId(c: Context): Int {
        val p = prefs(c)
        val id = p.getInt("next_id", 1)
        p.edit().putInt("next_id", id + 1).apply()
        return id
    }

    /** Wall-clock time the running timer ends, or 0 when it isn't running. */
    fun timerEnd(c: Context) = prefs(c).getLong("timer_end", 0L)

    /** Time left on a paused timer, or 0 when it isn't paused. */
    fun timerLeft(c: Context) = prefs(c).getLong("timer_left", 0L)

    fun setTimer(c: Context, end: Long, left: Long) =
        prefs(c).edit().putLong("timer_end", end).putLong("timer_left", left).apply()

    /** The last duration started, shown in the pickers next time. */
    fun timerLast(c: Context) = prefs(c).getLong("timer_last", 5 * 60_000L)

    fun setTimerLast(c: Context, ms: Long) = prefs(c).edit().putLong("timer_last", ms).apply()

    fun snoozeAt(c: Context) = prefs(c).getLong("snooze_at", 0L)

    fun setSnoozeAt(c: Context, at: Long) = prefs(c).edit().putLong("snooze_at", at).apply()

    /** How long Snooze delays the alarm, in minutes. */
    fun snoozeMinutes(c: Context) = prefs(c).getInt("snooze_min", 10)

    fun setSnoozeMinutes(c: Context, min: Int) = prefs(c).edit().putInt("snooze_min", min).apply()

    /** Whether a running timer shows its countdown in the status bar and on the widget. */
    fun statusCountdown(c: Context) = prefs(c).getBoolean("status_countdown", true)

    fun setStatusCountdown(c: Context, on: Boolean) = prefs(c).edit().putBoolean("status_countdown", on).apply()

    fun widgetCountdown(c: Context) = prefs(c).getBoolean("widget_countdown", true)

    fun setWidgetCountdown(c: Context, on: Boolean) = prefs(c).edit().putBoolean("widget_countdown", on).apply()

    /** How many minutes before an alarm its countdown starts showing: 5 or 10. */
    fun alarmLeadMinutes(c: Context) = prefs(c).getInt("alarm_lead_min", 10)

    fun setAlarmLeadMinutes(c: Context, min: Int) = prefs(c).edit().putInt("alarm_lead_min", min).apply()

    /** When the alarm currently counting down rings, or 0 when none is close. */
    fun upcomingAt(c: Context) = prefs(c).getLong("upcoming_at", 0L)

    fun setUpcomingAt(c: Context, at: Long) = prefs(c).edit().putLong("upcoming_at", at).apply()

    /** Stopwatch: when the current run started (wall clock, 0 when stopped) and time banked before it. */
    fun swStart(c: Context) = prefs(c).getLong("sw_start", 0L)

    fun swAcc(c: Context) = prefs(c).getLong("sw_acc", 0L)

    fun setSw(c: Context, start: Long, acc: Long) =
        prefs(c).edit().putLong("sw_start", start).putLong("sw_acc", acc).apply()

    /** Total elapsed time at each lap, oldest first. */
    fun swLaps(c: Context): List<Long> =
        prefs(c).getString("sw_laps", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    fun setSwLaps(c: Context, laps: List<Long>) = prefs(c).edit().putString("sw_laps", laps.joinToString(",")).apply()

    /** Alarm sound chosen in settings, or null for the system's default alarm sound. */
    fun alarmTone(c: Context): String? = prefs(c).getString("alarm_tone", null)

    fun setAlarmTone(c: Context, uri: String?) = prefs(c).edit().putString("alarm_tone", uri).apply()

    fun vibrate(c: Context) = prefs(c).getBoolean("vibrate", true)

    fun setVibrate(c: Context, on: Boolean) = prefs(c).edit().putBoolean("vibrate", on).apply()

    /** Alarms start quiet and get louder over half a minute. */
    fun gentleWake(c: Context) = prefs(c).getBoolean("gentle_wake", true)

    fun setGentleWake(c: Context, on: Boolean) = prefs(c).edit().putBoolean("gentle_wake", on).apply()

    /** Dismiss on the alarm screen needs a press-and-hold. */
    fun holdToDismiss(c: Context) = prefs(c).getBoolean("hold_dismiss", false)

    fun setHoldToDismiss(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hold_dismiss", on).apply()

    /** How long something rings before it gives up, in minutes. */
    fun maxRingMinutes(c: Context) = prefs(c).getInt("max_ring_min", 10)

    fun setMaxRingMinutes(c: Context, min: Int) = prefs(c).edit().putInt("max_ring_min", min).apply()

    /** Label of the alarm ringing now, and of the one snoozed, for the ring screen. */
    fun ringLabel(c: Context) = prefs(c).getString("ring_label", "").orEmpty()

    fun setRingLabel(c: Context, label: String) = prefs(c).edit().putString("ring_label", label).apply()

    /** Which alarm is ringing now, and which one a snooze belongs to (0: none / a timer). */
    fun ringAlarmId(c: Context) = prefs(c).getInt("ring_alarm", 0)

    fun setRingAlarmId(c: Context, id: Int) = prefs(c).edit().putInt("ring_alarm", id).apply()

    fun snoozeAlarmId(c: Context) = prefs(c).getInt("snooze_alarm", 0)

    fun setSnoozeAlarmId(c: Context, id: Int) = prefs(c).edit().putInt("snooze_alarm", id).apply()

    /** The sound for what's ringing now ("" for the one in settings), and the snoozed alarm's. */
    fun ringTone(c: Context) = prefs(c).getString("ring_tone", "").orEmpty()

    fun setRingTone(c: Context, tone: String) = prefs(c).edit().putString("ring_tone", tone).apply()

    fun snoozeTone(c: Context) = prefs(c).getString("snooze_tone", "").orEmpty()

    fun setSnoozeTone(c: Context, tone: String) = prefs(c).edit().putString("snooze_tone", tone).apply()

    /** Snoozes so far for the alarm ringing now, and the most allowed (0: no limit). */
    fun snoozeCount(c: Context) = prefs(c).getInt("snooze_count", 0)

    fun setSnoozeCount(c: Context, n: Int) = prefs(c).edit().putInt("snooze_count", n).apply()

    fun snoozeLimit(c: Context) = prefs(c).getInt("snooze_limit", 0)

    fun setSnoozeLimit(c: Context, n: Int) = prefs(c).edit().putInt("snooze_limit", n).apply()

    /** Dismissing an alarm needs a quick sum solved first. */
    fun wakeCheck(c: Context) = prefs(c).getBoolean("wake_check", false)

    fun setWakeCheck(c: Context, on: Boolean) = prefs(c).edit().putBoolean("wake_check", on).apply()

    fun snoozeLabel(c: Context) = prefs(c).getString("snooze_label", "").orEmpty()

    fun setSnoozeLabel(c: Context, label: String) = prefs(c).edit().putString("snooze_label", label).apply()

    /** Colour theme index into [Theme.ALL]; the widget's own, or -1 to follow the app. */
    fun theme(c: Context): Int {
        migrateThemes(c)
        return prefs(c).getInt("theme", Theme.DEFAULT_APP)
    }

    // The first theme list had Orange at 5, since removed: later themes move down one, and
    // whoever had Orange gets Nixie.
    // Then the list was reordered: Snow, Neon blue, Ice blue, Royal, Crimson, Nixie, Cyberpunk,
    // Matrix (from Ice blue, Neon blue, White, Cyberpunk, Nixie, Matrix, Crimson, Royal).
    private fun migrateThemes(c: Context) {
        val p = prefs(c)
        val v = p.getInt("themes_v", 1)
        if (v >= 3) return
        fun toV2(i: Int) = if (v >= 2) i else if (i >= 6) i - 1 else if (i == 5) 4 else i
        val reorder = intArrayOf(2, 1, 0, 6, 5, 7, 4, 3)
        fun moved(i: Int) = reorder[toV2(i).coerceIn(0, reorder.size - 1)]
        // Only colors actually picked are moved; anything unset keeps the defaults.
        val e = p.edit().putInt("themes_v", 3)
        if (p.contains("theme")) e.putInt("theme", moved(p.getInt("theme", 0)))
        p.getInt("widget_theme", -1).let { if (it >= 0) e.putInt("widget_theme", moved(it)) }
        e.apply()
    }

    fun setTheme(c: Context, i: Int) = prefs(c).edit().putInt("theme", i).apply()

    fun widgetTheme(c: Context): Int {
        migrateThemes(c)
        return prefs(c).getInt("widget_theme", -1).takeIf { it >= 0 } ?: Theme.DEFAULT_WIDGET
    }

    fun setWidgetTheme(c: Context, i: Int) = prefs(c).edit().putInt("widget_theme", i).apply()

    /** Widget background opacity, 0 (invisible) to 100 (solid). */
    fun widgetOpacity(c: Context) = prefs(c).getInt("widget_opacity", 50)

    fun setWidgetOpacity(c: Context, v: Int) = prefs(c).edit().putInt("widget_opacity", v).apply()

    /** Whether the settings show their explanations. */
    fun showHints(c: Context) = prefs(c).getBoolean("show_hints", false)

    fun setShowHints(c: Context, on: Boolean) = prefs(c).edit().putBoolean("show_hints", on).apply()

    /** Name of the alarm counting down now ("" when it has none). */
    fun upcomingLabel(c: Context) = prefs(c).getString("upcoming_label", "").orEmpty()

    fun setUpcomingLabel(c: Context, label: String) = prefs(c).edit().putString("upcoming_label", label).apply()

    /** Whether the widget shows the current time. */
    fun widgetClock(c: Context) = prefs(c).getBoolean("widget_clock", true)

    fun setWidgetClock(c: Context, on: Boolean) = prefs(c).edit().putBoolean("widget_clock", on).apply()

    fun tab(c: Context) = prefs(c).getInt("tab", 0)

    fun setTab(c: Context, tab: Int) = prefs(c).edit().putInt("tab", tab).apply()
}
