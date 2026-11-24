package app.chronoa

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The app's text style, matching the ring screen's clock: Inter in a near-white blue with a soft
 * blue glow. The glow scales with the text so small labels stay readable.
 */
object Glow {
    private var inter: Typeface? = null

    /** Styles [v] and every text view inside it, except the warning banner. */
    fun apply(v: View) {
        if (v is TextView && v.id != R.id.banner) style(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) apply(v.getChildAt(i))
    }

    /** Styles a dialog once it's showing: its title, message, buttons and anything inside. */
    fun apply(d: android.app.Dialog) {
        d.window?.decorView?.let { apply(it) }
    }

    fun style(t: TextView) {
        val c = t.context
        if (Build.VERSION.SDK_INT >= 26) {
            val font = inter ?: c.resources.getFont(R.font.inter_ui).also { inter = it }
            t.typeface = font
        }
        val core = Theme.core(c)
        val dim = (core and 0x00FFFFFF) or 0x55000000
        t.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(dim, core)))
        val dp = c.resources.displayMetrics.density
        // Capped, since a glow wider than the view gets cut off in a hard box.
        t.setShadowLayer((t.textSize * 0.28f).coerceIn(2 * dp, 8 * dp), 0f, 0f, Theme.glow(c))
    }
}
