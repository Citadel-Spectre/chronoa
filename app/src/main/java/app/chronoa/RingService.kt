package app.chronoa

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/**
 * Plays an alarm or timer: the chosen tone, looping on the alarm volume, with vibration if it's
 * on, rising gently for alarms if that's on, and giving up after the set maximum. Runs as a
 * foreground service so it keeps going whatever the screen is doing.
 */
class RingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var kind = Scheduler.KIND_ALARM

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        kind = intent?.getIntExtra(Scheduler.EXTRA_KIND, Scheduler.KIND_ALARM) ?: Scheduler.KIND_ALARM
        val n: Notification = Ringer.serviceNotification(this, kind)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Ringer.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(Ringer.SERVICE_ID, n)
        }
        stopSound()
        startSound(this, gentle = kind != Scheduler.KIND_TIMER && Store.gentleWake(this))
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ Ringer.timedOut(this, kind) }, Store.maxRingMinutes(this) * 60_000L)
        if (kind != Scheduler.KIND_TIMER && Store.gentleWake(this)) ramp(0)
        return START_NOT_STICKY
    }

    private fun startSound(c: Context, gentle: Boolean) {
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        player = tone(c)?.let { uri ->
            try {
                MediaPlayer().apply {
                    setAudioAttributes(audio)
                    setDataSource(c, uri)
                    isLooping = true
                    if (gentle) setVolume(RAMP_START, RAMP_START)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                null
            }
        }
        if (Store.vibrate(c)) {
            @Suppress("DEPRECATION")
            vibrator = (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).apply {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrate(VibrationEffect.createWaveform(VIBRATION, 0), audio)
                } else {
                    vibrate(VIBRATION, 0, audio)
                }
            }
        }
    }

    /** Gentle wake-up: from quiet to full volume over [RAMP_MS], a step a second. */
    private fun ramp(step: Int) {
        val steps = (RAMP_MS / 1000).toInt()
        if (step > steps) return
        val f = step.toFloat() / steps
        // Loudness is heard on a log scale, so ease in with a square.
        val v = RAMP_START + (1 - RAMP_START) * f * f
        player?.setVolume(v, v)
        main.postDelayed({ ramp(step + 1) }, 1000)
    }

    private fun stopSound() {
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
            }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopSound()
        super.onDestroy()
    }

    companion object {
        private const val RAMP_MS = 30_000L
        private const val RAMP_START = 0.05f
        private val VIBRATION = longArrayOf(0, 800, 800)

        /** The ringing alarm's own sound, else the one in settings, else the system alarm sound, else any notification sound. */
        fun tone(c: Context): Uri? =
            Store.ringTone(c).takeIf { it.isNotEmpty() }?.let(Uri::parse)
                ?: Store.alarmTone(c)?.let(Uri::parse)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: Settings.System.DEFAULT_NOTIFICATION_URI

        fun start(c: Context, kind: Int): Boolean = try {
            val i = Intent(c, RingService::class.java).putExtra(Scheduler.EXTRA_KIND, kind)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            true
        } catch (e: RuntimeException) {
            false
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, RingService::class.java))
        }
    }
}
