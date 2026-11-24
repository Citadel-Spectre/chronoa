package app.chronoa

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Domed moulded-plastic buttons, as on the ring screen: a face that bulges out with a bevel, a
 * soft highlight, a crisp rim and a cast shadow. Pushed in, it darkens and flattens.
 */
object Dome {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Draws a domed button filling [r]; returns the face colour it used. */
    fun draw(
        canvas: Canvas,
        r: RectF,
        base: Int,
        down: Boolean,
        dp: Float,
        radius: Float = minOf(44 * dp, r.height() * 0.35f),
        castShadow: Boolean = true,
    ): Int {
        val colour = if (down) shade(base, 0.8f) else base

        // Cast shadow on the bezel, so the button stands proud of it. Gone when pushed in.
        if (!down && castShadow) {
            fill.color = Color.BLACK
            fill.setShadowLayer(10 * dp, 0f, 4 * dp, Color.argb(220, 0, 0, 0))
            canvas.drawRoundRect(r, radius, radius, fill)
            fill.clearShadowLayer()
        }

        // Domed face: bright in the middle, falling off steeply towards the edges.
        fill.shader = RadialGradient(
            r.centerX(), r.top + r.height() * 0.4f, maxOf(r.width(), r.height()) * 0.72f,
            intArrayOf(
                shade(colour, if (down) 1.05f else 1.4f),
                shade(colour, if (down) 0.95f else 1.05f),
                shade(colour, 0.7f),
                shade(colour, 0.38f),
            ),
            floatArrayOf(0f, 0.35f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(r, radius, radius, fill)

        // Bevel: light rolling over the top edge, shade tucking under the bottom one.
        val bevel = minOf(r.height() * 0.2f, 70 * dp)
        fill.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            intArrayOf(
                Color.argb(if (down) 30 else 110, 255, 255, 255),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(if (down) 70 else 150, 0, 0, 0),
            ),
            floatArrayOf(0f, bevel / r.height(), 1 - bevel / r.height(), 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(r, radius, radius, fill)
        // Same at the sides, softer, so the face curves away in every direction.
        fill.shader = LinearGradient(
            r.left, 0f, r.right, 0f,
            intArrayOf(Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(90, 0, 0, 0)),
            floatArrayOf(0f, 0.12f, 0.88f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(r, radius, radius, fill)

        // Specular highlight: a soft oval of reflected light on the upper part of the dome.
        if (!down) {
            val hl = RectF(
                r.left + r.width() * 0.12f, r.top + r.height() * 0.04f,
                r.right - r.width() * 0.12f, r.top + r.height() * 0.42f,
            )
            // A circular glow squashed into an oval, brightest near its top, fading out at every edge.
            val glow = RadialGradient(
                hl.centerX(), hl.top + hl.height() * 0.4f, hl.width() / 2,
                intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            glow.setLocalMatrix(Matrix().apply {
                setScale(1f, hl.height() / hl.width(), hl.centerX(), hl.top + hl.height() * 0.4f)
            })
            fill.shader = glow
            canvas.drawRect(r, fill)
        }
        fill.shader = null

        // Crisp rim: catches light on top, falls into shadow underneath.
        stroke.strokeWidth = 2 * dp
        stroke.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            Color.argb(if (down) 50 else 160, 255, 255, 255), Color.argb(170, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        val rim = RectF(r).apply { inset(dp, dp) }
        canvas.drawRoundRect(rim, radius - dp, radius - dp, stroke)
        stroke.shader = null

        return colour
    }

    fun shade(c: Int, f: Float) = Color.argb(
        Color.alpha(c),
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255),
    )
}
