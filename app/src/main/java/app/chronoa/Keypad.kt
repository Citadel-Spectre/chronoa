package app.chronoa

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import java.util.Locale

/**
 * Phone-style number pad: 1-9, then 00, 0 and backspace. Digits fill from the right, so
 * typing 7 3 0 gives 07:30. Long-press backspace to clear.
 */
class Keypad(
    context: Context,
    private val maxDigits: Int,
    /** Keep zeros typed first, so 0 0 0 0 can mean 00:00 (the alarm entry). */
    private val keepZeros: Boolean = false,
    private val onChange: (String) -> Unit,
) : LinearLayout(context) {
    var digits = ""
        set(value) {
            field = (if (keepZeros) value else value.trimStart('0')).takeLast(maxDigits)
            onChange(field)
        }

    init {
        orientation = VERTICAL
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("00", "0", BACKSPACE),
        )
        val height = (64 * resources.displayMetrics.density).toInt()
        for (row in rows) {
            val line = LinearLayout(context)
            for (key in row) {
                val b = Button(context, null, android.R.attr.borderlessButtonStyle)
                b.text = key
                b.textSize = 28f
                Glow.style(b)
                b.setOnClickListener { press(key) }
                if (key == BACKSPACE) b.setOnLongClickListener { digits = ""; true }
                line.addView(b, LayoutParams(0, height, 1f))
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun press(key: String) {
        if (key == BACKSPACE) {
            digits = digits.dropLast(1)
            return
        }
        val next = (digits + key).let { if (keepZeros) it else it.trimStart('0') }
        if (next.length <= maxDigits) digits = next
    }

    companion object {
        private const val BACKSPACE = "⌫"

        /** "730" with 4 digits -> [7, 30]; "13005" with 6 digits -> [1, 30, 5]. */
        fun split(digits: String, maxDigits: Int): List<Int> =
            digits.padStart(maxDigits, '0').chunked(2).map { it.toInt() }

        fun format(parts: List<Int>) = parts.joinToString(":") { String.format(Locale.ROOT, "%02d", it) }
    }
}
