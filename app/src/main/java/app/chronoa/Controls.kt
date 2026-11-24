package app.chronoa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch

/**
 * The app's controls in the same moulded dark-grey plastic as the ring screen: domed buttons,
 * a checkbox that presses in and lights up, and a switch with a domed knob in a recessed track
 * that glows blue when on. Text stays in the glowing blue style.
 */
object Controls {
    /** Dark grey plastic. */
    val PLASTIC = Color.rgb(64, 66, 72)

    fun button(b: Button) {
        b.background = DomeDrawable(b.context)
        b.stateListAnimator = null
        val dp = b.resources.displayMetrics.density
        b.setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt())
        b.textSize = 26f
        // One line always: longer words like RESUME shrink a little to fit.
        b.maxLines = 1
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            b.setAutoSizeTextTypeUniformWithConfiguration(10, 26, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
    }

    /**
     * A symbol key: the glowing [symbol] ("+" or "♪") drawn dead centre on the button face, sized
     * by the button rather than the phone's font size, so it never sits off-centre.
     */
    fun symbol(b: Button, symbol: String) {
        button(b)
        b.text = ""
        b.contentDescription = b.contentDescription ?: symbol
        if (android.os.Build.VERSION.SDK_INT >= 23) b.foreground = SymbolDrawable(b.context, symbol)
        else b.text = symbol
    }

    fun check(b: CompoundButton, round: Boolean = false) {
        b.buttonDrawable = CheckDrawable(b.context, round)
        val dp = b.resources.displayMetrics.density
        b.compoundDrawablePadding = (8 * dp).toInt()
    }

    fun switch(s: Switch) {
        s.thumbDrawable = ThumbDrawable(s.context)
        s.trackDrawable = TrackDrawable(s.context)
        s.thumbTintList = null
        s.trackTintList = null
    }
}

/** Base for the stateful drawables below. */
private abstract class StateDrawable(context: Context) : Drawable() {
    protected val dp = context.resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val glow = Theme.glow(context)
    protected val glowCore = Theme.core(context)
    protected var pressed = false
    protected var checked = false
    protected var enabled = true

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = android.R.attr.state_pressed in state
        val c = android.R.attr.state_checked in state
        val e = android.R.attr.state_enabled in state
        val changed = p != pressed || c != checked || e != enabled
        pressed = p
        checked = c
        enabled = e
        if (changed) invalidateSelf()
        return changed
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A glowing "+" or "♪" centred on a domed button's face (see [DomeDrawable] for its insets). */
private class SymbolDrawable(context: Context, private val symbol: String) : StateDrawable(context) {
    private val note = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas) {
        val face = RectF(bounds).apply { inset(4 * dp, 3 * dp); bottom -= 3 * dp }
        val cx = face.centerX()
        val cy = face.centerY() + if (pressed) 1.5f * dp else 0f
        val size = minOf(face.width(), face.height()) * 0.42f
        val core = if (enabled) glowCore else (glowCore and 0x00FFFFFF) or 0x55000000
        if (symbol == "+") {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = size * 0.17f
            paint.color = core
            paint.setShadowLayer(5 * dp, 0f, 0f, glow)
            canvas.drawLine(cx - size / 2, cy, cx + size / 2, cy, paint)
            canvas.drawLine(cx, cy - size / 2, cx, cy + size / 2, paint)
            paint.clearShadowLayer()
        } else {
            note.textSize = size * 1.25f
            note.color = core
            note.setShadowLayer(5 * dp, 0f, 0f, glow)
            // Centre the glyph's actual ink, not its text line.
            val r = android.graphics.Rect().also { note.getTextBounds(symbol, 0, symbol.length, it) }
            canvas.drawText(symbol, cx - r.exactCenterX(), cy - r.exactCenterY(), note)
        }
    }
}

/** A domed grey button face; the button's own glowing text sits on top. */
private class DomeDrawable(context: Context) : StateDrawable(context) {
    override fun draw(canvas: Canvas) {
        // Leave room around the face for the cast shadow.
        val r = RectF(bounds).apply { inset(4 * dp, 3 * dp); bottom -= 3 * dp }
        val base = if (enabled) Controls.PLASTIC else Dome.shade(Controls.PLASTIC, 0.7f)
        Dome.draw(canvas, r, base, pressed || !enabled, dp, radius = minOf(18 * dp, r.height() * 0.4f))
    }
}

/** A small domed square (or round, for radio buttons) that sinks in and lights up when checked. */
private class CheckDrawable(context: Context, private val round: Boolean) : StateDrawable(context) {
    private val size = (28 * dp).toInt()

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds).apply {
            val s = minOf(width(), height()) - 6 * dp
            set(centerX() - s / 2, centerY() - s / 2, centerX() + s / 2, centerY() + s / 2)
        }
        val radius = if (round) r.width() / 2 else 6 * dp
        Dome.draw(canvas, r, Controls.PLASTIC, checked || pressed, dp, radius = radius, castShadow = !checked)
        if (!checked) return

        // Lit from inside: a soft blue glow, then a bright check mark (or dot).
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            r.centerX(), r.centerY(), r.width() * 0.6f,
            intArrayOf(Color.argb(150, Color.red(glow), Color.green(glow), Color.blue(glow)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
        paint.color = glowCore
        paint.setShadowLayer(4 * dp, 0f, 0f, glow)
        if (round) {
            canvas.drawCircle(r.centerX(), r.centerY(), r.width() * 0.2f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.6f * dp
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val w = r.width()
            canvas.drawPath(Path().apply {
                moveTo(r.left + w * 0.24f, r.top + w * 0.52f)
                lineTo(r.left + w * 0.43f, r.top + w * 0.70f)
                lineTo(r.left + w * 0.77f, r.top + w * 0.32f)
            }, paint)
        }
        paint.clearShadowLayer()
    }
}

/** The switch's recessed track: a dark groove that glows blue when on. */
private class TrackDrawable(context: Context) : StateDrawable(context) {
    override fun getIntrinsicWidth() = (52 * dp).toInt()
    override fun getIntrinsicHeight() = (30 * dp).toInt()

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds).apply { inset(3 * dp, 6 * dp) }
        val radius = r.height() / 2
        // Groove: darker at the top where the lip shades it.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(4, 4, 6), Color.rgb(30, 32, 36), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, paint)
        if (checked) {
            paint.shader = LinearGradient(
                r.left, 0f, r.right, 0f,
                Color.argb(70, Color.red(glow), Color.green(glow), Color.blue(glow)),
                Color.argb(200, Color.red(glow), Color.green(glow), Color.blue(glow)),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(r, radius, radius, paint)
        }
        paint.shader = null
        // Lip: light catching the lower edge of the groove.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * dp
        paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(160, 0, 0, 0), Color.argb(90, 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
    }
}

/** The switch's knob: a domed grey button with a blue light in it when on. */
private class ThumbDrawable(context: Context) : StateDrawable(context) {
    override fun getIntrinsicWidth() = (30 * dp).toInt()
    override fun getIntrinsicHeight() = (30 * dp).toInt()

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds).apply { inset(2 * dp, 2 * dp) }
        Dome.draw(canvas, r, Controls.PLASTIC, pressed, dp, radius = r.width() / 2)
        if (checked) {
            paint.style = Paint.Style.FILL
            paint.color = glowCore
            paint.setShadowLayer(5 * dp, 0f, 0f, glow)
            canvas.drawCircle(r.centerX(), r.centerY(), r.width() * 0.13f, paint)
            paint.clearShadowLayer()
        }
    }
}
