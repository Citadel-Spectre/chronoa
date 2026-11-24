package app.chronoa

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * The ring screen drawn straight over whatever app is in use, as a full-screen overlay window.
 * Used when Android doesn't let [RingActivity] open from the background, which some phones
 * refuse even with "Display over other apps" allowed.
 */
object OverlayRing {
    private var window: View? = null
    private var screen: RingScreen? = null

    val showing get() = window != null

    fun show(c: Context, kind: Int, rangAt: Long): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !Settings.canDrawOverlays(c)) return false
        if (window != null) return true
        val app = c.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val s = RingScreen(app, kind, rangAt, Store.ringLabel(app)) { hide(app) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        return try {
            wm.addView(s.view, lp)
            if (Build.VERSION.SDK_INT >= 30) {
                s.view.windowInsetsController?.hide(WindowInsets.Type.systemBars())
            }
            window = s.view
            screen = s
            s.start()
            Ringer.quiet(app)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    fun hide(c: Context) {
        val v = window ?: return
        screen?.stop()
        window = null
        screen = null
        try {
            c.applicationContext.getSystemService(WindowManager::class.java).removeView(v)
        } catch (e: RuntimeException) {
        }
    }
}
