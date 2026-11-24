package app.chronoa

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.media.RingtoneManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.NumberPicker
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/** Snooze length, when the alarm countdown starts, and where countdowns show. */
class SettingsActivity : Activity() {
    private lateinit var snoozeRow: TextView
    private lateinit var pane: ThemePane

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        snoozeRow = findViewById(R.id.snooze_row)
        snoozeRow.setOnClickListener { pickSnooze() }
        updateSnoozeRow()

        findViewById<TextView>(R.id.snooze_limit_row).setOnClickListener { pickSnoozeLimit() }
        updateSnoozeLimitRow()
        toggle(R.id.wake_check, Store.wakeCheck(this)) { Store.setWakeCheck(this, it) }
        findViewById<TextView>(R.id.max_ring_row).setOnClickListener { pickMaxRing() }
        updateMaxRingRow()
        findViewById<TextView>(R.id.tone_row).setOnClickListener { pickTone() }
        updateToneRow()
        toggle(R.id.vibrate, Store.vibrate(this)) { Store.setVibrate(this, it) }
        toggle(R.id.gentle, Store.gentleWake(this)) { Store.setGentleWake(this, it) }
        toggle(R.id.hold_dismiss, Store.holdToDismiss(this)) { Store.setHoldToDismiss(this, it) }

        findViewById<RadioGroup>(R.id.alarm_lead).apply {
            check(if (Store.alarmLeadMinutes(this@SettingsActivity) == 5) R.id.lead_5 else R.id.lead_10)
            setOnCheckedChangeListener { _, id ->
                Store.setAlarmLeadMinutes(this@SettingsActivity, if (id == R.id.lead_5) 5 else 10)
                Upcoming.refresh(this@SettingsActivity)
            }
        }
        findViewById<Switch>(R.id.status_countdown).apply {
            isChecked = Store.statusCountdown(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setStatusCountdown(this@SettingsActivity, on)
                Timer.show(this@SettingsActivity)
                Upcoming.refresh(this@SettingsActivity)
            }
        }
        findViewById<Switch>(R.id.widget_countdown).apply {
            isChecked = Store.widgetCountdown(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setWidgetCountdown(this@SettingsActivity, on)
                TimerWidget.update(this@SettingsActivity)
                pane.refreshPreview()
            }
        }
        findViewById<View>(R.id.add_widget).setOnClickListener { TimerWidget.requestPin(this) }

        listOf(R.id.vibrate, R.id.gentle, R.id.hold_dismiss, R.id.wake_check).forEach { Controls.switch(findViewById(it)) }
        Controls.switch(findViewById(R.id.status_countdown))
        Controls.switch(findViewById(R.id.widget_countdown))
        Controls.button(findViewById(R.id.add_widget))
        Controls.button(findViewById(R.id.add_wide_widget))
        findViewById<View>(R.id.add_wide_widget).setOnClickListener { TimerWidget.requestPin(this, wide = true) }
        Controls.check(findViewById(R.id.lead_5), round = true)
        Controls.check(findViewById(R.id.lead_10), round = true)
        Glow.apply(findViewById<View>(android.R.id.content))

