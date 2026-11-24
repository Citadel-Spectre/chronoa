package app.chronoa

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What shows while something rings, used both by [RingActivity] and, when Android won't let
 * that open over another app, by [OverlayRing]. Alarms get green Dismiss on top and red Snooze
 * below; timers get a keypad to type a new timer and a Dismiss / Start bar.
 */
class RingScreen(
    private val c: Context,
    kind: Int,
    rangAt: Long,
    label: String,
    private val onClose: () -> Unit,
) {
    private val isTimer = kind == Scheduler.KIND_TIMER
    private val rangAt = rangAt.takeIf { it > 0 } ?: System.currentTimeMillis()
    private val ring = RingView(c, onDismiss = ::dismiss, onSnooze = ::snooze).apply {
        hasSnooze = !isTimer
        centerLabel = if (isTimer) "" else label
        holdToDismiss = Store.holdToDismiss(c)
        snoozeEnabled = Ringer.canSnooze(c)
    }
    private var keypad: Keypad? = null
    private val main: View = if (isTimer) timerScreen() else ring

    /** Holds the ring screen, or the wake-up check in its place. */
    val view = FrameLayout(c).apply { addView(main) }

    fun start() {
        clock.removeCallbacks(clockTick)
        clockTick.run()
    }

    fun stop() = clock.removeCallbacks(clockTick)

    /**
     * Timers: dark screen with the keypad on top for typing a new timer, then the clock counting
     * up since the timer went off, then a button bar that dismisses, or starts the new timer.
     */
    private fun timerScreen(): View {
        val dp = c.resources.displayMetrics.density
        val entry = TextView(c).apply {
            gravity = Gravity.CENTER
            textSize = 48f
            fontFeatureSettings = "tnum"
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        Glow.style(entry)
        val pad = Keypad(c, 6) { digits ->
            entry.text = Keypad.format(Keypad.split(digits, 6))
            entry.alpha = if (digits.isEmpty()) 0.35f else 1f
            ring.timerLabel = c.getString(if (digits.isEmpty()) R.string.dismiss else R.string.start)
        }
        keypad = pad
        pad.setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
        pad.digits = ""
        // Keypad centred in the space above; the clock and button keep just the room they need.
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(entry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val w = c.resources.displayMetrics.widthPixels
        val ringH = (96 * dp + 4.5f * 12 * dp + w * 0.92f * 0.42f).toInt()
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 8, 9))
            addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(ring, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ringH))
        }
    }

    private fun dismiss() {
        // An alarm with the wake-up check on needs a sum solved first; it keeps ringing meanwhile.
        if (!isTimer && Store.wakeCheck(c)) return showCheck()
        reallyDismiss()
    }

    private fun reallyDismiss() {
        Ringer.stop(c)
        val digits = keypad?.digits.orEmpty()
        if (isTimer && digits.isNotEmpty()) {
            // Start the newly typed timer in place of the one that just rang.
            val (h, m, sec) = Keypad.split(digits, 6)
            val ms = ((h * 60L + m) * 60 + sec) * 1000
            Store.setTimerLast(c, ms)
            Timer.start(c, ms)
        }
        onClose()
    }

    /** The wake-up check: a quick sum to type in on the keypad. */
    private fun showCheck() {
        val dp = c.resources.displayMetrics.density
        val rnd = java.util.Random()
        var a = 0
        var b = 0
        val question = TextView(c).apply {
            gravity = Gravity.CENTER
            textSize = 56f
            fontFeatureSettings = "tnum"
        }
        val answer = TextView(c).apply {
            gravity = Gravity.CENTER
            textSize = 48f
            fontFeatureSettings = "tnum"
        }
        val hint = TextView(c).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setText(R.string.wake_check_title)
        }
        val pad = Keypad(c, 3) { digits ->
            answer.text = digits.ifEmpty { "?" }
            answer.alpha = if (digits.isEmpty()) 0.4f else 1f
        }
        fun newSum() {
            a = 12 + rnd.nextInt(48)
            b = 12 + rnd.nextInt(28)
            question.text = "$a + $b ="
            pad.digits = ""
        }
        newSum()
        pad.setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
        val back = Button(c).apply { setText(R.string.back) }
        val ok = Button(c).apply { setText(R.string.ok) }
        Controls.button(back)
        Controls.button(ok)
        back.setOnClickListener { showMain() }
        ok.setOnClickListener {
            if (pad.digits.toIntOrNull() == a + b) {
                reallyDismiss()
            } else {
                hint.setText(R.string.wake_check_wrong)
                newSum()
            }
        }
        val buttons = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            addView(back, LinearLayout.LayoutParams((140 * dp).toInt(), (64 * dp).toInt()).apply { marginEnd = (16 * dp).toInt() })
            addView(ok, LinearLayout.LayoutParams((140 * dp).toInt(), (64 * dp).toInt()))
        }
        val screen = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(8, 8, 9))
            fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(hint, full().apply { bottomMargin = (12 * dp).toInt() })
            addView(question, full())
            addView(answer, full().apply { bottomMargin = (12 * dp).toInt() })
            addView(pad, full())
            addView(buttons, full().apply { topMargin = (24 * dp).toInt() })
        }
        Glow.apply(screen)
        view.removeAllViews()
        view.addView(screen)
    }

    private fun showMain() {
        view.removeAllViews()
        view.addView(main)
    }

    private fun snooze() {
        if (!Ringer.canSnooze(c)) return
        Ringer.stop(c, completed = false)
        Scheduler.snooze(c)
        Toast.makeText(c, c.getString(R.string.snoozed, Store.snoozeMinutes(c)), Toast.LENGTH_SHORT).show()
        onClose()
    }

    private val clock = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (isTimer) {
                // How long ago the timer went off.
                val s = maxOf(0L, now - rangAt) / 1000
                ring.time = if (s >= 3600) {
                    String.format(Locale.ROOT, "+%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                } else {
                    String.format(Locale.ROOT, "+%d:%02d", s / 60, s % 60)
                }
                clock.postDelayed(this, 1000 - (now - rangAt) % 1000)
            } else {
                ring.time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                clock.postDelayed(this, 60_000 - now % 60_000)
            }
        }
    }
}
