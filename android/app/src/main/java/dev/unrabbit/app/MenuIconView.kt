package dev.unrabbit.app

import android.content.Context
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import kotlin.math.min
import kotlin.math.roundToInt

enum class MenuIcon(val label: String) {
    SETTINGS("Einstellungen"), STOP("Alles anhalten"), CLOSE("Schließen"), SAVE("Speichern"), SCAN("Verbindung scannen")
}

/** Slightly asymmetric, soft-stroked marks from the same visual vocabulary as the logo. */
class MenuIconView @JvmOverloads constructor(context: Context, val icon: MenuIcon = MenuIcon.SETTINGS) : View(context) {
    var showSelectionDot = true
    private var selectionProgress = 0f
    private var selectionAnimator: ValueAnimator? = null
    private val colors = ArgbEvaluator()
    private val accent = context.getColor(R.color.unrabbit_accent)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val shape = Path().apply {
        when (icon) {
            MenuIcon.SETTINGS -> {
                moveTo(4f, 9f); lineTo(10f, 9f); moveTo(19f, 9f); lineTo(28f, 8f)
                moveTo(4f, 24f); lineTo(17f, 23f); moveTo(26f, 23f); lineTo(29f, 23f)
                addOval(10f, 4f, 19f, 14f, Path.Direction.CW)
                addOval(17f, 18f, 26f, 28f, Path.Direction.CW)
            }
            MenuIcon.STOP -> {
                moveTo(9f, 6f); cubicTo(4f, 6f, 4f, 13f, 5f, 23f)
                quadTo(6f, 28f, 22f, 27f); quadTo(28f, 27f, 27f, 11f)
                quadTo(27f, 5f, 9f, 6f); close()
            }
            MenuIcon.CLOSE -> {
                moveTo(7f, 6f); cubicTo(12f, 14f, 21f, 20f, 26f, 27f)
                moveTo(26f, 6f); cubicTo(20f, 11f, 12f, 23f, 6f, 26f)
            }
            MenuIcon.SAVE -> {
                moveTo(5f, 16f); quadTo(9f, 19f, 13f, 25f)
                cubicTo(17f, 18f, 23f, 10f, 28f, 6f)
            }
            MenuIcon.SCAN -> {
                moveTo(4f, 12f); lineTo(4f, 5f); lineTo(11f, 4f)
                moveTo(21f, 4f); lineTo(28f, 5f); lineTo(28f, 12f)
                moveTo(28f, 21f); lineTo(28f, 28f); lineTo(21f, 29f)
                moveTo(11f, 29f); lineTo(4f, 28f); lineTo(4f, 21f)
                moveTo(10f, 16f); quadTo(16f, 14f, 23f, 16f)
            }
        }
    }

    init {
        contentDescription = icon.label
        isClickable = true
        isFocusable = true
        defaultFocusHighlightEnabled = false
        minimumHeight = (64 * resources.displayMetrics.density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val size = min(31 * density, width * .58f)
        ink.color = colors.evaluate(if (isPressed) 1f else selectionProgress,
            0xffafb4a8.toInt(), accent) as Int
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 32, size / 32)
        canvas.drawPath(shape, ink)
        canvas.restore()
        dot.alpha = (255 * selectionProgress).roundToInt()
        if (showSelectionDot && selectionProgress > 0f) canvas.drawCircle(4 * density, height / 2f, 2.3f * density, dot)
    }

    override fun setSelected(selected: Boolean) {
        if (isSelected == selected) return
        super.setSelected(selected)
        selectionAnimator?.cancel()
        val target = if (selected) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) {
            selectionProgress = target
            invalidate()
        } else selectionAnimator = ValueAnimator.ofFloat(selectionProgress, target).apply {
            duration = 140
            addUpdateListener { selectionProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { selectionAnimator?.cancel(); super.onDetachedFromWindow() }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
}
