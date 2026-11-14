package dev.unrabbit.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import kotlin.math.*

enum class LogoMode { QUIET, LISTENING, WAITING }

/** Native, hole-preserving morph of the project's three compatible SVG paths. */
class LogoView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfff2f0e8.toInt() }
    private val path = Path()
    private val token = Regex("[MCLZ]|-?\\d+(?:\\.\\d+)?")
    private val source = JSONObject(context.assets.open("unrabbit-morph.json").bufferedReader().use { it.readText() })
    private val tokens = (0 until source.getJSONArray("frames").length()).map { i ->
        token.findAll(source.getJSONArray("frames").getString(i)).map { it.value }.toList()
    }
    private val frames = tokens.map { row -> row.filter { it.toFloatOrNull() != null }.map(String::toFloat).toFloatArray() }
    private val commands = tokens.first().filter { it.toFloatOrNull() == null }
    private val waitingFrames = (0..2).map { blob(it * 2 * PI / 3) }
    private val current = frames.first().clone()
    private val target = FloatArray(current.size)
    private var mode = LogoMode.QUIET
    private var running = false
    private var playbackActive = false
    internal val hasScheduledFrame get() = running
    private val motion = LogoMotion()
    private var lastFrame = 0L
    private var currentOpacity = 1f
    private var transitionRemaining = 0L
    var foregroundVisible = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var restFraction = 0f
        set(value) { field = value; invalidate() }
    private val frame = Runnable { running = false; invalidate() }

    init {
        require(tokens.all { row -> row.filter { it.toFloatOrNull() == null } == commands })
        require(frames.all { it.size == current.size })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Unrabbit bereit"
    }

    fun setMode(value: LogoMode) {
        if (mode == value) return
        mode = value
        transitionRemaining = 600
        contentDescription = when (value) {
            LogoMode.QUIET -> "Unrabbit bereit"
            LogoMode.LISTENING -> "Simulierte Aufnahme, kein Mikrofon aktiv"
            LogoMode.WAITING -> "Demo-Antwort wird vorbereitet"
        }
        invalidate()
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        lastFrame = 0L
        removeCallbacks(frame)
        running = false
        if (active) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val moving = ValueAnimator.areAnimatorsEnabled()
        val delta = if (lastFrame == 0L || !playbackActive) 0L else (now - lastFrame).coerceIn(0, 250)
        if (moving) motion.advance(delta)
        transitionRemaining = (transitionRemaining - delta).coerceAtLeast(0)
        val phase = motion.waitingPhase
        val first = floor(phase).toInt()
        val second = (first + 1) % 3
        val mix = (1 - cos((phase - first) * PI)) / 2
        when {
            !moving -> (if (mode == LogoMode.WAITING) waitingFrames[0] else frames[0]).copyInto(target)
            mode == LogoMode.QUIET -> {
                // Two offset waves keep the ready shape gently alive without a visible pause.
                // Stay close to the base contour so listening and waiting remain distinct.
                val drift = motion.driftPhase
                val firstWeight = (1 - cos(drift)) * .08
                val secondWeight = (1 - sin(drift)) * .10
                for (i in target.indices) target[i] = (frames[0][i] * (1 - firstWeight - secondWeight) +
                    frames[1][i] * firstWeight + frames[2][i] * secondWeight).toFloat()
            }
            mode == LogoMode.WAITING -> for (i in target.indices) target[i] =
                (waitingFrames[first][i] * (1 - mix) + waitingFrames[second][i] * mix).toFloat()
            else -> frames[0].copyInto(target)
        }
        var offset = 0
        var moves = 0
        for (command in commands) {
            if (command == "M") moves++
            val count = when (command) { "M", "L" -> 2; "C" -> 6; else -> 0 }
            for (j in 0 until count step 2) {
                val x = target[offset + j]
                val y = target[offset + j + 1]
                if (mode == LogoMode.LISTENING) {
                    val ear = ((95 - y) / 87).coerceIn(0f, 1f)
                    val pulse = if (moving) sin(motion.listeningPhase).toFloat() else 0f
                    target[offset + j] = 100 + (x - 100) * (if (moves == 2) 1.22f else .92f + .26f * ear) +
                        (if (x < 100) -1 else 1) * ear * pulse * 4
                    target[offset + j + 1] = if (y < 95) 5 + (y - 8) * 1.27f - ear * pulse * 3
                        else 115.5f + (y - 95) * .70f
                }
            }
            offset += count
        }
        val ease = if (!moving || lastFrame == 0L) 1f else (1 - exp(-delta.toFloat() / 140f))
        for (i in current.indices) {
            current[i] += (target[i] - current[i]) * ease
        }
        val targetOpacity = if (foregroundVisible && mode != LogoMode.LISTENING) .10f else 1f
        currentOpacity += (targetOpacity - currentOpacity) * ease
        paint.alpha = (currentOpacity * 255).roundToInt().coerceIn(0, 255)
        lastFrame = now
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        offset = 0
        for (command in commands) {
            when (command) {
                "M" -> { path.moveTo(current[offset], current[offset + 1]); offset += 2 }
                "L" -> { path.lineTo(current[offset], current[offset + 1]); offset += 2 }
                "C" -> { path.cubicTo(current[offset], current[offset + 1], current[offset + 2], current[offset + 3], current[offset + 4], current[offset + 5]); offset += 6 }
                "Z" -> path.close()
            }
        }
        // A stable inset contains every morph. The rest position leaves room for the large clock.
        val density = resources.displayMetrics.density
        val size = (min(width, height) - 48 * density).coerceAtLeast(0f)
        val restOffset = min(32 * density, ((height - size) / 2 - 24 * density).coerceAtLeast(0f)) * restFraction
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2 + restOffset)
        canvas.scale(size / 200, size / 200)
        canvas.drawPath(path, paint)
        canvas.restore()
        if (moving && playbackActive && windowVisibility == VISIBLE && isShown && isAttachedToWindow && !running) {
            running = true
            val transitioning = transitionRemaining > 0 || abs(currentOpacity - targetOpacity) > .005f
            postDelayed(frame, motion.frameDelayMillis(mode, transitioning))
        }
    }

    /** Same command topology as the rabbit, so the entire outline and hole can morph into a pebble. */
    private fun blob(seed: Double): FloatArray {
        val values = ArrayList<Float>()
        var contour = 0
        var index = 0
        while (index < commands.size) {
            check(commands[index++] == "M")
            val end = commands.indexOfFirstFrom(index, "Z")
            val segments = end - index
            val start = if (contour == 0) 1.9 else 2.4
            val step = 2 * PI / segments
            fun point(angle: Double): Pair<Double, Double> {
                val inner = contour == 1
                val wave = if (inner) 2 * sin(angle * 2 + seed) else 7 * sin(angle * 3 + seed)
                val rx = if (inner) 28.0 + 5 * cos(seed) else 67.0 + 4 * sin(seed)
                val ry = if (inner) 32.0 + 4 * sin(seed) else 65.0 + 5 * cos(seed)
                return (100 + (if (inner) 5 * sin(seed) else 0.0) + (rx + wave) * cos(angle)) to
                    (103 + (if (inner) 4 * cos(seed) else 0.0) + (ry + wave) * sin(angle))
            }
            fun add(p: Pair<Double, Double>) { values += p.first.toFloat(); values += p.second.toFloat() }
            fun tangent(a: Double): Pair<Double, Double> {
                val l = point(a - .001); val r = point(a + .001)
                return (r.first - l.first) / .002 to (r.second - l.second) / .002
            }
            add(point(start))
            repeat(segments) { segment ->
                val a = start + segment * step; val b = a + step
                if (commands[index++] == "C") {
                    val p = point(a); val q = point(b); val t = tangent(a); val u = tangent(b)
                    add((p.first + t.first * step / 3) to (p.second + t.second * step / 3))
                    add((q.first - u.first * step / 3) to (q.second - u.second * step / 3))
                }
                add(point(b))
            }
            index++ // Z
            contour++
        }
        return values.toFloatArray().also { require(it.size == frames[0].size) }
    }

    private fun List<String>.indexOfFirstFrom(start: Int, value: String): Int =
        (start until size).first { this[it] == value }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrame = 0L
        if (visibility != VISIBLE) { removeCallbacks(frame); running = false }
        else invalidate()
    }
    override fun onDetachedFromWindow() { removeCallbacks(frame); running = false; super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
