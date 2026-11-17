package dev.unrabbit.app

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Scroller
import android.widget.ScrollView
import kotlin.math.roundToInt
import kotlin.math.sign

/** Keep long content inside its viewport, including during alpha animations. */
@SuppressLint("ViewConstructor")
open class DeviceScrollView(context: Context) : ScrollView(context) {
    private var wheelScroller: Scroller? = null
    private var lastWheelTime = 0L
    private var lastWheelDirection = 0

    fun scrollByWheel(deltaY: Int) {
        if (deltaY == 0 || childCount == 0) return
        val now = SystemClock.uptimeMillis()
        // Slow ticks stay precise; same-direction ticks 40 ms apart or faster reach 3×.
        val speed = if (lastWheelDirection == deltaY.sign)
            ((160L - (now - lastWheelTime)) / 120f).coerceIn(0f, 1f) else 0f
        val distance = (deltaY * (1f + 2f * speed)).roundToInt()
        lastWheelTime = now
        lastWheelDirection = deltaY.sign
        val scroller = wheelScroller ?: Scroller(context, DecelerateInterpolator(1.5f)).also { wheelScroller = it }
        val remaining = scroller.finalY - scrollY
        // Accumulate quick ticks, but discard the old destination when reversing direction.
        val origin = if (!scroller.isFinished && remaining.sign == deltaY.sign) scroller.finalY else scrollY
        val range = (getChildAt(0).height - height + paddingTop + paddingBottom).coerceAtLeast(0)
        val target = (origin + distance).coerceIn(0, range)

        // Finish any native touch fling before the wheel takes over.
        super.fling(0)
        super.computeScroll()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            scroller.forceFinished(true)
            super.scrollTo(scrollX, target)
            return
        }
        // ScrollView.smoothScrollBy falls back to jumps for closely spaced events.
        scroller.startScroll(0, scrollY, 0, target - scrollY, 180)
        postInvalidateOnAnimation()
    }

    fun stopWheelScroll() {
        wheelScroller?.forceFinished(true)
        lastWheelTime = 0L
        lastWheelDirection = 0
    }

    override fun computeScroll() {
        val scroller = wheelScroller
        if (scroller != null && scroller.computeScrollOffset()) {
            super.scrollTo(scrollX, scroller.currY)
            postInvalidateOnAnimation()
        } else super.computeScroll()
    }

    override fun scrollTo(x: Int, y: Int) {
        stopWheelScroll()
        super.scrollTo(x, y)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) stopWheelScroll()
        return super.dispatchTouchEvent(event)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) stopWheelScroll()
    }

    override fun onDetachedFromWindow() {
        stopWheelScroll()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipBounds = Rect(0, 0, w, h)
    }
}
