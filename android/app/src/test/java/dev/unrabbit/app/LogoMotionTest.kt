package dev.unrabbit.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class LogoMotionTest {
    @Test fun allMorphCyclesVaryInDurationWithoutPositionJumps() {
        val motion = LogoMotion(Random(7))
        fun phases() = listOf(motion.driftPhase / (2 * Math.PI), motion.listeningPhase / (2 * Math.PI), motion.waitingPhase / 3)
        val lastWrap = IntArray(3)
        val periods = List(3) { mutableListOf<Int>() }
        var previous = phases()
        for (time in 10..120_000 step 10) {
            motion.advance(10)
            val current = phases()
            current.forEachIndexed { i, phase ->
                assertTrue(phase in 0.0..1.0)
                val step = (phase - previous[i] + 1) % 1
                assertTrue("Continuous phase despite changing tempo", step < .008)
                if (phase < previous[i]) { periods[i] += time - lastWrap[i]; lastWrap[i] = time }
            }
            previous = current
        }
        val bounds = listOf(5190..7610, 1490..2110, 4190..5810)
        periods.forEachIndexed { i, values ->
            assertTrue(values.all { it in bounds[i] })
            assertTrue(values.toSet().size > 5)
        }
    }

    @Test fun quietCadenceStaysLowWhileStatesAndTransitionsRemainSmooth() {
        val motion = LogoMotion(Random(14))
        repeat(1500) {
            motion.advance(83)
            assertEquals(83L, motion.frameDelayMillis(LogoMode.QUIET, false))
        }
        assertEquals(33L, motion.frameDelayMillis(LogoMode.WAITING, false))
        assertEquals(33L, motion.frameDelayMillis(LogoMode.LISTENING, false))
        assertEquals(33L, motion.frameDelayMillis(LogoMode.QUIET, true))
    }
}
