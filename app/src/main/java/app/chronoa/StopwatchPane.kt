package app.chronoa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * The Stopwatch tab: a big glowing readout, Lap / Reset and Start / Stop buttons, and the laps
 * below. Its state lives in [Store] as wall-clock times, so it keeps running with the app closed.
 */
class StopwatchPane(context: Context) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val display = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 64f
        fontFeatureSettings = "tnum"
    }
    private val lapList = LinearLayout(context).apply { orientation = VERTICAL }
    private val lapButton = Button(context)
    private val goButton = Button(context)
    private val handler = Handler(Looper.getMainLooper())
    private var shownLaps = -1

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (Store.swStart(context) > 0) handler.postDelayed(this, 33)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        val buttons = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(lapButton, LayoutParams((140 * dp).toInt(), (64 * dp).toInt()).apply { marginEnd = (16 * dp).toInt() })
            addView(goButton, LayoutParams((140 * dp).toInt(), (64 * dp).toInt()))
        }
        Controls.button(lapButton)
        Controls.button(goButton)
        lapButton.setOnClickListener { if (Store.swStart(context) > 0) lap() else reset() }
        goButton.setOnClickListener { if (Store.swStart(context) > 0) stop() else start() }

        addView(display, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.1f).apply { topMargin = (24 * dp).toInt() })
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            ScrollView(context).apply { addView(lapList) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = (16 * dp).toInt()
                marginStart = (32 * dp).toInt()
                marginEnd = (32 * dp).toInt()
            },
        )
        Glow.apply(this)
    }

    /** Call while the tab is visible; it ticks only then. */
    fun resume() {
        handler.removeCallbacks(tick)
        shownLaps = -1
        tick.run()
    }

    fun pause() = handler.removeCallbacks(tick)

    private fun elapsed(): Long {
        val start = Store.swStart(context)
        return Store.swAcc(context) + if (start > 0) System.currentTimeMillis() - start else 0
    }

    private fun start() {
        Store.setSw(context, System.currentTimeMillis(), Store.swAcc(context))
        resume()
    }

    private fun stop() {
        Store.setSw(context, 0, elapsed())
        resume()
    }

    private fun reset() {
        Store.setSw(context, 0, 0)
        Store.setSwLaps(context, emptyList())
        resume()
    }

    private fun lap() {
        Store.setSwLaps(context, Store.swLaps(context) + elapsed())
        update()
    }

    private fun update() {
        val running = Store.swStart(context) > 0
        // The screen stays on while the stopwatch runs and this tab is showing.
        keepScreenOn = running
        val total = elapsed()
        display.text = format(total)
        goButton.setText(if (running) R.string.stop else if (total > 0) R.string.resume else R.string.start)
        lapButton.setText(if (running) R.string.lap else R.string.reset)
        lapButton.isEnabled = running || total > 0

        val laps = Store.swLaps(context)
        if (laps.size != shownLaps) {
            shownLaps = laps.size
            lapList.removeAllViews()
            // Newest first: lap number, that lap's time, and the running total.
            for (i in laps.indices.reversed()) {
                val split = laps[i] - if (i > 0) laps[i - 1] else 0
                val row = LinearLayout(context).apply { setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt()) }
                listOf(context.getString(R.string.lap_n, i + 1), format(split), format(laps[i])).forEachIndexed { col, text ->
                    row.addView(TextView(context).apply {
                        this.text = text
                        textSize = 17f
                        fontFeatureSettings = "tnum"
                        gravity = if (col == 0) Gravity.START else Gravity.END
                        alpha = if (col == 2) 0.6f else 1f
                    }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
                Glow.apply(row)
                lapList.addView(row)
            }
        }
    }

    private fun format(ms: Long): String {
        val cs = ms / 10 % 100
        val s = ms / 1000
        return if (s >= 3600) {
            String.format(Locale.ROOT, "%d:%02d:%02d.%02d", s / 3600, s % 3600 / 60, s % 60, cs)
        } else {
            String.format(Locale.ROOT, "%02d:%02d.%02d", s / 60, s % 60, cs)
        }
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }
}
