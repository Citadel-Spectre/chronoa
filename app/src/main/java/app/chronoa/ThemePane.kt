package app.chronoa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Colors in settings: glowing circles to pick the app's color (top of the Settings tab), and on
 * the Widget tab the widget's own color and background opacity with a preview.
 */
class ThemePane(private val c: Context, private val onAppThemeChanged: () -> Unit) {
    private val dp = c.resources.displayMetrics.density

    /** The app's color, at the top of the Settings tab. */
    fun buildAppColour(into: LinearLayout) {
        into.removeAllViews()
        into.addView(swatches(Store.theme(c)) { i ->
            Store.setTheme(c, i)
            TimerWidget.update(c)
            onAppThemeChanged()
        })
        Glow.apply(into)
    }

    /** The Widget tab: its color into [colours] (at the top), opacity and preview into [into]. */
    fun build(colours: LinearLayout, into: LinearLayout) {
        into.removeAllViews()

        val widgetColours = colours
        widgetColours.removeAllViews()
        val preview = Preview()
        this.preview = preview
        fun showWidgetColours() {
            widgetColours.removeAllViews()
            widgetColours.addView(swatches(Store.widgetTheme(c)) { i ->
                Store.setWidgetTheme(c, i)
                TimerWidget.update(c)
                preview.update()
            })
            Glow.apply(widgetColours)
        }
        showWidgetColours()

        val opacityLabel = heading(c.getString(R.string.widget_opacity, Store.widgetOpacity(c)))
        into.addView(opacityLabel)
        into.addView(SeekBar(c).apply {
            // In steps of 10%.
            max = 10
            progress = (Store.widgetOpacity(c) + 5) / 10
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.glow(c))
            thumbTintList = android.content.res.ColorStateList.valueOf(Theme.core(c))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, step: Int, fromUser: Boolean) {
                    val v = step * 10
                    opacityLabel.text = c.getString(R.string.widget_opacity, v)
                    Store.setWidgetOpacity(c, v)
                    preview.update()
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) = TimerWidget.update(c)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (12 * dp).toInt()
            marginEnd = (12 * dp).toInt()
        })

        into.addView(preview.view, LinearLayout.LayoutParams((240 * dp).toInt(), (120 * dp).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (20 * dp).toInt()
        })
        Glow.apply(into)
        // After the app's glow style, so the preview keeps the widget's own colour.
        preview.update()
    }

    private fun heading(text: String) = TextView(c).apply {
        this.text = text
        textSize = 17f
        setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
    }

    /** The themes as glowing circles in rows of four, with their names under them. */
    private fun swatches(selected: Int, onPick: (Int) -> Unit): View {
        val grid = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        val circles = mutableListOf<Swatch>()
        Theme.ALL.chunked(4).forEachIndexed { r, row ->
            val line = LinearLayout(c)
            row.forEachIndexed { k, theme ->
                val i = r * 4 + k
                val cell = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
                }
                val swatch = Swatch(theme, i == selected)
                circles += swatch
                cell.addView(swatch, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()))
                cell.addView(TextView(c).apply {
                    text = theme.name
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
                cell.setOnClickListener {
                    circles.forEachIndexed { j, s -> s.picked = j == i }
                    onPick(i)
                }
                line.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            // Keep the last row's circles the same width as the first's.
            repeat(4 - row.size) { line.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(line)
        }
        return grid
    }

    /** A glowing dot in a theme's colours, ringed when it's the one picked. */
    private inner class Swatch(private val theme: Theme, picked: Boolean) : View(c) {
        var picked = picked
            set(value) {
                field = value
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(theme.core, theme.glow, Color.argb(0, Color.red(theme.glow), Color.green(theme.glow), Color.blue(theme.glow))),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
            if (picked) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * dp
                paint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r - 2 * dp, paint)
            }
        }
    }

    /** The widget as it'll look: its colour and panel opacity over a sample wallpaper. */
    private var preview: Preview? = null

    /** Redraws the preview, e.g. after the clock is turned on or off. */
    fun refreshPreview() = preview?.update()

    private inner class Preview {
        private val panel = View(c)
        private val logo = android.widget.ImageView(c).apply {
            setImageResource(R.mipmap.ic_launcher_fg)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        private val time = TextView(c).apply {
            text = "12:34"
            textSize = 30f
            gravity = Gravity.CENTER
            if (android.os.Build.VERSION.SDK_INT >= 26) typeface = c.resources.getFont(R.font.inter_ui)
        }

        // A sample countdown under the time, as when an alarm is coming up.
        private val countdown = TextView(c).apply {
            text = "04:59"
            textSize = 20f
            gravity = Gravity.CENTER
            if (android.os.Build.VERSION.SDK_INT >= 26) typeface = c.resources.getFont(R.font.inter_ui)
        }
        private val label = TextView(c).apply {
            setText(R.string.alarm)
            textSize = 9f
            gravity = Gravity.CENTER
            alpha = 0.8f
            if (android.os.Build.VERSION.SDK_INT >= 26) typeface = c.resources.getFont(R.font.inter_ui)
        }
        private val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(time)
            addView(countdown)
            addView(label)
        }
        val view = FrameLayout(c).apply {
            // A sample wallpaper, so see-through shows as see-through.
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(176, 190, 230), Color.rgb(40, 52, 110), Color.rgb(16, 16, 30))).apply {
                cornerRadius = 16 * dp
            }
            setPadding((60 * dp).toInt(), (20 * dp).toInt(), (60 * dp).toInt(), (20 * dp).toInt())
            val tile = FrameLayout(c)
            tile.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            tile.addView(logo, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            tile.addView(texts, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(tile)
        }

        fun update() {
            panel.background = c.getDrawable(R.drawable.widget_bg)?.mutate()?.apply { alpha = Store.widgetOpacity(c) * 255 / 100 }
            val t = Theme.widget(c)
            for (v in listOf(time, countdown, label)) {
                v.setTextColor(t.core)
                v.setShadowLayer(if (v === label) 4 * dp else 10 * dp, 0f, 0f, t.glow)
            }
            // As on the real widget: the time if on, a countdown under it if on, and the
            // logo when there's neither.
            val clock = Store.widgetClock(c)
            val counting = Store.widgetCountdown(c)
            time.textSize = if (counting) 26f else 30f
            time.visibility = if (clock) View.VISIBLE else View.GONE
            countdown.visibility = if (counting) View.VISIBLE else View.GONE
            label.visibility = if (counting) View.VISIBLE else View.GONE
            logo.visibility = if (!clock && !counting) View.VISIBLE else View.GONE
        }
    }
}