        // A new app color redraws the whole screen in it, on the Settings tab.
        pane = ThemePane(this) {
            intent.putExtra(EXTRA_THEME_TAB, false)
            recreate()
        }
        pane.apply {
            buildAppColour(findViewById(R.id.app_colour))
            build(findViewById(R.id.widget_colours), findViewById(R.id.theme_content))
        }
        findViewById<Switch>(R.id.widget_clock_show).apply {
            isChecked = Store.widgetClock(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setWidgetClock(this@SettingsActivity, on)
                TimerWidget.update(this@SettingsActivity)
                pane.refreshPreview()
            }
            Controls.switch(this)
            Glow.style(this)
        }
        findViewById<ImageButton>(R.id.info).apply {
            setOnClickListener {
                Store.setShowHints(this@SettingsActivity, !Store.showHints(this@SettingsActivity))
                showHints()
            }
        }
        showHints()
        findViewById<View>(R.id.tab_settings).setOnClickListener { showTab(false) }
        findViewById<View>(R.id.tab_theme).setOnClickListener { showTab(true) }
        showTab(intent.getBooleanExtra(EXTRA_THEME_TAB, false))
    }

    /** The explanations under settings: shown or hidden by the (i) button, lit while shown. */
    private fun showHints() {
        val on = Store.showHints(this)
        fun walk(v: View) {
            if (v.tag == "hint") v.visibility = if (on) View.VISIBLE else View.GONE
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(findViewById(android.R.id.content))
        findViewById<ImageButton>(R.id.info).apply {
            imageTintList = android.content.res.ColorStateList.valueOf(Theme.core(this@SettingsActivity))
            alpha = if (on) 1f else 0.5f
        }
    }

    private fun showTab(theme: Boolean) {
        intent.putExtra(EXTRA_THEME_TAB, theme)
        findViewById<View>(R.id.settings_pane).visibility = if (theme) View.GONE else View.VISIBLE
        findViewById<View>(R.id.theme_pane).visibility = if (theme) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tab_settings).alpha = if (theme) 0.45f else 1f
        findViewById<View>(R.id.tab_theme).alpha = if (theme) 1f else 0.45f
    }

    override fun onResume() {
        super.onResume()
        // Offered wherever the launcher supports it. (Launchers can keep stale widget ids
        // around, so "is one placed already" can't be told reliably.)
        val canPin = Build.VERSION.SDK_INT >= 26 &&
            android.appwidget.AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported
        findViewById<View>(R.id.add_widget).visibility = if (canPin) View.VISIBLE else View.GONE
        findViewById<View>(R.id.add_wide_widget).visibility = if (canPin) View.VISIBLE else View.GONE
    }

    private fun toggle(id: Int, on: Boolean, set: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = on
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }
    }

    private fun updateSnoozeLimitRow() {
        val n = Store.snoozeLimit(this)
        findViewById<TextView>(R.id.snooze_limit_row).text =
            getString(R.string.snooze_limit_row, if (n == 0) getString(R.string.no_limit) else n.toString())
    }

    private fun pickSnoozeLimit() {
        val steps = listOf(0, 1, 2, 3, 5, 10)
        val dial = NumberPicker(this).apply {
            minValue = 0
            maxValue = steps.size - 1
            displayedValues = steps.map { if (it == 0) getString(R.string.no_limit) else it.toString() }.toTypedArray()
            wrapSelectorWheel = false
            value = steps.indexOf(Store.snoozeLimit(this@SettingsActivity)).coerceAtLeast(0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.snooze_limit_title)
            .setView(dial)
            .setPositiveButton(R.string.ok) { _, _ ->
                Store.setSnoozeLimit(this, steps[dial.value])
                updateSnoozeLimitRow()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { Glow.apply(it) }
    }

    private fun updateMaxRingRow() {
        findViewById<TextView>(R.id.max_ring_row).text = getString(R.string.max_ring_row, Store.maxRingMinutes(this))
    }

    private fun pickMaxRing() {
        val steps = listOf(1, 5, 10, 15, 20, 30)
        val dial = NumberPicker(this).apply {
            minValue = 0
            maxValue = steps.size - 1
            displayedValues = steps.map { it.toString() }.toTypedArray()
            wrapSelectorWheel = false
            value = steps.indexOf(Store.maxRingMinutes(this@SettingsActivity)).coerceAtLeast(0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.max_ring_title)
            .setView(dial)
            .setPositiveButton(R.string.ok) { _, _ ->
                Store.setMaxRingMinutes(this, steps[dial.value])
                updateMaxRingRow()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { Glow.apply(it) }
    }

    private fun updateToneRow() {
        val name = Store.alarmTone(this)?.let { uri ->
            RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this)
        } ?: getString(R.string.default_tone)
        findViewById<TextView>(R.id.tone_row).text = getString(R.string.tone_row, name)
    }

    /** Android's own sound picker, listing the alarm sounds on the phone. */
    @Suppress("DEPRECATION")
    private fun pickTone() {
        val current = Store.alarmTone(this)?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        startActivityForResult(i, REQ_TONE)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TONE || resultCode != RESULT_OK) return
        val picked = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // The "Default" entry follows whatever the phone's alarm sound is: store nothing.
        Store.setAlarmTone(this, picked?.takeIf { it != Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString())
        updateToneRow()
    }

    companion object {
        private const val REQ_TONE = 1
        private const val EXTRA_THEME_TAB = "theme_tab"
    }

    private fun updateSnoozeRow() {
        snoozeRow.text = getString(R.string.snooze_row, Store.snoozeMinutes(this))
    }

    private fun pickSnooze() {
        val steps = (5..60 step 5).toList()
        val dial = NumberPicker(this).apply {
            minValue = 0
            maxValue = steps.size - 1
            displayedValues = steps.map { it.toString() }.toTypedArray()
            wrapSelectorWheel = false
            value = steps.indexOf(Store.snoozeMinutes(this@SettingsActivity)).coerceAtLeast(0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.snooze_length)
            .setView(dial)
            .setPositiveButton(R.string.ok) { _, _ ->
                Store.setSnoozeMinutes(this, steps[dial.value])
                updateSnoozeRow()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { Glow.apply(it) }
    }
}
