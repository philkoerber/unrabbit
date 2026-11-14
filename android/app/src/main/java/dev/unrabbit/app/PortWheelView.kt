package dev.unrabbit.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** A bounded number wheel. Slow turns are exact; sustained fast turns cover larger port ranges. */
@SuppressLint("ViewConstructor", "SetTextI18n") // Protocol numbers use ASCII digits.
class PortWheelView(context: Context, initialPort: Int, private val onDone: (Int) -> Unit, onCancel: () -> Unit) : FrameLayout(context) {
    var value = initialPort.coerceIn(1, 65535)
        private set
    private var lastTurn = Long.MIN_VALUE
    private var lastDirection = 0
    private var streak = 0
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun number(size: Float, color: Int) = TextView(context).apply {
        typeface = resources.getFont(R.font.plex_regular)
        textSize = size; setTextColor(color); gravity = Gravity.CENTER; includeFontPadding = false
    }
    private val previous = number(24f, 0x66afb4a8)
    private val current = number(56f, context.getColor(R.color.unrabbit_accent)).apply {
        tag = "unrabbit-port-value"
        setOnClickListener { confirm() }
        isFocusable = false
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val next = number(24f, 0x66afb4a8)
    init {
        tag = "unrabbit-port-wheel"
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(number(14f, 0xffafb4a8.toInt()).apply {
                text = "SSH-Port"; setPadding(0, 0, 0, dp(20))
            })
            addView(previous, LinearLayout.LayoutParams(-1, dp(40)))
            addView(current, LinearLayout.LayoutParams(-1, dp(76)))
            addView(next, LinearLayout.LayoutParams(-1, dp(40)))
        }
        addView(column, LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(32); rightMargin = dp(32) })
        addOverlayCloseControl("Portänderung verwerfen", onCancel)
        refresh()
    }
    fun rotate(amount: Float) {
        if (!amount.isFinite() || amount == 0f) return
        val now = SystemClock.uptimeMillis()
        val direction = amount.sign.toInt()
        streak = if (direction == lastDirection && lastTurn != Long.MIN_VALUE && now - lastTurn < 120) streak + 1 else 0
        lastTurn = now; lastDirection = direction
        val step = when { streak >= 48 -> 1000; streak >= 24 -> 100; streak >= 8 -> 10; else -> 1 }
        value = (value + direction * step * abs(amount).coerceIn(1f, 100f).roundToInt()).coerceIn(1, 65535)
        refresh()
    }
    fun confirm() = onDone(value)
    private fun refresh() {
        previous.text = if (value > 1) (value - 1).toString() else ""
        current.text = value.toString()
        current.contentDescription = "SSH-Port $value übernehmen"
        next.text = if (value < 65535) (value + 1).toString() else ""
    }
}
