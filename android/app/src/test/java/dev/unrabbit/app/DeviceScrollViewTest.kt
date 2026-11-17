package dev.unrabbit.app

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceScrollViewTest {
    private fun feed(contentHeight: Int = 2000) = DeviceScrollView(RuntimeEnvironment.getApplication()).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(View(context).apply { minimumHeight = contentHeight }, FrameLayout.LayoutParams(320, contentHeight))
        measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        layout(0, 0, 320, 400)
        assertEquals(contentHeight, getChildAt(0).height)
    }

    private fun frame(feed: DeviceScrollView, millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        feed.computeScroll()
    }

    @Test fun rapidWheelTicksAccelerateWhileStillMovingThroughIntermediatePositions() {
        val feed = feed()
        feed.scrollTo(0, 200)
        feed.scrollByWheel(48)
        assertEquals(200, feed.scrollY)
        frame(feed, 40)
        assertTrue(feed.scrollY in 201..247)
        val before = feed.scrollY
        feed.scrollByWheel(48)
        feed.scrollByWheel(48)
        assertEquals(before, feed.scrollY)
        frame(feed, 40)
        assertTrue(feed.scrollY in (before + 1)..535)
        frame(feed, 200)
        assertEquals(536, feed.scrollY) // First tick 48 px, then two fast ticks capped at 144 px each.
        frame(feed, 300)
        assertEquals(536, feed.scrollY)
    }

    @Test fun slowTicksStayPreciseAndPausingResetsTheBoost() {
        val feed = feed()
        repeat(3) {
            feed.scrollByWheel(48)
            frame(feed, 250)
        }
        assertEquals(144, feed.scrollY)
        feed.scrollByWheel(48)
        frame(feed, 40)
        feed.scrollByWheel(48)
        frame(feed, 250)
        val afterFastTicks = feed.scrollY
        assertTrue(afterFastTicks > 144 + 96)
        feed.scrollByWheel(48)
        frame(feed, 250)
        assertEquals(afterFastTicks + 48, feed.scrollY)
    }

    @Test fun fasterTurningProgressivelyIncreasesTravelInBothDirections() {
        fun travel(interval: Long, direction: Int): Int {
            val feed = feed(5000)
            feed.scrollTo(0, 2000)
            repeat(4) {
                feed.scrollByWheel(direction * 48)
                frame(feed, interval)
            }
            frame(feed, 250)
            return (feed.scrollY - 2000) * direction
        }
        for (direction in listOf(-1, 1)) {
            val slow = travel(200, direction)
            val medium = travel(100, direction)
            val fast = travel(40, direction)
            assertEquals(192, slow)
            assertTrue(medium > slow)
            assertTrue(fast > medium)
            assertEquals(fast, travel(1, direction))
        }
    }

    @Test fun reversingDiscardsPendingTravelAndMovesBackImmediately() {
        val feed = feed()
        feed.scrollTo(0, 400)
        repeat(4) { feed.scrollByWheel(48) }
        frame(feed, 40)
        val before = feed.scrollY
        feed.scrollByWheel(-48)
        assertEquals(before, feed.scrollY)
        frame(feed, 40)
        assertTrue(feed.scrollY < before)
        frame(feed, 200)
        assertEquals(before - 48, feed.scrollY)
    }

    @Test fun contentEdgesDoNotBuildUpExtraTravel() {
        val feed = feed()
        feed.scrollTo(0, 1580)
        repeat(10) { feed.scrollByWheel(48) }
        frame(feed, 250)
        assertEquals(1600, feed.scrollY)
        feed.scrollByWheel(-48)
        frame(feed, 250)
        assertEquals(1552, feed.scrollY)
        feed.scrollByWheel(-5000)
        frame(feed, 250)
        assertEquals(0, feed.scrollY)
        val shortFeed = feed(200)
        shortFeed.scrollByWheel(48)
        frame(shortFeed, 250)
        assertEquals(0, shortFeed.scrollY)
    }

    @Test fun touchAndProgrammaticPositionChangesCancelPendingWheelTravel() {
        val feed = feed()
        feed.scrollByWheel(96)
        frame(feed, 40)
        val before = feed.scrollY
        val now = SystemClock.uptimeMillis()
        val touch = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        try { feed.dispatchTouchEvent(touch) } finally { touch.recycle() }
        frame(feed, 250)
        assertEquals(before, feed.scrollY)

        feed.scrollByWheel(48)
        frame(feed, 250)
        assertEquals(before + 48, feed.scrollY)

        feed.scrollByWheel(96)
        frame(feed, 40)
        feed.scrollTo(0, 500)
        frame(feed, 250)
        assertEquals(500, feed.scrollY)
    }

    @Test fun wheelTakesOverAnActiveTouchFlingWithoutLaterJumpingBack() {
        val feed = feed()
        feed.scrollTo(0, 400)
        feed.fling(2000)
        frame(feed, 40)
        val before = feed.scrollY
        assertTrue(before > 400)
        feed.scrollByWheel(-48)
        frame(feed, 250)
        assertEquals(before - 48, feed.scrollY)
        frame(feed, 500)
        assertEquals(before - 48, feed.scrollY)
    }

    @Test fun hiddenChatStopsAndDisabledAnimationsScrollDirectly() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val feed = feed()
        controller.get().setContentView(feed, android.view.ViewGroup.LayoutParams(320, 400))
        shadowOf(Looper.getMainLooper()).idle()
        feed.scrollByWheel(96)
        frame(feed, 40)
        val before = feed.scrollY
        feed.visibility = View.INVISIBLE
        frame(feed, 250)
        assertEquals(before, feed.scrollY)
        feed.visibility = View.VISIBLE
        ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType, 0f))
        try {
            feed.scrollByWheel(48)
            assertEquals(before + 48, feed.scrollY)
            frame(feed, 40)
            feed.scrollByWheel(48)
            assertEquals(before + 192, feed.scrollY)
        } finally {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ClassParameter.from(Float::class.javaPrimitiveType, 1f))
            controller.pause().stop().destroy()
        }
    }
}
