package app.chronoa

import android.content.Context
import android.graphics.Color

/**
 * The glow colors. Every glowing text and light in the app comes from a theme's bright [core]
 * and the softer [glow] around it; the widget can follow the app's theme or have its own.
 */
data class Theme(val name: String, val core: Int, val glow: Int) {
    companion object {
        val ALL = listOf(
            Theme("Snow", Color.rgb(255, 255, 255), Color.rgb(200, 210, 222)),
            Theme("Neon blue", Color.rgb(107, 240, 255), Color.rgb(0, 229, 255)),
            Theme("Ice blue", Color.rgb(90, 170, 255), Color.rgb(0, 120, 255)),
            Theme("Royal", Color.rgb(190, 95, 255), Color.rgb(150, 0, 255)),
            Theme("Crimson", Color.rgb(255, 45, 40), Color.rgb(205, 0, 0)),
            Theme("Nixie", Color.rgb(255, 122, 40), Color.rgb(255, 80, 0)),
            Theme("Cyberpunk", Color.rgb(255, 226, 40), Color.rgb(250, 205, 0)),
            Theme("Matrix", Color.rgb(60, 232, 100), Color.rgb(0, 200, 40)),
        )

        /** Ice blue for the app, Matrix for the widget, until other colors are picked. */
        const val DEFAULT_APP = 2
        const val DEFAULT_WIDGET = 7

        fun app(c: Context) = ALL[Store.theme(c).coerceIn(0, ALL.size - 1)]

        /** The widget's own theme. */
        fun widgetIndex(c: Context) = Store.widgetTheme(c)

        fun widget(c: Context) = ALL[widgetIndex(c).coerceIn(0, ALL.size - 1)]

        fun core(c: Context) = app(c).core

        fun glow(c: Context) = app(c).glow
    }
}
