package dev.unrabbit.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class SpeechLanguageView(context: Context, current: SpeechLanguage,
    onChoose: (SpeechLanguage) -> Unit, onClose: () -> Unit) : FrameLayout(context) {
    val navigation = mutableListOf<Pair<View, () -> Unit>>()
    var onSelection: (Int) -> Unit = {}
    init {
        tag = "speech-language"
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun label(value: String, size: Float) = TextView(context).apply {
            text = value; textSize = size; typeface = resources.getFont(R.font.plex_regular)
            setTextColor(0xfff2f0e8.toInt()); includeFontPadding = false
            setPadding(dp(16), 0, 0, dp(16))
        }
        column.addView(label("Language", 24f))
        column.addView(label("For your voice messages.\nCurrent: ${current.label}", 14f))
        fun action(title: String, callback: () -> Unit) {
            val index = navigation.size
            val row = SettingsActionView(context, title).apply { setOnClickListener { onSelection(index); callback() } }
            navigation += row to callback
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        SpeechLanguage.entries.forEach { language -> action(language.label) { onChoose(language) } }
        action("Back", onClose)
        addView(column, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(24); rightMargin = dp(24) })
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
}
