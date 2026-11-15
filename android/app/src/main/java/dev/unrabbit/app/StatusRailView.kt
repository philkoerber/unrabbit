package dev.unrabbit.app

import android.animation.ValueAnimator
import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

/** One set of glyphs moves from the rest view into the chat's existing 24 dp gutter. */
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
        set(value) {
            field = value
            describeStatus()
            invalidate()
        }
    var vmState = VmConnectionState.UNPAIRED
        set(value) { field = value; describeStatus(); invalidate() }

    private fun describeStatus() {
        val value = status
        contentDescription = "Akku ${value.batteryPercent?.let { "$it Prozent" } ?: "unbekannt"}${if (value.charging) ", lädt" else ""}. ${value.hour}:${value.minute}. ${value.wifi.description}. ${vmState.description}."
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = false
        isFocusable = false
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
            duration = (520 * abs(target - compactFraction)).toLong().coerceAtLeast(1)
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
        // Keep the small rail within the existing margin even with larger system font settings.
        val fontScale = min(resources.configuration.fontScale, 1.15f)
        val p = compactFraction
        val transitionArc = sin(PI * p).toFloat()
        fun blend(large: Float, small: Float) = large + (small - large) * p
        ink.textSize = 48f * fontScale
        val restClockWidth = ink.measureText("${status.hour}:${status.minute}")
        // Center the clock itself, not the combined row with its differently sized icons.
        val restClockX = (width / density - restClockWidth) / 2
        ink.getTextBounds("00", 0, 2, digitBounds)
        val rowCenterY = 68f + (digitBounds.top + digitBounds.bottom) / 2f
        val x = blend(restClockX, 4f)
        canvas.save()
        canvas.scale(density, density)
        // Anchor the compact rail near the top edge, independently of the chat's top margin.
        canvas.translate(0f, -24f * p)
        val tone = colors.evaluate(p, 0xfff2f0e8.toInt(), 0xffafb4a8.toInt()) as Int
        ink.color = tone
        ink.style = Paint.Style.FILL
        ink.textSize = blend(48f, 11f) * fontScale
        canvas.drawText(status.hour, x, blend(68f, 60f), ink)
        val minuteOffset = ink.measureText("${status.hour}:")
        ink.alpha = ((1 - 3 * p).coerceIn(0f, 1f) * 255).toInt()
        canvas.drawText(":", x + ink.measureText(status.hour), blend(68f, 60f), ink)
        ink.alpha = 255
        // Drop the minutes below the hours before tucking them left, keeping glyphs apart.
        val tuck = ((p - .5f) * 2).coerceIn(0f, 1f)
        val minuteX = x + minuteOffset * (1 - tuck * tuck * (3 - 2 * tuck))
        val minuteY = blend(68f, 73f) + transitionArc * 14f
        canvas.drawText(status.minute, minuteX, minuteY, ink)

        // Battery → clock → Wi-Fi: a row at rest, a single column when compact.
        val iconAxis = blend(restClockX - 18f - 7.5f, 11.5f)
        // Give the clock room as the icons move above and below it.
        val batteryY = blend(rowCenterY - 13f, 30f) - transitionArc * 8f
        val batteryWidth = blend(15f, 7f)
        val batteryHeight = blend(26f, 13f)
        val batteryX = iconAxis - batteryWidth / 2
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = blend(1.8f, 1.05f)
        canvas.drawRoundRect(batteryX, batteryY, batteryX + batteryWidth, batteryY + batteryHeight, 2f, 2f, ink)
        canvas.drawLine(iconAxis - batteryWidth * .15f, batteryY - 2f,
            iconAxis + batteryWidth * .15f, batteryY - 2f, ink)
        ink.style = Paint.Style.FILL
        val fill = status.batteryPercent?.div(100f)
        val fillBottom = batteryY + batteryHeight - 2f
        val fillTop = fillBottom - (batteryHeight - 4f) * (fill ?: 0f)
        if (fill != null && fill > 0) canvas.drawRect(batteryX + 2f, fillTop,
            batteryX + batteryWidth - 2f, fillBottom, ink)
        if (status.charging) {
            shape.reset(); shape.moveTo(iconAxis + batteryWidth * .12f, batteryY + batteryHeight * .22f)
            shape.lineTo(iconAxis - batteryWidth * .2f, batteryY + batteryHeight * .55f)
            shape.lineTo(iconAxis + batteryWidth * .15f, batteryY + batteryHeight * .55f)
            shape.lineTo(iconAxis - batteryWidth * .1f, batteryY + batteryHeight * .8f)
            ink.style = Paint.Style.STROKE; ink.strokeWidth = blend(1.4f, .85f)
            canvas.drawPath(shape, ink)
            canvas.save()
            canvas.clipRect(batteryX + 2f, fillTop, batteryX + batteryWidth - 2f, fillBottom)
            ink.color = 0xff171915.toInt()
            canvas.drawPath(shape, ink)
            canvas.restore()
            ink.color = tone
        } else if (fill == null) {
            ink.style = Paint.Style.STROKE
            canvas.drawLine(batteryX + 2f, batteryY + batteryHeight / 2,
                batteryX + batteryWidth - 2f, batteryY + batteryHeight / 2, ink)
        }

        canvas.save()
        val wifiSize = blend(30f, 15f)
        canvas.translate(blend(restClockX + restClockWidth + 18f, 4f),
            blend(rowCenterY - 15f, 79f) + transitionArc * 32f)
        canvas.scale(wifiSize / 24, wifiSize / 24)
        ink.style = Paint.Style.STROKE; ink.strokeWidth = blend(1.7f, 1.9f)
        // These arcs express connection state, never an invented signal-strength measurement.
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
            WifiStatus.UNKNOWN -> { ink.style = Paint.Style.FILL; ink.textSize = 12f; canvas.drawText("?", 9f, 25f, ink) }
        }
        canvas.restore()

        // Wi-Fi alone never means VM access: show a server with a check, pending dots or slash.
        canvas.save()
        val vmSize = blend(22f, 15f)
        canvas.translate(blend(restClockX + restClockWidth + 56f, 4f),
            blend(rowCenterY - 11f, 102f) + transitionArc * 38f)
        canvas.scale(vmSize / 24, vmSize / 24)
        ink.color = tone
        ink.style = Paint.Style.STROKE; ink.strokeWidth = 1.7f
        canvas.drawRoundRect(3f, 2f, 21f, 19f, 2f, 2f, ink)
        canvas.drawLine(3f, 10f, 21f, 10f, ink)
        canvas.drawPoint(7f, 6f, ink)
        canvas.drawPoint(7f, 14f, ink)
        when (vmState) {
            VmConnectionState.CONNECTED -> {
                ink.color = context.getColor(R.color.unrabbit_accent)
                shape.reset(); shape.moveTo(12f, 21f); shape.lineTo(16f, 24f); shape.lineTo(23f, 16f)
                canvas.drawPath(shape, ink)
            }
            VmConnectionState.CHECKING -> {
                ink.style = Paint.Style.FILL
                for (x in listOf(9f, 14f, 19f)) canvas.drawCircle(x, 23f, 1f, ink)
            }
            VmConnectionState.UNPAIRED, VmConnectionState.DISCONNECTED -> canvas.drawLine(1f, 1f, 23f, 23f, ink)
        }
        canvas.restore()
        canvas.restore()
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}
