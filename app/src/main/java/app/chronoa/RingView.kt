package app.chronoa

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * The ring screen, drawn by hand so it looks like hardware: two domed buttons (green Dismiss on
 * top, red Snooze below) on a dark bezel, split by a raised black bar whose hexagonal bezel holds
 * a recessed glass window with the glowing time. Timers have no snooze: they get a smaller
 * button bar along the bottom with the clock sitting just above it, and the space above is left
 * for the keypad.
 */
class RingView(
    context: Context,
    private val onDismiss: () -> Unit,
    private val onSnooze: () -> Unit,
) : View(context) {
    var hasSnooze = true
        set(value) {
            field = value
            if (width > 0) onSizeChanged(width, height, width, height)
            invalidate()
        }

    var time = ""
        set(value) {
            field = value
            invalidate()
        }

    /** An alarm's label: shown in the clock window instead of the time when there is one. */
    var centerLabel = ""
        set(value) {
            field = value
            invalidate()
        }

    /** False once the snooze limit is used up: Snooze is dark and does nothing. */
    var snoozeEnabled = true
        set(value) {
            field = value
            invalidate()
        }

    /** Dismiss needs a press-and-hold of [HOLD_MS], shown as light filling the button. */
    var holdToDismiss = false

    private var holdStart = 0L
    private val holdTick = object : Runnable {
        override fun run() {
            // Only while the finger is still on Dismiss.
            if (holdStart == 0L || pressed != 1) {
                holdStart = 0L
                return
            }
            if (SystemClock.uptimeMillis() - holdStart >= HOLD_MS) {
                holdStart = 0L
                pressed = 0
                invalidate()
                onDismiss()
                return
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    /** What the single timer button says: Dismiss, or Start once a new time has been typed. */
    var timerLabel = context.getString(R.string.dismiss)
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val green = context.getColor(R.color.dismiss)
    private val red = context.getColor(R.color.snooze)
    private val glow = Theme.glow(context)
    private val glowCore = Theme.core(context)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (Build.VERSION.SDK_INT >= 26) {
            resources.getFont(R.font.inter_ui)
        } else {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        textAlign = Paint.Align.CENTER
    }

    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (Build.VERSION.SDK_INT >= 26) {
            resources.getFont(R.font.inter_clock)
        } else {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        textAlign = Paint.Align.CENTER
    }

    private val topButton = RectF()
    private val bottomButton = RectF()
    private val bar = Path()
    private val window = Path()
    // Softens the corners where the bar meets the hexagon.
    private val joins = CornerPathEffect(8 * resources.displayMetrics.density)
    private val windowBounds = RectF()

    private var digitSize = 0f

    /** Which button is held down: 0 none, 1 top, 2 bottom. */
    private var pressed = 0

    init {
        // Shadow layers on shapes need software drawing before Android 9.
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = context.getString(R.string.dismiss)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val bezel = 12 * dp
        val mid = h / 2f
        val barH = 26 * dp
        // Hexagon: 62% of the narrow side wide, a bit under half as tall, with pointed ends.
        var hexW = minOf(w, h) * 0.62f
        var hexY = mid
        if (hasSnooze) {
            topButton.set(bezel, bezel, w - bezel, mid - barH / 2 - bezel / 2)
            bottomButton.set(bezel, mid + barH / 2 + bezel / 2, w - bezel, h - bezel)
            label.textSize = minOf(w * 0.12f, 56 * dp)
        } else {
            // Timer: a button bar along the bottom, the clock right above it.
            val buttonH = minOf(h * 0.4f, 96 * dp)
            topButton.set(bezel, h - bezel - buttonH, w - bezel, h - bezel)
            bottomButton.setEmpty()
            // The clock spans most of the width here.
            hexW = minOf(w * 0.92f, (topButton.top - 3 * bezel) / 0.42f)
            hexY = topButton.top - 1.5f * bezel - hexW * 0.42f / 2
            label.textSize = minOf(w * 0.09f, buttonH * 0.38f)
        }
        val hexH = hexW * 0.42f
        bar.reset()
        bar.addPath(hexagon(w / 2f, hexY, hexW, hexH))
        if (hasSnooze) {
            val strip = Path()
            strip.addRect(0f, mid - barH / 2, w.toFloat(), mid + barH / 2, Path.Direction.CW)
            bar.op(strip, Path.Op.UNION)
        }
        window.reset()
        window.addPath(hexagon(w / 2f, hexY, hexW - 20 * dp, hexH - 20 * dp))
        window.computeBounds(windowBounds, true)

        digitSize = hexH * 0.52f
    }

    /** An elongated hexagon with softly rounded corners, so it still reads as a hexagon. */
    private fun hexagon(cx: Float, cy: Float, w: Float, h: Float): Path {
        val point = h * 0.5f / 1.2f // how far in the pointed ends reach
        val corners = listOf(
            PointF(cx - w / 2, cy),
            PointF(cx - w / 2 + point, cy - h / 2),
            PointF(cx + w / 2 - point, cy - h / 2),
            PointF(cx + w / 2, cy),
            PointF(cx + w / 2 - point, cy + h / 2),
            PointF(cx - w / 2 + point, cy + h / 2),
        )
        return rounded(corners, h * 0.16f)
    }

    /** A closed polygon whose corners are rounded off by up to [radius] along each edge. */
    private fun rounded(pts: List<PointF>, radius: Float) = Path().apply {
        fun toward(from: PointF, to: PointF): PointF {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val d = minOf(radius, len / 2) / len
            return PointF(from.x + dx * d, from.y + dy * d)
        }
        pts.forEachIndexed { i, p ->
            val before = toward(p, pts[(i + pts.size - 1) % pts.size])
            val after = toward(p, pts[(i + 1) % pts.size])
            if (i == 0) moveTo(before.x, before.y) else lineTo(before.x, before.y)
            quadTo(p.x, p.y, after.x, after.y)
        }
        close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(8, 8, 9))
        val topText = if (hasSnooze) context.getString(R.string.dismiss) else timerLabel
        drawButton(canvas, topButton, green, topText, pressed == 1)
        if (hasSnooze) {
            if (snoozeEnabled) {
                drawButton(canvas, bottomButton, red, context.getString(R.string.snooze), pressed == 2)
            } else {
                drawButton(canvas, bottomButton, Dome.shade(red, 0.35f), context.getString(R.string.no_snooze), true)
            }
        }
        drawBar(canvas)
        drawClock(canvas)
    }

    private fun drawButton(canvas: Canvas, r: RectF, base: Int, text: String, down: Boolean) {
        val colour = Dome.draw(canvas, r, base, down, dp)

        // Label stamped into the plastic.
        // Long words shrink to fit the button.
        val normal = label.textSize
        val wide = label.measureText(text.uppercase())
        if (wide > r.width() * 0.85f) label.textSize = normal * r.width() * 0.85f / wide
        val y = r.centerY() - (label.descent() + label.ascent()) / 2 + if (down) 2 * dp else 0f
        drawStamped(canvas, text, r.centerX(), y, colour)
        label.textSize = normal

        // Hold to dismiss: say so under the label, and fill the button with light while held.
        if (holdToDismiss && hasSnooze && r === topButton) {
            val size = label.textSize
            label.textSize = size * 0.4f
            drawStamped(canvas, context.getString(R.string.hold), r.centerX(), y + size * 0.75f, colour)
            label.textSize = size
            if (holdStart != 0L) {
                val f = ((SystemClock.uptimeMillis() - holdStart).toFloat() / HOLD_MS).coerceIn(0f, 1f)
                canvas.save()
                canvas.clipRect(r.left, r.bottom - r.height() * f, r.right, r.bottom)
                fill.color = Color.argb(70, 255, 255, 255)
                val radius = minOf(44 * dp, r.height() * 0.35f)
                canvas.drawRoundRect(r, radius, radius, fill)
                canvas.restore()
            }
        }
    }

    /**
     * Lettering stamped into the plastic and filled with black ink, like an old elevator button:
     * the lower lip of each cut catches the light, the upper lip falls into shadow, and inside
     * the ink sits a little deeper under its top edge.
     */
    private fun drawStamped(canvas: Canvas, text: String, x: Float, y: Float, plastic: Int) {
        val letters = Path()
        val caps = text.uppercase()
        label.getTextPath(caps, 0, caps.length, x, y, letters)
        val lip = 1.6f * dp

        // Lower lip: light catching the bottom wall of the cut.
        fill.color = Color.argb(150, 255, 255, 255)
        fill.maskFilter = BlurMaskFilter(0.8f * dp, BlurMaskFilter.Blur.NORMAL)
        canvas.save()
        canvas.translate(0f, lip)
        canvas.drawPath(letters, fill)
        canvas.restore()
        // Upper lip: the top wall of the cut, in the plastic's own shadow.
        fill.color = shade(plastic, 0.3f)
        canvas.save()
        canvas.translate(0f, -lip * 0.7f)
        canvas.drawPath(letters, fill)
        canvas.restore()
        fill.maskFilter = null

        // Ink filling the cut, with a faint sheen towards the bottom.
        val b = RectF().also { letters.computeBounds(it, true) }
        fill.shader = LinearGradient(
            0f, b.top, 0f, b.bottom,
            Color.rgb(14, 14, 15), Color.rgb(44, 44, 48),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(letters, fill)
        fill.shader = null

        // Depth: a soft shadow cast by the top edge of the cut onto the ink.
        val outside = Path().apply {
            addRect(b.left - 20 * dp, b.top - 20 * dp, b.right + 20 * dp, b.bottom + 20 * dp, Path.Direction.CW)
            op(letters, Path.Op.DIFFERENCE)
        }
        canvas.save()
        canvas.clipPath(letters)
        canvas.translate(0f, lip)
        fill.color = Color.argb(200, 0, 0, 0)
        fill.maskFilter = BlurMaskFilter(lip, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(outside, fill)
        fill.maskFilter = null
        canvas.restore()

        // A hairline where the ink meets the edge of the cut.
        stroke.strokeWidth = 0.8f * dp
        stroke.color = Color.argb(140, Color.red(shade(plastic, 0.25f)), Color.green(shade(plastic, 0.25f)), Color.blue(shade(plastic, 0.25f)))
        canvas.drawPath(letters, stroke)
    }

    private fun drawBar(canvas: Canvas) {
        val b = RectF().also { bar.computeBounds(it, true) }
        // Drop shadow onto both buttons.
        fill.color = Color.BLACK
        fill.setShadowLayer(14 * dp, 0f, 5 * dp, Color.argb(200, 0, 0, 0))
        fill.pathEffect = joins
        stroke.pathEffect = joins
        canvas.drawPath(bar, fill)
        fill.clearShadowLayer()

        // Raised black plastic: dark grey on top fading to black.
        fill.shader = LinearGradient(
            0f, b.top, 0f, b.bottom,
            intArrayOf(Color.rgb(58, 60, 64), Color.rgb(22, 23, 25), Color.rgb(4, 4, 5)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(bar, fill)
        fill.shader = null

        // Thin light rim on the top edges.
        stroke.strokeWidth = 1.5f * dp
        stroke.shader = LinearGradient(
            0f, b.top, 0f, b.centerY(),
            Color.argb(150, 255, 255, 255), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(bar, stroke)
        stroke.shader = null
        fill.pathEffect = null
        stroke.pathEffect = null
    }

    private fun drawClock(canvas: Canvas) {
        val w = windowBounds
        // Recessed glass window: near black, a touch lighter at the bottom where light reaches in.
        fill.shader = LinearGradient(
            0f, w.top, 0f, w.bottom,
            Color.rgb(0, 0, 0), Color.rgb(14, 18, 24),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(window, fill)
        fill.shader = null

        // Inner shadow along the top lip, highlight along the bottom lip.
        stroke.strokeWidth = 2 * dp
        stroke.shader = LinearGradient(
            0f, w.top, 0f, w.bottom,
            Color.argb(255, 0, 0, 0), Color.argb(90, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(window, stroke)
        stroke.shader = null

        // Glowing digits: a wide soft glow, a tighter one, then the bright core.
        // Shrink long readings like +1:05:03 so they stay inside the window's flat part.
        // An alarm's label takes the time's place, in letters, sized to fit.
        val text = centerLabel.ifEmpty { time }
        val p = if (centerLabel.isEmpty()) digits else words
        p.textSize = if (centerLabel.isEmpty()) digitSize else digitSize * 0.8f
        val room = w.width() - w.height() * 0.9f
        val wide = p.measureText(text)
        if (wide > room) p.textSize *= room / wide
        val y = w.centerY() - (p.descent() + p.ascent()) / 2
        p.color = glowCore
        p.setShadowLayer(p.textSize * 0.45f, 0f, 0f, glow)
        canvas.drawText(text, w.centerX(), y, p)
        p.setShadowLayer(p.textSize * 0.15f, 0f, 0f, glow)
        canvas.drawText(text, w.centerX(), y, p)
        p.clearShadowLayer()
        canvas.drawText(text, w.centerX(), y, p)

        // Glass: a faint sheen across the upper half.
        canvas.save()
        canvas.clipPath(window)
        fill.shader = LinearGradient(
            0f, w.top, 0f, w.centerY(),
            Color.argb(40, 255, 255, 255), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(w.left, w.top, w.right, w.centerY(), fill)
        fill.shader = null
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = when {
            topButton.contains(e.x, e.y) -> 1
            bottomButton.contains(e.x, e.y) -> 2
            // The bar between them belongs to whichever half it's on.
            hasSnooze -> if (e.y < height / 2f) 1 else 2
            else -> 0
        }
        val holding = holdToDismiss && hasSnooze
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit
                if (holding && hit == 1) {
                    holdStart = SystemClock.uptimeMillis()
                    postOnAnimation(holdTick)
                }
            }
            MotionEvent.ACTION_MOVE -> if (hit != pressed) {
                pressed = 0
                holdStart = 0L
            }
            MotionEvent.ACTION_UP -> {
                if (pressed == hit && hit != 0) {
                    performClick()
                    // A held Dismiss fires from holdTick; letting go early does nothing.
                    if (hit == 2) {
                        if (snoozeEnabled) onSnooze()
                    } else if (!holding) {
                        onDismiss()
                    }
                }
                pressed = 0
                holdStart = 0L
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = 0
                holdStart = 0L
            }
        }
        invalidate()
        return true
    }

    override fun performClick() = super.performClick()

    // A hold only counts while this screen has the touch: if anything else takes over (a
    // system tip, the notification shade, another window), the hold is dropped.
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelHold()
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        super.onDetachedFromWindow()
    }

    private fun cancelHold() {
        holdStart = 0L
        pressed = 0
        removeCallbacks(holdTick)
        invalidate()
    }

    companion object {
        private const val HOLD_MS = 1000L
    }

    private fun shade(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255),
    )
}
