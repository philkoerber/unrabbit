package dev.unrabbit.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Settings is only a menu. Each action opens its own flow and returns here. */
@SuppressLint("ViewConstructor")
class SettingsView(
    context: Context,
    paired: Boolean,
    private val onClose: () -> Unit,
    private val onConnect: () -> Unit,
    private val onNetwork: () -> Unit,
) : FrameLayout(context) {
    val navigation = mutableListOf<Pair<View, () -> Unit>>()
    var onSelection: (Int) -> Unit = {}
    private val status = label("", 14f).apply {
        tag = "vm-status"; setPadding(dp(16), dp(8), 0, dp(20))
        setTextColor(0xffafb4a8.toInt())
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    init {
        tag = "unrabbit-settings"
        isClickable = true; isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        defaultFocusHighlightEnabled = false
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label("Einstellungen", 24f).apply { setPadding(dp(16), 0, 0, dp(4)) })
        column.addView(status)
        fun action(text: String, space: Int = 0, callback: () -> Unit) {
            val index = navigation.size
            val row = SettingsActionView(context, text).apply {
                setOnClickListener { onSelection(index); callback() }
            }
            navigation += row to callback
            column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(space) })
        }
        action(if (paired) "VM wechseln" else "VM verbinden", callback = onConnect)
        action("WLAN & SIM", callback = onNetwork)
        action("Zurück", 12, onClose)
        addView(column, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(24); rightMargin = dp(24) })
    }

    fun setConnectionState(value: VmConnectionState) { status.text = value.description }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; typeface = resources.getFont(R.font.plex_regular); includeFontPadding = false
        setTextColor(0xfff2f0e8.toInt())
    }
}
