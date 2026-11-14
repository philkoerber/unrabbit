package dev.unrabbit.app

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** One quiet touch target, shared by both camera surfaces and the number wheel. */
internal fun FrameLayout.addOverlayCloseControl(label: String, onClose: () -> Unit) {
    fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    val close = MenuIconView(context, MenuIcon.CLOSE).apply {
        contentDescription = label
        isFocusable = false
        minimumHeight = 0
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x99171915.toInt())
        }
        setOnClickListener { onClose() }
    }
    addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
        topMargin = dp(8); rightMargin = dp(8)
    })
}
