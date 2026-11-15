package dev.unrabbit.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import kotlin.math.roundToInt

/** Shared, borderless menu row; selection uses the same orange dot as the icon menu. */
internal class SettingsActionView(context: Context, label: String) : Button(context) {
    private val density = resources.displayMetrics.density
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.unrabbit_accent) }

    init {
        text = label; contentDescription = label
        textSize = 20f; typeface = resources.getFont(R.font.plex_regular)
        isAllCaps = false; includeFontPadding = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        setTextColor(context.getColorStateList(R.color.selection_text))
        background = null; stateListAnimator = null
        minHeight = (52 * density).roundToInt(); minimumHeight = minHeight
        setPadding((16 * density).roundToInt(), (8 * density).roundToInt(), (12 * density).roundToInt(), (8 * density).roundToInt())
        defaultFocusHighlightEnabled = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isSelected || isActivated || isPressed)
            canvas.drawCircle(3 * density, height / 2f, 2.3f * density, dot)
    }
}
