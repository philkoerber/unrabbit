package dev.unrabbit.app

import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceStatusMonitorTest {
    @Test fun batteryBroadcastUpdatesWithoutPollingAndStopsWithTheScreen() {
        val context = RuntimeEnvironment.getApplication()
        val updates = mutableListOf<DeviceStatus>()
        val monitor = DeviceStatusMonitor(context, updates::add)
        monitor.start()
        fun battery(level: Int, scale: Int, plugged: Int) {
            context.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, level)
                .putExtra(BatteryManager.EXTRA_SCALE, scale)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged))
            shadowOf(Looper.getMainLooper()).idle()
        }
        battery(164, 200, BatteryManager.BATTERY_PLUGGED_USB)
        assertEquals(82, updates.last().batteryPercent)
        assertTrue(updates.last().charging)
        assertTrue(updates.last().hour.matches(Regex("\\d{2}")))
        assertTrue(updates.last().minute.matches(Regex("\\d{2}")))
        battery(-1, 0, 0)
        assertNull(updates.last().batteryPercent)
        assertFalse(updates.last().charging)
        monitor.stop()
        val count = updates.size
        battery(30, 100, 0)
        assertEquals(count, updates.size)
    }

    @Test fun explicitSnapAndAnimatedTransitionVisitsIntermediateValues() {
        val rail = StatusRailView(RuntimeEnvironment.getApplication())
        rail.setCompact(true, animate = false)
        assertEquals(1f, rail.compactFraction, 0f)
        val frames = mutableListOf<Float>()
        rail.onProgress = { frames += it }
        rail.setCompact(false, animate = true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertEquals(0f, rail.compactFraction, 0f)
        assertTrue(frames.any { it > 0f && it < 1f })
        rail.setCompact(true, animate = false)
        assertEquals(1f, rail.compactFraction, 0f)
    }
}
