package dev.unrabbit.app

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import kotlin.math.roundToInt

/** One connection action: scan a complete invitation. No editable endpoint or save draft. */
@SuppressLint("ViewConstructor")
class SettingsView(
    context: Context,
    pairedHost: String?,
    private val onPair: (PairingQr) -> Unit,
    private val onClose: () -> Unit,
    private val onRequestScan: () -> Unit,
) : FrameLayout(context) {
    private val baseNavigation = mutableListOf<Pair<View, () -> Unit>>()
    val navigation: List<Pair<View, () -> Unit>> get() = when {
        scanner != null -> emptyList()
        isPairing -> baseNavigation.takeLast(1)
        else -> baseNavigation
    }
    val isScanning get() = scanner != null
    var isPairing = false
        private set
    var onSelection: (Int) -> Unit = {}
    var onNavigationChanged: (Int) -> Unit = {}
    private var scanner: SetupScannerView? = null
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val status = label(pairedHost?.let { "Gekoppelt · $it" } ?: "Nicht verbunden", 14f).apply {
        tag = "vm-status"; setPadding(dp(16), dp(8), 0, dp(20))
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    init {
        tag = "unrabbit-settings"; isClickable = true; isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        defaultFocusHighlightEnabled = false
        column.addView(label("VM", 22f).apply { setPadding(dp(16), 0, 0, dp(4)) })
        column.addView(status)
        val scan = SettingRow(context).apply {
            contentDescription = if (pairedHost == null) "VM verbinden" else "VM wechseln"
            val body = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            body.addView(label(contentDescription.toString(), 20f), LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(MenuIconView(context, MenuIcon.SCAN).apply {
                isFocusable = false; isClickable = false; showSelectionDot = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(body)
        }
        register(scan) { onRequestScan() }
        column.addView(scan, LinearLayout.LayoutParams(-1, -2))
        val close = MenuIconView(context, MenuIcon.CLOSE).apply { contentDescription = "Abbrechen" }
        register(close, allowDuringPairing = true, action = onClose)
        column.addView(close, LinearLayout.LayoutParams(dp(72), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20) })
        addView(column, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(24); rightMargin = dp(24) })
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; typeface = resources.getFont(R.font.plex_regular); includeFontPadding = false
        setTextColor(context.getColorStateList(R.color.selection_text))
    }
    private fun register(view: View, allowDuringPairing: Boolean = false, action: () -> Unit) {
        val index = baseNavigation.size
        baseNavigation += view to { if (!isPairing || allowDuringPairing) action() }
        view.setOnClickListener { if (!isPairing || allowDuringPairing) { onSelection(if (isPairing) 0 else index); action() } }
    }
    fun setPairing(active: Boolean) {
        isPairing = active
        baseNavigation.forEachIndexed { index, item -> item.first.isEnabled = !active || index == baseNavigation.lastIndex }
        if (active) status.text = "Verbinde …"
        onNavigationChanged(0)
    }
    fun showError(message: String) { setPairing(false); status.text = message }
    fun openScanner() {
        if (scanner != null || isPairing) return
        column.visibility = INVISIBLE
        scanner = SetupScannerView(context, { applyScan(it) }, { closeScanner() }).also {
            addView(it, LayoutParams(-1, -1))
            if (ValueAnimator.areAnimatorsEnabled()) { it.alpha = 0f; it.animate().alpha(1f).setDuration(240).start() }
        }
        onNavigationChanged(0)
    }
    fun applyScan(invitation: PairingQr) {
        if (isPairing) return
        closeScanner()
        onPair(invitation)
    }
    private fun closeScanner() {
        val old = scanner ?: return
        scanner = null; old.closeCamera(); old.animate().cancel()
        old.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        column.visibility = VISIBLE
        if (ValueAnimator.areAnimatorsEnabled()) old.animate().alpha(0f).setDuration(160).withEndAction { removeView(old) }.start()
        else removeView(old)
        onNavigationChanged(0)
        requestFocus()
    }
    fun handleWheel(amount: Float): Boolean = scanner != null
    fun handleButton(): Boolean = scanner != null
    fun handleBack(): Boolean = if (scanner != null) { closeScanner(); true } else false
    fun pauseCamera() { scanner?.pauseCamera() }
    fun resumeCamera() { scanner?.resumeCamera() }
    override fun onDetachedFromWindow() { scanner?.closeCamera(); super.onDetachedFromWindow() }
}

/** Fine rules and a small focus dot replace the platform's button and field chrome. */
private class SettingRow(context: Context) : LinearLayout(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.getColor(R.color.unrabbit_accent)
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding((16 * density).roundToInt(), (6 * density).roundToInt(), 0, (4 * density).roundToInt())
        minimumHeight = (52 * density).roundToInt()
        isClickable = true
        isFocusable = true
        defaultFocusHighlightEnabled = false
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = isSelected || isActivated || isPressed
        ink.color = if (active) (accent and 0x00ffffff) or 0x99000000.toInt() else 0x22f2f0e8
        ink.strokeWidth = density * .7f
        canvas.drawLine(16 * density, height - density, width.toFloat(), height - density, ink)
        if (active) {
            ink.color = accent
            canvas.drawCircle(3 * density, height / 2f, 2.3f * density, ink)
        }
    }
    override fun setSelected(selected: Boolean) { super.setSelected(selected); invalidate() }
    override fun setActivated(activated: Boolean) { super.setActivated(activated); invalidate() }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
}
