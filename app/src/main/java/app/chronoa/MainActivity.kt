package app.chronoa

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.RingtoneManager
import java.util.Calendar
import java.text.DateFormatSymbols
import android.media.AudioManager
import android.graphics.Color
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var tabAlarm: TextView
    private lateinit var tabStopwatch: TextView
    private lateinit var stopwatch: StopwatchPane
    private lateinit var stopwatchPane: View
    private lateinit var tabTimer: TextView
    private lateinit var alarmPane: View
    private lateinit var timerPane: View
    private lateinit var banner: TextView
    private lateinit var timerInput: TextView
    private lateinit var timerKeypad: Keypad
    private lateinit var pickers: View
    private lateinit var timerText: TextView
    private lateinit var startButton: Button
    private lateinit var resetButton: Button

    private val adapter = AlarmAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var bannerAction: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            updateTimer()
            // Keeps going while visible, so Pause/Reset from the notification show up here too.
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ringer.ensureChannel(this)

        tabAlarm = findViewById(R.id.tab_alarm)
        tabStopwatch = findViewById(R.id.tab_stopwatch)
        stopwatchPane = findViewById(R.id.stopwatch_pane)
        stopwatch = StopwatchPane(this)
        findViewById<android.widget.FrameLayout>(R.id.stopwatch_pane).addView(stopwatch)
        tabTimer = findViewById(R.id.tab_timer)
        alarmPane = findViewById(R.id.alarm_pane)
        timerPane = findViewById(R.id.timer_pane)
        banner = findViewById(R.id.banner)
        timerInput = findViewById(R.id.timer_input)
        pickers = findViewById(R.id.pickers)
        timerText = findViewById(R.id.timer_text)
        startButton = findViewById(R.id.start)
        resetButton = findViewById(R.id.reset)

        tabAlarm.setOnClickListener { showTab(0) }
        tabTimer.setOnClickListener { showTab(1) }
        tabStopwatch.setOnClickListener { showTab(TAB_STOPWATCH) }
        banner.setOnClickListener { bannerAction?.invoke() }

        val list = findViewById<ListView>(R.id.alarm_list)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        findViewById<View>(R.id.add_alarm).setOnClickListener { pickTime(null) }
        themeShown = Store.theme(this)
        findViewById<View>(R.id.glow_line).setBackgroundColor((Theme.glow(this) and 0x00FFFFFF) or 0x55000000)
        findViewById<ImageButton>(R.id.settings).imageTintList = android.content.res.ColorStateList.valueOf(Theme.core(this))
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        timerKeypad = Keypad(this, 6) { digits ->
            timerInput.text = Keypad.format(Keypad.split(digits, 6))
            timerInput.alpha = if (digits.isEmpty()) 0.4f else 1f
            updateTimer()
        }
        findViewById<FrameLayout>(R.id.timer_keypad).addView(timerKeypad)
        // The timer always opens at 00:00:00.
        timerKeypad.digits = ""
        startButton.setOnClickListener { startOrPause() }
        resetButton.setOnClickListener { resetTimer() }

        showTab(intent.getIntExtra(Timer.EXTRA_TAB, Store.tab(this)))
        Controls.symbol(findViewById(R.id.add_alarm), "+")
        Controls.button(startButton)
        Controls.button(resetButton)
        Glow.apply(findViewById<View>(android.R.id.content))

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(Timer.EXTRA_TAB)) showTab(intent.getIntExtra(Timer.EXTRA_TAB, 1))
    }

    /** The theme this screen was drawn in; a new one picked in settings redraws it. */
    private var themeShown = 0

    override fun onResume() {
        super.onResume()
        if (Store.theme(this) != themeShown) {
            recreate()
            return
        }
        refreshAlarms()
        updateBanner()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (Store.tab(this) == TAB_STOPWATCH) stopwatch.resume()
    }

    override fun onPause() {
        stopwatch.pause()
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        updateBanner()
    }

    // Tabs: 0 alarm, 1 timer (as the notifications ask for them), 2 stopwatch.
    private fun showTab(tab: Int) {
        if (entryOpen()) closeEntry()
        Store.setTab(this, tab)
        alarmPane.visibility = if (tab == 0) View.VISIBLE else View.GONE
        timerPane.visibility = if (tab == 1) View.VISIBLE else View.GONE
        stopwatchPane.visibility = if (tab == TAB_STOPWATCH) View.VISIBLE else View.GONE
        tabAlarm.alpha = if (tab == 0) 1f else 0.45f
        tabTimer.alpha = if (tab == 1) 1f else 0.45f
        tabStopwatch.alpha = if (tab == TAB_STOPWATCH) 1f else 0.45f
        if (tab == TAB_STOPWATCH) stopwatch.resume() else stopwatch.pause()
    }

    companion object {
        private const val TAB_STOPWATCH = 2
        /** A space as wide as a digit, so the colon stays put while typing. */
        private const val FIGURE_SPACE = '\u2007'
        private const val REQ_TONE = 1
        private const val WEEKDAYS = 0b0011111
        private const val WEEKENDS = 0b1100000
    }

    // Alarms

    private fun refreshAlarms() {
        adapter.items = Store.alarms(this)
        adapter.notifyDataSetChanged()
    }

    /**
     * Setting an alarm's time, laid out like the Timer tab: a big HH:MM display, the keypad, and
     * Reset / Cancel / Save buttons. It takes the place of the alarm list until it's closed.
     */
    private fun pickTime(existing: Alarm?) {
        val dp = resources.displayMetrics.density
        val display = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 56f
            fontFeatureSettings = "tnum"
        }
        val label = EditText(this).apply {
            setHint(R.string.label_hint)
            setText(existing?.label.orEmpty())
            gravity = Gravity.CENTER
            textSize = 20f
            // Capital first letter, one line.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            val core = Theme.core(this@MainActivity)
            setHintTextColor(Color.argb(110, Color.red(core), Color.green(core), Color.blue(core)))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Theme.glow(this@MainActivity))
        }
        entryTone = existing?.tone.orEmpty()
        // A square key with a note on it; the chosen sound's name shows when picked.
        val sound = Button(this).apply {
            Controls.symbol(this, "♪")
            setOnClickListener { pickEntryTone() }
        }
        entrySound = sound
        updateEntrySound()
        val reset = Button(this).apply { setText(R.string.reset) }
        val cancel = Button(this).apply { setText(R.string.cancel) }
        val ok = Button(this).apply { setText(R.string.save) }
        listOf(reset, cancel, ok).forEach(Controls::button)

        // Until something is typed, an existing alarm keeps its time and a new one can't be saved.
        var entered: List<Int>? = null
        val keypad = Keypad(this, 4, keepZeros = true) { digits ->
            val (h, m) = Keypad.split(digits, 4)
            entered = if (digits.isEmpty()) null else listOf(h, m)
            // A new alarm starts as just the colon; typed digits fill in from the right, so
            // 0 0 0 0 is midnight. Editing shows the alarm's current time, faded, until typing.
            display.text = when {
                digits.isEmpty() && existing != null -> formatTime(existing.hour, existing.minute)
                else -> digits.padStart(4, FIGURE_SPACE).let { "${it.take(2)}:${it.drop(2)}" }
            }
            display.alpha = if (digits.isEmpty() && existing != null) 0.4f else 1f
            ok.isEnabled = if (digits.isEmpty()) existing != null else h <= 23 && m <= 59
            reset.isEnabled = digits.isNotEmpty()
        }
        keypad.setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
        reset.setOnClickListener { keypad.digits = "" }
        cancel.setOnClickListener { closeEntry() }
        ok.setOnClickListener {
            val (h, m) = entered ?: listOf(existing!!.hour, existing.minute)
            val base = existing ?: Alarm(Store.newAlarmId(this), h, m, true)
            closeEntry()
            // A new time means any skip set for the old one no longer applies.
            save(
                base.copy(
                    hour = h, minute = m, enabled = true, label = label.text.toString().trim(), skip = 0,
                    // Editing starts the delete-after count over.
                    deleteAfter = false, rung = 0, tone = entryTone,
                ),
            )
        }

        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            for (b in listOf(reset, cancel, ok)) {
                addView(b, LinearLayout.LayoutParams(0, (64 * dp).toInt(), 1f).apply {
                    marginStart = (4 * dp).toInt()
                    marginEnd = (4 * dp).toInt()
                })
            }
        }
        val title = TextView(this).apply {
            setText(if (existing == null) R.string.new_alarm else R.string.edit_alarm)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(title, full().apply { bottomMargin = (8 * dp).toInt() })
            addView(label, full().apply {
                marginStart = (48 * dp).toInt()
                marginEnd = (48 * dp).toInt()
                bottomMargin = (8 * dp).toInt()
            })
            addView(display, full().apply { bottomMargin = (16 * dp).toInt() })
            addView(keypad, full())
            addView(buttons, full().apply { topMargin = (8 * dp).toInt() })
        }
        // The sound key, bottom right.
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), 0, (16 * dp).toInt(), (12 * dp).toInt())
            addView(sound, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        keypad.digits = ""
        Glow.apply(page)
        content.isFocusableInTouchMode = true
        content.requestFocus()
        alarmEntry.removeAllViews()
        alarmEntry.addView(page)
        alarmEntry.visibility = View.VISIBLE
        alarmPane.visibility = View.GONE
    }

    // The sound picked on the alarm entry screen ("" = the one in settings).
    private var entryTone = ""
    private var entrySound: Button? = null

    private fun updateEntrySound() {
        val name = entryTone.takeIf { it.isNotEmpty() }?.let {
            RingtoneManager.getRingtone(this, Uri.parse(it))?.getTitle(this)
        } ?: getString(R.string.default_tone)
        entrySound?.contentDescription = getString(R.string.sound_button, name)
    }

    /** Android's sound picker; "Default" means the alarm sound from settings. */
    @Suppress("DEPRECATION")
    private fun pickEntryTone() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                entryTone.takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
            )
        startActivityForResult(i, REQ_TONE)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TONE || resultCode != RESULT_OK) return
        val picked = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        entryTone = picked?.takeIf { it != Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString().orEmpty()
        updateEntrySound()
        entrySound?.let { Toast.makeText(this, it.contentDescription, Toast.LENGTH_SHORT).show() }
    }

    private val alarmEntry get() = findViewById<FrameLayout>(R.id.alarm_entry)

    private fun entryOpen() = alarmEntry.visibility == View.VISIBLE

    private fun closeEntry() {
        alarmEntry.removeAllViews()
        alarmEntry.visibility = View.GONE
        if (Store.tab(this) == 0) alarmPane.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (entryOpen()) closeEntry() else super.onBackPressed()
    }

    private fun save(a: Alarm, announce: Boolean = true) {
        Store.putAlarm(this, a)
        Scheduler.schedule(this, a)
        if (a.enabled && alarmVolumeOff()) {
            Toast.makeText(this, R.string.volume_off_warning, Toast.LENGTH_LONG).show()
        }
        if (a.enabled && announce) {
            val mins = ((Scheduler.nextTrigger(a) - System.currentTimeMillis() + 59_999) / 60_000).toInt()
            val text = if (mins >= 60) {
                getString(R.string.alarm_in_hours, mins / 60, mins % 60)
            } else {
                getString(R.string.alarm_in_minutes, mins)
            }
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
        refreshAlarms()
        updateBanner()
    }

    private val dayNames get() = DateFormatSymbols(Locale.ENGLISH).shortWeekdays.let { w ->
        // Monday first.
        listOf(w[Calendar.MONDAY], w[Calendar.TUESDAY], w[Calendar.WEDNESDAY], w[Calendar.THURSDAY], w[Calendar.FRIDAY], w[Calendar.SATURDAY], w[Calendar.SUNDAY])
    }

    /** "Once", "Every day", "Weekdays", "Weekends", "Mon–Thu" or "Mon Wed Fri". */
    private fun daysText(days: Int): String {
        if (days == 0) return getString(R.string.once)
        if (days == Alarm.EVERY_DAY) return getString(R.string.every_day)
        if (days == WEEKDAYS) return getString(R.string.weekdays)
        if (days == WEEKENDS) return getString(R.string.weekends)
        val on = (0..6).filter { days and (1 shl it) != 0 }
        // A run of three or more days in a row reads better as a range.
        return if (on.size >= 3 && on.last() - on.first() == on.size - 1) {
            "${dayNames[on.first()]}–${dayNames[on.last()]}"
        } else {
            on.joinToString(" ") { dayNames[it] }
        }
    }

    private fun dayIndex(time: Long) = Scheduler.weekday(Calendar.getInstance().apply { timeInMillis = time })

    /** "deleted after" note for a delete-after alarm: after its ring, or after its last remaining day. */
    private fun deleteText(a: Alarm): String {
        if (!a.deleteAfter) return ""
        if (!a.repeats) return getString(R.string.then_deleted)
        val left = a.days and a.rung.inv()
        // The last of the remaining days to come round, counting from today.
        val today = Scheduler.weekday(Calendar.getInstance())
        val last = (0..6).map { (today + it) % 7 }.lastOrNull { left and (1 shl it) != 0 } ?: return getString(R.string.then_deleted)
        return getString(R.string.deletes_after, dayNames[last])
    }

    private fun dayName(time: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = time }
        return dayNames[Scheduler.weekday(cal)]
    }

    /** The weekday picker, opened from an alarm's Repeated box. */
    private fun pickDays(a: Alarm) {
        val dp = resources.displayMetrics.density
        var days = a.days
        val dialog = android.app.Dialog(this)
        val full = DateFormatSymbols(Locale.ENGLISH).weekdays.let { w ->
            listOf(w[Calendar.MONDAY], w[Calendar.TUESDAY], w[Calendar.WEDNESDAY], w[Calendar.THURSDAY], w[Calendar.FRIDAY], w[Calendar.SATURDAY], w[Calendar.SUNDAY])
        }
        val boxes = full.mapIndexed { i, name ->
            CheckBox(this).apply {
                text = name
                textSize = 20f
                isChecked = days and (1 shl i) != 0
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), 0, (6 * dp).toInt())
                setOnCheckedChangeListener { _, on -> days = if (on) days or (1 shl i) else days and (1 shl i).inv() }
                Controls.check(this, round = true)
            }
        }
        fun set(mask: Int) = boxes.forEachIndexed { i, b -> b.isChecked = mask and (1 shl i) != 0 }
        fun button(text: Int, onClick: () -> Unit) = Button(this).apply {
            setText(text)
            Controls.button(this)
            setOnClickListener { onClick() }
        }
        fun row(vararg buttons: Button) = LinearLayout(this).apply {
            for (b in buttons) addView(b, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f).apply {
                marginStart = (3 * dp).toInt()
                marginEnd = (3 * dp).toInt()
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.bg))
                cornerRadius = 24 * dp
                setStroke((1.5f * dp).toInt(), Color.rgb(58, 61, 68))
            }
            addView(TextView(this@MainActivity).apply {
                setText(R.string.repeat_on)
                textSize = 22f
                setPadding((8 * dp).toInt(), 0, 0, (8 * dp).toInt())
            })
            boxes.forEach { addView(it) }
            addView(row(
                button(R.string.weekdays) { set(WEEKDAYS) },
                button(R.string.every_day) { set(Alarm.EVERY_DAY) },
                button(R.string.clear) { set(0) },
            ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
            addView(row(
                button(R.string.cancel) { dialog.dismiss() },
                button(R.string.done) {
                    dialog.dismiss()
                    // New days: an old skip may not fall on them any more.
                    save(a.copy(days = days, skip = 0, rung = 0), announce = false)
                },
            ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
        }
        Glow.apply(content)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun alarmVolumeOff(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(AudioManager.STREAM_ALARM) == 0
    }

    private fun raiseAlarmVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 3 + 3) / 4, AudioManager.FLAG_SHOW_UI)
            Toast.makeText(this, R.string.volume_raised, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // Do Not Disturb can forbid it: show the volume panel instead.
            am.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }
        updateBanner()
    }

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_alarm)
            .setPositiveButton(R.string.delete) { _, _ ->
                Store.removeAlarm(this, a.id)
                Scheduler.cancel(this, a.id)
                refreshAlarms()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { Glow.apply(it) }
    }

    private fun formatTime(hour: Int, minute: Int) = Keypad.format(listOf(hour, minute))

    private inner class AlarmAdapter : BaseAdapter() {
        var items: List<Alarm> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_alarm, parent, false).also {
                Controls.check(it.findViewById(R.id.daily))
                Controls.switch(it.findViewById(R.id.enabled))
                Controls.button(it.findViewById(R.id.skip))
                Glow.apply(it)
            }
            val a = items[position]
            val time = v.findViewById<TextView>(R.id.time)
            val details = v.findViewById<TextView>(R.id.details)
            val repeat = v.findViewById<CheckBox>(R.id.daily)
            val enabled = v.findViewById<Switch>(R.id.enabled)
            val skip = v.findViewById<Button>(R.id.skip)

            time.text = formatTime(a.hour, a.minute)
            time.alpha = if (a.enabled) 1f else 0.4f
            details.text = listOf(a.label, deleteText(a)).filter { it.isNotEmpty() }.joinToString(" · ")
            details.visibility = if (details.text.isEmpty()) View.GONE else View.VISIBLE
            details.alpha = if (a.enabled) 0.8f else 0.4f
            val days = v.findViewById<TextView>(R.id.days)
            days.text = daysText(a.days)
            days.alpha = if (a.enabled) 0.8f else 0.4f
            v.setOnClickListener { pickTime(a) }
            v.setOnLongClickListener { confirmDelete(a); true }

            // The box opens the weekday picker instead of ticking itself.
            repeat.setOnCheckedChangeListener(null)
            repeat.isChecked = a.repeats
            repeat.setOnClickListener {
                repeat.isChecked = a.repeats
                pickDays(a)
            }

            // Skip once: only for a repeating alarm that's on. Tapping again undoes it.
            val skipping = a.skip > System.currentTimeMillis()
            skip.visibility = if (a.enabled && a.repeats) View.VISIBLE else View.GONE
            skip.text = if (skipping) getString(R.string.skipping, dayName(a.skip)) else getString(R.string.skip_once)
            skip.setOnClickListener {
                if (skipping) {
                    val day = 1 shl dayIndex(a.skip)
                    save(a.copy(skip = 0, rung = if (a.deleteAfter) a.rung and day.inv() else a.rung), announce = false)
                } else {
                    val at = Scheduler.occurrence(a, System.currentTimeMillis())
                    // A skipped day counts as done for a delete-after alarm.
                    val next = a.copy(skip = at, rung = if (a.deleteAfter) a.rung or (1 shl dayIndex(at)) else a.rung)
                    if (next.deleteAfter && next.allDone) {
                        Store.removeAlarm(this@MainActivity, a.id)
                        Scheduler.cancel(this@MainActivity, a.id)
                        Toast.makeText(this@MainActivity, R.string.alarm_finished, Toast.LENGTH_SHORT).show()
                        refreshAlarms()
                    } else {
                        save(next, announce = false)
                    }
                }
            }

            // Clear listeners before setting state so recycled rows don't fire for the wrong alarm.
            enabled.setOnCheckedChangeListener(null)
            enabled.isChecked = a.enabled
            enabled.setOnCheckedChangeListener { _, checked -> save(a.copy(enabled = checked)) }
            return v
        }
    }

    // Timer

    // Fields over 59 just add up, so 00:90:00 is an hour and a half.
    private fun pickerMs(): Long {
        val (h, m, s) = Keypad.split(timerKeypad.digits, 6)
        return ((h * 60L + m) * 60 + s) * 1000
    }

    private fun startOrPause() {
        when {
            Store.timerEnd(this) > 0 -> Timer.pause(this)
            Store.timerLeft(this) > 0 -> Timer.resume(this)
            else -> {
                val ms = pickerMs()
                if (ms <= 0) return
                Store.setTimerLast(this, ms)
                Timer.start(this, ms)
            }
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** Stops any running or paused timer and clears the entry back to 00:00:00. */
    private fun resetTimer() {
        Timer.reset(this)
        timerKeypad.digits = ""
        updateTimer()
    }

    private fun updateTimer() {
        val end = Store.timerEnd(this)
        val left = Store.timerLeft(this)
        val idle = end == 0L && left == 0L
        pickers.visibility = if (idle) View.VISIBLE else View.GONE
        timerText.visibility = if (idle) View.GONE else View.VISIBLE
        if (!idle) timerText.text = formatDuration(if (end > 0) end - System.currentTimeMillis() else left)
        startButton.setText(
            when {
                end > 0 -> R.string.pause
                left > 0 -> R.string.resume
                else -> R.string.start
            }
        )
        // Both light up as soon as any time is entered, even before it's started.
        resetButton.isEnabled = !idle || pickerMs() > 0
        startButton.isEnabled = !idle || pickerMs() > 0
    }

    private fun formatDuration(ms: Long): String {
        val s = (maxOf(ms, 0) + 999) / 1000
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", m, sec)
        }
    }

    // Permissions an alarm clock depends on

    private fun updateBanner() {
        val pkg = Uri.parse("package:$packageName")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationsAllowed = Ringer.canRing(this) && (
            Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )
        val (text, action) = when {
            !notificationsAllowed -> R.string.fix_notifications to notificationSettings(pkg)
            !Scheduler.canExact(this) ->
                R.string.fix_exact to Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() ->
                R.string.fix_fullscreen to Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            !Ringer.canTakeOver(this) ->
                R.string.fix_overlay to Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            else -> 0 to null
        }
        bannerAction = action?.let { i -> { openSettings(i) } }
        if (action == null && Store.alarms(this).any { it.enabled } && alarmVolumeOff()) {
            bannerAction = ::raiseAlarmVolume
            banner.visibility = View.VISIBLE
            banner.setText(R.string.fix_volume)
            return
        }
        banner.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action != null) banner.setText(text)
    }

    private fun notificationSettings(pkg: Uri) = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
