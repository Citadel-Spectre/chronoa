package app.chronoa

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Takes over the screen while something rings. Alarms get green Dismiss on the top half and red
 * Snooze on the bottom; timers get a keypad to type a new timer and a Dismiss / Start bar. Either
 * way, answering stops the ringing and drops straight back to whatever was on screen before.
 */
class RingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bind(intent)
        // Only once the content is set: before that there's no window to hide the bars of.
        hideSystemBars()
        showing = this
    }

    private var screen: RingScreen? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        screen?.stop()
        val s = RingScreen(
            this,
            intent.getIntExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_ALARM),
            intent.getLongExtra(Ringer.EXTRA_RANG_AT, 0L),
            intent.getStringExtra(Ringer.EXTRA_LABEL) ?: Store.ringLabel(this),
        ) { finishAndRemoveTask() }
        screen = s
        setContentView(s.view)
        s.start()
    }

    override fun onResume() {
        super.onResume()
        // The overlay version isn't needed while this is on screen.
        OverlayRing.hide(this)
        Ringer.quiet(this)
        screen?.start()
    }

    // Back doesn't close the ring screen: only Dismiss or Snooze answer it.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {}

    override fun onPause() {
        screen?.stop()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    override fun onDestroy() {
        if (showing === this) showing = null
        super.onDestroy()
    }

    companion object {
        var showing: RingActivity? = null
    }
}
