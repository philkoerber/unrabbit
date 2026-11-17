package dev.unrabbit.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.min

/** Centered lock-screen clock and three status icons, compacted into the chat's 24 dp gutter. */
class StatusRailView(context: Context) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.plex_regular)
        fontFeatureSettings = "tnum"
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shape = Path()
    private val digitBounds = Rect()
    private val colors = ArgbEvaluator()
    private var animator: ValueAnimator? = null
    private var compactTarget = false
    var compactFraction = 0f
        private set
    var onProgress: ((Float) -> Unit)? = null
    var status = DeviceStatus()
        set(value) { field = value; describeStatus(); invalidate() }
    var vmState = VmConnectionState.UNPAIRED
        set(value) { field = value; describeStatus(); invalidate() }

    private fun describeStatus() {
        val value = status
        contentDescription = "Battery ${value.batteryPercent?.let { "$it percent" } ?: "unknown"}${if (value.charging) ", charging" else ""}. ${value.hour}:${value.minute}. ${value.wifi.description}. ${vmState.description}."
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        describeStatus()
    }

    fun setCompact(compact: Boolean, animate: Boolean) {
        val target = if (compact) 1f else 0f
        if (compactTarget == compact && (animator?.isRunning == true || compactFraction == target) && animate) return
        compactTarget = compact
        animator?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            updateFraction(target)
            return
        }
        animator = ValueAnimator.ofFloat(compactFraction, target).apply {
            duration = (360 * abs(target - compactFraction)).toLong().coerceAtLeast(1)
            interpolator = PathInterpolator(.22f, .7f, .18f, 1f)
            addUpdateListener { updateFraction(it.animatedValue as Float) }
            start()
        }
    }

    private fun updateFraction(value: Float) {
        compactFraction = value
        invalidate()
        onProgress?.invoke(value)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val fontScale = min(resources.configuration.fontScale, 1.15f)
        val p = compactFraction
        fun blend(rest: Float, compact: Float) = rest + (compact - rest) * p
        val centerX = width / density / 2f
        val centerY = height / density / 2f
        val tone = colors.evaluate(p, 0xfff2f0e8.toInt(), 0xffafb4a8.toInt()) as Int
        canvas.save()
        canvas.scale(density, density)

        ink.style = Paint.Style.FILL
        ink.color = tone
        ink.textSize = 56f * fontScale
        val clockX = centerX - ink.measureText("${status.hour}:${status.minute}") / 2f
        ink.getTextBounds("00", 0, 2, digitBounds)
        val baseline = centerY - (digitBounds.top + digitBounds.bottom) / 2f
        ink.textSize = blend(56f, 11f) * fontScale
        val x = blend(clockX, 4f)
        canvas.drawText(status.hour, x, blend(baseline, 36f), ink)
        ink.alpha = ((1 - p) * 255).toInt()
        canvas.drawText(":", x + ink.measureText(status.hour), blend(baseline, 36f), ink)
        ink.alpha = 255
        canvas.drawText(status.minute, x + ink.measureText("${status.hour}:") * (1 - p), blend(baseline, 49f), ink)

        // Equally spaced, centered under the clock. Every icon uses the same 24-unit canvas.
        val iconY = centerY + 60f * fontScale
        val restSize = 24f
        fun icon(restX: Float, compactY: Float, draw: (Canvas) -> Unit) {
            val size = blend(restSize, 15f)
            canvas.save()
            canvas.translate(blend(restX, 11.5f) - size / 2f, blend(iconY, compactY) - size / 2f)
            canvas.scale(size / 24f, size / 24f)
            ink.color = tone
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = 1.7f
            draw(canvas)
            canvas.restore()
        }
        icon(centerX - 44f, 13f, ::drawBattery)
        icon(centerX, 63f, ::drawWifi)
        icon(centerX + 44f, 86f, ::drawVm)
        canvas.restore()
    }

    private fun drawBattery(canvas: Canvas) {
        canvas.drawRoundRect(6f, 3f, 18f, 23f, 2f, 2f, ink)
        canvas.drawLine(10f, 1f, 14f, 1f, ink)
        val fill = status.batteryPercent?.div(100f)
        val fillTop = 21f - 16f * (fill ?: 0f)
        if (fill != null && fill > 0) {
            ink.style = Paint.Style.FILL
            canvas.drawRect(8f, fillTop, 16f, 21f, ink)
        }
        ink.style = Paint.Style.STROKE
        if (status.charging) {
            shape.reset(); shape.moveTo(13f, 7f); shape.lineTo(10f, 13f)
            shape.lineTo(14f, 13f); shape.lineTo(11f, 19f)
            canvas.drawPath(shape, ink)
            canvas.save()
            canvas.clipRect(8f, fillTop, 16f, 21f)
            ink.color = Color.BLACK
            canvas.drawPath(shape, ink)
            canvas.restore()
        } else if (fill == null) canvas.drawLine(9f, 13f, 15f, 13f, ink)
    }

    private fun drawWifi(canvas: Canvas) {
        // Arcs show connection state, never an invented signal-strength measurement.
        for (i in 0..2) {
            val y = 5f + i * 5f
            val inset = 2f + i * 3.3f
            shape.reset(); shape.moveTo(inset, y)
            shape.quadTo(12f, y - (7f - i * 2f), 24f - inset, y)
            canvas.drawPath(shape, ink)
        }
        when (status.wifi) {
            WifiStatus.CONNECTED -> { ink.style = Paint.Style.FILL; canvas.drawCircle(12f, 21f, 1.6f, ink) }
            WifiStatus.LOCAL -> { canvas.drawLine(12f, 18f, 12f, 20f, ink); canvas.drawPoint(12f, 23f, ink) }
            WifiStatus.OFF -> canvas.drawLine(3f, 2f, 22f, 23f, ink)
            WifiStatus.DISCONNECTED -> { canvas.drawLine(9f, 18f, 15f, 24f, ink); canvas.drawLine(15f, 18f, 9f, 24f, ink) }
            WifiStatus.UNKNOWN -> { ink.style = Paint.Style.FILL; ink.textSize = 12f; canvas.drawText("?", 9f, 24f, ink) }
        }
    }

    private fun drawVm(canvas: Canvas) {
        // A terminal represents the execution host; only an authenticated health check lights its dot.
        canvas.drawRoundRect(1f, 3f, 23f, 21f, 3f, 3f, ink)
        shape.reset(); shape.moveTo(6f, 8f); shape.lineTo(10f, 12f); shape.lineTo(6f, 16f)
        canvas.drawPath(shape, ink)
        canvas.drawLine(13f, 16f, 17f, 16f, ink)
        ink.style = Paint.Style.FILL
        ink.color = Color.BLACK
        canvas.drawCircle(21f, 21f, 4f, ink)
        when (vmState) {
            VmConnectionState.CONNECTED -> {
                ink.color = context.getColor(R.color.unrabbit_accent)
                canvas.drawCircle(21f, 21f, 2.5f, ink)
            }
            VmConnectionState.CHECKING -> {
                ink.color = 0xffafb4a8.toInt(); ink.style = Paint.Style.STROKE
                canvas.drawCircle(21f, 21f, 2.5f, ink)
            }
            VmConnectionState.UNPAIRED, VmConnectionState.DISCONNECTED -> {
                ink.color = 0xffafb4a8.toInt(); ink.style = Paint.Style.STROKE
                canvas.drawLine(19f, 23f, 23f, 19f, ink)
            }
        }
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}
