package dev.unrabbit.app

import kotlin.math.PI
import kotlin.random.Random

/** Vary the tempo of continuous morph cycles without frame-to-frame noise. */
internal class LogoMotion(random: Random = Random.Default) {
    private class Cycle(private val random: Random, private val minMs: Double, private val maxMs: Double) {
        private var elapsed = 0.0
        private var duration = random.nextDouble(minMs, maxMs)
        val fraction get() = elapsed / duration
        fun advance(milliseconds: Long) {
            elapsed += milliseconds
            while (elapsed >= duration) {
                elapsed -= duration
                duration = random.nextDouble(minMs, maxMs)
            }
        }
    }

    private val drift = Cycle(Random(random.nextInt()), 5200.0, 7600.0)
    private val listening = Cycle(Random(random.nextInt()), 1500.0, 2100.0)
    private val waiting = Cycle(Random(random.nextInt()), 4200.0, 5800.0)

    val driftPhase get() = drift.fraction * 2 * PI
    val listeningPhase get() = listening.fraction * 2 * PI
    val waitingPhase get() = waiting.fraction * 3

    fun advance(milliseconds: Long) {
        require(milliseconds >= 0)
        drift.advance(milliseconds); listening.advance(milliseconds); waiting.advance(milliseconds)
    }

    fun frameDelayMillis(mode: LogoMode, transitioning: Boolean): Long =
        if (mode != LogoMode.QUIET || transitioning) 33L else 83L
}
