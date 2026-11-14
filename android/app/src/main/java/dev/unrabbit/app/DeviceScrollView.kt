package dev.unrabbit.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.widget.ScrollView

/** Keep long content inside its viewport, including during alpha animations. */
@SuppressLint("ViewConstructor")
open class DeviceScrollView(context: Context) : ScrollView(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipBounds = Rect(0, 0, w, h)
    }
}
